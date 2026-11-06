#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <unwind.h>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <memory>
#include <limits>
#include <mutex>
#include <sstream>
#include <unordered_map>
#include <unordered_set>
#include "shadowhook.h"
#include "http2_parser.h"
#include "native_io.h"

#define LOG_TAG "AdClose-Native"
#if DEBUG
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...)
#endif

namespace {
JavaVM* vm = nullptr;
jclass callback_class = nullptr;
jmethodID on_data = nullptr, on_h2_request = nullptr, on_h2_chunk = nullptr;
jmethodID collect_body = nullptr, on_close = nullptr, on_discard = nullptr;
jmethodID on_h2_stream_close = nullptr, on_h2_reset = nullptr, on_h2_body_discard = nullptr;
pthread_key_t thread_key;
thread_local JNIEnv* thread_env = nullptr;
thread_local bool in_hook = false;
thread_local unsigned java_capture_depth = 0;
std::mutex init_mutex;
bool hooks_attempted = false, libc_active = false;
std::atomic<uint64_t> next_id{1};
constexpr size_t MAX_CONNECTIONS = 512;

struct HookGuard {
    bool previous = in_hook;
    HookGuard() { in_hook = true; }
    ~HookGuard() { in_hook = previous; }
};
struct ErrnoGuard {
    int value = errno;
    ~ErrnoGuard() { errno = value; }
};
struct LocalFrame {
    JNIEnv* env;
    bool ready;
    explicit LocalFrame(JNIEnv* e) : env(e), ready(e->PushLocalFrame(16) == JNI_OK) {}
    ~LocalFrame() { if (ready) env->PopLocalFrame(nullptr); }
};
bool clear_callback_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}
void detach_thread(void*) {
    thread_env = nullptr;
    if (vm) vm->DetachCurrentThread();
}
JNIEnv* callback_env() {
    if (!vm || !callback_class) return nullptr;
    if (!thread_env) {
        const int status = vm->GetEnv(reinterpret_cast<void**>(&thread_env), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (vm->AttachCurrentThread(&thread_env, nullptr) != JNI_OK) { thread_env = nullptr; return nullptr; }
            if (pthread_setspecific(thread_key, thread_env) != 0) {
                vm->DetachCurrentThread(); thread_env = nullptr; return nullptr;
            }
        } else if (status != JNI_OK) { thread_env = nullptr; return nullptr; }
    }
    // Leave an application's pending exception untouched.
    return thread_env->ExceptionCheck() ? nullptr : thread_env;
}
jstring java_string(JNIEnv* env, const std::string& text) {
    if (env->ExceptionCheck()) return nullptr;
    const auto utf16 = native_utf16(text);
    return env->NewString(reinterpret_cast<const jchar*>(utf16.data()), jsize(utf16.size()));
}
struct State {
    uint64_t id = next_id.fetch_add(1);
    std::atomic<bool> blocked{false}, observing{true}, eof{false}, h1_only{false};
    std::atomic<uint64_t> active{get_current_ms()};
    std::mutex tx, rx, peer_mutex;
    std::string peer_address;
    uint64_t peer_retry_at = 0;
    ObservedWrite writes;
};
struct FdEntry {
    uint64_t device;
    uint64_t inode;
    std::shared_ptr<State> state;
};
std::mutex states_mutex;
std::unordered_map<int, FdEntry> fd_states;
std::unordered_map<void*, std::shared_ptr<State>> ssl_states;
std::string socket_address(int fd);

