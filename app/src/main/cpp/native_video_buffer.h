#pragma once

#include <cstdlib>
#include <mutex>
#include <vector>

// Exclusive frame buffers, shared by capture and decode threads via ownership transfer.
// This pool outlives capture sessions; an in-flight frame can be returned after USB close.
class NativeVideoBufferPool {
public:
    struct Buffer { void *data; size_t capacity; };
    ~NativeVideoBufferPool() {
        for (const auto &buffer : idle_) std::free(buffer.data);
    }
    bool reserve(size_t capacity) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (capacity > MAX_LEASED_BYTES - leased_bytes_) return false;
        leased_bytes_ += capacity;
        return true;
    }
    void releaseReservation(size_t capacity) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        leased_bytes_ -= capacity;
    }
    void *acquire(size_t capacity) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto it = idle_.begin(); it != idle_.end(); ++it) {
            if (it->capacity == capacity) {
                void *data = it->data;
                cached_bytes_ -= it->capacity;
                idle_.erase(it);
                return data;
            }
        }
        return std::malloc(capacity);
    }
    void recycle(void *data, size_t capacity) noexcept {
        if (!data) return;
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (idle_.size() < 8 && capacity <= MAX_CACHED_BYTES - cached_bytes_) {
                idle_.push_back({data, capacity});
                cached_bytes_ += capacity;
                return;
            }
        } catch (...) { /* Allocation failure must still release the owned buffer. */ }
        std::free(data);
    }
private:
    static constexpr size_t MAX_CACHED_BYTES = 32 * 1024 * 1024;
    static constexpr size_t MAX_LEASED_BYTES = 128 * 1024 * 1024;
    std::mutex mutex_;
    std::vector<Buffer> idle_;
    size_t cached_bytes_ = 0;
    size_t leased_bytes_ = 0;
};
