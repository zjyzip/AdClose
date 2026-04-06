#include <jni.h>
#include <string>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <sys/stat.h>
#include <unwind.h>
#include <iomanip>
#include <sstream>
#include <pthread.h>
#include <unordered_map>
#include <unordered_set>
#include <mutex>
#include <atomic>
#include <vector>
#include <cstdlib>
#include "shadowhook.h"
#include "http2_parser.h"

#if DEBUG
    #define LOG_TAG "AdClose-Native"
    #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
    #define LOGI(...)
    #define LOGE(...)
#endif

#define MAX_STACK_DEPTH 12
#define JNI_MAX_BUFFER_MAPPING (2 * 1024 * 1024)

static JavaVM *gJvm = nullptr;
static jclass gNativeRequestHookClass = nullptr;
static jmethodID gOnNativeDataMethod    = nullptr;
static jmethodID gOnH2RequestMethod     = nullptr;
static jmethodID gOnH2DataChunkMethod   = nullptr;
static jmethodID gCollectRespBodyMethod = nullptr;
static jmethodID gOnConnClosedMethod    = nullptr;

static pthread_key_t g_thread_key;
thread_local bool g_is_in_hook = false;
static std::atomic<uint8_t> g_fd_cache[65536];
static std::mutex g_cache_mutex;
static std::unordered_map<jlong, std::string> g_stack_cache;
static std::unordered_map<int, std::string> g_socket_info_cache;

typedef ssize_t (*type_send)(int, const void *, size_t, int);
typedef ssize_t (*type_recv)(int, void *, size_t, int);
typedef ssize_t (*type_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
typedef ssize_t (*type_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *);
typedef ssize_t (*type_write)(int, const void *, size_t);
typedef ssize_t (*type_read)(int, void *, size_t);
typedef int (*type_close)(int);
typedef int (*type_SSL_write)(void *ssl, const void *buf, int num);
typedef int (*type_SSL_read)(void *ssl, void *buf, int num);
typedef void (*type_SSL_free)(void *ssl);

static type_send orig_send;
static type_recv orig_recv;
static type_sendto orig_sendto;
static type_recvfrom orig_recvfrom;
static type_write orig_write;
static type_read orig_read;
static type_close orig_close;

struct SslHookEntry {
    const char* lib_name;
    type_SSL_write orig_ssl_write;
    type_SSL_read  orig_ssl_read;
    type_SSL_free  orig_ssl_free;
};

static SslHookEntry g_ssl_hooks[] = {
    { "libssl.so",             nullptr, nullptr, nullptr },
    { "libconscrypt_jni.so",   nullptr, nullptr, nullptr },
    { "libttboringssl.so",     nullptr, nullptr, nullptr },
    { "libflutter.so",         nullptr, nullptr, nullptr },
};
static const int SSL_HOOK_COUNT = sizeof(g_ssl_hooks) / sizeof(g_ssl_hooks[0]);

struct ScopedHookGuard {
    ScopedHookGuard() { g_is_in_hook = true; }
    ~ScopedHookGuard() { g_is_in_hook = false; }
};

struct JniLocalRefGuard {
    JNIEnv* env;
    std::vector<jobject> refs;
    JniLocalRefGuard(JNIEnv* e) : env(e) {}
    ~JniLocalRefGuard() {
        for (jobject ref : refs) if (ref) env->DeleteLocalRef(ref);
    }
    template<typename T> T add(T ref) {
        if (ref) refs.push_back((jobject)ref);
        return ref;
    }
};

static void detach_current_thread(void *env) { if (gJvm) gJvm->DetachCurrentThread(); }

thread_local JNIEnv* tls_env = nullptr;

JNIEnv* get_jni_env() {
    if (tls_env) return tls_env;
    if (!gJvm) return nullptr;
    int envStat = gJvm->GetEnv((void **)&tls_env, JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&tls_env, nullptr) == JNI_OK) {
            pthread_setspecific(g_thread_key, tls_env);
        } else {
            tls_env = nullptr;
        }
    }
    return tls_env;
}

bool check_exception(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return false;
}

struct BacktraceState { void** current; void** end; };
_Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    BacktraceState* state = static_cast<BacktraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        if (state->current == state->end) return _URC_END_OF_STACK;
        *state->current++ = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

