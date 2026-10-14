#pragma once
#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <optional>

// Completed payloads / wall time, only across an uninterrupted supply of work.
class SustainedThroughput {
public:
    using Clock = std::chrono::steady_clock;
    using Time = Clock::time_point;
    struct Reading { double short_rate = 0, long_rate = 0; Time measured; };
private:
    struct Point { Time time; uint64_t bytes; };
    std::deque<Point> points;
    uint64_t bytes = 0;
    std::optional<Reading> result;
public:
    void demand(Time now, bool available) {
        if (!available) { points.clear(); bytes = 0; return; }
        if (points.empty()) points.push_back({now, 0});
    }
    void record(size_t size, Time now) {
        if (points.empty() || size < 188 * 32) return;
        bytes += size; points.push_back({now, bytes});
        auto rate = [&](int seconds) {
            for (auto it = points.rbegin(); it != points.rend(); ++it)
                if (it->time <= now - std::chrono::seconds(seconds))
                    return std::clamp((bytes - it->bytes) / std::chrono::duration<double>(now - it->time).count(), 1000.0, 125000000.0);
            return 0.0;
        };
        const auto short_rate = rate(5), long_rate = rate(30);
        if (short_rate > 0) result = Reading{short_rate, long_rate, now};
        while (points.size() > 1 && points[1].time <= now - std::chrono::seconds(30)) points.pop_front();
    }
    std::optional<Reading> reading(Time now) const {
        if (!result || now < result->measured || now - result->measured > std::chrono::seconds(10)) return {};
        return result;
    }
};
