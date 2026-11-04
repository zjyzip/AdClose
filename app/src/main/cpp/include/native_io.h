#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

// Bytes offered to a write can be observed before blocking, but retries must not be fed twice.
class ObservedWrite {
    std::vector<uint8_t> retry;
    bool valid = true;
public:
    static constexpr size_t MAX_RETRY_BYTES = 64 * 1024;
    bool enabled() const { return valid; }
    bool pending() const { return !retry.empty(); }
    void disable() { valid = false; std::vector<uint8_t>().swap(retry); }
    size_t begin(const uint8_t* bytes, size_t size) {
        if (!valid) return size;
        const size_t prefix = std::min(size, retry.size());
        if (prefix && std::memcmp(bytes, retry.data(), prefix) != 0) {
            disable();
            return size;
        }
        return prefix;
    }
    void finish(const uint8_t* bytes, size_t size, int64_t written) {
        if (!valid) return;
        const size_t accepted = written > 0 ? std::min(size, size_t(written)) : 0;
        if (size < retry.size()) {
            retry.erase(retry.begin(), retry.begin() + accepted);
        } else if (size - accepted > MAX_RETRY_BYTES) disable();
        else retry.assign(bytes + accepted, bytes + size);
    }
};

// NewStringUTF expects modified UTF-8; HTTP/2 fields contain ordinary UTF-8.
inline std::u16string native_utf16(const std::string& text) {
    std::u16string result;
    result.reserve(text.size());
    for (size_t i = 0; i < text.size();) {
        const uint8_t lead = uint8_t(text[i++]);
        uint32_t code = lead;
        size_t extra = 0;
        uint32_t minimum = 0;
        if (lead >= 0xc2 && lead <= 0xdf) { code = lead & 0x1f; extra = 1; minimum = 0x80; }
        else if (lead >= 0xe0 && lead <= 0xef) { code = lead & 0xf; extra = 2; minimum = 0x800; }
        else if (lead >= 0xf0 && lead <= 0xf4) { code = lead & 7; extra = 3; minimum = 0x10000; }
        else if (lead >= 0x80) { result.push_back(0xfffd); continue; }
        bool valid = extra <= text.size() - i;
        for (size_t j = 0; valid && j < extra; ++j) {
            const uint8_t b = uint8_t(text[i + j]);
            if ((b & 0xc0) != 0x80) valid = false;
            else code = (code << 6) | (b & 0x3f);
        }
        if (!valid || code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) {
            result.push_back(0xfffd);
            continue;
        }
        i += extra;
        if (code > 0xffff) {
            code -= 0x10000;
            result.push_back(char16_t(0xd800 | (code >> 10)));
            result.push_back(char16_t(0xdc00 | (code & 0x3ff)));
        } else result.push_back(char16_t(code));
    }
    return result;
}