std::string get_native_stack_internal() {
    void* buffer[MAX_STACK_DEPTH];
    BacktraceState state = {buffer, buffer + MAX_STACK_DEPTH};
    _Unwind_Backtrace(unwind_callback, &state);
    std::stringstream ss;
    ss << "Native Stack:\n";
    for (void** ptr = buffer; ptr < state.current; ++ptr) {
        const void* addr = *ptr;
        Dl_info info;
        if (dladdr(addr, &info) && info.dli_fname) {
            uintptr_t offset = (uintptr_t)addr - (uintptr_t)info.dli_fbase;
            const char* lib_name = strrchr(info.dli_fname, '/');
            lib_name = (lib_name != nullptr) ? lib_name + 1 : info.dli_fname;
            ss << "  #" << (ptr - buffer) << " pc " << std::hex << offset << "  " << lib_name << "\n";
        }
    }
    return ss.str();
}

std::string get_cached_stack(jlong id) {
    std::lock_guard<std::mutex> lock(g_cache_mutex);
    auto it = g_stack_cache.find(id);
    if (it != g_stack_cache.end()) return it->second;
    std::string stack = get_native_stack_internal();
    if (g_stack_cache.size() >= 2048) g_stack_cache.clear();
    g_stack_cache[id] = stack;
    return stack;
}

std::string get_cached_socket_info(int fd) {
    if (fd <= 0) return "";
    {
        std::lock_guard<std::mutex> lock(g_cache_mutex);
        auto it = g_socket_info_cache.find(fd);
        if (it != g_socket_info_cache.end()) return it->second;
    }
    struct sockaddr_storage addr; socklen_t len = sizeof(addr);
    if (getpeername(fd, (struct sockaddr*)&addr, &len) != 0) return "";
    char ip_str[INET6_ADDRSTRLEN] = {0}; int port = 0;
    if (addr.ss_family == AF_INET) {
        struct sockaddr_in *s = (struct sockaddr_in *)&addr;
        port = ntohs(s->sin_port); inet_ntop(AF_INET, &s->sin_addr, ip_str, sizeof(ip_str));
    } else if (addr.ss_family == AF_INET6) {
        struct sockaddr_in6 *s = (struct sockaddr_in6 *)&addr;
        port = ntohs(s->sin6_port); inet_ntop(AF_INET6, &s->sin6_addr, ip_str, sizeof(ip_str));
    } else return "unknown";
    std::string info = std::string(ip_str) + ":" + std::to_string(port);
    std::lock_guard<std::mutex> lock(g_cache_mutex);
    g_socket_info_cache[fd] = info;
    return info;
}

bool is_network_fd(int fd) {
    if (fd < 0 || fd >= 65536) return false;
    uint8_t state = g_fd_cache[fd].load(std::memory_order_relaxed);
    if (state != 0) return state == 2;
    struct stat statbuf;
    if (fstat(fd, &statbuf) != 0 || !S_ISSOCK(statbuf.st_mode)) {
        g_fd_cache[fd].store(1, std::memory_order_relaxed); return false;
    }
    struct sockaddr_storage addr; socklen_t len = sizeof(addr);
    if (getsockname(fd, (struct sockaddr*)&addr, &len) == 0 && (addr.ss_family == AF_INET || addr.ss_family == AF_INET6)) {
        g_fd_cache[fd].store(2, std::memory_order_relaxed); return true;
    }
    g_fd_cache[fd].store(1, std::memory_order_relaxed); return false;
}

void notify_kotlin_close(jlong id, bool is_ssl) {
    if (gNativeRequestHookClass == nullptr || gOnConnClosedMethod == nullptr) return;
    JNIEnv *env = get_jni_env();
    if (!env) return;
    env->CallStaticVoidMethod(gNativeRequestHookClass, gOnConnClosedMethod, id, (jboolean)is_ssl);
    check_exception(env);
}