void notify(uint64_t id, bool ssl, bool discard) {
    JNIEnv* env = callback_env();
    if (!env) return;
    env->CallStaticVoidMethod(callback_class, discard ? on_discard : on_close, jlong(id), jboolean(ssl));
    clear_callback_exception(env);
}
void discard_state(const std::shared_ptr<State>& state, bool ssl) {
    if (state && state->observing.exchange(false)) {
        h2_free(state->id);
        notify(state->id, ssl, true);
    }
}
std::shared_ptr<State> fd_state(int fd) {
    if (fd < 0) return nullptr;
    struct stat statbuf{};
    if (fstat(fd, &statbuf) != 0 || !S_ISSOCK(statbuf.st_mode)) return nullptr;
    std::shared_ptr<State> stale;
    {
        std::lock_guard<std::mutex> lock(states_mutex);
        auto it = fd_states.find(fd);
        if (it != fd_states.end()) {
            if (it->second.device == statbuf.st_dev && it->second.inode == statbuf.st_ino) {
                it->second.state->active = get_current_ms();
                return it->second.state;
            }
            stale = it->second.state;
            fd_states.erase(it);
        }
    }
    discard_state(stale, false);
    int type = 0;
    socklen_t type_len = sizeof(type);
    sockaddr_storage addr{};
    socklen_t addr_len = sizeof(addr);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &type_len) != 0 || type != SOCK_STREAM ||
        getsockname(fd, reinterpret_cast<sockaddr*>(&addr), &addr_len) != 0 ||
        (addr.ss_family != AF_INET && addr.ss_family != AF_INET6)) return nullptr;
    std::lock_guard<std::mutex> lock(states_mutex);
    if (fd_states.size() >= MAX_CONNECTIONS) return nullptr;
    auto it = fd_states.find(fd);
    if (it != fd_states.end()) return it->second.state;
    auto state = std::make_shared<State>();
    fd_states.emplace(fd, FdEntry{statbuf.st_dev, statbuf.st_ino, state});
    return state;
}
std::shared_ptr<State> ssl_state(void* ssl, bool create = true) {
    if (!ssl) return nullptr;
    std::lock_guard<std::mutex> lock(states_mutex);
    auto it = ssl_states.find(ssl);
    if (it != ssl_states.end()) { it->second->active = get_current_ms(); return it->second; }
    if (!create || ssl_states.size() >= MAX_CONNECTIONS) return nullptr;
    auto state = std::make_shared<State>();
    ssl_states.emplace(ssl, state);
    return state;
}
std::string socket_address(int fd) {
    sockaddr_storage addr{};
    socklen_t len = sizeof(addr);
    if (getpeername(fd, reinterpret_cast<sockaddr*>(&addr), &len) != 0) return {};
    char ip[INET6_ADDRSTRLEN]{};
    if (addr.ss_family == AF_INET) {
        auto* s = reinterpret_cast<sockaddr_in*>(&addr);
        if (inet_ntop(AF_INET, &s->sin_addr, ip, sizeof(ip))) return std::string(ip) + ":" + std::to_string(ntohs(s->sin_port));
    } else if (addr.ss_family == AF_INET6) {
        auto* s = reinterpret_cast<sockaddr_in6*>(&addr);
        if (inet_ntop(AF_INET6, &s->sin6_addr, ip, sizeof(ip))) return "[" + std::string(ip) + "]:" + std::to_string(ntohs(s->sin6_port));
    }
    return {};
}
std::string cached_socket_address(State& state, int fd) {
    std::lock_guard<std::mutex> lock(state.peer_mutex);
    if (state.peer_address.empty() && get_current_ms() >= state.peer_retry_at) {
        state.peer_address = socket_address(fd);
        state.peer_retry_at = get_current_ms() + 1000;
    }
    return state.peer_address;
}
struct Backtrace { void* addresses[12]; size_t count = 0; };
_Unwind_Reason_Code unwind(_Unwind_Context* context, void* arg) {
    auto& trace = *static_cast<Backtrace*>(arg);
    if (trace.count == 12) return _URC_END_OF_STACK;
    const auto ip = _Unwind_GetIP(context);
    if (ip) trace.addresses[trace.count++] = reinterpret_cast<void*>(ip);
    return _URC_NO_REASON;
}
std::string native_stack() {
    Backtrace trace{};
    _Unwind_Backtrace(unwind, &trace);
    std::ostringstream out;
    out << "Native Stack:\n";
    for (size_t i = 0; i < trace.count; ++i) {
        Dl_info info{};
        if (dladdr(trace.addresses[i], &info) && info.dli_fname) {
            const char* name = std::strrchr(info.dli_fname, '/');
            out << "  #" << i << " pc " << std::hex
                << (uintptr_t(trace.addresses[i]) - uintptr_t(info.dli_fbase)) << "  "
                << (name ? name + 1 : info.dli_fname) << '\n';
        }
    }
    return out.str();
}
bool should_collect() {
    auto* env = callback_env();
    if (!env) return false;
    const bool result = env->CallStaticBooleanMethod(callback_class, collect_body);
    return !clear_callback_exception(env) && result;
}
std::string format_headers(const std::vector<std::pair<std::string, std::string>>& headers) {
    std::string result;
    for (const auto& h : headers) { result += h.first; result += '\n'; result += h.second; result += '\n'; }
    return result;
}
void h2_callback(const std::shared_ptr<Http2Connection>& conn, const H2FeedResult& result) {
    auto* env = callback_env();
    if (!env) return;
    // Rule enforcement belongs at a request boundary. A TLS connection can carry unrelated streams.
    for (const auto& check : result.early_checks) {
        LocalFrame refs(env);
        if (!refs.ready) { clear_callback_exception(env); break; }
        auto method = java_string(env, check.method), path = java_string(env, check.path);
        auto authority = java_string(env, check.authority), scheme = java_string(env, check.scheme);
        auto headers = java_string(env, format_headers(check.req_headers));
        if (clear_callback_exception(env)) continue;
        env->CallStaticBooleanMethod(callback_class, on_h2_request, jlong(conn->conn_id), jint(check.stream_id),
            method, path, authority, scheme, headers, nullptr, jint(-1), jboolean(false),
            jboolean(check.request_complete), jboolean(false));
        clear_callback_exception(env);
    }
    for (const auto& chunk : result.data_chunks) {
        if (chunk.discarded) {
            env->CallStaticVoidMethod(callback_class, on_h2_body_discard, jlong(conn->conn_id), jint(chunk.stream_id), jboolean(chunk.is_request));
            clear_callback_exception(env);
            continue;
        }
        LocalFrame refs(env);
        if (!refs.ready) { clear_callback_exception(env); break; }
        auto buffer = env->NewDirectByteBuffer(const_cast<uint8_t*>(chunk.data.data()), jlong(chunk.data.size()));
        if (clear_callback_exception(env) || !buffer) continue;
        env->CallStaticVoidMethod(callback_class, on_h2_chunk, jlong(conn->conn_id), jint(chunk.stream_id), jboolean(chunk.is_request), buffer);
        clear_callback_exception(env);
    }
    for (const auto& req : result.completed) {
        LocalFrame refs(env);
        if (!refs.ready) { clear_callback_exception(env); break; }
        auto method = java_string(env, req.method), path = java_string(env, req.path);
        auto authority = java_string(env, req.authority), scheme = java_string(env, req.scheme);
        auto request = java_string(env, format_headers(req.req_headers)), response = java_string(env, format_headers(req.resp_headers));
        if (clear_callback_exception(env)) continue;
        env->CallStaticBooleanMethod(callback_class, on_h2_request, jlong(conn->conn_id), jint(req.stream_id),
            method, path, authority, scheme, request, response, jint(req.status_code), jboolean(req.is_complete),
            jboolean(req.request_complete), jboolean(req.response_complete));
        clear_callback_exception(env);
    }
    for (int stream : result.closed_streams) {
        env->CallStaticVoidMethod(callback_class, on_h2_stream_close, jlong(conn->conn_id), jint(stream));
        clear_callback_exception(env);
    }
    if (result.reset) {
        env->CallStaticVoidMethod(callback_class, on_h2_reset, jlong(conn->conn_id));
        clear_callback_exception(env);
    }
}
bool observe(uint64_t id, bool write, const uint8_t* bytes, size_t size, bool ssl, int fd = -1,
              const std::string& cached_address = {}, bool address_cached = false, State* state = nullptr) {
    if (!bytes || !size) return false;
    auto* env = callback_env();
    if (!env) return false;
    const auto address = ssl ? std::string{} : (address_cached ? cached_address : socket_address(fd));
    // No fd-keyed stack cache: fd/SSL pointer reuse must not retain another connection's metadata.
    thread_local unsigned sample = 0;
    const auto stack = write && (++sample % 10 == 0) ? native_stack() : std::string{};
    auto conn = state && state->h1_only ? nullptr : h2_get_or_create(id);
    const bool collect = conn && (!conn->h2_checked || conn->is_h2) && should_collect();
    for (size_t offset = 0; offset < size;) {
        const size_t count = std::min(size - offset, H2_MAX_FEED_BYTES);
        if (conn) {
            // Serialize parsing with delivery so another direction cannot complete a stream before its body callback.
            // The parser itself never calls JNI while holding its mutex.
            std::lock_guard<std::mutex> delivery(conn->delivery_mutex);
            auto result = h2_feed(conn, bytes + offset, count, write, collect);
            h2_callback(conn, result);
            if (conn->is_h2) { offset += count; continue; }
            if (state && (conn->h2_checked || conn->disabled)) {
                state->h1_only = true;
                h2_free(id); // HTTP/1 sockets must not occupy the H2 connection budget.
            }
        }
        LocalFrame refs(env);
        if (!refs.ready) { clear_callback_exception(env); return false; }
        auto buffer = env->NewDirectByteBuffer(const_cast<uint8_t*>(bytes + offset), jlong(count));
        auto info = address.empty() ? nullptr : java_string(env, address);
        auto trace = stack.empty() ? nullptr : java_string(env, stack);
        if (clear_callback_exception(env) || !buffer) return false;
        const bool blocked = env->CallStaticBooleanMethod(callback_class, on_data, jlong(id), jboolean(write), buffer, info, trace, jboolean(ssl));
        if (!clear_callback_exception(env) && blocked) return true;
        offset += count;
    }
    return false;
}

