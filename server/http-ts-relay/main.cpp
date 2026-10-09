// HTTP TS relay: ordered, acknowledged uploads and paced, delayed binary playback.
// No TS demuxing, timestamp editing, keyframe filtering or session-boundary rewriting.
#ifdef _WIN32
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netdb.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#endif
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <iostream>
#include <iomanip>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <tuple>
#include <vector>

using Clock = std::chrono::steady_clock;
using Time = Clock::time_point;
using Us = std::chrono::microseconds;
static void log_line(const std::string& line) {
    static std::mutex log_mutex;
    std::lock_guard<std::mutex> lock(log_mutex);
    std::cout << line << std::endl;
}
#ifdef _WIN32
using Socket = SOCKET;
constexpr Socket invalid_socket = INVALID_SOCKET;
static void close_socket(Socket s) { closesocket(s); }
static bool retryable() {
    const int error = WSAGetLastError();
    return error == WSAEWOULDBLOCK || error == WSAEINTR;
}
#else
using Socket = int;
constexpr Socket invalid_socket = -1;
static void close_socket(Socket s) { close(s); }
static bool retryable() { return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR; }
#endif

struct SocketOwner {
    Socket socket;
    explicit SocketOwner(Socket s) : socket(s) {}
    ~SocketOwner() { if (socket != invalid_socket) close_socket(socket); }
    SocketOwner(const SocketOwner&) = delete;
    SocketOwner& operator=(const SocketOwner&) = delete;
};

static void nonblocking(Socket socket) {
#ifdef _WIN32
    u_long mode = 1;
    if (ioctlsocket(socket, FIONBIO, &mode)) throw std::runtime_error("nonblocking socket failed");
#else
    int flags = fcntl(socket, F_GETFL, 0);
    if (flags < 0 || fcntl(socket, F_SETFL, flags | O_NONBLOCK) < 0)
        throw std::runtime_error("nonblocking socket failed");
#endif
}

static bool ready(Socket socket, bool write, Time deadline, [[maybe_unused]] bool connecting = false) {
    while (true) {
        const auto left = std::chrono::duration_cast<Us>(deadline - Clock::now()).count();
        if (left <= 0) return false;
#ifdef _WIN32
        fd_set descriptors, errors;
        FD_ZERO(&descriptors); FD_SET(socket, &descriptors);
        FD_ZERO(&errors); FD_SET(socket, &errors);
        timeval timeout{static_cast<long>(left / 1000000), static_cast<long>(left % 1000000)};
        const int result = select(0, write ? nullptr : &descriptors,
                                  write ? &descriptors : nullptr, connecting ? &errors : nullptr, &timeout);
#else
        pollfd descriptor{socket, static_cast<short>(write ? POLLOUT : POLLIN), 0};
        const int result = poll(&descriptor, 1, static_cast<int>((left + 999) / 1000));
#endif
        if (result > 0) return true;
        if (!result || !retryable()) return false;
    }
}

static bool send_all(Socket socket, const char* data, size_t size) {
    const auto deadline = Clock::now() + std::chrono::seconds(10);
    while (size) {
        if (!ready(socket, true, deadline)) return false;
        const int length = static_cast<int>(std::min<size_t>(size, 16384));
#ifdef _WIN32
        const int sent = send(socket, data, length, 0);
#else
        const int sent = static_cast<int>(send(socket, data, length, MSG_NOSIGNAL));
#endif
        if (sent > 0) { data += sent; size -= static_cast<size_t>(sent); }
        else if (sent == 0 || !retryable()) return false;
    }
    return true;
}
static bool send_all(Socket socket, const std::string& text) {
    return send_all(socket, text.data(), text.size());
}

struct HttpError : std::runtime_error {
    int code;
    HttpError(int status, const std::string& message) : std::runtime_error(message), code(status) {}
};
struct Request {
    std::string method, path;
    std::map<std::string, std::string> headers;
    std::vector<char> body;
    double receive_seconds = 0;
};

