#include "native_video_buffer.h"
#include <cassert>
#include <cstring>
#include <thread>
#include <unordered_set>
#include <cstdio>

int main() {
    NativeVideoBufferPool pool;
    void *held = pool.acquire(8192);
    assert(held); memset(held, 11, 8192);
    void *other = pool.acquire(8192);
    assert(other && other != held);
    pool.recycle(other, 8192);
    void *reused = pool.acquire(8192);
    assert(reused == other && reused != held);
    memset(reused, 22, 8192);
    assert(static_cast<unsigned char *>(held)[0] == 11);
    pool.recycle(reused, 8192); pool.recycle(held, 8192);
    assert(pool.reserve(128 * 1024 * 1024));
    assert(!pool.reserve(1));
    pool.releaseReservation(128 * 1024 * 1024);
    assert(pool.reserve(8192)); pool.releaseReservation(8192);
    std::mutex active_mutex;
    std::unordered_set<void *> active;
    std::vector<std::thread> workers;
    for (int i = 0; i < 8; ++i) workers.emplace_back([&, i] {
        for (int j = 0; j < 1000; ++j) {
            void *data = pool.acquire(8192);
            assert(data);
            { std::lock_guard<std::mutex> lock(active_mutex); assert(active.insert(data).second); }
            memset(data, i, 8192);
            std::this_thread::yield();
            assert(static_cast<unsigned char *>(data)[0] == i);
            { std::lock_guard<std::mutex> lock(active_mutex); assert(active.erase(data) == 1); }
            pool.recycle(data, 8192);
        }
    });
    for (auto &worker : workers) worker.join();
    assert(active.empty());
    puts("Exclusive native video buffer tests passed");
}
