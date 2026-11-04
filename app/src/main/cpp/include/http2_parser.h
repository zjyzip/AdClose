#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

extern "C" {
#include "nghttp2/nghttp2.h"
}

using H2ConnectionId = uint64_t;
constexpr uint8_t H2_FRAME_DATA = 0;
constexpr uint8_t H2_FRAME_HEADERS = 1;
constexpr uint8_t H2_FRAME_RST_STREAM = 3;
constexpr uint8_t H2_FRAME_SETTINGS = 4;
constexpr uint8_t H2_FRAME_PUSH_PROMISE = 5;
constexpr uint8_t H2_FRAME_GOAWAY = 7;
constexpr uint8_t H2_FRAME_CONTINUATION = 9;
constexpr uint8_t H2_FLAG_END_STREAM = 1;
constexpr uint8_t H2_FLAG_END_HEADERS = 4;
constexpr uint8_t H2_FLAG_PADDED = 8;
constexpr uint8_t H2_FLAG_PRIORITY = 32;
constexpr size_t H2_FRAME_HEADER_SIZE = 9;
constexpr size_t H2_MAX_STREAMS_PER_CONN = 1000;
constexpr size_t H2_MAX_CONNECTIONS = 512;
constexpr size_t H2_MAX_PAYLOAD_SIZE = 0xffffff;
constexpr size_t H2_MAX_HEADER_BYTES = 64 * 1024;
constexpr size_t H2_MAX_METADATA_BYTES = 1024 * 1024;
constexpr size_t H2_MAX_FEED_BYTES = 64 * 1024;
constexpr uint8_t H2_CLIENT_PREFACE[] = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
constexpr size_t H2_CLIENT_PREFACE_LEN = 24;

struct Http2Stream {
    int stream_id = 0;
    uint64_t last_active_time_ms = 0;
    std::string method, path, authority, scheme;
    std::vector<std::pair<std::string, std::string>> req_headers, resp_headers;
    bool req_headers_done = false, req_end_stream = false;
    bool resp_headers_done = false, resp_end_stream = false;
    int status_code = -1;
    bool is_complete() const {
        return req_headers_done && req_end_stream && resp_headers_done && resp_end_stream;
    }
};

struct Http2EarlyCheck {
    int stream_id = 0;
    std::string method, path, authority, scheme;
    std::vector<std::pair<std::string, std::string>> req_headers;
    bool request_complete = false;
};
struct Http2DataChunk {
    int stream_id = 0;
    bool is_request = false;
    std::vector<uint8_t> data;
    bool discarded = false;
};
struct Http2Request {
    int stream_id = 0;
    bool is_complete = false;
    std::string method, path, authority, scheme;
    std::vector<std::pair<std::string, std::string>> req_headers, resp_headers;
    int status_code = -1;
    bool request_complete = false, response_complete = false;
};
struct H2FeedResult {
    std::vector<Http2EarlyCheck> early_checks;
    std::vector<Http2DataChunk> data_chunks;
    std::vector<Http2Request> completed;
    std::vector<int> closed_streams;
    bool reset = false;
};
struct H2Direction {
    std::vector<uint8_t> buffer, header_block;
    nghttp2_hd_inflater* inflater = nullptr;
    int continuation_stream = 0;
    bool header_end_stream = false, push_promise = false;
    bool data_active = false;
    size_t data_remaining = 0, padding_remaining = 0;
    int data_stream = 0;
    bool data_end_stream = false;
};
struct Http2Connection {
    H2ConnectionId conn_id = 0;
    std::atomic<bool> is_h2{false}, h2_checked{false}, disabled{false};
    std::atomic<uint64_t> last_active_time_ms{0};
    // Parsing both directions and draining one feed's results are one transaction.
    std::mutex mutex, delivery_mutex;
    H2Direction local, remote;
    std::unordered_map<int, Http2Stream> streams;
    int last_accepted_stream = 0x7fffffff;
    uint64_t next_stream_expire_scan_ms = 0;
    ~Http2Connection();
};
std::shared_ptr<Http2Connection> h2_get_or_create(H2ConnectionId id);
void h2_free(H2ConnectionId id);
H2FeedResult h2_feed(const std::shared_ptr<Http2Connection>& conn, const uint8_t* data,
                     size_t len, bool is_local, bool collect_resp_body);
uint64_t get_current_ms();
