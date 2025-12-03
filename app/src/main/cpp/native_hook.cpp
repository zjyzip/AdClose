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
#include "shadowhook.h"

#if DEBUG
    #define LOG_TAG "AdClose-Native"
    #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
    #define LOGI(...)
    #define LOGE(...)
#endif

#define MAX_BUFFER_SIZE (32 * 1024)
#define MAX_STACK_DEPTH 12

static JavaVM *gJvm = nullptr;
static jclass gNativeRequestHookClass = nullptr;
static jmethodID gOnNativeDataMethod = nullptr;

// --- Stub 定义 ---
typedef ssize_t (*type_send)(int, const void *, size_t, int);
typedef ssize_t (*type_recv)(int, void *, size_t, int);
typedef ssize_t (*type_sendto)(int, const void *, size_t, int, const struct sockaddr *, socklen_t);
typedef ssize_t (*type_recvfrom)(int, void *, size_t, int, struct sockaddr *, socklen_t *);
typedef ssize_t (*type_write)(int, const void *, size_t);
typedef ssize_t (*type_read)(int, void *, size_t);
typedef int (*type_SSL_write)(void *ssl, const void *buf, int num);
typedef int (*type_SSL_read)(void *ssl, void *buf, int num);

static type_send orig_send;
static type_recv orig_recv;
static type_sendto orig_sendto;
static type_recvfrom orig_recvfrom;
static type_write orig_write;
static type_read orig_read;
static type_SSL_write orig_SSL_write;
static type_SSL_read orig_SSL_read;

// --- 堆栈回溯 ---

struct BacktraceState {
    void** current;
    void** end;
};

_Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    BacktraceState* state = static_cast<BacktraceState*>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        if (state->current == state->end) {
            return _URC_END_OF_STACK;
        }
        *state->current++ = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

std::string get_native_stack() {
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

std::string get_socket_info(int fd) {
    if (fd <= 0) return "";
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
    return std::string(ip_str) + ":" + std::to_string(port);
}

bool is_socket(int fd) {
    struct stat statbuf;
    if (fstat(fd, &statbuf) != 0) return false;
    return S_ISSOCK(statbuf.st_mode);
}

bool is_network_socket(int fd) {
    struct sockaddr_storage addr;
    socklen_t len = sizeof(addr);
    if (getsockname(fd, (struct sockaddr*)&addr, &len) != 0) return false;
    return (addr.ss_family == AF_INET || addr.ss_family == AF_INET6);
}

// 回调核心
bool callback_kotlin(int id, bool is_write, const void *buf, size_t len, bool is_ssl) {
    if (gJvm == nullptr || gNativeRequestHookClass == nullptr || buf == nullptr) return false;
    if (len <= 0 || len > 100 * 1024 * 1024) return false;

    if (!is_ssl) {
        if (!is_socket(id)) return false;
        if (!is_network_socket(id)) return false;
    }

    JNIEnv *env;
    bool needsDetach = false;
    int getEnvStat = gJvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, nullptr) != 0) return false;
        needsDetach = true;
    } else if (getEnvStat != JNI_OK) {
        return false;
    }

    bool shouldBlock = false;
    size_t copy_len = (len > MAX_BUFFER_SIZE) ? MAX_BUFFER_SIZE : len;

    jbyteArray jData = env->NewByteArray(copy_len);
    if (jData != nullptr) {
        env->SetByteArrayRegion(jData, 0, copy_len, (const jbyte *)buf);
        
        std::string info = (!is_ssl && id > 0) ? get_socket_info(id) : "";
        jstring jInfo = env->NewStringUTF(info.c_str());
        
        std::string stack = get_native_stack();
        jstring jStack = env->NewStringUTF(stack.c_str());

        shouldBlock = env->CallStaticBooleanMethod(
            gNativeRequestHookClass, 
            gOnNativeDataMethod, 
            id, is_write, jData, jInfo, jStack, is_ssl
        );

        env->DeleteLocalRef(jData);
        env->DeleteLocalRef(jInfo);
        env->DeleteLocalRef(jStack);
    }

    if (needsDetach) gJvm->DetachCurrentThread();
    return shouldBlock;
}

