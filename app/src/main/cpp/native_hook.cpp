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
#include <mutex>
#include <atomic>
#include "shadowhook.h"

#if DEBUG
    #define LOG_TAG "AdClose-Native"
    #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
    #define LOGI(...)
    #define LOGE(...)
#endif

#define MAX_STACK_DEPTH 12
#define MAX_PAYLOAD_SIZE (5 * 1024 * 1024)

static JavaVM *gJvm = nullptr;
static jclass gNativeRequestHookClass = nullptr;
static jmethodID gOnNativeDataMethod = nullptr;

static pthread_key_t g_thread_key;

thread_local bool g_is_in_hook = false;

static std::atomic<uint8_t> g_fd_cache[65536];

static std::mutex g_cache_mutex;
static std::unordered_map<jlong, std::string> g_stack_cache;
static std::unordered_map<int, std::string> g_socket_info_cache;

// --- Stub 定义 ---
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

static type_SSL_write orig_SSL_write_libssl = nullptr;
static type_SSL_read  orig_SSL_read_libssl  = nullptr;
static type_SSL_free  orig_SSL_free_libssl  = nullptr;

static type_SSL_write orig_SSL_write_conscrypt = nullptr;
static type_SSL_read  orig_SSL_read_conscrypt  = nullptr;
static type_SSL_free  orig_SSL_free_conscrypt  = nullptr;

static type_SSL_write orig_SSL_write_ttboringssl = nullptr;
static type_SSL_read  orig_SSL_read_ttboringssl  = nullptr;
static type_SSL_free  orig_SSL_free_ttboringssl  = nullptr;

static type_SSL_write orig_SSL_write_flutter = nullptr;
static type_SSL_read  orig_SSL_read_flutter  = nullptr;
static type_SSL_free  orig_SSL_free_flutter  = nullptr;

struct ScopedHookGuard {
    ScopedHookGuard() { g_is_in_hook = true; }
    ~ScopedHookGuard() { g_is_in_hook = false; }
};

static void detach_current_thread(void *env) {
    if (gJvm) gJvm->DetachCurrentThread();
}

// --- 堆栈与信息工具 ---
struct BacktraceState {
    void** current;
    void** end;
};

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
            ss << "  #" << std::setw(2) << (ptr - buffer) << " pc "
               << std::setw(8) << std::setfill('0') << std::hex << offset
               << "  " << lib_name;
            if (info.dli_sname) ss << " (" << info.dli_sname << ")";
            ss << "\n";
        } else {
            ss << "  #" << std::setw(2) << (ptr - buffer) << " pc " << addr << "\n";
        }
    }
    return ss.str();
}

std::string get_cached_stack(jlong id) {
    std::lock_guard<std::mutex> lock(g_cache_mutex);
    auto it = g_stack_cache.find(id);
    if (it != g_stack_cache.end()) {
        return it->second;
    }
    std::string stack = get_native_stack_internal();
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

    struct sockaddr_storage addr;
    socklen_t len = sizeof(addr);
    if (getpeername(fd, (struct sockaddr*)&addr, &len) != 0) return "";

    char ip_str[INET6_ADDRSTRLEN] = {0};
    int port = 0;

    if (addr.ss_family == AF_INET) {
        struct sockaddr_in *s = (struct sockaddr_in *)&addr;
        port = ntohs(s->sin_port);
        inet_ntop(AF_INET, &s->sin_addr, ip_str, sizeof(ip_str));
    } else if (addr.ss_family == AF_INET6) {
        struct sockaddr_in6 *s = (struct sockaddr_in6 *)&addr;
        port = ntohs(s->sin6_port);
        inet_ntop(AF_INET6, &s->sin6_addr, ip_str, sizeof(ip_str));
    } else {
        return "unknown";
    }

    std::string info = std::string(ip_str) + ":" + std::to_string(port);
    std::lock_guard<std::mutex> lock(g_cache_mutex);
    g_socket_info_cache[fd] = info;
    return info;
}

// FD 性能判定核心
bool is_network_fd(int fd) {
    if (fd < 0 || fd >= 65536) return false;
    uint8_t state = g_fd_cache[fd].load(std::memory_order_relaxed);
    if (state != 0) return state == 2;

    struct stat statbuf;
    if (fstat(fd, &statbuf) != 0 || !S_ISSOCK(statbuf.st_mode)) {
        g_fd_cache[fd].store(1, std::memory_order_relaxed);
        return false;
    }

    struct sockaddr_storage addr;
    socklen_t len = sizeof(addr);
    if (getsockname(fd, (struct sockaddr*)&addr, &len) == 0 &&
        (addr.ss_family == AF_INET || addr.ss_family == AF_INET6)) {
        g_fd_cache[fd].store(2, std::memory_order_relaxed);
        return true;
    }

    g_fd_cache[fd].store(1, std::memory_order_relaxed);
    return false;
}

