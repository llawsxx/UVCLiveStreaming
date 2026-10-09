// Included after Relay: uses the existing bounded HTTP parser, socket helpers and TS playback clock.
struct StoredChunk {
    std::string session;
    uint64_t epoch = 0, sequence = 0, start = 0, duration = 0, hash = 0;
    bool final = false;
    size_t size = 0;
    Time created = Clock::now();
    std::vector<char> data;
};
static StoredChunk chunk_metadata(const Request& request) {
    auto required = [&](const std::string& key) -> const std::string& {
        const auto found = request.headers.find(key);
        if (found == request.headers.end()) throw HttpError(400, "Missing " + key);
        return found->second;
    };
    StoredChunk chunk;
    chunk.session = required("x-session-id");
    if (!identifier(chunk.session)) throw HttpError(400, "Invalid session");
    const auto maximum = static_cast<uint64_t>(std::numeric_limits<int64_t>::max() / 2);
    chunk.epoch = number(required("x-session-started-ms"), maximum);
    chunk.sequence = number(required("x-sequence"), maximum);
    chunk.start = number(required("x-start-us"), maximum);
    chunk.duration = number(required("x-duration-us"), 300000000);
    chunk.final = number(required("x-final"), 1) != 0;
    chunk.size = request.body.size();
    if (chunk.size % 188 || (chunk.size && !chunk.duration) ||
        (!chunk.size && (!chunk.final || chunk.duration))) throw HttpError(400, "Invalid TS block");
    for (size_t i = 0; i < chunk.size; i += 188)
        if (static_cast<unsigned char>(request.body[i]) != 0x47) throw HttpError(400, "Invalid TS sync byte");
    chunk.hash = hash_bytes(request.body);
    return chunk;
}
static Request chunk_request(StoredChunk chunk) {
    Request request;
    request.headers = {{"x-session-id", chunk.session}, {"x-sequence", std::to_string(chunk.sequence)},
        {"x-duration-us", std::to_string(chunk.duration)}, {"x-final", chunk.final ? "1" : "0"}};
    request.body = std::move(chunk.data);
    return request;
}

