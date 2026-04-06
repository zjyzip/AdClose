#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <memory>
#include <utility>

extern "C" {
#include "nghttp2/nghttp2.h"
}

#define H2_FRAME_DATA          0x0
#define H2_FRAME_HEADERS       0x1
#define H2_FRAME_RST_STREAM    0x3
#define H2_FRAME_SETTINGS      0x4
#define H2_FRAME_GOAWAY        0x7
#define H2_FRAME_WINDOW_UPDATE 0x8
#define H2_FRAME_CONTINUATION  0x9

#define H2_FLAG_END_STREAM  0x1
#define H2_FLAG_END_HEADERS 0x4
#define H2_FLAG_PADDED      0x8
#define H2_FLAG_PRIORITY    0x20

#define H2_FRAME_HEADER_SIZE 9
#define H2_MAX_STREAMS_PER_CONN 1000

#define H2_MAX_PAYLOAD_SIZE (5 * 1024 * 1024)

static const uint8_t H2_CLIENT_PREFACE[] = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
static const size_t H2_CLIENT_PREFACE_LEN = 24;

struct Http2Stream {
    int stream_id = 0;
    bool is_blocked = false;
    uint64_t last_active_time_ms = 0;

    std::string method;
    std::string path;
    std::string authority;
    std::string scheme;
    
    std::vector<std::pair<std::string, std::string>> req_headers;
    bool req_headers_done = false;
    bool req_end_stream   = false;

    int status_code = -1;
    std::vector<std::pair<std::string, std::string>> resp_headers;
    bool resp_headers_done = false;
    bool resp_end_stream   = false;

    std::vector<uint8_t> pending_header_block;

    bool is_complete() const {
        return req_headers_done && req_end_stream &&
               resp_headers_done && resp_end_stream;
    }
};

struct Http2EarlyCheck {
    int stream_id = 0;
    std::string method;
    std::string path;
    std::string authority;
    std::string scheme;
    std::vector<std::pair<std::string, std::string>> req_headers;
};

struct Http2DataChunk {
    int stream_id = 0;
    bool is_request = false;
    std::vector<uint8_t> data;
};

struct Http2Request {
    int  stream_id   = 0;
    bool is_complete = false;

    std::string method;
    std::string path;
    std::string authority;
    std::string scheme;
    std::vector<std::pair<std::string, std::string>> req_headers;
    std::vector<std::pair<std::string, std::string>> resp_headers;
    int status_code = -1;

    bool should_block = false;
};

struct H2FeedResult {
    std::vector<Http2EarlyCheck> early_checks;
    std::vector<Http2DataChunk>  data_chunks;
    std::vector<Http2Request>    completed;
};

struct Http2Connection {
    uintptr_t conn_id = 0;
    bool      is_h2   = false;
    bool      h2_checked = false;

    std::vector<uint8_t> local_buf;
    size_t local_offset = 0;

    std::vector<uint8_t> remote_buf;
    size_t remote_offset = 0;

    nghttp2_hd_inflater* local_inflater  = nullptr;
    nghttp2_hd_inflater* remote_inflater = nullptr;

    std::unordered_map<int, Http2Stream> streams;
    
    std::vector<Http2EarlyCheck> early_checks;
    std::vector<Http2DataChunk>  data_chunks;
    std::vector<Http2Request>    completed;
    
    std::vector<std::vector<uint8_t>> pending_rst_frames;

    std::mutex tx_mutex;       
    std::mutex rx_mutex;       
    std::mutex streams_mutex;  
    
    std::mutex local_inflater_mutex;
    std::mutex remote_inflater_mutex;

    uint64_t last_evict_time_ms = 0;

    Http2Connection() = default;
    ~Http2Connection();

    bool init();
    void evict_completed_streams();
};

std::shared_ptr<Http2Connection> h2_get_or_create(uintptr_t conn_id);
void h2_free(uintptr_t conn_id);

H2FeedResult h2_feed(std::shared_ptr<Http2Connection> conn,
                     const uint8_t* data, size_t len,
                     bool is_local, bool collect_resp_body);

bool h2_is_http2(uintptr_t conn_id);
std::vector<std::vector<uint8_t>> h2_take_rst_frames(uintptr_t conn_id);
void h2_enqueue_rst_stream(uintptr_t conn_id, int stream_id, uint32_t error_code = 0x8);
void h2_block_stream(uintptr_t conn_id, int stream_id);

uint64_t get_current_ms();