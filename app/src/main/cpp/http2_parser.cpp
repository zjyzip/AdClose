#include "http2_parser.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <limits>

namespace {
std::mutex connections_mutex;
std::unordered_map<H2ConnectionId, std::shared_ptr<Http2Connection>> connections;

uint32_t read32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}
size_t metadata_size(const Http2Stream& s) {
    size_t size = s.method.size() + s.path.size() + s.authority.size() + s.scheme.size();
    for (const auto& h : s.req_headers) size += h.first.size() + h.second.size() + 64;
    for (const auto& h : s.resp_headers) size += h.first.size() + h.second.size() + 64;
    return size;
}
void disable(Http2Connection& c, H2FeedResult& result) {
    c.disabled = true;
    c.streams.clear();
    for (auto* d : {&c.local, &c.remote}) {
        std::vector<uint8_t>().swap(d->buffer);
        std::vector<uint8_t>().swap(d->header_block);
        if (d->inflater) nghttp2_hd_inflate_del(d->inflater);
        d->inflater = nullptr;
        d->continuation_stream = 0;
    }
    result = {};
    result.reset = true;
}
void snapshot(const Http2Stream& s, H2FeedResult& result) {
    result.completed.push_back({s.stream_id, s.is_complete(), s.method, s.path, s.authority,
        s.scheme, s.req_headers, s.resp_headers, s.status_code,
        s.req_headers_done && s.req_end_stream, s.resp_headers_done && s.resp_end_stream});
}
void erase_stream(Http2Connection& c, int id, H2FeedResult& result) {
    if (c.streams.erase(id)) result.closed_streams.push_back(id);
}
struct Headers {
    std::string method, path, authority, scheme;
    std::vector<std::pair<std::string, std::string>> regular;
    int status = -1;
};
bool decode(H2Direction& d, Headers& out) {
    const uint8_t empty = 0;
    const uint8_t* p = d.header_block.empty() ? &empty : d.header_block.data();
    size_t remaining = d.header_block.size(), bytes = 0, fields = 0;
    for (;;) {
        nghttp2_nv nv{};
        int flags = 0;
        const ssize_t consumed = nghttp2_hd_inflate_hd2(d.inflater, &nv, &flags, p, remaining, 1);
        if (consumed < 0 || size_t(consumed) > remaining) return false;
        if (flags & NGHTTP2_HD_INFLATE_EMIT) {
            if (++fields > 256 || nv.namelen > H2_MAX_HEADER_BYTES - bytes) return false;
            bytes += nv.namelen;
            if (nv.valuelen > H2_MAX_HEADER_BYTES - bytes) return false;
            bytes += nv.valuelen;
            std::string name(reinterpret_cast<const char*>(nv.name), nv.namelen);
            std::string value(reinterpret_cast<const char*>(nv.value), nv.valuelen);
            // JNI's alternating header representation requires individual fields to be single lines.
            if (name.find_first_of("\r\n\0", 0, 3) != std::string::npos ||
                value.find_first_of("\r\n\0", 0, 3) != std::string::npos) return false;
            if (name == ":method") out.method = value;
            else if (name == ":path") out.path = value;
            else if (name == ":authority") out.authority = value;
            else if (name == ":scheme") out.scheme = value;
            else if (name == ":status") {
                if (value.size() != 3 || value[0] < '1' || value[0] > '5' ||
                    value[1] < '0' || value[1] > '9' || value[2] < '0' || value[2] > '9') return false;
                out.status = (value[0] - '0') * 100 + (value[1] - '0') * 10 + value[2] - '0';
            } else out.regular.emplace_back(std::move(name), std::move(value));
        }
        p += consumed;
        remaining -= consumed;
        if (flags & NGHTTP2_HD_INFLATE_FINAL) {
            if (remaining != 0) return false;
            return nghttp2_hd_inflate_end_headers(d.inflater) == 0;
        }
        if (consumed == 0 && !(flags & NGHTTP2_HD_INFLATE_EMIT)) return false;
    }
}
bool unpad(const uint8_t*& payload, size_t& size, uint8_t flags) {
    if (!(flags & H2_FLAG_PADDED)) return true;
    if (size == 0 || size_t(payload[0]) > size - 1) return false;
    size -= size_t(payload[0]) + 1;
    ++payload;
    return true;
}
bool process_data(Http2Connection& c, int id, const uint8_t* payload, size_t size,
                  bool end, bool local, bool collect, H2FeedResult& result) {
    if (id == 0) return false;
    auto it = c.streams.find(id);
    if (it == c.streams.end()) return true;
    auto& s = it->second;
    s.last_active_time_ms = get_current_ms();
    if ((local && (!s.req_headers_done || s.req_end_stream)) ||
        (!local && (!s.resp_headers_done || s.resp_end_stream))) return false;
    if (size) {
        if (local || collect) result.data_chunks.push_back({id, local, {payload, payload + size}, false});
        else result.data_chunks.push_back({id, false, {}, true});
    }
    if (end) {
        if (local) s.req_end_stream = true; else s.resp_end_stream = true;
        snapshot(s, result);
        if (s.is_complete()) erase_stream(c, id, result);
    }
    return true;
}
bool process_frame(Http2Connection& c, H2Direction& d, const uint8_t* frame,
                   size_t size, bool local, bool collect, H2FeedResult& result) {
    const uint8_t type = frame[3], flags = frame[4];
    const int id = int(read32(frame + 5) & 0x7fffffff);
    const uint8_t* payload = frame + H2_FRAME_HEADER_SIZE;
    if (d.continuation_stream && (type != H2_FRAME_CONTINUATION || id != d.continuation_stream)) return false;
    if (type == H2_FRAME_SETTINGS) {
        if (id != 0 || size % 6 || ((flags & 1) && size != 0)) return false;
        for (size_t i = 0; i < size; i += 6) {
            const uint16_t setting = uint16_t(payload[i]) << 8 | payload[i + 1];
            if (setting == 1) {
                const uint32_t value = read32(payload + i + 2);
                if (value > H2_MAX_HEADER_BYTES) return false;
                auto* inflater = local ? c.remote.inflater : c.local.inflater;
                if (nghttp2_hd_inflate_change_table_size(inflater, value) != 0) return false;
            }
        }
        return true;
    }
    if (type == H2_FRAME_GOAWAY) {
        if (id != 0 || size < 8) return false;
        if (read32(payload + 4) != 0) return false;
        if (!local) {
            c.last_accepted_stream = std::min(c.last_accepted_stream, int(read32(payload) & 0x7fffffff));
            for (auto it = c.streams.begin(); it != c.streams.end();) {
                if (it->first > c.last_accepted_stream) {
                    result.closed_streams.push_back(it->first);
                    it = c.streams.erase(it);
                } else ++it;
            }
        }
        return true;
    }
    if (type == H2_FRAME_RST_STREAM) {
        if (id == 0 || size != 4) return false;
        erase_stream(c, id, result);
        return true;
    }
    if (type == H2_FRAME_HEADERS || type == H2_FRAME_CONTINUATION || type == H2_FRAME_PUSH_PROMISE) {
        if (id == 0) return false;
        if (type != H2_FRAME_CONTINUATION) {
            if (d.continuation_stream || !unpad(payload, size, flags)) return false;
            if (type == H2_FRAME_HEADERS && (flags & H2_FLAG_PRIORITY)) {
                if (size < 5) return false;
                payload += 5;
                size -= 5;
            }
            d.push_promise = type == H2_FRAME_PUSH_PROMISE;
            if (d.push_promise) {
                if (local || size < 4) return false;
                payload += 4;
                size -= 4;
            }
            d.continuation_stream = id;
            d.header_end_stream = !d.push_promise && (flags & H2_FLAG_END_STREAM);
        } else if (d.continuation_stream != id) return false;
        if (size > H2_MAX_HEADER_BYTES - d.header_block.size()) return false;
        d.header_block.insert(d.header_block.end(), payload, payload + size);
        if (!(flags & H2_FLAG_END_HEADERS)) return true;
        Headers headers;
        if (!decode(d, headers)) return false;
        d.header_block.clear();
        d.continuation_stream = 0;
        // Push promises and untracked response streams still update HPACK's dynamic table.
        if (d.push_promise) return true;
        auto it = c.streams.find(id);
        if (local && it == c.streams.end() && !headers.method.empty() && (id & 1) && id <= c.last_accepted_stream) {
            if (c.streams.size() >= H2_MAX_STREAMS_PER_CONN) {
                auto oldest = std::min_element(c.streams.begin(), c.streams.end(), [](const auto& a, const auto& b) {
                    return a.second.last_active_time_ms < b.second.last_active_time_ms;
                });
                erase_stream(c, oldest->first, result);
            }
            it = c.streams.emplace(id, Http2Stream{}).first;
            it->second.stream_id = id;
        }
        if (it == c.streams.end()) return true;
        auto& s = it->second;
        s.last_active_time_ms = get_current_ms();
        if (local) {
            if (d.header_end_stream) s.req_end_stream = true;
            if (!s.req_headers_done) {
                if (headers.method.empty()) return false;
                s.method = std::move(headers.method); s.path = std::move(headers.path);
                s.authority = std::move(headers.authority); s.scheme = std::move(headers.scheme);
                s.req_headers = std::move(headers.regular);
                s.req_headers_done = true;
                result.early_checks.push_back({id, s.method, s.path, s.authority, s.scheme, s.req_headers,
                    s.req_end_stream});
            } else {
                if (!headers.method.empty()) return false;
                s.req_headers.insert(s.req_headers.end(), headers.regular.begin(), headers.regular.end());
            }
            if (d.header_end_stream) snapshot(s, result);
        } else {
            if (headers.status > 0 && headers.status < 200) return !d.header_end_stream;
            if (!s.resp_headers_done) {
                if (headers.status < 200) return false;
                s.status_code = headers.status;
                s.resp_headers = std::move(headers.regular);
                s.resp_headers_done = true;
            } else {
                if (headers.status != -1) return false;
                s.resp_headers.insert(s.resp_headers.end(), headers.regular.begin(), headers.regular.end());
            }
            if (d.header_end_stream) s.resp_end_stream = true;
            snapshot(s, result);
        }
        size_t metadata = 0;
        for (const auto& entry : c.streams) metadata += metadata_size(entry.second);
        if (metadata > H2_MAX_METADATA_BYTES) return false;
        if (s.is_complete()) erase_stream(c, id, result);
        return true;
    }
    if (type == H2_FRAME_DATA) {
        if (id == 0 || !unpad(payload, size, flags)) return false;
        return process_data(c, id, payload, size, flags & H2_FLAG_END_STREAM, local, collect, result);
    }
    return true;
}
bool parse_buffer(Http2Connection& c, H2Direction& d, bool local, bool collect, H2FeedResult& result) {
    size_t offset = 0;
    while (offset < d.buffer.size() || d.data_active) {
        if (d.data_active) {
            const size_t count = std::min(d.data_remaining, d.buffer.size() - offset);
            const bool end = count == d.data_remaining && d.padding_remaining == 0 && d.data_end_stream;
            if (count && !process_data(c, d.data_stream, d.buffer.data() + offset, count, end, local, collect, result)) return false;
            offset += count;
            d.data_remaining -= count;
            if (d.data_remaining) break;
            const size_t padding = std::min(d.padding_remaining, d.buffer.size() - offset);
            d.padding_remaining -= padding;
            offset += padding;
            if (d.padding_remaining) break;
            if (d.data_end_stream && !end && !process_data(c, d.data_stream, nullptr, 0, true, local, collect, result)) return false;
            if (d.data_end_stream && count == 0 && end && !process_data(c, d.data_stream, nullptr, 0, true, local, collect, result)) return false;
            d.data_active = false;
            continue;
        }
        if (d.buffer.size() - offset < H2_FRAME_HEADER_SIZE) break;
        const uint8_t* frame = d.buffer.data() + offset;
        const size_t size = (size_t(frame[0]) << 16) | (size_t(frame[1]) << 8) | frame[2];
        if (frame[3] == H2_FRAME_DATA) {
            if (d.continuation_stream) return false;
            const bool padded = frame[4] & H2_FLAG_PADDED;
            if (padded && d.buffer.size() - offset < H2_FRAME_HEADER_SIZE + 1) break;
            const size_t padding = padded ? frame[H2_FRAME_HEADER_SIZE] : 0;
            if (padded && (size == 0 || padding > size - 1)) return false;
            d.data_stream = int(read32(frame + 5) & 0x7fffffff);
            if (d.data_stream == 0) return false;
            d.data_end_stream = frame[4] & H2_FLAG_END_STREAM;
            d.data_remaining = size - padding - (padded ? 1 : 0);
            d.padding_remaining = padding;
            d.data_active = true;
            offset += H2_FRAME_HEADER_SIZE + (padded ? 1 : 0);
            continue;
        }
        // DATA is streamed above; metadata frames remain bounded independently of payload length.
        if (size > H2_MAX_HEADER_BYTES + 261) return false;
        if (d.buffer.size() - offset < H2_FRAME_HEADER_SIZE + size) break;
        if (!process_frame(c, d, frame, size, local, collect, result)) return false;
        offset += H2_FRAME_HEADER_SIZE + size;
    }
    if (offset) d.buffer.erase(d.buffer.begin(), d.buffer.begin() + offset);
    return true;
}
} // namespace