using Send = ssize_t (*)(int, const void*, size_t, int);
using Recv = ssize_t (*)(int, void*, size_t, int);
using SendTo = ssize_t (*)(int, const void*, size_t, int, const sockaddr*, socklen_t);
using RecvFrom = ssize_t (*)(int, void*, size_t, int, sockaddr*, socklen_t*);
using Write = ssize_t (*)(int, const void*, size_t);
using Read = ssize_t (*)(int, void*, size_t);
using Close = int (*)(int);
Send orig_send = nullptr; Recv orig_recv = nullptr;
SendTo orig_sendto = nullptr; RecvFrom orig_recvfrom = nullptr;
Write orig_write = nullptr; Read orig_read = nullptr; Close orig_close = nullptr;

// Only observation is allowed to fail open; the original operation is executed exactly once.
template<class Original>
ssize_t write_fd(int fd, const void* buffer, size_t count, Original original, bool capture = true) {
    if (in_hook || java_capture_depth || !buffer || !count || count > size_t(std::numeric_limits<ssize_t>::max())) return original();
    HookGuard guard;
    ErrnoGuard error;
    std::shared_ptr<State> state;
    try { state = fd_state(fd); } catch (...) {}
    if (!state) { errno = error.value; auto ret = original(); error.value = errno; return ret; }
    std::lock_guard<std::mutex> lock(state->tx);
    if (state->blocked) { error.value = ECONNRESET; return -1; }
    const auto* bytes = static_cast<const uint8_t*>(buffer);
    try {
        if (capture && state->observing) {
            const size_t skip = state->writes.begin(bytes, count);
            if (!state->writes.enabled()) discard_state(state, false);
            else if (observe(state->id, true, bytes + skip, count - skip, false, fd,
                cached_socket_address(*state, fd), true, state.get())) {
                state->blocked = true; error.value = ECONNRESET; return -1;
            }
        }
    } catch (...) { discard_state(state, false); }
    errno = error.value;
    const ssize_t ret = original();
    error.value = errno;
    try {
        if (capture && state->observing) {
            state->writes.finish(bytes, count, ret);
            if (!state->writes.enabled()) discard_state(state, false);
        }
    } catch (...) { discard_state(state, false); }
    return ret;
}
template<class Original>
ssize_t read_fd(int fd, void* buffer, size_t count, Original original, bool capture = true) {
    if (in_hook || java_capture_depth || !buffer || !count || count > size_t(std::numeric_limits<ssize_t>::max())) return original();
    HookGuard guard;
    ErrnoGuard error;
    std::shared_ptr<State> state;
    try { state = fd_state(fd); } catch (...) {}
    if (!state) { errno = error.value; auto ret = original(); error.value = errno; return ret; }
    std::lock_guard<std::mutex> lock(state->rx);
    if (state->blocked) { error.value = ECONNRESET; return -1; }
    errno = error.value;
    const ssize_t ret = original();
    error.value = errno;
    try {
        if (capture && state->observing && ret > 0 && size_t(ret) <= count && observe(state->id, false,
            static_cast<uint8_t*>(buffer), size_t(ret), false, fd, cached_socket_address(*state, fd), true, state.get())) {
            state->blocked = true; error.value = ECONNRESET; return -1;
        }
        if (state->observing && ret == 0 && !state->eof.exchange(true)) notify(state->id, false, false);
    } catch (...) { discard_state(state, false); }
    return ret;
}
ssize_t hook_send(int fd, const void* buf, size_t size, int flags) {
    return write_fd(fd, buf, size, [&] { return orig_send(fd, buf, size, flags); }, !(flags & MSG_OOB));
}
ssize_t hook_recv(int fd, void* buf, size_t size, int flags) {
    return read_fd(fd, buf, size, [&] { return orig_recv(fd, buf, size, flags); }, !(flags & (MSG_PEEK | MSG_OOB | MSG_TRUNC)));
}
ssize_t hook_sendto(int fd, const void* buf, size_t size, int flags, const sockaddr* to, socklen_t len) {
    return write_fd(fd, buf, size, [&] { return orig_sendto(fd, buf, size, flags, to, len); }, !(flags & MSG_OOB));
}
ssize_t hook_recvfrom(int fd, void* buf, size_t size, int flags, sockaddr* from, socklen_t* len) {
    return read_fd(fd, buf, size, [&] { return orig_recvfrom(fd, buf, size, flags, from, len); }, !(flags & (MSG_PEEK | MSG_OOB | MSG_TRUNC)));
}
ssize_t hook_write(int fd, const void* buf, size_t size) {
    return write_fd(fd, buf, size, [&] { return orig_write(fd, buf, size); });
}
ssize_t hook_read(int fd, void* buf, size_t size) {
    return read_fd(fd, buf, size, [&] { return orig_read(fd, buf, size); });
}
int hook_close(int fd) {
    HookGuard guard;
    ErrnoGuard error;
    std::shared_ptr<State> state;
    {
        std::lock_guard<std::mutex> lock(states_mutex);
        auto it = fd_states.find(fd);
        if (it != fd_states.end()) { state = it->second.state; fd_states.erase(it); }
    }
    // Retire before close: the fd number can be reused immediately after the syscall.
    errno = error.value;
    const int ret = orig_close(fd);
    error.value = errno;
    try { if (state) discard_state(state, false); } catch (...) {}
    return ret;
}