bool callback_kotlin(jlong id, bool is_write, const void *buf, size_t len, bool is_ssl) {
    if (gNativeRequestHookClass == nullptr || buf == nullptr || len <= 0) return false;
    if (len > JNI_MAX_BUFFER_MAPPING) return false;
    if (!is_ssl && !is_network_fd((int)id)) return false;

    JNIEnv *env = get_jni_env();
    if (!env) return false;

    JniLocalRefGuard refGuard(env);

    jobject jBuffer = refGuard.add(env->NewDirectByteBuffer((void*)buf, len));
    if (jBuffer == nullptr) return false;
    
    jstring jInfo = nullptr;
    std::string info = (!is_ssl && id > 0) ? get_cached_socket_info((int)id) : "";
    if (!info.empty()) {
        jInfo = refGuard.add(env->NewStringUTF(info.c_str()));
        if (check_exception(env)) jInfo = nullptr;
    }
    
    jstring jStack = nullptr;
    if (is_write && (rand() % 10 == 0)) { 
        std::string stack = get_cached_stack(id);
        if (!stack.empty()) {
            jStack = refGuard.add(env->NewStringUTF(stack.c_str()));
            if (check_exception(env)) jStack = nullptr;
        }
    }

    jboolean shouldBlock = env->CallStaticBooleanMethod(
        gNativeRequestHookClass, gOnNativeDataMethod, id, is_write, jBuffer, jInfo, jStack, is_ssl
    );

    if (check_exception(env)) return false;
    return (bool)shouldBlock;
}

bool callback_kotlin_h2(uintptr_t conn_id, const H2FeedResult& feed_result) {
    if (gNativeRequestHookClass == nullptr || gOnH2RequestMethod == nullptr || gOnH2DataChunkMethod == nullptr) return false;
    JNIEnv *env = get_jni_env();
    if (!env) return false;
    
    bool should_block = false;
    std::unordered_set<int> newly_blocked_streams;
    
    for (const auto& check : feed_result.early_checks) {
        JniLocalRefGuard refGuard(env);

        std::string req_hdr_str;
        for (const auto& kv : check.req_headers)  { req_hdr_str  += kv.first + "\n" + kv.second + "\n"; }
        
        jstring jMethod = refGuard.add(env->NewStringUTF(check.method.c_str()));
        jstring jPath = refGuard.add(env->NewStringUTF(check.path.c_str()));
        jstring jAuthority = refGuard.add(env->NewStringUTF(check.authority.c_str()));
        jstring jScheme = refGuard.add(env->NewStringUTF(check.scheme.c_str()));
        jstring jReqHdr = refGuard.add(env->NewStringUTF(req_hdr_str.c_str()));
        
        if (check_exception(env)) continue;

        jboolean blocked = env->CallStaticBooleanMethod(
            gNativeRequestHookClass, gOnH2RequestMethod, (jlong)conn_id, (jint)check.stream_id, 
            jMethod, jPath, jAuthority, jScheme, jReqHdr, nullptr, (jint)-1, (jboolean)false
        );

        if (check_exception(env)) blocked = false;
        
        if (blocked) {
            newly_blocked_streams.insert(check.stream_id);
            h2_enqueue_rst_stream(conn_id, check.stream_id, 0x8);
            h2_block_stream(conn_id, check.stream_id);
            should_block = true;
        }
    }

    for (const auto& chunk : feed_result.data_chunks) {
        if (newly_blocked_streams.count(chunk.stream_id)) continue;
        if (chunk.data.size() > JNI_MAX_BUFFER_MAPPING) continue;
        
        JniLocalRefGuard refGuard(env);
        jobject jBuffer = refGuard.add(env->NewDirectByteBuffer((void*)chunk.data.data(), chunk.data.size()));
        if (jBuffer == nullptr) continue;

        env->CallStaticVoidMethod(gNativeRequestHookClass, gOnH2DataChunkMethod,
                                  (jlong)conn_id, (jint)chunk.stream_id, (jboolean)chunk.is_request, jBuffer);
        check_exception(env);
    }

    for (const auto& req : feed_result.completed) {
        if (newly_blocked_streams.count(req.stream_id)) continue;
        JniLocalRefGuard refGuard(env);

        std::string req_hdr_str, resp_hdr_str;
        for (const auto& kv : req.req_headers)  { req_hdr_str  += kv.first + "\n" + kv.second + "\n"; }
        for (const auto& kv : req.resp_headers) { resp_hdr_str += kv.first + "\n" + kv.second + "\n"; }
        
        jstring jMethod = refGuard.add(env->NewStringUTF(req.method.c_str()));
        jstring jPath = refGuard.add(env->NewStringUTF(req.path.c_str()));
        jstring jAuthority = refGuard.add(env->NewStringUTF(req.authority.c_str()));
        jstring jScheme = refGuard.add(env->NewStringUTF(req.scheme.c_str()));
        jstring jReqHdr = refGuard.add(env->NewStringUTF(req_hdr_str.c_str()));
        jstring jRespHdr = refGuard.add(env->NewStringUTF(resp_hdr_str.c_str()));
        
        if (check_exception(env)) continue;

        jboolean blocked = env->CallStaticBooleanMethod(
            gNativeRequestHookClass, gOnH2RequestMethod, (jlong)conn_id, (jint)req.stream_id, 
            jMethod, jPath, jAuthority, jScheme, jReqHdr, jRespHdr, (jint)req.status_code, (jboolean)true
        );

        if (check_exception(env)) blocked = false;
        
        if (blocked) {
            h2_enqueue_rst_stream(conn_id, req.stream_id, 0x8);
            should_block = true;
        }
    }

    return should_block;
}