static uint64_t number(const std::string& text, uint64_t limit) {
    if (text.empty() || text.size() > 20) throw HttpError(400, "Invalid numeric header");
    uint64_t result = 0;
    for (unsigned char c : text) {
        if (c < '0' || c > '9' || result > limit / 10 ||
            (result == limit / 10 && static_cast<unsigned>(c - '0') > limit % 10))
            throw HttpError(400, "Numeric header out of range");
        result = result * 10 + c - '0';
    }
    return result;
}
static std::string trim(std::string text) {
    const auto begin = text.find_first_not_of(" \t\r");
    if (begin == std::string::npos) return {};
    return text.substr(begin, text.find_last_not_of(" \t\r") - begin + 1);
}
static bool identifier(const std::string& text) {
    return !text.empty() && text.size() <= 64 && std::all_of(text.begin(), text.end(), [](unsigned char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
               (c >= '0' && c <= '9') || c == '-' || c == '_';
    });
}

static Request read_request(Socket socket) {
    Request request;
    std::string incoming;
    char buffer[8192];
    const auto begin = Clock::now();
    const auto deadline = begin + std::chrono::seconds(15);
    size_t end;
    while ((end = incoming.find("\r\n\r\n")) == std::string::npos) {
        if (incoming.size() > 16384) throw HttpError(431, "Headers too large");
        if (!ready(socket, false, deadline)) throw HttpError(408, "Request timeout");
        const int count = static_cast<int>(recv(socket, buffer, sizeof(buffer), 0));
        if (count > 0) incoming.append(buffer, static_cast<size_t>(count));
        else if (count == 0 || !retryable()) throw HttpError(400, "Incomplete request");
    }
    if (end > 16384) throw HttpError(431, "Headers too large");
    std::istringstream lines(incoming.substr(0, end));
    std::string line, version, extra;
    std::getline(lines, line);
    std::istringstream first(trim(line));
    if (!(first >> request.method >> request.path >> version) || (first >> extra) ||
        (version != "HTTP/1.1" && version != "HTTP/1.0")) throw HttpError(400, "Bad request line");
    while (std::getline(lines, line)) {
        const auto colon = line.find(':');
        if (colon == std::string::npos) throw HttpError(400, "Bad header");
        auto key = line.substr(0, colon);
        std::transform(key.begin(), key.end(), key.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        if (!request.headers.emplace(key, trim(line.substr(colon + 1))).second)
            throw HttpError(400, "Duplicate header");
    }
    if (request.headers.count("transfer-encoding")) throw HttpError(400, "Content-Length required");
    size_t length = 0;
    if (request.method == "POST") {
        if (!request.headers.count("content-length")) throw HttpError(411, "Content-Length required");
        length = static_cast<size_t>(number(request.headers.at("content-length"), 16 * 1024 * 1024));
    }
    if (request.headers.count("expect")) {
        if (request.headers.at("expect") != "100-continue") throw HttpError(417, "Unsupported expectation");
        if (!send_all(socket, "HTTP/1.1 100 Continue\r\n\r\n")) throw HttpError(408, "Client disconnected");
    }
    request.body.reserve(length);
    const size_t copied = std::min(length, incoming.size() - end - 4);
    request.body.insert(request.body.end(), incoming.begin() + static_cast<std::ptrdiff_t>(end + 4),
                        incoming.begin() + static_cast<std::ptrdiff_t>(end + 4 + copied));
    while (request.body.size() < length) {
        if (!ready(socket, false, deadline)) throw HttpError(408, "Body timeout");
        const int count = static_cast<int>(recv(socket, buffer,
            static_cast<int>(std::min<size_t>(sizeof(buffer), length - request.body.size())), 0));
        if (count > 0) request.body.insert(request.body.end(), buffer, buffer + count);
        else if (count == 0 || !retryable()) throw HttpError(400, "Incomplete body");
    }
    request.receive_seconds = std::chrono::duration<double>(Clock::now() - begin).count();
    return request;
}

static void response(Socket socket, int status, const std::string& body, const std::string& headers = {}, bool keep_alive = false) {
    if (socket == invalid_socket) return; // Internal merge ingestion does not have an HTTP client.
    send_all(socket, "HTTP/1.1 " + std::to_string(status) + (status == 200 ? " OK\r\n" : " Error\r\n") +
        "Content-Type: text/plain; charset=utf-8\r\nConnection: " + (keep_alive ? "keep-alive" : "close") + "\r\nContent-Length: " +
        std::to_string(body.size()) + "\r\n" + headers + "\r\n" + body);
}

struct Block {
    uint64_t index;
    Us start;
    Us duration;
    std::vector<char> data;
    std::optional<Time> played_at;
    size_t skipped_prefix = 0;
};
struct Receipt {
    uint64_t next = 0, last_hash = 0, last_duration = 0;
    bool final = false, last_final = false;
};
static uint64_t hash_bytes(const std::vector<char>& data) {
    uint64_t hash = 14695981039346656037ULL;
    for (unsigned char c : data) { hash ^= c; hash *= 1099511628211ULL; }
    return hash;
}

class Relay {
    std::mutex mutex;
    std::condition_variable changed;
    std::deque<std::shared_ptr<Block>> blocks;
    std::map<std::string, Receipt> receipts;
    std::deque<std::string> completed;
    std::string active_session;
    size_t bytes = 0;
    uint64_t next_index = 1;
    bool buffering = true;
    Time anchor_time;
    Us tail{0}, position{0}, anchor_position{0};
    Us delay, retention, max_pending;
    size_t max_bytes;
    uint64_t accepted_bytes = 0;
    uint64_t dropped_blocks = 0, skipped_sequences = 0, skip_before_index = 1;
    uint64_t playback_generation = 0, dropped_bytes = 0;
    std::atomic<bool> reporting_stopped{false};
    std::thread reporter;

    struct Stats {
        std::string session, sequence;
        size_t bytes, blocks;
        double cached_seconds = 0, pending_seconds = 0;
        uint64_t accepted_bytes;
        bool buffering;
        uint64_t dropped_blocks, skipped_sequences;
        uint64_t dropped_bytes;
    };
    Stats snapshot() {
        std::lock_guard<std::mutex> lock(mutex);
        const auto now = Clock::now();
        update(now);
        const auto receipt = receipts.find(active_session);
        Stats stats{active_session.empty() ? "-" : active_session,
            receipt == receipts.end() || !receipt->second.next ? "-" : std::to_string(receipt->second.next - 1),
            bytes, blocks.size(), 0, std::chrono::duration<double>(tail - position).count(), accepted_bytes,
            buffering, dropped_blocks, skipped_sequences, dropped_bytes};
        for (const auto& block : blocks) {
            stats.cached_seconds += std::chrono::duration<double>(block->duration).count();
        }
        return stats;
    }
    static void cache_fields(std::ostream& line, const Stats& stats) {
        line << " cache=" << stats.bytes / 1024.0 << " KiB"
             << " cached=" << stats.cached_seconds << " s"
             << " pending=" << stats.pending_seconds << " s blocks=" << stats.blocks
             << " state=" << (stats.buffering ? "buffering" : "playing")
             << " dropped=" << stats.dropped_blocks << " skipped=" << stats.skipped_sequences
             << " dropped_bytes=" << stats.dropped_bytes / 1024.0 << " KiB";
    }
    void log_upload(const std::string& session, const std::string& sequence, int status,
                    const std::string& result, size_t received, double seconds) {
        const auto stats = snapshot();
        std::ostringstream line;
        line << std::fixed << std::setprecision(2)
             << "[upload] session=" << session << " seq=" << sequence << " status=" << status
             << " result=" << result << " received=" << received / 1024.0 << " KiB"
             << " speed=" << received / 1024.0 / std::max(seconds, 0.000001) << " KiB/s";
        cache_fields(line, stats);
        log_line(line.str());
    }
    void report() {
        auto previous = Clock::now();
        uint64_t previous_bytes = 0;
        while (!reporting_stopped) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            if (reporting_stopped) break;
            const auto now = Clock::now();
            {
                std::lock_guard<std::mutex> lock(mutex);
                update(now);
            }
            if (now - previous < std::chrono::seconds(1)) continue;
            const auto stats = snapshot();
            const auto seconds = std::chrono::duration<double>(now - previous).count();
            std::ostringstream line;
            line << std::fixed << std::setprecision(2)
                 << "[status] session=" << stats.session << " seq=" << stats.sequence
                 << " speed=" << (stats.accepted_bytes - previous_bytes) / 1024.0 / seconds << " KiB/s";
            cache_fields(line, stats);
            log_line(line.str());
            previous = now; previous_bytes = stats.accepted_bytes;
        }
    }

    void prune(Time now) {
        while (!blocks.empty() && blocks.front()->played_at && *blocks.front()->played_at <= now - retention) {
            bytes -= blocks.front()->data.size(); blocks.pop_front();
        }
    }
    static size_t offset_at(const Block& block, Us point) {
        if (point <= block.start) return 0;
        if (point >= block.start + block.duration) return block.data.size();
        const auto relative = (point - block.start).count();
        const auto packets = block.data.size() / 188;
        return static_cast<size_t>((relative * packets + block.duration.count() - 1) / block.duration.count()) * 188;
    }
    void catch_up(Time now) {
        // The seconds threshold controls pending playback only. Cache eviction
        // is governed by the byte budget and retention of played/skipped history.
        if (tail - position < max_pending) return;
        const auto target = tail - delay;
        if (target <= position) return;
        for (const auto& block : blocks) {
            const auto end = block->start + block->duration;
            if (end <= position) continue;
            if (block->start >= target) break;
            const auto previous = std::max(block->skipped_prefix, offset_at(*block, position));
            const auto offset = offset_at(*block, target);
            if (offset > previous) dropped_bytes += offset - previous;
            block->skipped_prefix = offset;
            if (end <= target) {
                block->played_at = now;
                skip_before_index = block->index + 1;
                dropped_blocks++;
            }
        }
        position = target;
        anchor_position = position; anchor_time = now;
        playback_generation++;
        changed.notify_all();
    }
    void discard_front(Time now) {
        const auto block = blocks.front();
        const auto end = block->start + block->duration;
        if (end > position) {
            dropped_bytes += block->data.size() - std::max(block->skipped_prefix, offset_at(*block, position));
            position = end;
            anchor_position = position; anchor_time = now;
            skip_before_index = block->index + 1;
            dropped_blocks++;
            playback_generation++;
            if (position >= tail) buffering = true;
        }
        bytes -= block->data.size(); blocks.pop_front();
    }
    // One shared media clock for every viewer. Never runs past the available media.
    void update(Time now) {
        if (!buffering) {
            position = std::min(tail, anchor_position + std::chrono::duration_cast<Us>(now - anchor_time));
            if (position >= tail) buffering = true;
        }
        for (const auto& block : blocks) {
            if (block->start + block->duration <= position && !block->played_at) block->played_at = now;
        }
        prune(now);
        catch_up(now);
        if (position >= tail) buffering = true;
        if (buffering && tail > position && tail - position >= delay) {
            buffering = false;
            anchor_position = position; anchor_time = now;
            changed.notify_all();
        }
    }
    void complete_session(const std::string& session) {
        receipts.at(session).final = true;
        completed.push_back(session);
        while (completed.size() > 128) {
            const auto old = completed.front(); completed.pop_front();
            if (old != active_session) receipts.erase(old);
        }
    }
    void accept_upload(Socket socket, Request request, bool keep_alive = false) {
        auto required = [&](const std::string& key) -> std::string {
            const auto found = request.headers.find(key);
            if (found == request.headers.end()) throw HttpError(400, "Missing " + key);
            return found->second;
        };
        const auto session = required("x-session-id");
        if (!identifier(session)) throw HttpError(400, "Invalid session ID");
        const auto sequence = number(required("x-sequence"), std::numeric_limits<int64_t>::max() - 1);
        const auto duration = number(required("x-duration-us"), 300000000);
        const auto final = number(required("x-final"), 1) == 1;
        if (request.body.size() % 188 || (request.body.empty() && (!final || duration)) ||
            (!request.body.empty() && !duration)) throw HttpError(400, "Invalid TS block or duration");
        for (size_t offset = 0; offset < request.body.size(); offset += 188)
            if (static_cast<unsigned char>(request.body[offset]) != 0x47) throw HttpError(400, "Invalid TS sync byte");
        const auto hash = hash_bytes(request.body);
        const auto received = request.body.size();
        std::unique_lock<std::mutex> lock(mutex);
        auto reply = [&](int status, const std::string& result, const std::string& body, const std::string& headers = std::string{}) {
            lock.unlock();
            response(socket, status, body, headers, keep_alive);
            log_upload(session, std::to_string(sequence), status, result, received, request.receive_seconds);
        };
        const auto now = Clock::now();
        update(now);
        auto found = receipts.find(session);
        if (found != receipts.end() && sequence < found->second.next) {
            const auto& receipt = found->second;
            if (sequence + 1 == receipt.next && (hash != receipt.last_hash || duration != receipt.last_duration || final != receipt.last_final)) {
                reply(409, "duplicate-mismatch", "Duplicate block has different contents\n"); return;
            }
            reply(200, "duplicate", "Already accepted\n", "X-Ack-Sequence: " + std::to_string(sequence) + "\r\n"); return;
        }
        if (found != receipts.end() && found->second.final) {
            const auto expected = found->second.next;
            reply(409, "out-of-order", "Out of order\n", "X-Expected-Sequence: " + std::to_string(expected) + "\r\n"); return;
        }
        // Live streams may lose blocks. Retire oldest cache instead of blocking all later uploads.
        while (bytes + request.body.size() > max_bytes && !blocks.empty()) discard_front(now);
        if (found == receipts.end()) {
            // A sender process restart loses its memory queue and may never send
            // the previous final marker. A new ID supersedes it,
            // retaining old cached bytes while rejecting additional late old blocks.
            if (!active_session.empty() && !receipts.at(active_session).final) complete_session(active_session);
            active_session = session;
            found = receipts.emplace(session, Receipt{}).first;
        }
        if (!request.body.empty()) {
            auto block = std::make_shared<Block>(Block{next_index++, tail, Us(duration), std::move(request.body), {}});
            tail += block->duration;
            bytes += block->data.size();
            blocks.push_back(std::move(block));
        }
        auto& receipt = found->second;
        skipped_sequences += sequence - receipt.next;
        receipt.next = sequence + 1;
        receipt.last_hash = hash; receipt.last_duration = duration; receipt.last_final = final;
        accepted_bytes += received;
        if (final) {
            complete_session(session);
        }
        update(now);
        changed.notify_all();
        reply(200, "accepted", "Accepted\n", "X-Ack-Sequence: " + std::to_string(sequence) + "\r\n");
    }

public:
    Relay(Us latency, Us keep, Us maximum, size_t limit) : delay(latency), retention(keep), max_pending(maximum), max_bytes(limit),
        reporter([this] { report(); }) {}
    ~Relay() { reporting_stopped = true; if (reporter.joinable()) reporter.join(); }

    void ingest(Request request) { accept_upload(invalid_socket, std::move(request)); }

    void upload(Socket socket, Request request, bool keep_alive = false) {
        // Only log validated identifiers/numbers, keeping user input from injecting log lines.
        const auto session_header = request.headers.find("x-session-id");
        const auto sequence_header = request.headers.find("x-sequence");
        const auto session = session_header != request.headers.end() && identifier(session_header->second)
            ? session_header->second : "invalid";
        std::string sequence = "invalid";
        if (sequence_header != request.headers.end()) {
            try { sequence = std::to_string(number(sequence_header->second, std::numeric_limits<int64_t>::max() - 1)); }
            catch (const HttpError&) {}
        }
        const auto received = request.body.size();
        const auto seconds = request.receive_seconds;
        try { accept_upload(socket, std::move(request), keep_alive); }
        catch (const HttpError& error) {
            log_upload(session, sequence, error.code, "invalid-block", received, seconds);
            throw;
        }
    }

    void play(Socket socket) {
        if (!send_all(socket, "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n"
                      "Cache-Control: no-cache, no-store\r\nConnection: close\r\n"
                      "Transfer-Encoding: chunked\r\n\r\n")) return;
        uint64_t cursor = 0;
        auto disconnected = [&] {
            if (!ready(socket, false, Clock::now() + Us(1))) return false;
            char byte;
            const int result = recv(socket, &byte, 1, MSG_PEEK);
            return !result || result > 0 || (result < 0 && !retryable());
        };
        while (true) {
            std::shared_ptr<Block> block;
            uint64_t generation = 0;
            size_t start_offset = 0;
            {
                std::unique_lock<std::mutex> lock(mutex);
                while (!block) {
                    update(Clock::now());
                    if (!blocks.empty() && (cursor < blocks.front()->index || cursor < skip_before_index)) cursor = 0;
                    if (!buffering) {
                        for (const auto& candidate : blocks) {
                            if ((cursor && candidate->index == cursor) ||
                                (!cursor && candidate->start + candidate->duration > position)) {
                                block = candidate; cursor = block->index; break;
                            }
                        }
                    }
                    if (block) {
                        generation = playback_generation;
                        start_offset = block->skipped_prefix;
                        break;
                    }
                    changed.wait_for(lock, std::chrono::milliseconds(50));
                    if (disconnected()) return;
                }
            }
            constexpr size_t batch = 188 * 32;
            bool retired = false;
            for (size_t offset = start_offset; offset < block->data.size(); offset += batch) {
                {
                    std::unique_lock<std::mutex> lock(mutex);
                    while (true) {
                        const auto now = Clock::now();
                        update(now);
                        if (generation != playback_generation || blocks.empty() || block->index < blocks.front()->index || block->index < skip_before_index) {
                            retired = true; break;
                        }
                        const auto media_due = block->start + Us(static_cast<int64_t>(block->duration.count() * offset / block->data.size()));
                        if (media_due <= position && (!buffering || block->start + block->duration <= position)) break;
                        changed.wait_for(lock, std::chrono::milliseconds(20));
                        if (disconnected()) return;
                    }
                }
                if (retired) break;
                const auto size = std::min(batch, block->data.size() - offset);
                std::ostringstream prefix; prefix << std::hex << size << "\r\n";
                if (!send_all(socket, prefix.str()) || !send_all(socket, block->data.data() + offset, size) ||
                    !send_all(socket, "\r\n")) return;
            }
            cursor = retired ? 0 : cursor + 1;
        }
    }

};

