#include "http2_parser.h"
#include <android/log.h>
#include <cstring>
#include <time.h>

#define LOG_TAG "AdClose-H2"

#if DEBUG
#define H2LOG(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define H2LOG(...)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::mutex g_h2_map_mutex;
static std::unordered_map<uintptr_t, std::shared_ptr<Http2Connection>> g_h2_conns;

uint64_t get_current_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

Http2Connection::~Http2Connection() {
    if (local_inflater) nghttp2_hd_inflate_del(local_inflater);
    if (remote_inflater) nghttp2_hd_inflate_del(remote_inflater);
}

bool Http2Connection::init() {
    if (nghttp2_hd_inflate_new(&local_inflater) != 0) return false;
    if (nghttp2_hd_inflate_new(&remote_inflater) != 0) return false;
    return true;
}

void Http2Connection::evict_completed_streams() {
    uint64_t now = get_current_ms();
    for (auto it = streams.begin(); it != streams.end(); ) {
        if (it->second.is_complete() || (now - it->second.last_active_time_ms > 60000)) {
            it = streams.erase(it);
        } else {
            ++it;
        }
    }

    if ((int)streams.size() >= H2_MAX_STREAMS_PER_CONN) {
        int count = H2_MAX_STREAMS_PER_CONN / 2;
        for (auto it = streams.begin(); it != streams.end() && count > 0; ) {
            it = streams.erase(it);
            --count;
        }
    }
}

std::shared_ptr<Http2Connection> h2_get_or_create(uintptr_t conn_id) {
    std::lock_guard<std::mutex> lk(g_h2_map_mutex);
    auto it = g_h2_conns.find(conn_id);
    if (it != g_h2_conns.end()) return it->second;

    if (g_h2_conns.size() >= 2048) g_h2_conns.erase(g_h2_conns.begin());

    auto conn = std::make_shared<Http2Connection>();
    conn->conn_id = conn_id;
    if (!conn->init()) return nullptr;
    
    g_h2_conns[conn_id] = conn;
    return conn;
}

void h2_free(uintptr_t conn_id) {
    std::lock_guard<std::mutex> lk(g_h2_map_mutex);
    g_h2_conns.erase(conn_id);
}

bool h2_is_http2(uintptr_t conn_id) {
    std::shared_ptr<Http2Connection> conn;
    {
        std::lock_guard<std::mutex> lk(g_h2_map_mutex);
        auto it = g_h2_conns.find(conn_id);
        if (it == g_h2_conns.end()) return false;
        conn = it->second;
    }
    return conn->is_h2;
}

std::vector<std::vector<uint8_t>> h2_take_rst_frames(uintptr_t conn_id) {
    std::shared_ptr<Http2Connection> conn;
    {
        std::lock_guard<std::mutex> lk(g_h2_map_mutex);
        auto it = g_h2_conns.find(conn_id);
        if (it == g_h2_conns.end()) return {};
        conn = it->second;
    }
    std::lock_guard<std::mutex> st_lk(conn->streams_mutex);
    auto frames = std::move(conn->pending_rst_frames);
    conn->pending_rst_frames.clear();
    return frames;
}

void h2_enqueue_rst_stream(uintptr_t conn_id, int stream_id, uint32_t error_code) {
    std::shared_ptr<Http2Connection> conn;
    {
        std::lock_guard<std::mutex> lk(g_h2_map_mutex);
        auto it = g_h2_conns.find(conn_id);
        if (it == g_h2_conns.end()) return;
        conn = it->second;
    }
    std::lock_guard<std::mutex> st_lk(conn->streams_mutex);

    std::vector<uint8_t> frame(H2_FRAME_HEADER_SIZE + 4);
    frame[0] = 0; frame[1] = 0; frame[2] = 4;
    frame[3] = H2_FRAME_RST_STREAM; frame[4] = 0;
    frame[5] = (stream_id >> 24) & 0x7f; frame[6] = (stream_id >> 16) & 0xff;
    frame[7] = (stream_id >>  8) & 0xff; frame[8] =  stream_id        & 0xff;
    frame[9]  = (error_code >> 24) & 0xff; frame[10] = (error_code >> 16) & 0xff;
    frame[11] = (error_code >>  8) & 0xff; frame[12] =  error_code        & 0xff;

    conn->pending_rst_frames.push_back(std::move(frame));
}

void h2_block_stream(uintptr_t conn_id, int stream_id) {
    std::shared_ptr<Http2Connection> conn;
    {
        std::lock_guard<std::mutex> lk(g_h2_map_mutex);
        auto it = g_h2_conns.find(conn_id);
        if (it == g_h2_conns.end()) return;
        conn = it->second;
    }
    std::lock_guard<std::mutex> st_lk(conn->streams_mutex);
    if (conn->streams.find(stream_id) != conn->streams.end()) {
        conn->streams[stream_id].is_blocked = true;
    }
}