using SslWrite = int (*)(void*, const void*, int);
using SslRead = int (*)(void*, void*, int);
using SslFree = void (*)(void*);
using SslError = int (*)(const void*, int);
struct SslHooks { const char* library; SslWrite write; SslRead read; SslFree free; SslError error; };
SslHooks ssl_hooks[] = {
    {"libssl.so", nullptr, nullptr, nullptr, nullptr},
    {"libconscrypt_jni.so", nullptr, nullptr, nullptr, nullptr},
    {"libttboringssl.so", nullptr, nullptr, nullptr, nullptr},
    {"libflutter.so", nullptr, nullptr, nullptr, nullptr}
};
template<int I>
int ssl_write(void* ssl, const void* buffer, int count) {
    auto original = ssl_hooks[I].write;
    if (in_hook || java_capture_depth || !buffer || count <= 0) return original(ssl, buffer, count);
    HookGuard guard;
    ErrnoGuard error;
    std::shared_ptr<State> state;
    try { state = ssl_state(ssl); } catch (...) {}
    if (!state) { errno = error.value; int ret = original(ssl, buffer, count); error.value = errno; return ret; }
    std::lock_guard<std::mutex> lock(state->tx);
    if (state->blocked) { error.value = ECONNRESET; return -1; }
    const auto* bytes = static_cast<const uint8_t*>(buffer);
    try {
        if (state->observing) {
            const size_t skip = state->writes.begin(bytes, size_t(count));
            if (!state->writes.enabled()) discard_state(state, true);
            else if (observe(state->id, true, bytes + skip, size_t(count) - skip, true, -1, {}, false, state.get())) {
                state->blocked = true; error.value = ECONNRESET; return -1;
            }
        }
    } catch (...) { discard_state(state, true); }
    errno = error.value;
    const int ret = original(ssl, buffer, count);
    error.value = errno;
    try {
        if (state->observing) {
            state->writes.finish(bytes, size_t(count), ret);
            if (!state->writes.enabled()) discard_state(state, true);
        }
    } catch (...) { discard_state(state, true); }
    return ret;
}
template<int I>
int ssl_read(void* ssl, void* buffer, int count) {
    auto original = ssl_hooks[I].read;
    if (in_hook || java_capture_depth || !buffer || count <= 0) return original(ssl, buffer, count);
    HookGuard guard;
    ErrnoGuard error;
    std::shared_ptr<State> state;
    try { state = ssl_state(ssl); } catch (...) {}
    if (!state) { errno = error.value; int ret = original(ssl, buffer, count); error.value = errno; return ret; }
    std::lock_guard<std::mutex> lock(state->rx);
    if (state->blocked) { error.value = ECONNRESET; return -1; }
    errno = error.value;
    const int ret = original(ssl, buffer, count);
    error.value = errno;
    // Inspect TLS shutdown before making callbacks that may touch thread-local OpenSSL state.
    const bool clean_eof = ret == 0 && ssl_hooks[I].error && ssl_hooks[I].error(ssl, ret) == 6;
    try {
        if (state->observing && ret > 0 && ret <= count && observe(state->id, false,
            static_cast<uint8_t*>(buffer), size_t(ret), true, -1, {}, false, state.get())) {
            state->blocked = true; error.value = ECONNRESET; return -1;
        }
        if (ret == 0 && state->observing && !state->eof.exchange(true)) {
            if (clean_eof) notify(state->id, true, false); else discard_state(state, true);
        }
    } catch (...) { discard_state(state, true); }
    return ret;
}
template<int I>
int ssl_error(const void* ssl, int ret) {
    ErrnoGuard error;
    if (ret <= 0) {
        try {
            auto state = ssl_state(const_cast<void*>(ssl), false);
            if (state && state->blocked) { error.value = ECONNRESET; return 5; } // SSL_ERROR_SYSCALL
        } catch (...) {}
    }
    return ssl_hooks[I].error(ssl, ret);
}
template<int I>
void ssl_free(void* ssl) {
    HookGuard guard;
    ErrnoGuard error;
    std::shared_ptr<State> state;
    {
        std::lock_guard<std::mutex> lock(states_mutex);
        auto it = ssl_states.find(ssl);
        if (it != ssl_states.end()) { state = it->second; ssl_states.erase(it); }
    }
    try { discard_state(state, true); } catch (...) {}
    errno = error.value;
    ssl_hooks[I].free(ssl);
    error.value = errno;
}
SslWrite writes[] = {ssl_write<0>, ssl_write<1>, ssl_write<2>, ssl_write<3>};
SslRead reads[] = {ssl_read<0>, ssl_read<1>, ssl_read<2>, ssl_read<3>};
SslFree frees[] = {ssl_free<0>, ssl_free<1>, ssl_free<2>, ssl_free<3>};
SslError errors[] = {ssl_error<0>, ssl_error<1>, ssl_error<2>, ssl_error<3>};
bool hook(const char* library, const char* symbol, void* replacement, void** original) {
    void* stub = shadowhook_hook_sym_name(library, symbol, replacement, original);
    if (stub) LOGI("Hook registered: %s in %s", symbol, library);
    return stub != nullptr;
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_close_hook_ads_hook_network_NativeRequestHook_setJavaCaptureActive(JNIEnv*, jobject, jboolean active) {
    if (active) ++java_capture_depth;
    else if (java_capture_depth) --java_capture_depth;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_close_hook_ads_hook_network_NativeRequestHook_feedH2Data(
    JNIEnv* env, jobject, jlong id, jboolean local, jbyteArray data, jint offset, jint length, jboolean collect) {
    HookGuard guard;
    ErrnoGuard error;
    if (!data || offset < 0 || length <= 0) return 0;
    const jsize size = env->GetArrayLength(data);
    if (offset > size || length > size - offset) return 0;
    try {
        auto conn = h2_get_or_create(uint64_t(id));
        if (!conn) return 0;
        // Copy bounded regions and release the Java array before calling back into Java.
        std::vector<uint8_t> bytes(std::min(size_t(length), H2_MAX_FEED_BYTES));
        for (jint consumed = 0; consumed < length;) {
            const jint count = jint(std::min(size_t(length - consumed), bytes.size()));
            env->GetByteArrayRegion(data, offset + consumed, count, reinterpret_cast<jbyte*>(bytes.data()));
            if (env->ExceptionCheck()) return 0;
            std::lock_guard<std::mutex> delivery(conn->delivery_mutex);
            auto result = h2_feed(conn, bytes.data(), size_t(count), local, collect);
            h2_callback(conn, result);
            consumed += count;
        }
        if (conn->is_h2) return 1;
        if (!conn->h2_checked && !conn->disabled) return -1; // Matching but incomplete client preface.
        h2_free(uint64_t(id));
        return 0;
    } catch (...) { return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_close_hook_ads_hook_network_NativeRequestHook_freeH2Conn(JNIEnv*, jobject, jlong id) {
    HookGuard guard;
    h2_free(uint64_t(id));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_close_hook_ads_hook_network_NativeRequestHook_initNativeHook(JNIEnv* env, jobject thiz, jboolean enable) {
    HookGuard guard;
    ErrnoGuard error;
    std::lock_guard<std::mutex> lock(init_mutex);
    if (!callback_class) {
        jclass clazz = env->GetObjectClass(thiz);
        if (!clazz) return false;
        on_data = env->GetStaticMethodID(clazz, "onNativeData", "(JZLjava/nio/ByteBuffer;Ljava/lang/String;Ljava/lang/String;Z)Z");
        if (!env->ExceptionCheck()) on_h2_request = env->GetStaticMethodID(clazz, "onH2Request", "(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZZZ)Z");
        if (!env->ExceptionCheck()) on_h2_chunk = env->GetStaticMethodID(clazz, "onH2DataChunk", "(JIZLjava/nio/ByteBuffer;)V");
        if (!env->ExceptionCheck()) collect_body = env->GetStaticMethodID(clazz, "getCollectResponseBody", "()Z");
        if (!env->ExceptionCheck()) on_close = env->GetStaticMethodID(clazz, "onConnectionClosed", "(JZ)V");
        if (!env->ExceptionCheck()) on_discard = env->GetStaticMethodID(clazz, "onConnectionDiscarded", "(JZ)V");
        if (!env->ExceptionCheck()) on_h2_stream_close = env->GetStaticMethodID(clazz, "onH2StreamClosed", "(JI)V");
        if (!env->ExceptionCheck()) on_h2_reset = env->GetStaticMethodID(clazz, "onH2ConnectionReset", "(J)V");
        if (!env->ExceptionCheck()) on_h2_body_discard = env->GetStaticMethodID(clazz, "onH2BodyDiscarded", "(JIZ)V");
        if (env->ExceptionCheck()) { env->DeleteLocalRef(clazz); return false; }
        auto global = static_cast<jclass>(env->NewGlobalRef(clazz));
        env->DeleteLocalRef(clazz);
        if (!global) return false;
        if (env->GetJavaVM(&vm) != JNI_OK || pthread_key_create(&thread_key, detach_thread) != 0) {
            env->DeleteGlobalRef(global);
            jclass exception = env->FindClass("java/lang/IllegalStateException");
            if (exception) env->ThrowNew(exception, "Cannot initialize native callback threads");
            return false;
        }
        callback_class = global;
    }
    if (enable && !hooks_attempted) {
        hooks_attempted = true;
        if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0) return false;
        bool ready = true;
        ready &= hook("libc.so", "send", reinterpret_cast<void*>(hook_send), reinterpret_cast<void**>(&orig_send));
        ready &= hook("libc.so", "recv", reinterpret_cast<void*>(hook_recv), reinterpret_cast<void**>(&orig_recv));
        ready &= hook("libc.so", "sendto", reinterpret_cast<void*>(hook_sendto), reinterpret_cast<void**>(&orig_sendto));
        ready &= hook("libc.so", "recvfrom", reinterpret_cast<void*>(hook_recvfrom), reinterpret_cast<void**>(&orig_recvfrom));
        ready &= hook("libc.so", "write", reinterpret_cast<void*>(hook_write), reinterpret_cast<void**>(&orig_write));
        ready &= hook("libc.so", "read", reinterpret_cast<void*>(hook_read), reinterpret_cast<void**>(&orig_read));
        ready &= hook("libc.so", "close", reinterpret_cast<void*>(hook_close), reinterpret_cast<void**>(&orig_close));
        libc_active = ready;
        for (size_t i = 0; i < sizeof(ssl_hooks) / sizeof(ssl_hooks[0]); ++i) {
            auto& slot = ssl_hooks[i];
            // NativeCrypto_* methods use JNI signatures and must never share these ABI slots.
            hook(slot.library, "SSL_write", reinterpret_cast<void*>(writes[i]), reinterpret_cast<void**>(&slot.write));
            hook(slot.library, "SSL_read", reinterpret_cast<void*>(reads[i]), reinterpret_cast<void**>(&slot.read));
            hook(slot.library, "SSL_free", reinterpret_cast<void*>(frees[i]), reinterpret_cast<void**>(&slot.free));
            hook(slot.library, "SSL_get_error", reinterpret_cast<void*>(errors[i]), reinterpret_cast<void**>(&slot.error));
        }
    }
    return libc_active;
}