#include "distributed.h"

int main(int argc, char** argv) {
    try {
        int port = 8080;
        std::string bind_address = "0.0.0.0", stream = "live";
        std::string mode = "relay";
        std::vector<Upstream> upstreams;
        double gap_timeout = 10;
        double delay = 10, retention = 120, max_pending = 20;
        size_t memory_mb = 256;
        for (int i = 1; i < argc; ++i) {
            const std::string option = argv[i];
            if (option == "--help") {
                std::cout << "http-ts-relay [--bind 0.0.0.0] [--port 8080] [--stream live] "
                             "[--delay 10] [--max-pending-seconds 20] [--retention 120] [--buffer-mb 256] "
                             "[--mode relay|store|merge] [--upstream http://host:port] [--gap-timeout 10]\n"; return 0;
            }
            if (++i >= argc) throw std::runtime_error("Missing option value");
            const std::string value = argv[i];
            if (option == "--port") port = static_cast<int>(number(value, 65535));
            else if (option == "--bind") bind_address = value;
            else if (option == "--stream") stream = value;
            else if (option == "--mode") mode = value;
            else if (option == "--upstream") upstreams.push_back(Upstream::parse(value));
            else if (option == "--gap-timeout") { size_t used; gap_timeout = std::stod(value, &used); if (used != value.size()) throw std::runtime_error("Invalid gap timeout"); }
            else if (option == "--delay") { size_t used; delay = std::stod(value, &used); if (used != value.size()) throw std::runtime_error("Invalid delay"); }
            else if (option == "--retention") { size_t used; retention = std::stod(value, &used); if (used != value.size()) throw std::runtime_error("Invalid retention"); }
            else if (option == "--max-pending-seconds") { size_t used; max_pending = std::stod(value, &used); if (used != value.size()) throw std::runtime_error("Invalid max pending seconds"); }
            else if (option == "--buffer-mb") memory_mb = static_cast<size_t>(number(value, 4096));
            else throw std::runtime_error("Unknown option " + option);
        }
        if (port < 1 || !(delay >= 0 && delay <= 300) || !(max_pending >= 0.001 && max_pending >= delay && max_pending <= 3600) || !(retention >= 1 && retention <= 3600) ||
            memory_mb < 16 || !identifier(stream) || (mode != "relay" && mode != "store" && mode != "merge") ||
            !(gap_timeout >= .1 && gap_timeout <= 300) ||
            (mode == "merge" && (upstreams.empty() || upstreams.size() > 8)) ||
            (mode != "merge" && !upstreams.empty())) throw std::runtime_error("Invalid relay options");
#ifdef _WIN32
        WSADATA wsa;
        if (WSAStartup(MAKEWORD(2, 2), &wsa)) throw std::runtime_error("WSAStartup failed");
#endif
        SocketOwner listener(socket(AF_INET, SOCK_STREAM, IPPROTO_TCP));
        if (listener.socket == invalid_socket) throw std::runtime_error("socket failed");
        int reuse = 1;
        setsockopt(listener.socket, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&reuse), sizeof(reuse));
        sockaddr_in address{};
        address.sin_family = AF_INET; address.sin_port = htons(static_cast<uint16_t>(port));
        if (inet_pton(AF_INET, bind_address.c_str(), &address.sin_addr) != 1) throw std::runtime_error("Invalid bind IPv4 address");
        if (bind(listener.socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) || listen(listener.socket, 64))
            throw std::runtime_error("bind/listen failed");
        Relay relay(Us(static_cast<int64_t>(delay * 1000000)), Us(static_cast<int64_t>(retention * 1000000)),
                    Us(static_cast<int64_t>(max_pending * 1000000)), memory_mb * 1024 * 1024 / (mode == "merge" ? 2 : 1));
        ChunkStore store(memory_mb * 1024 * 1024, Us(static_cast<int64_t>(retention * 1000000)));
        std::unique_ptr<ChunkMerger> merger;
        if (mode == "merge") merger = std::make_unique<ChunkMerger>(relay, upstreams, stream,
            memory_mb * 1024 * 1024 / 2, gap_timeout, delay, max_pending);
        std::atomic<int> clients{0};
        const auto upload = "/upload/" + stream, playback = "/live/" + stream + ".ts";
        const auto index_path = "/index/" + stream, chunk_prefix = "/chunks/" + stream + '/', status_path = "/status/" + stream,
            feedback_path = "/feedback/" + stream;
        std::cout << "Listening on " << bind_address << ':' << port << ", delay=" << delay << "s, max-pending=" << max_pending << "s, mode=" << mode << "\n"
                  << "POST " << upload << "\nGET " << playback << std::endl;
        while (true) {
            const Socket client = accept(listener.socket, nullptr, nullptr);
            if (client == invalid_socket) continue;
            if (clients.fetch_add(1) >= 64) { clients--; close_socket(client); continue; }
            try {
                nonblocking(client);
                std::thread([client, &relay, &store, &clients, upload, playback, mode, index_path, chunk_prefix, status_path, feedback_path] {
                    SocketOwner owner(client);
                    struct CountGuard { std::atomic<int>& count; ~CountGuard() { count--; } } count{clients};
                    bool streaming = false;
                    while (true) try {
                        auto request = read_request(client);
                        const auto connection = request.headers.find("connection");
                        bool keep_alive = mode != "merge" && connection != request.headers.end() && connection->second == "keep-alive";
                        if (request.method == "POST" && request.path == upload && mode != "merge") {
                            if (mode == "store") store.upload(client, std::move(request), keep_alive);
                            else relay.upload(client, std::move(request), keep_alive);
                        }
                        else if (request.method == "GET" && request.path == playback && mode != "store") { streaming = true; relay.play(client); keep_alive = false; }
                        else if (request.method == "GET" && request.path == index_path && mode == "store") store.index(client, request, true, keep_alive);
                        else if (request.method == "GET" && request.path == feedback_path && mode == "store") store.index(client, request, false, keep_alive);
                        else if (request.method == "GET" && request.path == status_path && mode == "store") store.status(client, keep_alive);
                        else if (request.method == "GET" && request.path.rfind(chunk_prefix, 0) == 0 && mode == "store") {
                            const auto key = request.path.substr(chunk_prefix.size());
                            const auto slash = key.find('/');
                            if (slash == std::string::npos) throw HttpError(400, "Missing chunk sequence");
                            if (!store.download(client, key.substr(0, slash), key.substr(slash + 1), keep_alive)) keep_alive = false;
                        }
                        else if (request.method == "GET" && request.path == "/health") response(client, 200, "OK\n", "", keep_alive);
                        else { response(client, 404, "Not found\n"); keep_alive = false; }
                        if (!keep_alive) break;
                    } catch (const HttpError& error) {
                        if (!streaming) response(client, error.code, std::string(error.what()) + "\n");
                        break;
                    } catch (const std::exception& error) {
                        if (!streaming) response(client, 500, "Internal error\n");
                        std::cerr << error.what() << std::endl;
                        break;
                    }
                }).detach();
            } catch (...) { clients--; close_socket(client); }
        }
    } catch (const std::exception& error) {
        std::cerr << error.what() << std::endl; return 1;
    }
}
