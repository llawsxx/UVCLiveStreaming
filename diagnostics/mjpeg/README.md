# MJPEG 丢帧诊断

用于区分 JPEG 解码失败、成功但有容错警告、libuvc 回调覆盖与解码队列淘汰。保持原有解码策略：缺 EOI 时补标记再解码，容错解码成功的帧继续输出。

新版应用运行时，每约 5 秒输出以下日志，可在预览或录像／串流中采集：

```powershell
& 'D:\AndroidSDK\platform-tools\adb.exe' -t 1 logcat -v threadtime 'UVCLiveStreamingUsb:I' 'UsbVideoDiagnostics:I' '*:S'
```

- `USB video negotiated`：协商的分辨率、格式、精确帧间隔和最大 payload 大小。
- `USB video callbacks`：近期 USB 回调 FPS、累计帧数／字节数、累计 `sequenceGaps`、近期回调最大耗时。
- `MJPEG process totals`：进程内所有预览与录像解码 worker 的累计 `failed`、`noSOI`、`missingEOI`、前导字节、容错警告帧与警告数量。切换会话不会清零，比较前后差值。
- `MJPEG decode rejected`：首次 3 个及每 100 个失败样本的尺寸、长度和原因，不保存图像。
- `MJPEG tolerated warning`：首次 3 个及每 1000 个带警告的样本；`decoded=1` 表示该帧仍可输出。
- `Video fps`：录像／串流会话最近约 5 秒的 USB 收帧、GPU 成功提交编码和编码输出 FPS，以及 MJPEG 池统计。

MJPEG 池的 `decodeFailures` 统计解码返回空或异常；`inputDrops` 表示压缩输入缓存满；`outputSkippedSequences` 表示输出缓存受限后推进输出序号；`lateCompletions` 表示结果到达时该序号已被跳过。这些计数描述不同事件，可能重叠，不应相加当成总丢帧数。平均解码耗时是每帧耗时，4 个 worker 并行，不能直接将该值与 16.67 ms 比较来判断是否能达到 60 fps。

`sequenceGaps` 来自 libuvc 拼帧序号，表示拼出的帧没有全部进入应用回调；不是采集卡原生帧计数，也不能检测尚未拼成一帧的传输损失。缺 EOI、容错警告和解码失败并不等价；几何／色彩不匹配或分配异常也可能导致解码失败。

判断方式：

- 回调 FPS 正常、`decodeFailures` 增长且解码池输出降低：检查拒绝样本的 JPEG 错误，支持解码失败导致掉帧。
- `missingEOI`／警告增长但 `decodeFailures=0`：容错处理成功，不能据此判断掉帧。
- `sequenceGaps` 增长：调查 libuvc 回调消费和帧覆盖。
- 队列淘汰增长：调查解码／渲染处理延迟。
- 编码提交正常、编码输出降低：调查编码器或编码输出阻塞。不同阶段有缓存延迟，须比较持续窗口，不能将单次计数差视为丢帧。

## 验证

`MjpegDecodePoolTest` 验证失败帧不阻塞后续帧，并区分解码失败与输入缓存满。

`app/src/test/native/mjpeg_diagnostics_test.c` 使用当前 libjpeg 生成 4:2:0 与 4:2:2 JPEG，验证正常解码、缺 EOI 容错警告、截断熵数据并补 EOI 的损坏检测，以及无效 JPEG 拒绝。在 vivo V2338A 上通过独立 ARM64 测试程序执行，不需要安装或停止应用。损坏样本在两种采样格式中均容错成功，产生 `Corrupt JPEG data: premature end of data segment` 警告。

应用单元测试与 ARM64 debug APK 构建通过。

## 2026-10-04 真机结果

用户安装诊断版后，在 vivo V2338A 上分别开启 MJPG 1080p60 和 720p60 RTMP 推流，5 fps 预览、时间戳平滑开启。手机仍有温控，实时 skin 约 49°C，thermal status=4。

- 1080p60：截至 02:07:45，本次串流收到 3596 帧，解码失败／输入淘汰／输出跳号／晚到结果均为 0，USB sequenceGaps=0。02:07:30.562 至 02:07:45.581 的收帧与编码输出均增加 901 帧，近期速率约 59.99 fps。
- 720p60：02:08:58.965 协商 `interval100ns=166666`（60.000240 fps），maxPayload=16384。02:08:59.536 出现 1 帧 6903 字节无 SOI 的数据；本次串流 decodeFailures 增至 1，此后没有继续增长。
- 720p60 首个 5 秒窗口 received=58.376 fps。02:09:04.397 至 02:10:14.490，收到与编码输出均增加 4206 帧，稳定窗口约 60.01 fps。截至最后一条日志，收到 4500 帧，解码失败仍为 1，所有队列丢弃计数和 sequenceGaps 都是 0。平均解码耗时约 5.54 ms，4 worker 并行。
- 720p 过程中进程累计 `missingEOI=0`；累计容错警告数保持为 1，没有新增警告。进程累计失败数从 1 增到 2，其中新增的 1 次是上述 720p 启动帧，不能把进程累计数当成本次串流数。
- 界面全程平均 FPS 从启动后的 59.0 回升到 59.6，但稳定窗口收帧与编码输出约 60 fps。

结论：确实观察到 1 次启动阶段的无效 JPEG，但本次采样没有持续坏帧、USB 回调覆盖或解码队列淘汰的证据。持续运行帧率约 60，低平均值主要受启动等待和首个窗口收帧不足影响；不能将这一帧无效数据推断为持续损坏或 USB 带宽不足。仍需在问题再次出现时观察这些计数，才能判断间歇性问题。
