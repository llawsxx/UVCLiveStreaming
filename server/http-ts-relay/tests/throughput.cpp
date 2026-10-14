#include "../throughput.h"
#include <cassert>
#include <iostream>

int main() {
    using Time = SustainedThroughput::Time;
    auto at = [](int ms) { return Time{} + std::chrono::milliseconds(ms); };
    SustainedThroughput busy;
    busy.demand(at(0), true);
    for (int i = 0; i <= 20; ++i) busy.record(750000, at(i * 2000 + 500));
    auto reading = busy.reading(at(40500));
    assert(reading && reading->short_rate == 375000 && reading->long_rate == 375000);
    assert(!busy.reading(at(50501)));
    SustainedThroughput idle;
    for (int i = 0; i < 60; ++i) {
        idle.demand(at(i * 1000), true);
        idle.record(250000, at(i * 1000 + 100));
        idle.demand(at(i * 1000 + 100), false);
    }
    assert(!idle.reading(at(60000)));
    std::cout << "PASS: sustained burst goodput, idle supply, and original expiry\n";
}