bool callback_collect_resp_body() {
    if (gNativeRequestHookClass == nullptr || gCollectRespBodyMethod == nullptr) return false;
    JNIEnv *env = get_jni_env();
    if (!env) return false;
    jboolean res = env->CallStaticBooleanMethod(gNativeRequestHookClass, gCollectRespBodyMethod);
    if (check_exception(env)) return false;
    return (bool)res;
}

ssize_t hook_send(int s, const void *buf, size_t len, int flags) {
    if (g_is_in_hook) return orig_send(s, buf, len, flags);
    ScopedHookGuard guard;
    if (callback_kotlin((jlong)s, true, buf, len, false)) { errno = ECONNRESET; return -1; }
    return orig_send(s, buf, len, flags);
}
ssize_t hook_recv(int s, void *buf, size_t len, int flags) {
    if (g_is_in_hook) return orig_recv(s, buf, len, flags);
    ScopedHookGuard guard;
    ssize_t ret = orig_recv(s, buf, len, flags);
    if (ret > 0) callback_kotlin((jlong)s, false, buf, ret, false);
    return ret;
}
ssize_t hook_sendto(int s, const void *buf, size_t len, int flags, const struct sockaddr *to, socklen_t tolen) {
    if (g_is_in_hook) return orig_sendto(s, buf, len, flags, to, tolen);
    ScopedHookGuard guard;
    if (callback_kotlin((jlong)s, true, buf, len, false)) { errno = ECONNRESET; return -1; }
    return orig_sendto(s, buf, len, flags, to, tolen);
}
ssize_t hook_recvfrom(int s, void *buf, size_t len, int flags, struct sockaddr *from, socklen_t *fromlen) {
    if (g_is_in_hook) return orig_recvfrom(s, buf, len, flags, from, fromlen);
    ScopedHookGuard guard;
    ssize_t ret = orig_recvfrom(s, buf, len, flags, from, fromlen);
    if (ret > 0) callback_kotlin((jlong)s, false, buf, ret, false);
    return ret;
}
ssize_t hook_write(int fd, const void *buf, size_t count) {
    if (g_is_in_hook || fd <= 2) return orig_write(fd, buf, count);
    ScopedHookGuard guard;
    if (callback_kotlin((jlong)fd, true, buf, count, false)) { errno = ECONNRESET; return -1; }
    return orig_write(fd, buf, count);
}
ssize_t hook_read(int fd, void *buf, size_t count) {
    if (g_is_in_hook || fd <= 2) return orig_read(fd, buf, count);
    ScopedHookGuard guard;
    ssize_t ret = orig_read(fd, buf, count);
    if (ret > 0) callback_kotlin((jlong)fd, false, buf, ret, false);
    return ret;
}
int hook_close(int fd) {
    if (g_is_in_hook) return orig_close(fd);
    ScopedHookGuard guard;
    if (fd >= 0 && fd < 65536) g_fd_cache[fd].store(0, std::memory_order_relaxed);
    { std::lock_guard<std::mutex> lock(g_cache_mutex); g_stack_cache.erase((jlong)fd); g_socket_info_cache.erase(fd); }
    notify_kotlin_close((jlong)fd, false);
    return orig_close(fd);
}