static uint32_t read_frame_length(const uint8_t* p) {
    return ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | p[2];
}

static int read_stream_id(const uint8_t* p) {
    return (int)(((uint32_t)(p[5] & 0x7f) << 24) | ((uint32_t)p[6] << 16) | ((uint32_t)p[7] << 8) | (uint32_t)p[8]);
}

static bool decode_headers(nghttp2_hd_inflater* inflater, const uint8_t* data, size_t len,
                           std::vector<std::pair<std::string, std::string>>& out_vec, std::string& out_method, std::string& out_path,
                           std::string& out_authority, std::string& out_scheme, int& out_status) {
    const uint8_t* pos = data;
    size_t remaining = len;

    while (remaining > 0) {
        nghttp2_nv nv;
        int inflate_flags = 0;
        ssize_t consumed = nghttp2_hd_inflate_hd2(inflater, &nv, &inflate_flags, pos, remaining, 1);

        if (consumed < 0) {
            LOGE("nghttp2 header decode failed: %zd", consumed);
            nghttp2_hd_inflate_end_headers(inflater);
            return false;
        }

        if (inflate_flags & NGHTTP2_HD_INFLATE_EMIT) {
            std::string name (reinterpret_cast<const char*>(nv.name),  nv.namelen);
            std::string value(reinterpret_cast<const char*>(nv.value), nv.valuelen);

            if (name == ":method") out_method = value;
            else if (name == ":path") out_path = value;
            else if (name == ":authority") out_authority = value;
            else if (name == ":scheme") out_scheme = value;
            else if (name == ":status") {
                out_status = 0;
                for (char c : value) if (c >= '0' && c <= '9') out_status = out_status * 10 + (c - '0');
            } else out_vec.push_back({name, value});
        }

        pos += consumed; remaining -= (size_t)consumed;
        if (inflate_flags & NGHTTP2_HD_INFLATE_FINAL) {
            nghttp2_hd_inflate_end_headers(inflater);
            break;
        }
    }
    return true;
}

static void snapshot_stream(Http2Connection* conn, Http2Stream& st, bool complete) {
    Http2Request req;
    req.stream_id    = st.stream_id;
    req.is_complete  = complete;
    req.method       = st.method;
    req.path         = st.path;
    req.authority    = st.authority;
    req.scheme       = st.scheme;
    req.req_headers  = st.req_headers;
    req.resp_headers = st.resp_headers;
    req.status_code  = st.status_code;

    conn->completed.push_back(std::move(req));
}

static void process_frame(Http2Connection* conn, const uint8_t* data, uint32_t frame_len,
                          uint8_t frame_type, uint8_t flags, int stream_id,
                          bool is_local, bool collect_resp_body) {
    const uint8_t* payload = data + H2_FRAME_HEADER_SIZE;

    if (stream_id == 0) return;
    if (frame_type == H2_FRAME_SETTINGS || frame_type == H2_FRAME_WINDOW_UPDATE || frame_type == H2_FRAME_GOAWAY) return;

    std::lock_guard<std::mutex> st_lk(conn->streams_mutex);

    if (frame_type == H2_FRAME_RST_STREAM) {
        conn->streams.erase(stream_id);
        return;
    }

    if (conn->streams.find(stream_id) == conn->streams.end()) {
        if (frame_type != H2_FRAME_HEADERS) return;
    } else {
        if (conn->streams[stream_id].is_blocked) return;
    }

    uint64_t now = get_current_ms();
    if (conn->streams.size() > H2_MAX_STREAMS_PER_CONN / 2 || (now - conn->last_evict_time_ms > 2000)) {
        conn->evict_completed_streams();
        conn->last_evict_time_ms = now;
    }

    Http2Stream& st = conn->streams[stream_id];
    st.stream_id = stream_id;
    st.last_active_time_ms = now;

    nghttp2_hd_inflater* inflater = is_local ? conn->local_inflater : conn->remote_inflater;

    if (frame_type == H2_FRAME_HEADERS || frame_type == H2_FRAME_CONTINUATION) {
        const uint8_t* hblock = payload; uint32_t hblock_len = frame_len;
        if (frame_type == H2_FRAME_HEADERS) {
            if (flags & H2_FLAG_PADDED) {
                if (frame_len < 1) return;
                uint8_t pad_len = payload[0]; hblock++;
                hblock_len = (frame_len > 1u + pad_len) ? frame_len - 1 - pad_len : 0;
            }
            if (flags & H2_FLAG_PRIORITY) {
                if (hblock_len < 5) return;
                hblock += 5; hblock_len -= 5;
            }
        }

        st.pending_header_block.insert(st.pending_header_block.end(), hblock, hblock + hblock_len);

        if (flags & H2_FLAG_END_HEADERS) {
            const auto& block = st.pending_header_block;
            std::lock_guard<std::mutex> inf_lk(is_local ? conn->local_inflater_mutex : conn->remote_inflater_mutex);
            
            if (is_local) {
                decode_headers(inflater, block.data(), block.size(), st.req_headers, st.method, st.path, st.authority, st.scheme, st.status_code);
                st.req_headers_done = true;
                
                Http2EarlyCheck check;
                check.stream_id = st.stream_id;
                check.method = st.method;
                check.path = st.path;
                check.authority = st.authority;
                check.scheme = st.scheme;
                check.req_headers = st.req_headers;
                conn->early_checks.push_back(std::move(check));

                if (flags & H2_FLAG_END_STREAM) st.req_end_stream = true;
                if (st.is_complete()) {
                    snapshot_stream(conn, st, true);
                    conn->streams.erase(stream_id);
                }
            } else {
                std::string dm, dp, da, ds;
                decode_headers(inflater, block.data(), block.size(), st.resp_headers, dm, dp, da, ds, st.status_code);
                st.resp_headers_done = true;
                if (flags & H2_FLAG_END_STREAM) {
                    st.resp_end_stream = true;
                    snapshot_stream(conn, st, st.is_complete());
                    if (st.is_complete()) conn->streams.erase(stream_id);
                }
            }
            st.pending_header_block.clear();
        }
    }
    else if (frame_type == H2_FRAME_DATA) {
        const uint8_t* body = payload; uint32_t body_len = frame_len;
        if (flags & H2_FLAG_PADDED) {
            if (frame_len < 1) return;
            uint8_t pad_len = payload[0]; body++;
            body_len = (frame_len > 1u + pad_len) ? frame_len - 1 - pad_len : 0;
        }

        if (body_len > 0) {
            if (is_local) {
                conn->data_chunks.push_back({stream_id, true, std::vector<uint8_t>(body, body + body_len)});
            } else if (collect_resp_body) {
                conn->data_chunks.push_back({stream_id, false, std::vector<uint8_t>(body, body + body_len)});
            }
        }

        if (flags & H2_FLAG_END_STREAM) {
            if (is_local) st.req_end_stream = true;
            else st.resp_end_stream = true;
            
            if (st.is_complete()) {
                snapshot_stream(conn, st, true);
                conn->streams.erase(stream_id);
            }
        }
    }
}

