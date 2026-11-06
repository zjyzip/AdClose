#if !defined(__arm__) && !defined(__aarch64__)
struct shadowhook_cpu_context_t;
#endif
#include "../../main/cpp/native_hook.cpp"
#include "support/capture_jni.h"
#include <filesystem>
#include <fstream>
#include <iostream>

extern "C" int shadowhook_init(shadowhook_mode_t, bool) { return 0; }
extern "C" void* shadowhook_hook_sym_name(const char*, const char*, void*, void**) { return nullptr; }

int main(int argc, char** argv) {
    assert(argc == 3);
    CaptureJni capture;
    std::ifstream input(argv[1], std::ios::binary);
    assert(input);
    const std::filesystem::path output(argv[2]);
    std::filesystem::create_directories(output);
    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    assert(fd >= 0);
    const size_t splits[] = {1, 2, 5, 17, 4096, 8191};
    size_t step = 0, records = 0;
    while (input.peek() != EOF) {
        uint8_t header[5]; input.read(reinterpret_cast<char*>(header), 5); assert(input.gcount() == 5);
        const bool write = header[0];
        const size_t size = (size_t(header[1]) << 24) | (size_t(header[2]) << 16) | (size_t(header[3]) << 8) | header[4];
        assert(size > 0 && size <= 1024 * 1024);
        std::vector<uint8_t> bytes(size); input.read(reinterpret_cast<char*>(bytes.data()), size);
        assert(size_t(input.gcount()) == size);
        for (size_t offset = 0; offset < size;) {
            const size_t count = std::min(size - offset, splits[step++ % 6]);
            errno = EDOM;
            if (write) {
                assert(write_fd(fd, bytes.data() + offset, count, [&]() -> ssize_t { return count; }) == ssize_t(count));
            } else {
                std::vector<uint8_t> read_buffer(count);
                assert(read_fd(fd, read_buffer.data(), count, [&]() -> ssize_t {
                    std::memcpy(read_buffer.data(), bytes.data() + offset, count); return count;
                }) == ssize_t(count));
                assert(std::memcmp(read_buffer.data(), bytes.data() + offset, count) == 0);
            }
            if (capture.resets) {
                // grpcio's forced server shutdown emits GOAWAY(INTERNAL_ERROR) after
                // all RPCs finish. Discarding parser state then is correct; losing an
                // active RPC before its trailers is not.
                assert(!capture.streams.empty());
                for (const auto& entry : capture.streams) {
                    assert(entry.second.request_complete && entry.second.response_complete);
                }
            }
            assert(errno == EDOM);
            offset += count;
        }
        ++records;
    }
    assert(!fd_state(fd)->blocked && !capture.streams.empty());
    for (const auto& entry : capture.streams) {
        const auto path = output / std::to_string(entry.first);
        std::filesystem::create_directories(path);
        const auto& s = entry.second;
        assert(s.request_complete && s.response_complete && s.status == 200 && s.scheme == "http");
        auto text = [&](const char* name, const std::string& value) { std::ofstream(path / name, std::ios::binary) << value; };
        auto body = [&](const char* name, const std::vector<uint8_t>& value) {
            std::ofstream file(path / name, std::ios::binary);
            file.write(reinterpret_cast<const char*>(value.data()), value.size());
        };
        text("path.txt", s.path); text("request.headers", s.request_headers); text("response.headers", s.response_headers);
        text("scheme.txt", s.scheme); text("authority.txt", s.authority);
        body("request.bin", s.request_body); body("response.bin", s.response_body);
    }
    orig_close = ::close; hook_close(fd);
    std::cout << "Replayed " << records << " real TCP records through production Native hooks; "
              << capture.streams.size() << " completed H2 streams, I/O unchanged; shutdown resets="
              << capture.resets << std::endl;
}