template<int IDX>
int hook_SSL_write_t(void *ssl, const void *buf, int num) {
    if (g_is_in_hook) return g_ssl_hooks[IDX].orig_ssl_write(ssl, buf, num);
    ScopedHookGuard guard;
    uintptr_t conn_id = reinterpret_cast<uintptr_t>(ssl);
    std::shared_ptr<Http2Connection> h2conn = h2_get_or_create(conn_id);
    
    std::vector<std::vector<uint8_t>> local_rst_queue;

    if (h2conn != nullptr && buf != nullptr && num > 0) {
        bool collect = callback_collect_resp_body();
        auto feed_res = h2_feed(h2conn, static_cast<const uint8_t*>(buf), (size_t)num, true, collect);
        if (!feed_res.early_checks.empty() || !feed_res.data_chunks.empty() || !feed_res.completed.empty()) {
            callback_kotlin_h2(conn_id, feed_res);
        }
        
        auto new_rst = h2_take_rst_frames(conn_id);
        if (!new_rst.empty()) {
            local_rst_queue.insert(local_rst_queue.end(), new_rst.begin(), new_rst.end());
        }
        
        if (h2conn->is_h2) {
            int ret = g_ssl_hooks[IDX].orig_ssl_write(ssl, buf, num);
            if (ret > 0 && !local_rst_queue.empty()) {
                for (const auto& frame : local_rst_queue) {
                    g_ssl_hooks[IDX].orig_ssl_write(ssl, frame.data(), (int)frame.size());
                }
            }
            return ret;
        }
    }

    if (buf != nullptr && num > 0) {
        if (callback_kotlin(reinterpret_cast<jlong>(ssl), true, buf, num, true)) {
            return -1;
        }
    }
    
    return g_ssl_hooks[IDX].orig_ssl_write(ssl, buf, num);
}

template<int IDX>
int hook_SSL_read_t(void *ssl, void *buf, int num) {
    if (g_is_in_hook) return g_ssl_hooks[IDX].orig_ssl_read(ssl, buf, num);
    ScopedHookGuard guard;
    int ret = g_ssl_hooks[IDX].orig_ssl_read(ssl, buf, num);
    if (ret > 0 && buf != nullptr) {
        uintptr_t conn_id = reinterpret_cast<uintptr_t>(ssl);
        if (h2_is_http2(conn_id)) {
            std::shared_ptr<Http2Connection> h2conn = h2_get_or_create(conn_id);
            if (h2conn != nullptr) {
                bool collect = callback_collect_resp_body();
                auto feed_res = h2_feed(h2conn, static_cast<const uint8_t*>(buf), (size_t)ret, false, collect);
                if (!feed_res.early_checks.empty() || !feed_res.data_chunks.empty() || !feed_res.completed.empty()) {
                    callback_kotlin_h2(conn_id, feed_res);
                }
            }
        } else callback_kotlin(reinterpret_cast<jlong>(ssl), false, buf, ret, true);
    }
    return ret;
}

template<int IDX>
void hook_SSL_free_t(void *ssl) {
    if (g_is_in_hook) { g_ssl_hooks[IDX].orig_ssl_free(ssl); return; }
    ScopedHookGuard guard;
    { std::lock_guard<std::mutex> lock(g_cache_mutex); g_stack_cache.erase(reinterpret_cast<jlong>(ssl)); }
    h2_free(reinterpret_cast<uintptr_t>(ssl));
    notify_kotlin_close(reinterpret_cast<jlong>(ssl), true);
    g_ssl_hooks[IDX].orig_ssl_free(ssl);
}

static type_SSL_write ssl_write_hooks[] = { hook_SSL_write_t<0>, hook_SSL_write_t<1>, hook_SSL_write_t<2>, hook_SSL_write_t<3> };
static type_SSL_read ssl_read_hooks[] = { hook_SSL_read_t<0>, hook_SSL_read_t<1>, hook_SSL_read_t<2>, hook_SSL_read_t<3> };
static type_SSL_free ssl_free_hooks[] = { hook_SSL_free_t<0>, hook_SSL_free_t<1>, hook_SSL_free_t<2>, hook_SSL_free_t<3> };