class ChunkStore {
    using Key = std::pair<std::string, uint64_t>;
    std::mutex mutex;
    std::map<Key, std::shared_ptr<StoredChunk>> chunks;
    size_t bytes = 0, maximum;
    Us retention;
    double download_rate = 0;
    std::string rescue_session;
    std::optional<uint64_t> rescue_sequence;
    Time feedback_time = Clock::now(), rescue_time = Clock::now();
    void prune() {
        const auto oldest = Clock::now() - retention;
        for (auto it = chunks.begin(); it != chunks.end();) {
            if (it->second->created < oldest) { bytes -= it->second->size; it = chunks.erase(it); }
            else ++it;
        }
    }
    std::string status_headers() const {
        std::string headers = "X-Relay-Mode: store\r\n";
        // Publish only measured feedback; missing/stale samples must not impose a guessed cap.
        if (download_rate > 0 && Clock::now() - feedback_time < std::chrono::seconds(10))
            headers += "X-Download-Rate-Bps: " + std::to_string(static_cast<uint64_t>(download_rate)) + "\r\n";
        if (rescue_sequence && Clock::now() - rescue_time < std::chrono::seconds(3))
            headers += "X-Rescue-Session: " + rescue_session + "\r\nX-Rescue-Sequence: " + std::to_string(*rescue_sequence) + "\r\n";
        return headers;
    }
public:
    ChunkStore(size_t limit, Us keep) : maximum(limit), retention(keep) {}
    void upload(Socket socket, Request request, bool keep_alive = false) {
        auto chunk = chunk_metadata(request);
        const auto key = Key{chunk.session, chunk.sequence};
        std::string headers;
        {
            std::lock_guard<std::mutex> lock(mutex);
            prune();
            const auto previous = chunks.find(key);
            if (previous != chunks.end()) {
                const auto& old = *previous->second;
                if (old.hash != chunk.hash || old.start != chunk.start || old.duration != chunk.duration ||
                    old.epoch != chunk.epoch || old.final != chunk.final || old.size != chunk.size)
                    throw HttpError(409, "Conflicting duplicate block");
            } else {
                if (chunk.size > maximum) throw HttpError(413, "Block exceeds cache budget");
                while (!chunks.empty() && (bytes + chunk.size > maximum || chunks.size() >= 4096)) {
                    auto oldest = std::min_element(chunks.begin(), chunks.end(), [](const auto& a, const auto& b) {
                        return a.second->created < b.second->created;
                    });
                    bytes -= oldest->second->size; chunks.erase(oldest);
                }
                bytes += chunk.size;
                chunk.data = std::move(request.body);
                chunks.emplace(key, std::make_shared<StoredChunk>(std::move(chunk)));
            }
            headers = status_headers() + "X-Ack-Sequence: " + std::to_string(key.second) + "\r\n";
        }
        response(socket, 200, "Stored\n", headers, keep_alive);
        log_line("[store] session=" + key.first + " seq=" + std::to_string(key.second));
    }
    void index(Socket socket, const Request& request, bool list_chunks = true, bool keep_alive = false) {
        std::string body, headers;
        {
            std::lock_guard<std::mutex> lock(mutex);
            prune();
            const auto rate = request.headers.find("x-download-rate-bps");
            if (rate != request.headers.end()) {
                const double sample = static_cast<double>(number(rate->second, 125000000));
                if (sample >= 1000) {
                    download_rate = download_rate == 0 || Clock::now() - feedback_time >= std::chrono::seconds(10) || sample < download_rate
                        ? sample : download_rate * .75 + sample * .25;
                    feedback_time = Clock::now();
                }
            }
            rescue_sequence.reset();
            const auto session = request.headers.find("x-rescue-session"), sequence = request.headers.find("x-rescue-sequence");
            if (session != request.headers.end() && sequence != request.headers.end() && identifier(session->second)) {
                const auto seq = number(sequence->second, std::numeric_limits<int64_t>::max() / 2);
                if (chunks.count(Key{session->second, seq})) {
                    rescue_session = session->second; rescue_sequence = seq; rescue_time = Clock::now();
                }
            }
            std::ostringstream list;
            list << "TS-CHUNKS 1\n";
            const auto from_epoch = request.headers.find("x-index-epoch"), from_sequence = request.headers.find("x-index-from");
            const auto from_session = request.headers.find("x-index-session");
            const auto epoch = from_epoch == request.headers.end() ? 0 : number(from_epoch->second, std::numeric_limits<int64_t>::max() / 2);
            const auto first_sequence = from_sequence == request.headers.end() ? 0 : number(from_sequence->second, std::numeric_limits<int64_t>::max() / 2);
            if (from_session != request.headers.end() && !identifier(from_session->second)) throw HttpError(400, "Invalid index session");
            for (const auto& entry : chunks) {
                if (!list_chunks) break;
                const auto& c = *entry.second;
                if (c.epoch < epoch || (from_session != request.headers.end() && c.session == from_session->second && c.sequence < first_sequence)) continue;
                list << c.session << ' ' << c.epoch << ' ' << c.sequence << ' ' << c.start << ' ' << c.duration << ' '
                     << c.final << ' ' << c.size << ' ' << c.hash << '\n';
            }
            body = list_chunks ? list.str() : "OK\n"; headers = status_headers();
        }
        response(socket, 200, body, headers, keep_alive);
    }
    void status(Socket socket, bool keep_alive = false) {
        std::string headers;
        { std::lock_guard<std::mutex> lock(mutex); headers = status_headers(); }
        response(socket, 200, "OK\n", headers, keep_alive);
    }
    bool download(Socket socket, const std::string& session, const std::string& sequence, bool keep_alive = false) {
        if (!identifier(session)) throw HttpError(400, "Invalid session");
        const auto seq = number(sequence, std::numeric_limits<int64_t>::max() / 2);
        std::shared_ptr<StoredChunk> chunk;
        {
            std::lock_guard<std::mutex> lock(mutex);
            prune();
            const auto found = chunks.find(Key{session, seq});
            if (found == chunks.end()) throw HttpError(404, "Block expired or not stored here");
            chunk = found->second;
        }
        return send_all(socket, "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: " +
            std::string(keep_alive ? "keep-alive" : "close") + "\r\nContent-Length: " +
            std::to_string(chunk->size) + "\r\nX-Chunk-Hash: " + std::to_string(chunk->hash) + "\r\n\r\n")
            && send_all(socket, chunk->data.data(), chunk->size);
    }
};

