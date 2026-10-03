# 720p60 推流时间戳与播放器卡顿调查

2026-10-04，vivo V2338A + U4 4K60，MJPG 1280×720 60 fps，H.264 + 48 kHz AAC-LC。用户报告关闭时间戳平滑后，观看 RTMP 推流的播放器每秒卡几次。

用户分别开启／关闭平滑后重启推流。通过 ffprobe 从既有 RTMP 地址读取各约 8 秒的 packet PTS；与采集／解码诊断同时核对，稳定阶段仍约 60 fps，没有持续解码失败或队列淘汰。

| 项目 | 开启平滑 | 关闭平滑 |
| --- | --- | --- |
| 视频采样包数 | 480 | 480 |
| 视频 PTS 间隔 | 仅 16/17 ms | 7～26 ms |
| 视频 PTS 间隔 P95/P99 | 17/17 ms | 18/21 ms |
| 视频相同或倒退 PTS | 0 | 0 |
| 音频 PTS 间隔 | 11～31 ms | 11～32 ms |

这里的 packet PTS 速率不是墙钟收帧统计。短采样没有视频大于等于 30 ms 的 PTS 间隔，不能单凭这些间隔证明所有卡顿都来自时间戳；播放器呈现、网络到达抖动也可能影响观看效果。但视频平滑确实消除了本次采样中的 PTS 抖动，未见其导致持续掉帧。

## 修复

1. 原始视频时间戳原先取自 native 完成 JVM attach、Java 数组分配和复制后的 monotonic 时刻。改为 libuvc 的 `capture_time_finished`（CLOCK_MONOTONIC，帧拼接完成时刻），无可用值时退回回调入口时刻，减少应用回调处理带来的额外抖动。该时刻仍受 USB 接收调度影响，不是 HDMI 源硬件时钟；关闭平滑仍可能保留真实接收抖动。
2. 之前只对 PCM 输入的时间戳做音频平滑。AAC 编码器可能沿用不等长 PCM batch 的时间戳，导致已编码音频仍出现 11/30 ms 的步长。增加独立的 AAC 输出平滑器：当前配置固定 AAC-LC，每个 access unit 为 1024 sample frames，在 48 kHz 下按 21.333... ms 累进。保持首包时间偏移，并遵循用户的平滑开关和最大偏差阈值，超阈值仍重设基准。修改位于共享输出 router 之前，对录像／HTTP／RTMP 使用同一时间轴。

新增测试以 10-ms PCM batch 时钟生成 AAC 输出 PTS，验证 10001 个 access unit 的精确样本时钟与首包偏移，并验证大时间间断时重设基准。单元测试和 ARM64 debug APK 构建通过。

原始采样位于 `build/perf-diagnostics/packets-smoothed.json`、`packets-unsmoothed.json`；分析脚本 `analyze_packets.py` 同目录。

修复版尚未覆盖安装，因此修复后的真实 AAC 输出间隔和播放器卡顿改善尚未验证。时间戳平滑不会补帧，也不改变按墙钟计算的累计平均 FPS。