void hook_func(const char *lib_name, const char *sym_name, void *hook_func, void **orig_func) {
    void *stub = shadowhook_hook_sym_name(lib_name, sym_name, hook_func, orig_func);
    if (stub != nullptr) LOGI("ShadowHook SUCCESS: %s in %s", sym_name, lib_name ? lib_name : "global");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_close_hook_ads_hook_gc_network_NativeRequestHook_feedH2Data(
    JNIEnv *env, jclass clazz, jlong connId, jboolean isLocal, jbyteArray data, jint offset, jint length, jboolean collectRespBody) {
    if (data == nullptr || length <= 0) return 0;
    jbyte* buf = env->GetByteArrayElements(data, nullptr);
    if (buf == nullptr) return 0;
    std::shared_ptr<Http2Connection> conn = h2_get_or_create((uintptr_t)connId);
    bool should_block = false;
    if (conn != nullptr) {
        auto feed_res = h2_feed(conn, (const uint8_t*)(buf + offset), (size_t)length, (bool)isLocal, (bool)collectRespBody);
        if (!feed_res.early_checks.empty() || !feed_res.data_chunks.empty() || !feed_res.completed.empty()) {
            should_block = callback_kotlin_h2((uintptr_t)connId, feed_res);
        }
    }
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    if (conn != nullptr && conn->is_h2) return should_block ? 2 : 1;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_close_hook_ads_hook_gc_network_NativeRequestHook_freeH2Conn(JNIEnv *env, jclass clazz, jlong connId) {
    h2_free((uintptr_t)connId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_close_hook_ads_hook_gc_network_NativeRequestHook_initNativeHook(JNIEnv *env, jobject thiz, jboolean enableNativeHook) {
    env->GetJavaVM(&gJvm);
    pthread_key_create(&g_thread_key, detach_current_thread);
    jclass clazz = env->FindClass("com/close/hook/ads/hook/gc/network/NativeRequestHook");
    if (!clazz) return;
    gNativeRequestHookClass = (jclass) env->NewGlobalRef(clazz);
    
    gOnNativeDataMethod = env->GetStaticMethodID(clazz, "onNativeData", "(JZLjava/nio/ByteBuffer;Ljava/lang/String;Ljava/lang/String;Z)Z");
    gOnH2RequestMethod = env->GetStaticMethodID(clazz, "onH2Request", "(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZ)Z");
    gOnH2DataChunkMethod = env->GetStaticMethodID(clazz, "onH2DataChunk", "(JIZLjava/nio/ByteBuffer;)V");
    gCollectRespBodyMethod = env->GetStaticMethodID(clazz, "getCollectResponseBody", "()Z");
    gOnConnClosedMethod = env->GetStaticMethodID(clazz, "onConnectionClosed", "(JZ)V");

    if (enableNativeHook) {
        shadowhook_init(SHADOWHOOK_MODE_UNIQUE, true);
        hook_func("libc.so", "send", (void*)hook_send, (void**)&orig_send);
        hook_func("libc.so", "recv", (void*)hook_recv, (void**)&orig_recv);
        hook_func("libc.so", "sendto", (void*)hook_sendto, (void**)&orig_sendto);
        hook_func("libc.so", "recvfrom", (void*)hook_recvfrom, (void**)&orig_recvfrom);
        hook_func("libc.so", "write", (void*)hook_write, (void**)&orig_write);
        hook_func("libc.so", "read", (void*)hook_read, (void**)&orig_read);
        hook_func("libc.so", "close", (void*)hook_close, (void**)&orig_close);
        for (int i = 0; i < SSL_HOOK_COUNT; i++) {
            const char* lib = g_ssl_hooks[i].lib_name;
            hook_func(lib, "SSL_write", (void*)ssl_write_hooks[i], (void**)&g_ssl_hooks[i].orig_ssl_write);
            hook_func(lib, "SSL_read", (void*)ssl_read_hooks[i], (void**)&g_ssl_hooks[i].orig_ssl_read);
            hook_func(lib, "NativeCrypto_SSL_write", (void*)ssl_write_hooks[i], (void**)&g_ssl_hooks[i].orig_ssl_write);
            hook_func(lib, "NativeCrypto_SSL_read", (void*)ssl_read_hooks[i], (void**)&g_ssl_hooks[i].orig_ssl_read);
            hook_func(lib, "SSL_free", (void*)ssl_free_hooks[i], (void**)&g_ssl_hooks[i].orig_ssl_free);
            hook_func(lib, "NativeCrypto_SSL_free", (void*)ssl_free_hooks[i], (void**)&g_ssl_hooks[i].orig_ssl_free);
        }
    }
}