struct Upstream {
    std::string host, port, prefix;
    static Upstream parse(const std::string& url) {
        if (url.substr(0, 7) != "http://") throw std::runtime_error("Upstreams require http://host:port (TLS may terminate at a proxy)");
        const auto slash = url.find('/', 7);
        const auto authority = url.substr(7, slash == std::string::npos ? slash : slash - 7);
        const auto colon = authority.rfind(':');
        Upstream result{authority.substr(0, colon), colon == std::string::npos ? "80" : authority.substr(colon + 1),
            slash == std::string::npos ? "" : url.substr(slash)};
        while (!result.prefix.empty() && result.prefix.back() == '/') result.prefix.pop_back();
        if (result.host.empty() || authority.find_first_of("@?#[] \r\n") != std::string::npos ||
            result.prefix.find_first_of("?# \r\n") != std::string::npos || number(result.port, 65535) == 0)
            throw std::runtime_error("Invalid upstream URL");
        return result;
    }
};
static Request fetch(const Upstream& upstream, const std::string& path, const std::string& headers,
                     size_t maximum, double seconds, Socket& reused) {
    SocketOwner socket(reused);
    reused = invalid_socket;
    const auto begin = Clock::now();
    const auto target = "http://" + upstream.host + ':' + upstream.port + upstream.prefix + path;
    std::string phase = "resolve";
    size_t received_bytes = 0, expected_bytes = 0;
    auto socket_error = []() {
#ifdef _WIN32
        return WSAGetLastError();
#else
        return errno;
#endif
    };
    try {
        if (socket.socket == invalid_socket) {
            addrinfo hints{}, *addresses = nullptr;
            hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
            if (getaddrinfo(upstream.host.c_str(), upstream.port.c_str(), &hints, &addresses)) throw std::runtime_error("Upstream DNS failed");
            std::unique_ptr<addrinfo, decltype(&freeaddrinfo)> owner(addresses, freeaddrinfo);
            phase = "connect";
            const auto connect_deadline = Clock::now() + std::chrono::seconds(5);
            Socket connected = invalid_socket;
            std::string connection_error = "No usable address";
            for (auto address = addresses; address; address = address->ai_next) {
                SocketOwner candidate(::socket(address->ai_family, address->ai_socktype, address->ai_protocol));
                if (candidate.socket == invalid_socket) { connection_error = "socket error=" + std::to_string(socket_error()); continue; }
                nonblocking(candidate.socket);
                const auto result = connect(candidate.socket, address->ai_addr, static_cast<int>(address->ai_addrlen));
                if (result != 0 && !ready(candidate.socket, true, connect_deadline, true)) {
                    connection_error = "Connect wait failed or timed out (5s)"; continue;
                }
                int error = 0;
        #ifdef _WIN32
                int length = sizeof(error);
        #else
                socklen_t length = sizeof(error);
        #endif
                if (getsockopt(candidate.socket, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&error), &length)) {
                    connection_error = "socket error=" + std::to_string(socket_error()); continue;
                }
                if (error) { connection_error = "socket error=" + std::to_string(error); continue; }
                connected = candidate.socket; candidate.socket = invalid_socket; break;
            }
            if (connected == invalid_socket) throw std::runtime_error("Upstream connection failed: " + connection_error);
            socket.socket = connected;
        }
        phase = "request";
        if (!send_all(socket.socket, "GET " + upstream.prefix + path + " HTTP/1.1\r\nHost: " + upstream.host + ':' + upstream.port +
            "\r\nConnection: keep-alive\r\n" + headers + "\r\n")) throw std::runtime_error("Upstream write failed");
        // Connection setup has its own budget; it must not consume the response deadline.
        const auto deadline = Clock::now() + Us(static_cast<int64_t>(seconds * 1000000));
        phase = "headers";
        Request response;
        std::string input;
        char buffer[16384];
        size_t end;
        auto receive = [&]() {
            while (true) {
                if (!ready(socket.socket, false, deadline)) throw std::runtime_error("Upstream response timeout (budget=" + std::to_string(seconds) + "s)");
                const int count = static_cast<int>(recv(socket.socket, buffer, sizeof(buffer), 0));
                if (count > 0) return count;
                if (count < 0 && retryable()) continue;
                if (count < 0) throw std::runtime_error("Upstream receive failed: socket error=" + std::to_string(socket_error()));
                throw std::runtime_error("Incomplete upstream response");
            }
        };
        while ((end = input.find("\r\n\r\n")) == std::string::npos) {
            if (input.size() > 16384) throw std::runtime_error("Oversized upstream headers");
            const auto count = receive(); input.append(buffer, static_cast<size_t>(count));
        }
        if (end > 16384) throw std::runtime_error("Oversized upstream headers");
        std::istringstream lines(input.substr(0, end));
        std::string line, version; int status;
        std::getline(lines, line); std::istringstream first(line);
        if (!(first >> version >> status)) throw std::runtime_error("Malformed upstream status");
        if (status != 200) throw std::runtime_error("Upstream HTTP " + std::to_string(status));
        while (std::getline(lines, line)) {
            const auto colon = line.find(':');
            if (colon == std::string::npos) throw std::runtime_error("Malformed upstream header");
            auto key = line.substr(0, colon);
            std::transform(key.begin(), key.end(), key.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
            if (!response.headers.emplace(key, trim(line.substr(colon + 1))).second) throw std::runtime_error("Duplicate upstream header");
        }
        if (response.headers.count("transfer-encoding") || !response.headers.count("content-length"))
            throw std::runtime_error("Upstream Content-Length required");
        const auto size = static_cast<size_t>(number(response.headers.at("content-length"), maximum));
        phase = "body"; expected_bytes = size;
        response.body.assign(input.begin() + static_cast<std::ptrdiff_t>(end + 4), input.end());
        received_bytes = response.body.size();
        if (response.body.size() > size) throw std::runtime_error("Upstream size mismatch");
        while (response.body.size() < size) {
            const auto count = receive();
            if (response.body.size() + static_cast<size_t>(count) > size) throw std::runtime_error("Upstream size mismatch");
            response.body.insert(response.body.end(), buffer, buffer + count);
            received_bytes = response.body.size();
        }
        const auto connection = response.headers.find("connection");
        if (connection != response.headers.end() && connection->second == "keep-alive") {
            reused = socket.socket;
            socket.socket = invalid_socket;
        }
        return response;
    } catch (const std::exception& error) {
        const auto elapsed = std::chrono::duration<double>(Clock::now() - begin).count();
        throw std::runtime_error("upstream=" + target + " phase=" + phase + " elapsed=" + std::to_string(elapsed) +
            "s" + (phase == "body" ? " bytes=" + std::to_string(received_bytes) + '/' + std::to_string(expected_bytes) : "") +
            ": " + error.what());
    }
}