// 回调核心
bool callback_kotlin(jlong id, bool is_write, const void *buf, size_t len, bool is_ssl) {
    if (gJvm == nullptr || gNativeRequestHookClass == nullptr || buf == nullptr || len <= 0) return false;
    if (len > MAX_PAYLOAD_SIZE) return false;

    if (!is_ssl) {
        int fd = (int)id;
        if (!is_network_fd(fd)) return false;
    }

    JNIEnv *env = nullptr;
    int envStat = gJvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            pthread_setspecific(g_thread_key, env);
        } else {
            return false;
        }
    } else if (envStat != JNI_OK) {
        return false;
    }

    jbyteArray jData = env->NewByteArray(len);
    if (jData == nullptr) return false;

    env->SetByteArrayRegion(jData, 0, len, (const jbyte *)buf);

    std::string info = (!is_ssl && id > 0) ? get_cached_socket_info((int)id) : "";
    jstring jInfo = info.empty() ? nullptr : env->NewStringUTF(info.c_str());

    std::string stack = get_cached_stack(id);
    jstring jStack = stack.empty() ? nullptr : env->NewStringUTF(stack.c_str());

    bool shouldBlock = env->CallStaticBooleanMethod(
        gNativeRequestHookClass,
        gOnNativeDataMethod,
        id, is_write, jData, jInfo, jStack, is_ssl
    );

    env->DeleteLocalRef(jData);
    if (jInfo) env->DeleteLocalRef(jInfo);
    if (jStack) env->DeleteLocalRef(jStack);

    return shouldBlock;
}

// --- Hooks ---

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
    if (fd < 65536) {
        uint8_t state = g_fd_cache[fd].load(std::memory_order_relaxed);
        if (state == 1) return orig_write(fd, buf, count);
    }
    ScopedHookGuard guard;
    if (callback_kotlin((jlong)fd, true, buf, count, false)) { errno = ECONNRESET; return -1; }
    return orig_write(fd, buf, count);
}

ssize_t hook_read(int fd, void *buf, size_t count) {
    if (g_is_in_hook || fd <= 2) return orig_read(fd, buf, count);
    if (fd < 65536) {
        uint8_t state = g_fd_cache[fd].load(std::memory_order_relaxed);
        if (state == 1) return orig_read(fd, buf, count);
    }
    ScopedHookGuard guard;
    ssize_t ret = orig_read(fd, buf, count);
    if (ret > 0) callback_kotlin((jlong)fd, false, buf, ret, false);
    return ret;
}

int hook_close(int fd) {
    if (g_is_in_hook) return orig_close(fd);
    ScopedHookGuard guard;

    if (fd >= 0 && fd < 65536) {
        g_fd_cache[fd].store(0, std::memory_order_relaxed);
    }
    {
        std::lock_guard<std::mutex> lock(g_cache_mutex);
        g_stack_cache.erase((jlong)fd);
        g_socket_info_cache.erase(fd);
    }
    return orig_close(fd);
}

#define DEFINE_SSL_HOOKS(suffix, orig_write_ptr, orig_read_ptr, orig_free_ptr)   \
int hook_SSL_write_##suffix(void *ssl, const void *buf, int num) {               \
    if (g_is_in_hook) return orig_write_ptr(ssl, buf, num);                      \
    ScopedHookGuard guard;                                                        \
    if (buf != nullptr && num > 0) {                                              \
        callback_kotlin(reinterpret_cast<jlong>(ssl), true, buf, num, true);     \
    }                                                                             \
    return orig_write_ptr(ssl, buf, num);                                         \
}                                                                                 \
int hook_SSL_read_##suffix(void *ssl, void *buf, int num) {                      \
    if (g_is_in_hook) return orig_read_ptr(ssl, buf, num);                       \
    ScopedHookGuard guard;                                                        \
    int ret = orig_read_ptr(ssl, buf, num);                                       \
    if (ret > 0 && buf != nullptr) {                                              \
        callback_kotlin(reinterpret_cast<jlong>(ssl), false, buf, ret, true);    \
    }                                                                             \
    return ret;                                                                   \
}                                                                                 \
void hook_SSL_free_##suffix(void *ssl) {                                         \
    if (g_is_in_hook) { orig_free_ptr(ssl); return; }                            \
    ScopedHookGuard guard;                                                        \
    {                                                                             \
        std::lock_guard<std::mutex> lock(g_cache_mutex);                         \
        g_stack_cache.erase(reinterpret_cast<jlong>(ssl));                       \
    }                                                                             \
    orig_free_ptr(ssl);                                                           \
}

DEFINE_SSL_HOOKS(libssl,      orig_SSL_write_libssl,      orig_SSL_read_libssl,      orig_SSL_free_libssl)
DEFINE_SSL_HOOKS(conscrypt,   orig_SSL_write_conscrypt,   orig_SSL_read_conscrypt,   orig_SSL_free_conscrypt)
DEFINE_SSL_HOOKS(ttboringssl, orig_SSL_write_ttboringssl, orig_SSL_read_ttboringssl, orig_SSL_free_ttboringssl)
DEFINE_SSL_HOOKS(flutter,     orig_SSL_write_flutter,     orig_SSL_read_flutter,     orig_SSL_free_flutter)

