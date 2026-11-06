#pragma once
#include <cassert>
#include <cstdarg>
#include <map>
#include <string>
#include <vector>

// Host JNI adapter for the real observe/write_fd/read_fd path. Java strings and
// direct buffers live until PopLocalFrame, matching the callbacks' synchronous use.
struct CapturedH2Stream {
    std::string method, path, authority, scheme, request_headers, response_headers;
    std::vector<uint8_t> request_body, response_body;
    int status = -1;
    bool request_complete = false, response_complete = false;
};
struct CaptureJni {
    struct Buffer { const uint8_t* bytes; size_t size; };
    inline static CaptureJni* active = nullptr;
    JNINativeInterface table{};
    JNIEnv env{&table};
    std::map<int, CapturedH2Stream> streams;
    std::vector<uint8_t> h1_tx, h1_rx;
    bool collect = true, block_h1 = false;
    int resets = 0;
    std::vector<std::unique_ptr<std::u16string>> strings;
    std::vector<std::unique_ptr<Buffer>> buffers;
    std::vector<std::pair<size_t, size_t>> frames;

    static std::string text(jstring value) {
        if (!value) return {};
        const auto& input = *reinterpret_cast<std::u16string*>(value);
        std::string result;
        for (auto c : input) {
            assert(c <= 127); // The wire fixture uses ASCII metadata; bodies remain bytes.
            result.push_back(char(c));
        }
        return result;
    }
    CaptureJni() {
        assert(!active); active = this;
        table.ExceptionCheck = +[](JNIEnv*) -> jboolean { return false; };
        table.PushLocalFrame = +[](JNIEnv*, jint) -> jint {
            active->frames.emplace_back(active->strings.size(), active->buffers.size());
            return JNI_OK;
        };
        table.PopLocalFrame = +[](JNIEnv*, jobject object) -> jobject {
            const auto size = active->frames.back(); active->frames.pop_back();
            active->strings.resize(size.first); active->buffers.resize(size.second);
            return object;
        };
        table.NewString = +[](JNIEnv*, const jchar* data, jsize count) -> jstring {
            auto value = std::make_unique<std::u16string>(reinterpret_cast<const char16_t*>(data), size_t(count));
            auto* pointer = value.get(); active->strings.push_back(std::move(value));
            return reinterpret_cast<jstring>(pointer);
        };
        table.NewDirectByteBuffer = +[](JNIEnv*, void* data, jlong size) -> jobject {
            auto buffer = std::make_unique<Buffer>(Buffer{static_cast<const uint8_t*>(data), size_t(size)});
            auto* pointer = buffer.get(); active->buffers.push_back(std::move(buffer));
            return reinterpret_cast<jobject>(pointer);
        };
        table.CallStaticBooleanMethodV = +[](JNIEnv*, jclass, jmethodID method, va_list args) -> jboolean {
            if (method == collect_body) return active->collect;
            (void)va_arg(args, jlong);
            if (method == on_data) {
                const bool write = va_arg(args, int);
                const auto& b = *reinterpret_cast<Buffer*>(va_arg(args, jobject));
                auto& out = write ? active->h1_tx : active->h1_rx;
                out.insert(out.end(), b.bytes, b.bytes + b.size);
                return active->block_h1;
            }
            assert(method == on_h2_request);
            auto& s = active->streams[va_arg(args, int)];
            const auto method_name = text(va_arg(args, jstring));
            const auto path = text(va_arg(args, jstring));
            const auto authority = text(va_arg(args, jstring));
            const auto scheme = text(va_arg(args, jstring));
            const auto request = text(va_arg(args, jstring));
            const auto response = text(va_arg(args, jstring));
            const int status = va_arg(args, int);
            (void)va_arg(args, int);
            s.request_complete |= va_arg(args, int) != 0;
            s.response_complete |= va_arg(args, int) != 0;
            if (!method_name.empty()) s.method = method_name;
            if (!path.empty()) s.path = path;
            if (!authority.empty()) s.authority = authority;
            if (!scheme.empty()) s.scheme = scheme;
            if (!request.empty()) s.request_headers = request;
            if (!response.empty()) s.response_headers = response;
            if (status >= 0) s.status = status;
            return true; // A callback match must never abort a shared H2 socket.
        };
        table.CallStaticVoidMethodV = +[](JNIEnv*, jclass, jmethodID method, va_list args) {
            (void)va_arg(args, jlong);
            if (method == on_h2_chunk) {
                const int id = va_arg(args, int);
                const bool write = va_arg(args, int);
                const auto& b = *reinterpret_cast<Buffer*>(va_arg(args, jobject));
                auto& stream = active->streams[id];
                auto& out = write ? stream.request_body : stream.response_body;
                out.insert(out.end(), b.bytes, b.bytes + b.size);
            } else if (method == on_h2_reset) ++active->resets;
        };
        vm = reinterpret_cast<JavaVM*>(1);
        callback_class = reinterpret_cast<jclass>(1);
        thread_env = &env;
        on_data = reinterpret_cast<jmethodID>(1);
        on_h2_request = reinterpret_cast<jmethodID>(2);
        on_h2_chunk = reinterpret_cast<jmethodID>(3);
        collect_body = reinterpret_cast<jmethodID>(4);
        on_close = reinterpret_cast<jmethodID>(5);
        on_discard = reinterpret_cast<jmethodID>(6);
        on_h2_stream_close = reinterpret_cast<jmethodID>(7);
        on_h2_reset = reinterpret_cast<jmethodID>(8);
        on_h2_body_discard = reinterpret_cast<jmethodID>(9);
    }
    ~CaptureJni() {
        thread_env = nullptr; callback_class = nullptr; vm = nullptr; active = nullptr;
    }
};
