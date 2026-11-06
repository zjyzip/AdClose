// ShadowHook's interceptor context is ARM-only; the host tests only use symbol-hook registration.
#if !defined(__arm__) && !defined(__aarch64__)
struct shadowhook_cpu_context_t;
#endif
#include "../../main/cpp/native_hook.cpp"
#include <cassert>
#include <fcntl.h>
#include <iostream>
#include <thread>
#include <functional>
#include "support/capture_jni.h"
#ifdef NDEBUG
#error Native regression tests require assertions enabled
#endif

extern "C" int shadowhook_init(shadowhook_mode_t, bool) { return 0; }
extern "C" void* shadowhook_hook_sym_name(const char*, const char*, void*, void**) { return nullptr; }

namespace {
using Bytes = std::vector<uint8_t>;
Bytes frame(uint8_t type, uint8_t flags, int id, const Bytes& body = {}) {
    const size_t n = body.size();
    Bytes result{uint8_t(n >> 16), uint8_t(n >> 8), uint8_t(n), type, flags,
        uint8_t(id >> 24), uint8_t(id >> 16), uint8_t(id >> 8), uint8_t(id)};
    result.insert(result.end(), body.begin(), body.end());
    return result;
}
struct Encoder {
    nghttp2_hd_deflater* value = nullptr;
    Encoder() { assert(nghttp2_hd_deflate_new(&value, 4096) == 0); }
    ~Encoder() { nghttp2_hd_deflate_del(value); }
    Bytes encode(std::vector<std::pair<std::string, std::string>> headers) {
        std::vector<nghttp2_nv> nv;
        for (auto& h : headers) nv.push_back({reinterpret_cast<uint8_t*>(h.first.data()),
            reinterpret_cast<uint8_t*>(h.second.data()), h.first.size(), h.second.size(), NGHTTP2_NV_FLAG_NONE});
        Bytes bytes(nghttp2_hd_deflate_bound(value, nv.data(), nv.size()));
        const auto count = nghttp2_hd_deflate_hd(value, bytes.data(), bytes.size(), nv.data(), nv.size());
        assert(count >= 0); bytes.resize(count); return bytes;
    }
    Bytes request(const std::string& method = "GET") {
        return encode({{":method", method}, {":scheme", "https"}, {":authority", "example.com"}, {":path", "/path"}});
    }
};
struct Fixture {
    std::shared_ptr<Http2Connection> c = std::make_shared<Http2Connection>();
    Encoder tx, rx;
    Fixture() {
        c->conn_id = 100000;
        auto r = h2_feed(c, H2_CLIENT_PREFACE, H2_CLIENT_PREFACE_LEN, true, true);
        assert(c->is_h2 && !r.reset);
    }
    H2FeedResult feed(const Bytes& bytes, bool local = true, bool collect = true) {
        return h2_feed(c, bytes.data(), bytes.size(), local, collect);
    }
    void request(int id = 1, bool end = true) {
        auto r = feed(frame(H2_FRAME_HEADERS, H2_FLAG_END_HEADERS | (end ? H2_FLAG_END_STREAM : 0), id, tx.request(end ? "GET" : "POST")));
        assert(r.early_checks.size() == 1);
    }
};
void fragmented_headers() {
    auto c = std::make_shared<Http2Connection>();
    for (size_t i = 0; i < H2_CLIENT_PREFACE_LEN; ++i) {
        h2_feed(c, H2_CLIENT_PREFACE + i, 1, true, true);
        assert(c->is_h2 == (i + 1 == H2_CLIENT_PREFACE_LEN));
    }
    Encoder tx, rx;
    auto header = tx.request();
    const size_t split = header.size() / 2;
    auto first = frame(H2_FRAME_HEADERS, H2_FLAG_END_STREAM, 1, {header.begin(), header.begin() + split});
    for (auto byte : first) assert(h2_feed(c, &byte, 1, true, true).early_checks.empty());
    auto last = frame(H2_FRAME_CONTINUATION, H2_FLAG_END_HEADERS, 1, {header.begin() + split, header.end()});
    auto r = h2_feed(c, last.data(), last.size(), true, true);
    assert(r.early_checks.size() == 1 && c->streams.at(1).req_end_stream);
    auto response = frame(H2_FRAME_HEADERS, 5, 1, rx.encode({{":status", "200"}}));
    r = h2_feed(c, response.data(), response.size(), false, true);
    assert(r.completed.size() == 1 && r.completed[0].is_complete && r.completed[0].status_code == 200);
    assert(c->streams.empty() && r.closed_streams == std::vector<int>{1});
}
void bidirectional_continuations_and_trailers() {
    Fixture f; f.request(1, false);
    auto trailer = f.tx.encode({{"x-request-trailer", "one"}});
    auto response = f.rx.encode({{":status", "200"}, {"x-response", "two"}});
    f.feed(frame(H2_FRAME_HEADERS, 1, 1, {trailer.begin(), trailer.begin() + 1}));
    f.feed(frame(H2_FRAME_HEADERS, 0, 1, {response.begin(), response.begin() + 1}), false);
    auto r = f.feed(frame(H2_FRAME_CONTINUATION, 4, 1, {trailer.begin() + 1, trailer.end()}));
    assert(r.early_checks.empty());
    r = f.feed(frame(H2_FRAME_CONTINUATION, 4, 1, {response.begin() + 1, response.end()}), false);
    assert(r.completed.size() == 1 && !r.completed[0].is_complete);
    r = f.feed(frame(H2_FRAME_DATA, 1, 1, {'o','k'}), false);
    assert(r.completed.size() == 1 && r.completed[0].is_complete);
    const auto& h = r.completed[0].req_headers;
    assert(std::find(h.begin(), h.end(), std::make_pair(std::string("x-request-trailer"), std::string("one"))) != h.end());
}
void interim_and_dynamic_table_push() {
    Fixture f; f.request();
    auto push = f.rx.encode({{":method", "GET"}, {":path", "/push"}, {"x-shared", "dynamic-value"}});
    push.insert(push.begin(), {0,0,0,2});
    f.feed(frame(H2_FRAME_PUSH_PROMISE, 4, 1, push), false);
    auto interim = f.feed(frame(H2_FRAME_HEADERS, 4, 1, f.rx.encode({{":status", "103"}, {"link", "/style"}})), false);
    assert(interim.completed.empty());
    auto r = f.feed(frame(H2_FRAME_HEADERS, 5, 1, f.rx.encode({{":status", "204"}, {"x-shared", "dynamic-value"}})), false);
    assert(!r.reset && r.completed.size() == 1 && r.completed[0].is_complete);
    assert(r.completed[0].resp_headers[0].second == "dynamic-value");
}
void rst_and_goaway_cleanup() {
    Fixture f; f.request(1); f.request(3); f.request(5);
    auto r = f.feed(frame(H2_FRAME_RST_STREAM, 0, 5, {0,0,0,8}), false);
    assert(r.closed_streams == std::vector<int>{5} && f.c->streams.count(5) == 0);
    r = f.feed(frame(H2_FRAME_GOAWAY, 0, 0, {0,0,0,1,0,0,0,0}), false);
    assert(r.closed_streams == std::vector<int>{3} && f.c->streams.size() == 1);
    r = f.feed(frame(H2_FRAME_HEADERS, 5, 1, f.rx.encode({{":status", "200"}})), false);
    assert(r.completed[0].is_complete && f.c->streams.empty());
}
void disabled_capture_does_not_publish_a_tail() {
    Fixture f; f.request();
    f.feed(frame(H2_FRAME_HEADERS, 4, 1, f.rx.encode({{":status", "200"}})), false);
    auto r = f.feed(frame(H2_FRAME_DATA, 0, 1, {'a','b'}), false, false);
    assert(r.data_chunks.size() == 1 && r.data_chunks[0].discarded && r.data_chunks[0].data.empty());
    r = f.feed(frame(H2_FRAME_DATA, 1, 1, {'c','d'}), false, true);
    assert(r.completed[0].is_complete);
}
void malformed_frames_stop_observation() {
    Fixture f; f.request();
    Bytes too_large{0x04,0x00,0x01,H2_FRAME_HEADERS,0,0,0,0,1};
    auto r = f.feed(too_large, false);
    assert(r.reset && f.c->disabled && f.c->streams.empty() && f.c->remote.buffer.empty());
    f.feed(Bytes(1024, 0), false);
    assert(f.c->remote.buffer.empty());
    Fixture bad_padding; bad_padding.request();
    r = bad_padding.feed(frame(H2_FRAME_DATA, H2_FLAG_PADDED, 1, {9}), false);
    assert(r.reset);
    Fixture bad_hpack;
    r = bad_hpack.feed(frame(H2_FRAME_HEADERS, 5, 1, {0xff,0xff,0xff,0xff,0xff,0xff,0xff}));
    assert(r.reset && r.early_checks.empty());
}
void header_and_stream_budgets() {
    Fixture f;
    f.feed(frame(H2_FRAME_HEADERS, 1, 1, Bytes(1024, 0)));
    H2FeedResult r;
    for (int i = 0; i < 65 && !f.c->disabled; ++i) r = f.feed(frame(H2_FRAME_CONTINUATION, 0, 1, Bytes(1024, 0)));
    assert(r.reset && f.c->disabled && f.c->local.header_block.empty());
    Fixture streams;
    bool evicted = false;
    for (size_t i = 0; i < H2_MAX_STREAMS_PER_CONN + 2; ++i) {
        r = streams.feed(frame(H2_FRAME_HEADERS, 5, int(i * 2 + 1), streams.tx.request()));
        evicted |= !r.closed_streams.empty();
        assert(streams.c->streams.size() <= H2_MAX_STREAMS_PER_CONN);
    }
    assert(evicted);
}
void connection_ids_and_capacity() {
    std::vector<std::shared_ptr<Http2Connection>> held;
    for (size_t i = 0; i < H2_MAX_CONNECTIONS; ++i) {
        auto c = h2_get_or_create((1ULL << 48) + i);
        assert(c && c->conn_id == (1ULL << 48) + i); held.push_back(c);
    }
    assert(!h2_get_or_create(999));
    auto old = held[0];
    h2_free(old->conn_id);
    auto replacement = h2_get_or_create(old->conn_id);
    assert(replacement != old);
    for (auto& c : held) h2_free(c->conn_id);
}
void concurrent_feed_results_stay_with_their_caller() {
    Fixture f;
    std::atomic<int> requests{0};
    std::thread tx([&] {
        for (int i = 0; i < 100; ++i) {
            auto r = f.feed(frame(H2_FRAME_HEADERS, 5, i * 2 + 1, f.tx.request()));
            assert(!r.reset); requests += int(r.early_checks.size());
        }
    });
    std::thread rx([&] {
        for (int i = 0; i < 100; ++i) {
            auto r = f.feed(frame(H2_FRAME_SETTINGS, 1, 0), false);
            assert(!r.reset && r.early_checks.empty() && r.completed.empty());
        }
    });
    tx.join(); rx.join(); assert(requests == 100);
}
void partial_writes_and_retries() {
    ObservedWrite writes;
    const std::string first = "abcdef", retry = "cdefXYZ";
    auto ptr = [](const std::string& s) { return reinterpret_cast<const uint8_t*>(s.data()); };
    assert(writes.begin(ptr(first), first.size()) == 0);
    writes.finish(ptr(first), first.size(), 2);
    assert(writes.begin(ptr(retry), retry.size()) == 4);
    writes.finish(ptr(retry), retry.size(), -1);
    assert(writes.begin(ptr(retry), retry.size()) == retry.size());
    writes.finish(ptr(retry), retry.size(), 1);
    const std::string short_retry = "de";
    assert(writes.begin(ptr(short_retry), short_retry.size()) == 2);
    writes.finish(ptr(short_retry), short_retry.size(), 2);
    const std::string remaining = "fXYZ";
    assert(writes.begin(ptr(remaining), remaining.size()) == remaining.size());
    writes.finish(ptr(remaining), remaining.size(), remaining.size());
    assert(!writes.pending());
    writes.finish(ptr(first), first.size(), 1);
    assert(writes.begin(ptr(first), first.size()) == first.size() && !writes.enabled());
    ObservedWrite large;
    Bytes bytes(ObservedWrite::MAX_RETRY_BYTES + 1, 1);
    large.finish(bytes.data(), bytes.size(), -1);
    assert(!large.enabled());
}
void jni_utf8_conversion() {
    assert(native_utf16("plain") == u"plain");
    assert(native_utf16(std::string("a\0b", 3)) == std::u16string(u"a\0b", 3));
    assert(native_utf16("\xf0\x9f\x98\x80") == u"\U0001f600");
    assert(native_utf16("\xc0\xaf") == u"\ufffd\ufffd");
    assert(native_utf16("\xe2X") == u"\ufffdX");
}
void fd_reuse_and_non_stream_sockets() {
    int first = socket(AF_INET, SOCK_STREAM, 0), second = socket(AF_INET, SOCK_STREAM, 0);
    assert(first >= 0 && second >= 0);
    auto old = fd_state(first); assert(old && fd_state(first) == old);
    assert(dup2(second, first) == first);
    auto fresh = fd_state(first);
    assert(fresh && fresh->id != old->id && !old->observing);
    orig_close = ::close;
    hook_close(first); hook_close(second);
    int udp = socket(AF_INET, SOCK_DGRAM, 0);
    assert(!fd_state(udp)); ::close(udp);
    int pair[2]; assert(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    assert(!fd_state(pair[0])); ::close(pair[0]); ::close(pair[1]);
    assert(!fd_state(-1));
}
void syscall_errno_and_blocked_results() {
    const char buffer[] = "data";
    errno = EDOM;
    int calls = 0;
    auto ret = write_fd(-1, buffer, 4, [&]() -> ssize_t { ++calls; assert(errno == EDOM); errno = EBADF; return -1; });
    assert(ret == -1 && errno == EBADF && calls == 1);
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    auto state = fd_state(fd); state->blocked = true;
    ret = write_fd(fd, buffer, 4, [&]() -> ssize_t { ++calls; return 4; });
    assert(ret == -1 && errno == ECONNRESET && calls == 1);
    char output[8];
    ret = read_fd(fd, output, 8, [&]() -> ssize_t { ++calls; return 4; });
    assert(ret == -1 && errno == ECONNRESET && calls == 1);
    hook_close(fd);
}
int mock_ssl_writes = 0;
int mock_h2_checks = 0;
void h2_match_does_not_poison_shared_connection() {
    JNINativeInterface table{};
    table.ExceptionCheck = +[](JNIEnv*) -> jboolean { return false; };
    table.PushLocalFrame = +[](JNIEnv*, jint) -> jint { return JNI_OK; };
    table.PopLocalFrame = +[](JNIEnv*, jobject object) -> jobject { return object; };
    table.NewString = +[](JNIEnv*, const jchar*, jsize) -> jstring { return reinterpret_cast<jstring>(1); };
    table.CallStaticBooleanMethodV = +[](JNIEnv*, jclass, jmethodID method, va_list) -> jboolean {
        if (method == on_h2_request) { ++mock_h2_checks; return true; }
        return false;
    };
    table.CallStaticVoidMethodV = +[](JNIEnv*, jclass, jmethodID, va_list) {};
    JNIEnv environment{&table};
    vm = reinterpret_cast<JavaVM*>(1);
    callback_class = reinterpret_cast<jclass>(1);
    thread_env = &environment;
    on_h2_request = reinterpret_cast<jmethodID>(2);
    collect_body = reinterpret_cast<jmethodID>(3);
    ssl_hooks[0].write = +[](void*, const void*, int size) { ++mock_ssl_writes; return size; };
    ssl_hooks[0].free = +[](void*) {};
    ssl_hooks[0].error = +[](const void*, int) { return 2; };
    Encoder encoder;
    Bytes bytes(H2_CLIENT_PREFACE, H2_CLIENT_PREFACE + H2_CLIENT_PREFACE_LEN);
    const auto headers = frame(H2_FRAME_HEADERS, 5, 1, encoder.request());
    bytes.insert(bytes.end(), headers.begin(), headers.end());
    int token;
    errno = EDOM;
    const int ret = ssl_write<0>(&token, bytes.data(), int(bytes.size()));
    assert(ret == int(bytes.size()) && errno == EDOM && mock_h2_checks >= 1 && mock_ssl_writes == 1);
    assert(!ssl_state(&token)->blocked);
    const auto other = frame(H2_FRAME_HEADERS, 5, 3, encoder.request());
    assert(ssl_write<0>(&token, other.data(), int(other.size())) == int(other.size()));
    assert(mock_ssl_writes == 2 && !ssl_state(&token)->blocked);
    assert(ssl_error<0>(&token, -1) == 2);
    ssl_free<0>(&token);
    thread_env = nullptr; callback_class = nullptr; vm = nullptr;
}
void independent_body_end_events() {
    Fixture upload; upload.request(1, false);
    auto r = upload.feed(frame(H2_FRAME_DATA, 1, 1, {'u','p'}));
    assert(r.completed.size() == 1 && r.completed[0].request_complete && !r.completed[0].response_complete);
    assert(!r.completed[0].is_complete && upload.c->streams.count(1));
    Fixture early; early.request(1, false);
    r = early.feed(frame(H2_FRAME_HEADERS, 5, 1, early.rx.encode({{":status", "403"}})), false);
    assert(r.completed.size() == 1 && !r.completed[0].request_complete && r.completed[0].response_complete);
    r = early.feed(frame(H2_FRAME_DATA, 1, 1, {'u','p'}));
    assert(r.completed[0].is_complete && r.completed[0].request_complete && r.completed[0].response_complete);
}
void large_data_frames_are_streamed_without_disabling_capture() {
    Fixture f; f.request();
    f.feed(frame(H2_FRAME_HEADERS, 4, 1, f.rx.encode({{":status", "200"}})), false);
    auto bytes = frame(H2_FRAME_DATA, 1, 1, Bytes(512 * 1024, 'x'));
    size_t captured = 0, completed = 0;
    for (size_t offset = 0; offset < bytes.size();) {
        const size_t count = std::min(H2_MAX_FEED_BYTES, bytes.size() - offset);
        auto r = f.feed(Bytes(bytes.begin() + offset, bytes.begin() + offset + count), false);
        assert(!r.reset && !f.c->disabled && f.c->remote.buffer.size() <= H2_MAX_FEED_BYTES);
        for (auto& chunk : r.data_chunks) captured += chunk.data.size();
        completed += r.completed.size();
        offset += count;
    }
    assert(captured == 512 * 1024 && completed == 1 && f.c->streams.empty());
}
void streamed_padding_and_empty_end_frames_finish_once() {
    Fixture f; f.request();
    f.feed(frame(H2_FRAME_HEADERS, 4, 1, f.rx.encode({{":status", "200"}})), false);
    auto bytes = frame(H2_FRAME_DATA, H2_FLAG_PADDED | H2_FLAG_END_STREAM, 1, {2,'a','b','c',0,0});
    size_t captured = 0, completed = 0;
    for (auto byte : bytes) {
        auto r = f.feed(Bytes{byte}, false);
        assert(!r.reset);
        for (auto& chunk : r.data_chunks) captured += chunk.data.size();
        completed += r.completed.size();
    }
    assert(captured == 3 && completed == 1);
    Fixture empty; empty.request();
    empty.feed(frame(H2_FRAME_HEADERS, 4, 1, empty.rx.encode({{":status", "200"}})), false);
    auto r = empty.feed(frame(H2_FRAME_DATA, 1, 1), false);
    assert(r.completed.size() == 1 && r.completed[0].is_complete);
}
void java_capture_suppresses_only_the_current_native_call() {
    int token;
    char bytes[4]{};
    ssl_hooks[0].write = +[](void*, const void*, int size) { return size; };
    ssl_hooks[0].error = nullptr;
    ssl_hooks[0].free = +[](void*) {};
    Java_com_close_hook_ads_hook_network_NativeRequestHook_setJavaCaptureActive(nullptr, nullptr, true);
    Java_com_close_hook_ads_hook_network_NativeRequestHook_setJavaCaptureActive(nullptr, nullptr, true);
    assert(ssl_write<0>(&token, bytes, 4) == 4 && !ssl_state(&token, false));
    Java_com_close_hook_ads_hook_network_NativeRequestHook_setJavaCaptureActive(nullptr, nullptr, false);
    assert(java_capture_depth == 1 && ssl_write<0>(&token, bytes, 4) == 4 && !ssl_state(&token, false));
    Java_com_close_hook_ads_hook_network_NativeRequestHook_setJavaCaptureActive(nullptr, nullptr, false);
    assert(java_capture_depth == 0 && ssl_write<0>(&token, bytes, 4) == 4 && ssl_state(&token, false));
    ssl_free<0>(&token);
}
void peek_skips_observation_but_respects_an_existing_block() {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    auto state = fd_state(fd);
    orig_recv = +[](int, void* buffer, size_t, int flags) -> ssize_t {
        assert(flags & MSG_PEEK); *static_cast<char*>(buffer) = 'x'; errno = EDOM; return 1;
    };
    char buffer[4];
    assert(hook_recv(fd, buffer, sizeof(buffer), MSG_PEEK) == 1 && errno == EDOM);
    assert(!state->eof);
    state->blocked = true;
    assert(hook_recv(fd, buffer, sizeof(buffer), MSG_PEEK) == -1 && errno == ECONNRESET);
    hook_close(fd);
}
void cleartext_h2_uses_the_real_socket_observer_without_blocking_other_streams() {
    CaptureJni capture;
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    Encoder tx, rx;
    Bytes bytes(H2_CLIENT_PREFACE, H2_CLIENT_PREFACE + H2_CLIENT_PREFACE_LEN);
    const auto headers = frame(H2_FRAME_HEADERS, 4, 1, tx.encode({{":method", "POST"}, {":scheme", "http"},
        {":authority", "localhost:50051"}, {":path", "/fixture.Service/Echo"}, {"content-type", "application/grpc"}}));
    bytes.insert(bytes.end(), headers.begin(), headers.end());
    const auto data = frame(H2_FRAME_DATA, 1, 1, {0,0,0,0,2,'u','p'});
    bytes.insert(bytes.end(), data.begin(), data.end());
    int writes = 0;
    for (auto byte : bytes) {
        const auto ret = write_fd(fd, &byte, 1, [&]() -> ssize_t { ++writes; errno = EDOM; return 1; });
        assert(ret == 1 && errno == EDOM);
    }
    const auto& request = capture.streams.at(1);
    assert(request.scheme == "http" && request.path == "/fixture.Service/Echo" && request.request_complete);
    assert(request.request_body == Bytes({0,0,0,0,2,'u','p'}));
    auto response = frame(H2_FRAME_HEADERS, 4, 1, rx.encode({{":status", "200"}, {"content-type", "application/grpc"}}));
    const auto payload = frame(H2_FRAME_DATA, 0, 1, {0,0,0,0,2,'o','k'});
    response.insert(response.end(), payload.begin(), payload.end());
    const auto trailer = frame(H2_FRAME_HEADERS, 5, 1, rx.encode({{"grpc-status", "0"}}));
    response.insert(response.end(), trailer.begin(), trailer.end());
    for (auto byte : response) {
        uint8_t buffer = 0;
        assert(read_fd(fd, &buffer, 1, [&]() -> ssize_t { buffer = byte; errno = EDOM; return 1; }) == 1);
        assert(buffer == byte && errno == EDOM);
    }
    assert(capture.streams.at(1).response_complete && capture.streams.at(1).status == 200);
    assert(capture.streams.at(1).response_headers.find("grpc-status\n0\n") != std::string::npos);
    assert(capture.streams.at(1).response_body == Bytes({0,0,0,0,2,'o','k'}));
    const auto second = frame(H2_FRAME_HEADERS, 5, 3, tx.encode({{":method", "GET"}, {":scheme", "http"},
        {":authority", "localhost:50051"}, {":path", "/normal"}}));
    assert(write_fd(fd, second.data(), second.size(), [&]() -> ssize_t { return second.size(); }) == ssize_t(second.size()));
    assert(capture.streams.count(3) && !fd_state(fd)->blocked && writes == int(bytes.size()));
    assert(capture.resets == 0);
    orig_close = ::close; hook_close(fd);
}
void cleartext_http1_fallback_preserves_every_byte_and_block_decision() {
    CaptureJni capture;
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    const std::string request = "POST /upload HTTP/1.1\r\nHost: example.com\r\nContent-Length: 2\r\n\r\nup";
    for (auto byte : request) {
        assert(write_fd(fd, &byte, 1, []() -> ssize_t { return 1; }) == 1);
    }
    assert(std::string(capture.h1_tx.begin(), capture.h1_tx.end()) == request && capture.streams.empty());
    capture.block_h1 = true;
    int forwarded = 0;
    assert(write_fd(fd, request.data(), request.size(), [&]() -> ssize_t { ++forwarded; return request.size(); }) == -1);
    assert(errno == ECONNRESET && forwarded == 0);
    orig_close = ::close; hook_close(fd);
}
void server_first_preface_probe_is_bounded() {
    auto connection = std::make_shared<Http2Connection>();
    Bytes bytes(H2_MAX_FEED_BYTES, 'x');
    assert(!h2_feed(connection, bytes.data(), bytes.size(), false, true).reset);
    auto result = h2_feed(connection, bytes.data(), 1, false, true);
    assert(result.reset && connection->disabled && connection->remote.buffer.empty());
}

void ssl_abi_error_and_reentrant_free() {
    int token;
    auto* ssl = &token;
    auto state = ssl_state(ssl); state->blocked = true;
    int calls = 0;
    ssl_hooks[0].write = +[](void*, const void*, int) { assert(false); return 0; };
    ssl_hooks[0].read = +[](void*, void*, int) { assert(false); return 0; };
    ssl_hooks[0].error = +[](const void*, int) { return 2; };
    ssl_hooks[0].free = +[](void*) {};
    char bytes[8]{};
    assert(ssl_write<0>(ssl, bytes, 8) == -1 && errno == ECONNRESET);
    assert(ssl_read<0>(ssl, bytes, 8) == -1 && errno == ECONNRESET);
    assert(ssl_error<0>(ssl, -1) == 5 && errno == ECONNRESET);
    { HookGuard guard; ssl_free<0>(ssl); assert(in_hook); }
    assert(!ssl_state(ssl, false));
    assert(ssl_state(ssl)->id != state->id);
    ssl_free<0>(ssl);
    (void)calls;
}
} // namespace

int main() {
    const std::vector<std::pair<const char*, std::function<void()>>> tests{
        {"fragmented_headers", fragmented_headers}, {"bidirectional_continuations_and_trailers", bidirectional_continuations_and_trailers},
        {"interim_and_dynamic_table_push", interim_and_dynamic_table_push}, {"rst_and_goaway_cleanup", rst_and_goaway_cleanup},
        {"disabled_capture_does_not_publish_a_tail", disabled_capture_does_not_publish_a_tail},
        {"malformed_frames_stop_observation", malformed_frames_stop_observation}, {"header_and_stream_budgets", header_and_stream_budgets},
        {"connection_ids_and_capacity", connection_ids_and_capacity}, {"concurrent_feed_results_stay_with_their_caller", concurrent_feed_results_stay_with_their_caller},
        {"partial_writes_and_retries", partial_writes_and_retries}, {"jni_utf8_conversion", jni_utf8_conversion},
        {"fd_reuse_and_non_stream_sockets", fd_reuse_and_non_stream_sockets}, {"syscall_errno_and_blocked_results", syscall_errno_and_blocked_results},
        {"h2_match_does_not_poison_shared_connection", h2_match_does_not_poison_shared_connection},
        {"independent_body_end_events", independent_body_end_events},
        {"large_data_frames_are_streamed_without_disabling_capture", large_data_frames_are_streamed_without_disabling_capture},
        {"streamed_padding_and_empty_end_frames_finish_once", streamed_padding_and_empty_end_frames_finish_once},
        {"java_capture_suppresses_only_the_current_native_call", java_capture_suppresses_only_the_current_native_call},
        {"peek_skips_observation_but_respects_an_existing_block", peek_skips_observation_but_respects_an_existing_block},
        {"cleartext_h2_uses_the_real_socket_observer_without_blocking_other_streams", cleartext_h2_uses_the_real_socket_observer_without_blocking_other_streams},
        {"cleartext_http1_fallback_preserves_every_byte_and_block_decision", cleartext_http1_fallback_preserves_every_byte_and_block_decision},
        {"server_first_preface_probe_is_bounded", server_first_preface_probe_is_bounded},
        {"ssl_abi_error_and_reentrant_free", ssl_abi_error_and_reentrant_free}
    };
    for (const auto& test : tests) { std::cout << "RUN " << test.first << std::endl; test.second(); std::cout << "PASS " << test.first << std::endl; }
    std::cout << tests.size() << " native tests passed" << std::endl;
}