uint64_t get_current_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
Http2Connection::~Http2Connection() {
    if (local.inflater) nghttp2_hd_inflate_del(local.inflater);
    if (remote.inflater) nghttp2_hd_inflate_del(remote.inflater);
}
std::shared_ptr<Http2Connection> h2_get_or_create(H2ConnectionId id) {
    std::lock_guard<std::mutex> lock(connections_mutex);
    auto it = connections.find(id);
    if (it != connections.end()) {
        it->second->last_active_time_ms = get_current_ms();
        return it->second;
    }
    if (connections.size() >= H2_MAX_CONNECTIONS) {
        auto oldest = connections.end();
        for (auto candidate = connections.begin(); candidate != connections.end(); ++candidate) {
            if (candidate->second.use_count() == 1 && (oldest == connections.end() ||
                candidate->second->last_active_time_ms < oldest->second->last_active_time_ms)) oldest = candidate;
        }
        if (oldest == connections.end()) return nullptr;
        connections.erase(oldest);
    }
    auto conn = std::make_shared<Http2Connection>();
    conn->conn_id = id;
    conn->last_active_time_ms = get_current_ms();
    connections[id] = conn;
    return conn;
}
void h2_free(H2ConnectionId id) {
    std::lock_guard<std::mutex> lock(connections_mutex);
    connections.erase(id);
}
H2FeedResult h2_feed(const std::shared_ptr<Http2Connection>& conn, const uint8_t* data,
                    size_t len, bool local, bool collect) {
    H2FeedResult result;
    if (!conn || !data || len == 0) return result;
    std::lock_guard<std::mutex> lock(conn->mutex);
    auto& c = *conn;
    c.last_active_time_ms = get_current_ms();
    if (c.disabled || (c.h2_checked && !c.is_h2)) return result;
    auto& d = local ? c.local : c.remote;
    if (len > H2_MAX_FEED_BYTES || len > H2_MAX_PAYLOAD_SIZE + H2_FRAME_HEADER_SIZE - d.buffer.size()) {
        disable(c, result);
        return result;
    }
    d.buffer.insert(d.buffer.end(), data, data + len);
    if (!c.h2_checked) {
        const size_t prefix = std::min(c.local.buffer.size(), H2_CLIENT_PREFACE_LEN);
        if (prefix && std::memcmp(c.local.buffer.data(), H2_CLIENT_PREFACE, prefix) != 0) {
            c.h2_checked = true;
            std::vector<uint8_t>().swap(c.local.buffer);
            std::vector<uint8_t>().swap(c.remote.buffer);
            return result;
        }
        if (c.local.buffer.size() < H2_CLIENT_PREFACE_LEN) return result;
        c.is_h2 = true;
        c.h2_checked = true;
        c.local.buffer.erase(c.local.buffer.begin(), c.local.buffer.begin() + H2_CLIENT_PREFACE_LEN);
        if (nghttp2_hd_inflate_new(&c.local.inflater) != 0 || nghttp2_hd_inflate_new(&c.remote.inflater) != 0) {
            disable(c, result);
            return result;
        }
    }
    const uint64_t now = get_current_ms();
    if (now >= c.next_stream_expire_scan_ms) {
        c.next_stream_expire_scan_ms = now + 1000;
        for (auto it = c.streams.begin(); it != c.streams.end();) {
            if (now - it->second.last_active_time_ms > 60000) {
                result.closed_streams.push_back(it->first);
                it = c.streams.erase(it);
            } else ++it;
        }
    }
    if (!parse_buffer(c, c.local, true, collect, result) || !parse_buffer(c, c.remote, false, collect, result)) {
        disable(c, result);
    }
    return result;
}