// --- Init ---

void hook_func(const char *lib_name, const char *sym_name, void *hook_func, void **orig_func) {
    void *stub = shadowhook_hook_sym_name(lib_name, sym_name, hook_func, orig_func);
    if (stub != nullptr) {
        LOGI("ShadowHook SUCCESS: %s in %s", sym_name, lib_name ? lib_name : "global");
    } else {
        if (shadowhook_get_errno() != 2) {
            LOGE("ShadowHook ERROR: %s", sym_name);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_close_hook_ads_hook_gc_network_NativeRequestHook_initNativeHook(JNIEnv *env, jobject thiz) {
    env->GetJavaVM(&gJvm);

    // 初始化线程 Key
    pthread_key_create(&g_thread_key, detach_current_thread);

    jclass clazz = env->FindClass("com/close/hook/ads/hook/gc/network/NativeRequestHook");
    if (!clazz) return;
    gNativeRequestHookClass = (jclass) env->NewGlobalRef(clazz);

    gOnNativeDataMethod = env->GetStaticMethodID(clazz, "onNativeData", "(JZ[BLjava/lang/String;Ljava/lang/String;Z)Z");
    if (!gOnNativeDataMethod) return;

    for (int i = 0; i < 65536; i++) {
        g_fd_cache[i].store(0, std::memory_order_relaxed);
    }

    shadowhook_init(SHADOWHOOK_MODE_UNIQUE, true);

    // Hook Libc
    hook_func("libc.so", "send",     (void*)hook_send,     (void**)&orig_send);
    hook_func("libc.so", "recv",     (void*)hook_recv,     (void**)&orig_recv);
    hook_func("libc.so", "sendto",   (void*)hook_sendto,   (void**)&orig_sendto);
    hook_func("libc.so", "recvfrom", (void*)hook_recvfrom, (void**)&orig_recvfrom);
    hook_func("libc.so", "write",    (void*)hook_write,    (void**)&orig_write);
    hook_func("libc.so", "read",     (void*)hook_read,     (void**)&orig_read);
    hook_func("libc.so", "close",    (void*)hook_close,    (void**)&orig_close);

    // Hook SSL
    hook_func("libssl.so",           "SSL_write",              (void*)hook_SSL_write_libssl,      (void**)&orig_SSL_write_libssl);
    hook_func("libssl.so",           "SSL_read",               (void*)hook_SSL_read_libssl,       (void**)&orig_SSL_read_libssl);
    hook_func("libssl.so",           "SSL_free",               (void*)hook_SSL_free_libssl,       (void**)&orig_SSL_free_libssl);

    hook_func("libconscrypt_jni.so", "SSL_write",              (void*)hook_SSL_write_conscrypt,   (void**)&orig_SSL_write_conscrypt);
    hook_func("libconscrypt_jni.so", "SSL_read",               (void*)hook_SSL_read_conscrypt,    (void**)&orig_SSL_read_conscrypt);
    hook_func("libconscrypt_jni.so", "SSL_free",               (void*)hook_SSL_free_conscrypt,    (void**)&orig_SSL_free_conscrypt);
    hook_func("libconscrypt_jni.so", "NativeCrypto_SSL_write", (void*)hook_SSL_write_conscrypt,   (void**)&orig_SSL_write_conscrypt);
    hook_func("libconscrypt_jni.so", "NativeCrypto_SSL_read",  (void*)hook_SSL_read_conscrypt,    (void**)&orig_SSL_read_conscrypt);
    hook_func("libconscrypt_jni.so", "NativeCrypto_SSL_free",  (void*)hook_SSL_free_conscrypt,    (void**)&orig_SSL_free_conscrypt);

    hook_func("libttboringssl.so",   "SSL_write",              (void*)hook_SSL_write_ttboringssl, (void**)&orig_SSL_write_ttboringssl);
    hook_func("libttboringssl.so",   "SSL_read",               (void*)hook_SSL_read_ttboringssl,  (void**)&orig_SSL_read_ttboringssl);
    hook_func("libttboringssl.so",   "SSL_free",               (void*)hook_SSL_free_ttboringssl,  (void**)&orig_SSL_free_ttboringssl);

    hook_func("libflutter.so",       "SSL_write",              (void*)hook_SSL_write_flutter,     (void**)&orig_SSL_write_flutter);
    hook_func("libflutter.so",       "SSL_read",               (void*)hook_SSL_read_flutter,      (void**)&orig_SSL_read_flutter);
    hook_func("libflutter.so",       "SSL_free",               (void*)hook_SSL_free_flutter,      (void**)&orig_SSL_free_flutter);
}