// --- Hooks ---

ssize_t hook_send(int s, const void *buf, size_t len, int flags) {
    if (callback_kotlin(s, true, buf, len, false)) return -1;
    return orig_send(s, buf, len, flags);
}

ssize_t hook_recv(int s, void *buf, size_t len, int flags) {
    ssize_t ret = orig_recv(s, buf, len, flags);
    if (ret > 0) callback_kotlin(s, false, buf, ret, false);
    return ret;
}

ssize_t hook_sendto(int s, const void *buf, size_t len, int flags, const struct sockaddr *to, socklen_t tolen) {
    if (callback_kotlin(s, true, buf, len, false)) return -1;
    return orig_sendto(s, buf, len, flags, to, tolen);
}

ssize_t hook_recvfrom(int s, void *buf, size_t len, int flags, struct sockaddr *from, socklen_t *fromlen) {
    ssize_t ret = orig_recvfrom(s, buf, len, flags, from, fromlen);
    if (ret > 0) callback_kotlin(s, false, buf, ret, false);
    return ret;
}

ssize_t hook_write(int fd, const void *buf, size_t count) {
    if (fd > 2 && callback_kotlin(fd, true, buf, count, false)) return -1;
    return orig_write(fd, buf, count);
}

ssize_t hook_read(int fd, void *buf, size_t count) {
    ssize_t ret = orig_read(fd, buf, count);
    if (ret > 0 && fd > 2) callback_kotlin(fd, false, buf, ret, false);
    return ret;
}

int hook_SSL_write(void *ssl, const void *buf, int num) {
    if (buf != nullptr && num > 0) {
        callback_kotlin((int)(long)ssl, true, buf, num, true);
    }
    return orig_SSL_write(ssl, buf, num);
}

int hook_SSL_read(void *ssl, void *buf, int num) {
    int ret = orig_SSL_read(ssl, buf, num);
    if (ret > 0 && buf != nullptr) {
        callback_kotlin((int)(long)ssl, false, buf, ret, true);
    }
    return ret;
}

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
    
    jclass clazz = env->FindClass("com/close/hook/ads/hook/gc/network/NativeRequestHook");
    if (!clazz) return;
    gNativeRequestHookClass = (jclass) env->NewGlobalRef(clazz);
    
    gOnNativeDataMethod = env->GetStaticMethodID(clazz, "onNativeData", "(IZ[BLjava/lang/String;Ljava/lang/String;Z)Z");
    if (!gOnNativeDataMethod) return;

    shadowhook_init(SHADOWHOOK_MODE_UNIQUE, true);

    // Hook Libc
    hook_func("libc.so", "send", (void*)hook_send, (void**)&orig_send);
    hook_func("libc.so", "recv", (void*)hook_recv, (void**)&orig_recv);
    hook_func("libc.so", "sendto", (void*)hook_sendto, (void**)&orig_sendto);
    hook_func("libc.so", "recvfrom", (void*)hook_recvfrom, (void**)&orig_recvfrom);
    hook_func("libc.so", "write", (void*)hook_write, (void**)&orig_write);
    hook_func("libc.so", "read", (void*)hook_read, (void**)&orig_read);

    // Hook SSL
    const char* ssl_libs[] = {
        "libssl.so", "libconscrypt_jni.so",
        "libttboringssl.so", "libflutter.so", nullptr
    };

    for (int i = 0; ssl_libs[i] != nullptr; i++) {
        hook_func(ssl_libs[i], "SSL_write", (void*)hook_SSL_write, (void**)&orig_SSL_write);
        hook_func(ssl_libs[i], "SSL_read", (void*)hook_SSL_read, (void**)&orig_SSL_read);
        hook_func(ssl_libs[i], "NativeCrypto_SSL_write", (void*)hook_SSL_write, (void**)&orig_SSL_write);
        hook_func(ssl_libs[i], "NativeCrypto_SSL_read", (void*)hook_SSL_read, (void**)&orig_SSL_read);
    }
}