class ChunkMerger {
    struct Entry {
        StoredChunk metadata;
        std::map<size_t, Time> claims;
        bool downloaded = false;
    };
    Relay& relay;
    std::vector<Upstream> upstreams;
    std::string stream, session;
    uint64_t epoch = 0, next = 0;
    size_t bytes = 0, maximum;
    std::map<uint64_t, Entry> entries;
    std::map<uint64_t, StoredChunk> complete;
    std::mutex mutex;
    std::atomic<bool> stopping{false};
    std::vector<std::thread> workers;
    double gap_seconds, delay_seconds, max_pending_seconds;
    static constexpr double initial_rate = 375000; // Bootstrap estimate, replaced by measured speed; not a cap.
    std::vector<double> rates;
    std::vector<bool> indexed;
    Time waiting_since = Clock::now();
    bool selected = false, finished = false, priming = false;

    std::string feedback_headers(size_t index) {
        std::lock_guard<std::mutex> lock(mutex);
        drain();
        std::string headers;
        if (rates[index] > 0)
            headers = "X-Download-Rate-Bps: " + std::to_string(static_cast<uint64_t>(rates[index])) + "\r\n";
        // Do not repeatedly download the retained history directory on narrow egress links.
        if (selected) headers += "X-Index-Epoch: " + std::to_string(epoch) + "\r\nX-Index-Session: " + session +
            "\r\nX-Index-From: " + std::to_string(next) + "\r\n";
        double rescue_after = 2;
        const auto head = entries.find(next);
        if (head != entries.end() && !head->second.claims.empty()) {
            const auto source = head->second.claims.begin()->first;
            const auto rate = rates[source] > 0 ? rates[source] : initial_rate;
            rescue_after = std::max(2.0, head->second.metadata.size / rate * 1.5 + .5);
        }
        if (selected && !priming && !finished && std::chrono::duration<double>(Clock::now() - waiting_since).count() > rescue_after)
            headers += "X-Rescue-Session: " + session + "\r\nX-Rescue-Sequence: " + std::to_string(next) + "\r\n";
        return headers;
    }
    void feedback(size_t index) {
        SocketOwner connection(invalid_socket);
        while (!stopping) {
            try { fetch(upstreams[index], "/feedback/" + stream, feedback_headers(index), 1024, 5, connection.socket); }
            catch (const std::exception&) { /* Data workers report errors; feedback must not block other sources. */ }
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
    }

    void drain() {
        if (priming) {
            if (entries.empty()) return;
            // Collect the other sources' initial indexes without waiting indefinitely for an offline source.
            if (!std::all_of(indexed.begin(), indexed.end(), [](bool seen) { return seen; }) &&
                std::chrono::duration<double>(Clock::now() - waiting_since).count() < std::min(2.0, gap_seconds)) return;
            uint64_t oldest = std::numeric_limits<uint64_t>::max(), latest = 0;
            for (const auto& item : entries) {
                oldest = std::min(oldest, item.second.metadata.start);
                latest = std::max(latest, item.second.metadata.start + item.second.metadata.duration);
            }
            auto first = entries.begin();
            if (latest - oldest > max_pending_seconds * 1000000) {
                const auto keep = static_cast<uint64_t>(delay_seconds * 1000000);
                const auto cutoff = latest > keep ? latest - keep : 0;
                while (first != entries.end() && first->second.metadata.start + first->second.metadata.duration <= cutoff) ++first;
                if (first == entries.end()) first = std::prev(entries.end());
            }
            next = first->first;
            entries.erase(entries.begin(), first);
            priming = false; waiting_since = Clock::now();
            log_line("[merge] start seq=" + std::to_string(next) + " from retained live window");
        }
        while (true) {
            auto found = complete.find(next);
            if (found == complete.end()) break;
            bytes -= found->second.size;
            const auto entry = entries.find(next);
            if (entry != entries.end()) bytes -= entry->second.claims.size() * found->second.size;
            finished = found->second.final;
            relay.ingest(chunk_request(std::move(found->second)));
            complete.erase(found); entries.erase(next); ++next;
            waiting_since = Clock::now();
        }
        const auto later = entries.upper_bound(next);
        if (!finished && later != entries.end() &&
            std::chrono::duration<double>(Clock::now() - waiting_since).count() >= gap_seconds) {
            const auto resume = later->first;
            log_line("[merge] missing seq=" + std::to_string(next) + ".." + std::to_string(resume - 1) + " skipped after timeout");
            for (auto it = entries.begin(); it != later;) {
                bytes -= it->second.claims.size() * it->second.metadata.size;
                it = entries.erase(it);
            }
            next = resume; waiting_since = Clock::now();
        }
    }
    void run(size_t index) {
        SocketOwner connection(invalid_socket);
        double rate = initial_rate;
        bool measured = false;
        unsigned failures = 0;
        while (!stopping) {
            std::optional<StoredChunk> chosen;
            std::string active;
            try {
                const auto listing = fetch(upstreams[index], "/index/" + stream, feedback_headers(index), 4 * 1024 * 1024, 5, connection.socket);
                std::istringstream lines(std::string(listing.body.begin(), listing.body.end()));
                std::string line;
                std::getline(lines, line);
                if (line != "TS-CHUNKS 1") throw std::runtime_error("Upstream is not a chunk store");
                std::vector<StoredChunk> available;
                while (std::getline(lines, line)) {
                    StoredChunk chunk; uint64_t flag = 0;
                    std::istringstream row(line); std::string extra;
                    if (!(row >> chunk.session >> chunk.epoch >> chunk.sequence >> chunk.start >> chunk.duration >> flag >> chunk.size >> chunk.hash) ||
                        row >> extra || !identifier(chunk.session) || flag > 1 || chunk.size > 16 * 1024 * 1024 || chunk.size % 188 ||
                        chunk.duration > 300000000 || chunk.epoch > static_cast<uint64_t>(std::numeric_limits<int64_t>::max() / 2) ||
                        chunk.sequence > static_cast<uint64_t>(std::numeric_limits<int64_t>::max() / 2) ||
                        (chunk.size && !chunk.duration) || (!chunk.size && (!flag || chunk.duration)))
                        throw std::runtime_error("Invalid upstream index");
                    chunk.final = flag != 0;
                    available.push_back(std::move(chunk));
                }
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    for (const auto& chunk : available) {
                        if (!selected || std::tie(chunk.epoch, chunk.session) > std::tie(epoch, session)) {
                            session = chunk.session; epoch = chunk.epoch; next = 0; bytes = 0;
                            entries.clear(); complete.clear(); selected = true; finished = false; priming = true;
                            std::fill(indexed.begin(), indexed.end(), false); waiting_since = Clock::now();
                            log_line("[merge] session=" + session);
                        }
                        if (chunk.session != session || chunk.epoch != epoch || chunk.sequence < next || finished) continue;
                        auto found = entries.find(chunk.sequence);
                        if (found == entries.end()) {
                            if (entries.size() >= 4096) continue;
                            found = entries.emplace(chunk.sequence, Entry{chunk, {}, false}).first;
                        }
                        const auto& known = found->second.metadata;
                        if (known.hash != chunk.hash || known.size != chunk.size || known.start != chunk.start ||
                            known.duration != chunk.duration || known.final != chunk.final) throw std::runtime_error("Conflicting block metadata");
                    }
                    indexed[index] = true;
                    drain();
                    if (!priming) for (const auto& chunk : available) {
                        if (chunk.session != session || chunk.epoch != epoch || chunk.sequence < next || finished) continue;
                        auto found = entries.find(chunk.sequence);
                        if (found == entries.end() || found->second.downloaded || complete.count(chunk.sequence)) continue;
                        auto& entry = found->second;
                        if (!entry.claims.empty() && Clock::now() - entry.claims.begin()->second < std::chrono::seconds(2)) continue;
                        const auto reserve = chunk.sequence > next ? std::min(maximum / 2, size_t{16 * 1024 * 1024}) : 0;
                        if (entry.claims.count(index) || bytes + chunk.size > maximum - reserve) continue;
                        bytes += chunk.size; entry.claims[index] = Clock::now(); chosen = chunk; active = session; break;
                    }
                }
                if (chosen) {
                    const auto begin = Clock::now();
                    auto payload = fetch(upstreams[index], "/chunks/" + stream + '/' + chosen->session + '/' + std::to_string(chosen->sequence),
                        "", 16 * 1024 * 1024, std::min(15.0, std::max(3.0, chosen->size / rate * 2 + 2)), connection.socket);
                    if (payload.body.size() != chosen->size || hash_bytes(payload.body) != chosen->hash)
                        throw std::runtime_error("Block size/hash mismatch");
                    for (size_t offset = 0; offset < payload.body.size(); offset += 188)
                        if (static_cast<unsigned char>(payload.body[offset]) != 0x47) throw std::runtime_error("Invalid TS sync byte");
                    const double elapsed = std::chrono::duration<double>(Clock::now() - begin).count();
                    if (chosen->size >= 188 * 32 && elapsed > 0) {
                        const double sample = std::max(1000.0, std::min(125000000.0, chosen->size / elapsed));
                        rate = !measured || sample < rate ? sample : rate * .75 + sample * .25;
                        measured = true;
                    }
                    std::lock_guard<std::mutex> lock(mutex);
                    if (measured) rates[index] = rate;
                    if (active == session) {
                        const auto found = entries.find(chosen->sequence);
                        if (found != entries.end() && found->second.claims.erase(index)) {
                            bytes -= chosen->size;
                            if (!found->second.downloaded) {
                                chosen->data = std::move(payload.body);
                                bytes += chosen->size; found->second.downloaded = true;
                                complete.emplace(chosen->sequence, std::move(*chosen));
                            }
                        }
                        drain();
                    }
                    log_line("[merge] source=" + std::to_string(index) + " rate=" + std::to_string(static_cast<uint64_t>(rate)) + " B/s");
                } else std::this_thread::sleep_for(std::chrono::milliseconds(250));
                failures = 0;
            } catch (const std::exception& error) {
                if (chosen) {
                    rate = std::max(1000.0, rate * .5);
                    std::lock_guard<std::mutex> lock(mutex);
                    if (measured) rates[index] = rate;
                    if (active == session) {
                        const auto found = entries.find(chosen->sequence);
                        if (found != entries.end() && found->second.claims.erase(index)) bytes -= chosen->size;
                    }
                }
                log_line("[merge] source=" + std::to_string(index) + " retry: " + error.what());
                failures = std::min(failures + 1, 4U);
                std::this_thread::sleep_for(std::chrono::milliseconds(250U << (failures - 1)));
            }
        }
    }
public:
    ChunkMerger(Relay& output, std::vector<Upstream> sources, std::string name, size_t limit, double gap, double delay, double max_pending)
        : relay(output), upstreams(std::move(sources)), stream(std::move(name)), maximum(limit), gap_seconds(gap),
          delay_seconds(delay), max_pending_seconds(max_pending), rates(upstreams.size(), 0), indexed(upstreams.size(), false) {
        for (size_t i = 0; i < upstreams.size(); ++i) {
            workers.emplace_back([this, i] { run(i); });
            workers.emplace_back([this, i] { feedback(i); });
        }
    }
    ~ChunkMerger() { stopping = true; for (auto& worker : workers) worker.join(); }
};