H2FeedResult h2_feed(std::shared_ptr<Http2Connection> conn, const uint8_t* data, size_t len, bool is_local, bool collect_resp_body) {
    if (!conn) return {};
    
    std::mutex& io_mutex = is_local ? conn->tx_mutex : conn->rx_mutex;
    std::lock_guard<std::mutex> io_lk(io_mutex);

    std::vector<uint8_t>& buf = is_local ? conn->local_buf : conn->remote_buf;
    size_t& offset = is_local ? conn->local_offset : conn->remote_offset;

    buf.insert(buf.end(), data, data + len);

    if (offset > 128 * 1024 && offset > buf.size() / 2) {
        buf.erase(buf.begin(), buf.begin() + offset);
        offset = 0;
    }

    if (!conn->h2_checked && is_local) {
        if (buf.size() - offset < H2_CLIENT_PREFACE_LEN) return {};
        if (memcmp(buf.data() + offset, H2_CLIENT_PREFACE, H2_CLIENT_PREFACE_LEN) == 0) {
            conn->is_h2 = true;
            conn->h2_checked = true;
            offset += H2_CLIENT_PREFACE_LEN;
            H2LOG("HTTP/2 detected: conn_id=0x%lx", (unsigned long)conn->conn_id);
        } else {
            conn->is_h2 = false;
            conn->h2_checked = true;
            buf.clear(); offset = 0;
            return {};
        }
    }

    if (!conn->is_h2) {
        buf.clear(); offset = 0;
        return {};
    }

    while (buf.size() - offset >= H2_FRAME_HEADER_SIZE) {
        const uint8_t* frame_ptr = buf.data() + offset;
        uint32_t frame_len  = read_frame_length(frame_ptr);
        uint8_t  frame_type = frame_ptr[3];
        uint8_t  flags      = frame_ptr[4];
        int      stream_id  = read_stream_id(frame_ptr);

        size_t total = H2_FRAME_HEADER_SIZE + (size_t)frame_len;
        if (buf.size() - offset < total) break;

        process_frame(conn.get(), frame_ptr, frame_len, frame_type, flags, stream_id, is_local, collect_resp_body);
        offset += total;
    }

    H2FeedResult result;
    {
        std::lock_guard<std::mutex> st_lk(conn->streams_mutex);
        result.early_checks = std::move(conn->early_checks);
        result.data_chunks  = std::move(conn->data_chunks);
        result.completed    = std::move(conn->completed);
        conn->early_checks.clear();
        conn->data_chunks.clear();
        conn->completed.clear();
    }
    
    return result;
}
