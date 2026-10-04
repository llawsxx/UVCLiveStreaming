# MJPEG 丢帧诊断

2026-10-04 后续更新：实时路径已改为保留 JPEG 原始色度采样，以及无压缩输入的 4:2:2 / 10 位数据。下文 I420 描述和复制次数属于当时版本，当前行为见 [输入采样保留说明](../source-sampling/README.md)。

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

## 同日长时间运行后的 noSOI 增长与修复

10:27:46 至 10:28:01 的日志中，进程累计 noSOI 从 789 增至 814，解码尝试约 171 万帧。累计失败占比较低，但这 15 秒出现了 25 次新增 noSOI，不能再归因于启动阶段。本次串流 decodeFailures 从 231 增至 256；同期回调 FPS 可达 61～62，而解码输出约 59.5～60，符合碎片被单独发布的可能情况。一个无 SOI 样本长 16150 字节，尚未取得其完整内容，不能确认确切的包损坏／拼帧原因。

新增受限自动修复：没有 SOI 时，验证帧首（允许最多 32 字节前缀）是否存在完整的 8-bit baseline JPEG 头。必须有量化表、与采集模式匹配的 SOF 尺寸／组件和合法 SOS；熵数据片段、缺失关键表／头或尺寸不匹配均拒绝。验证通过则补 `FF D8`，按原策略补缺失 EOI，再由 libjpeg 容错解码。不会复用上一帧 JPEG 头或像素。

`noSOI` 仍统计收到的数据中缺少标记的次数，`repairedSOI` 新增为补 SOI 后成功解码的累计次数。即使修复成功，noSOI 仍增长；判断是否掉帧应看 failed／本次 decodeFailures。无法修复时，拒绝日志带最前 16 字节的十六进制值。

同时修正两处 UVC 解析行为：

- 读取 PTS／SCR 或处理 FID 前，验证可选字段确实位于包头内；拒绝短／畸形包头，避免越界读取及假帧边界。
- 仅含包头的 EOF 包也会结束此前积累的图像数据。

新增 `UVC assembly totals` 日志包含 payloads、badHeaders、errorPayloads（设备置 UVC ERR）、fidWithoutEOF、sizeWithoutEOF、transferErrors，均为当前 UVC stream 的累计事件数。计数可用于与 noSOI 增量关联，不表示每个事件都会导致坏图像。

原生测试已在 vivo V2338A 上独立执行：4:2:0／4:2:2 JPEG 去掉 SOI 后补回，解码 I420 与完整输入逐字节一致；尺寸不匹配、截断头和仅熵数据片段均拒绝。UVC 模拟 payload 验证正常拼帧、可选字段被截断、设备错误标志、header-only EOF 和缺 EOF 后 FID 切换。ARM64 APK 构建通过。

上述构建完成时尚未覆盖安装。后续观察见下节。

## 10:52～10:55 修复版运行中的 SOI 调查

通过 ADB 读取用户正在运行的应用（PID 9132），日志已经包含 `repairedSOI` 和 `UVC assembly totals`，确认新诊断代码正在运行。采集日志保存在忽略的 `build/perf-diagnostics/nosoi-current.log`。

- 10:53:03.316 的拒绝样本：1920×1080，16328 字节，前 16 字节为 `E09C83818A794C02C3DB93D31EE739C7`。SOI 全数据扫描未找到，完整 baseline JPEG 头检查也未通过。它不像只少了两个 SOI 字节的完整图像，而更像压缩图像中的片段；尚未保存完整样本，不能确认其熵编码结构。
- 10:52:31.748～10:52:36.761：UVC 发布帧增加 307，异常包头不增加，ERR 增加 12，未收到 EOF 而由 FID 切换结束帧的次数增加 5；紧随其后的解码统计增加 8 次 noSOI／失败。
- 10:53:01.778～10:53:06.783：UVC 发布帧增加 311，异常包头增加 1，ERR 增加 9，FID 边界增加 7；解码统计增加 9 次 noSOI／失败。
- 10:54:31.870～10:54:36.875：UVC 发布帧增加 308，异常包头增加 1，ERR 增加 10，FID 边界增加 1；解码统计增加 9 次 noSOI／失败。同期 received=61.520 fps，rendered／encoded=59.722 fps。
- 10:55:07.070，累计 24740 次解码尝试，noSOI=160、failed=160、repairedSOI=0。USB transferErrors=0、sizeWithoutEOF=0，MJPEG inputDrops=0。没有证据表明这些 noSOI 来自解码输入缓存淘汰或已报告的 USB 传输失败；也不能由 transferErrors=0 排除所有设备／主机传输边界问题。

当前证据将异常定位到 JPEG 解码之前的 USB／UVC payload 边界或组帧阶段，支持部分图像片段被额外发布成“帧”。noSOI 次数不能直接等同于丢失同样数量的真实视频帧：异常窗口回调／发布数反而常超过 60 fps。

重要限制：`errorPayloads` 只表示解析出的 `payload[1]` 包含 ERR 位。当前解析器仍允许较宽泛的 header 长度，也未验证 EOH 位；如果 bulk transfer 从 JPEG 中间开始，随机图像字节可能被当成 UVC 包头，其 ERR／FID／EOF 位都可能是假值。因此不能仅凭 ERR 计数断言采集卡主动报告故障。进一步区分采集卡输出与接收分段，需要记录异常前后原始 bulk transfer 的长度、原始头、FID／EOF／PTS 以及对应发布边界。

## 原始接收边界追踪版

继续采样时，应用仍为 PID 9132、MJPG 1920×1080 60 fps。10:59:17～11:05:23 的 noSOI 保持 191，ERR 保持 514、badHeaders 保持 21、FID 边界保持 81。该窗口回调接收约 7.1 MB/s，相比之前约 22 MB/s 降低；分辨率设置未变。数据量与异常停止增长存在相关性，但没有控制输入画面，因此不能据此证明接收慢或带宽不足。

代码审核发现 Bulk 完成回调直接将每次返回当成一个完整 UVC payload。当前包头校验检查长度及可选字段是否足够，但不要求 EOH、也不拒绝保留标志。原生模拟测试输入 `{04 03 AB CD E0 9C 83 81}`，现有解析器会剥掉前 4 字节、把剩下的 4 字节作为无 SOI 的额外帧发布。此测试复现的是解析器接受伪包头的路径，未证明实际采集卡返回过相同结构。

为区分具体原因，新增以下诊断，保留现有解析／解码行为：

- 内存中循环保留最近 16 次 payload 的原始前 24 字节、长度、单调时间，以及接收前组帧序号／累积字节／FID／PTS。遇到异常输出这些记录，再输出后续 8 次接收。整组异常追踪最多每 5 秒触发一次，不保存完整图像。
- 异常包括短／畸形包头、缺 EOH、保留标志、解析出的 ERR、没有 EOF 的 FID 切换、发布帧全数据找不到 SOI，以及重新提交失败。
- `UVC assembly totals` 增加 `missingEOH`、`reservedFlags`、`frameNoSOI`、`submitErrors`、`emptyTransfers`、`shortTransfers`、`fullTransfers`、`callbackMaxUs`。`frameNoSOI` 在组帧发布时扫描，能够区别于解码端的 noSOI；回调覆盖时两者可能不同。
- `callbackMaxUs` 是最近 5 秒视频 USB 完成回调入口至重新提交请求／处理退出的最大耗时，包含组帧锁等待、诊断日志和提交调用。它不是请求在内核中的完成时间，也不覆盖共享事件线程处理音频回调或调度等待的时间，不能单独证明接收请求曾耗尽。原始记录间的时间差同样是应用处理间隔，不是 USB 总线到达间隔。

UVC 原生测试新增伪包头路径、诊断头内容、环形记录覆盖及空 payload 校验；已在 vivo V2338A 上通过。ARM64 debug APK 构建通过，输出为 `app/build/outputs/apk/debug/app-debug.apk`。这版需要安装并在异常出现时采样，才能判断原始头是否有效、边界是否错位，以及异常前是否有明显的回调处理延迟。

## 11:15～11:17 原始追踪版真机结果

用户已安装并开启串流，应用 PID 29914。记录保存在忽略的 `build/perf-diagnostics/uvc-boundary-live.log` 与 `uvc-boundary-live-latest.log`。该次采样直接观察到 Bulk 返回边界错位以及图像字节被误解析为头字段，尚未确认错位最初由设备还是主机造成。

- 11:15:56，payload 113248～113261 均有正常 `0C 8D` 头，FID=1、PTS=3490224236，图像累积量每次增加 16372 字节。payload 113262 为 `0C 8F`（EOF），长度恰好 16384，组帧随后发布。下一个 payload 113263 的原始头却为 `27BD16B8B4140FC6...`，之后连续返回也是图像数据形态。解析器把 0x27 当头长度、0xBD 当标志，剥掉 39 字节后产生 16345 字节的无 SOI“帧”，与解码拒绝日志的长度完全一致。
- 11:16:02，payload 120212 同样是正常 `0C 8F` EOF、长度 16384；下一个 payload 120213 的原始头为 `1A453BF5416F2B39...`。
- 11:16:27，payload 150078 是正常 `0C 8E` EOF、长度 16384；下一个 payload 150079 头为 `F5C8FA9E7DFF002A...`。其中 0xC8 被解析出 ERR，不代表这一包存在有效 UVC ERR 头。
- 最新日志中还观察到 payload 169878、177083 的同类满长 EOF 后接无正常 UVC 头的返回。上述 5 处 EOF 到下个回调间隔分别为 288、344、299、337、62 微秒，未显示此处发生长时间的应用接收停顿。
- 11:16:01 最近窗口 received=62.480 fps、rendered=59.086、encoded=59.286，decodeFailures=19；组帧端 frameNoSOI=23。解码输入／输出队列淘汰和晚到均为 0，transferErrors=0、submitErrors=0。组帧无 SOI 与解码失败数不同，符合 libuvc 最新帧回调覆盖／各计数采样时间不同的行为，不能把差值视为额外解码故障。
- 11:17:21，frameNoSOI=52，解码 noSOI／失败=42；transferErrors、submitErrors 仍为 0。
- 此外 11:15:34 初期日志也出现了 `0C CF` 的包，包头长度、PTS／SCR 结构与设备正常头一致。这部分可能是真实设备 ERR 报告，不能把所有 ERR 都归为图像字节误解析。

工作假设：帧尾 payload 的长度若恰好为 USB 包长的整数倍，没有正确的 short packet／ZLP 结束边界，下一帧开头可能被接到当前 Bulk 返回内，导致后续读取错位。现在的头部追踪未记录返回内部的 JPEG／UVC 标记位置，满长 EOF 本身也可以是合法数据，因此此假设尚未证实。

新增进一步检查（仍不改变解析行为）：针对满长 EOF 及不符合 U4 已观察头形式的返回，记录首个 SOI／EOI 偏移，及返回内部“12 字节 UVC 头 + SOI + JPEG 标记”的候选偏移。`fullEOF`、`embeddedFrameHeaders` 为累计计数；`markersInspected=0` 表示未扫描，不应把 `soiAt=-1` 解读为没有 SOI。候选结构仅用于诊断，不作为切帧依据。

原生测试模拟“旧 JPEG 在偏移 510 结束，下一帧 UVC 头位于 512，新 SOI 位于 524”的合并返回，验证检测偏移准确；另验证合法满长 EOF 没有下一帧时不会计为嵌入帧头。测试已在 vivo V2338A 上通过，ARM64 APK 构建成功。新增标记位置检查版尚未覆盖安装。

## 11:25～11:34 确认粘包并实现受限组包修复

用户已安装标记位置检查版并开启串流，PID 11765。记录位于忽略的 `build/perf-diagnostics/uvc-marker-live.log` 和 `uvc-marker-live-latest.log`。真机实际偏移如下，均处于一次 16384 字节 Bulk 返回中：

| payload | 上帧 EOI 偏移 | 下一帧 UVC 头偏移 | 下一帧 SOI 偏移 |
| --- | ---: | ---: | ---: |
| 17276 | 13822 | 13824 | 13836 |
| 33504 | 2558 | 2560 | 2572 |
| 44323 | 3582 | 3584 | 3596 |
| 81550 | 11774 | 11776 | 11788 |

这些记录确认两帧边界确实混在同一次 Bulk 返回内，下一帧头偏移恰好是 512 字节的整数倍。这与缺失或未被正确体现的 short packet／ZLP 结束边界一致；软件日志没有总线级的 ZLP 观测，仍不能确定错误最初由采集卡固件还是主机 USB 栈造成。已确认的应用缺陷是 libuvc 将一次 Bulk 返回直接视为一个 UVC payload。

截至 11:34:24，fullEOF=37、embeddedFrameHeaders=32、组帧 frameNoSOI=203，解码 noSOI／失败=148；transferErrors=0、submitErrors=0。noSOI 事件中包含额外发布的碎片，不能等同于丢失 148 张真实 JPEG。

新增修复在 Bulk 分支、MJPEG 模式下生效：

- 从实际 endpoint 描述符读取 USB 包长。在 EOF 数据里发现“EOI 恰好结束于 USB 包边界 + 下一帧 12 字节 PTS／SCR 头 + FID 切换 + PTS 改变 + SOI／合法 JPEG 头标记”时，拆开粘在一起的两个 payload。
- 先发布上一帧完整结尾，保存下一帧开头；继续拼接后续 Bulk 返回，凑齐协商的 payload 大小后再剥 UVC 头。short packet 或独立零长度返回会冲刷剩余 payload，并恢复普通接收路径。连续出现粘包也会重新拆分。
- 仅在确认粘包后分配一个 payload 大小的额外缓存，本卡为 16 KB，额外缓存上限为 1 MiB。停止关闭时释放，传输异常／重新启动会丢弃跨接收的残留，避免拼入下一段数据。
- 原始追踪仍记录真实接收返回，修复后的 payload 不重复计入 raw 接收数。新增 `bulkRepairs` 与 `bulkPending`，以及限频的 `UVC bulk boundary repaired` 日志。正常接收和非 MJPEG 模式保留原处理路径。

真机独立原生测试通过：在 512／1024／1536 边界模拟粘包，确认两帧输出图像数据与输入逐字节一致；验证 header-only EOF、独立 ZLP、连续两次缺失分隔、后续普通帧，以及同 FID／同 PTS／缺 EOI／非 USB 包边界／非法 JPEG 标记／非 MJPEG／无 EOF 时不会误触发修复。已有 UVC 解析诊断测试继续通过。ARM64 APK 构建通过。修复版尚未覆盖安装，需要运行后验证 `bulkRepairs` 增长时 noSOI 是否停止增长，并继续观察真实 UVC ERR 与回调覆盖等独立事件。

## 11:44～11:47 修复版真机验证

用户已安装并开启串流，PID 24347。日志保存在忽略的 `build/perf-diagnostics/uvc-repair-live.log` 和 `uvc-repair-live-latest.log`。

- 11:44:54.138，修复日志记录 count=2、splitAt=8704、packetSize=512；11:44:57.809 记录 count=3、splitAt=15872。这些是实际接收数据触发的拆分，不是模拟测试。
- 11:44:55～11:47:41，bulkRepairs 从 2 增至 9，fullEOF／embeddedFrameHeaders 同步从 2 增至 9；组帧 frameNoSOI 始终为 2。
- 11:44:46～11:47:41，解码尝试从 15569 增至 26071，noSOI 始终为 2、failed 始终为 3、missingEOI 始终为 0，容错警告帧始终为 1。上述失败／警告在采样前已经存在，当前日志不足以确认其发生原因，不能直接归为启动阶段。
- 所有 UVC 汇总中的 badHeaders、missingEOH、reservedFlags、transferErrors、submitErrors 均为 0；真实／解析出的 ERR 累计保持 142，FID 边界保持 1，没有新增。bulkPending 在汇总采样点均为 0；这表示采样时已冲刷完，不意味着恢复过程中从未保留半包。
- MJPEG 池 inputDrops／outputSkippedSequences／lateCompletions 均保持 0。
- 11:44:56.281～11:47:41.473，共 165.192 秒，接收／GPU 提交／编码输出均增加 9901 帧，三段窗口速率均约 59.936 fps。5 秒窗口会因采样边界和流水线暂存上下波动，不能将单个窗口编码输出低 1 帧视为永久丢帧。

结论：本次真机窗口内，Bulk 粘包仍持续发生，但新增 7 次粘包都进入修复，未新增 noSOI 或解码失败，收帧和编码输出的长期增量一致。已验证此次受限 Bulk 组包修复有效；此前累计的 2 次 noSOI／3 次失败仍需在再次出现时独立追踪，不能据此声称所有潜在坏帧原因已经消除。

## 扩展到固定长度未压缩格式

新增 YUYV／UYVY、RGB／BGR、NV12／NV21／I420、P010、GRAY8／GRAY16 的 Bulk 粘包修复。仅用于紧密排列、固定长度的原始图像；与 APP 目前解码所假定的内存布局一致。几何信息来自所选帧描述符，非零行跨度必须与紧密布局匹配，格式标记为可变长度或实际帧大于分配容量时不启用。`dwMaxVideoFrameSize` 仅作为容量校验，不能直接用作实际帧长。

固定帧长分别按 16-bit packed=宽×高×2、RGB=宽×高×3、8-bit YUV420=宽×高×3/2、P010=宽×高×3 等计算。EOF payload 的边界为“包头长度 + 实际帧长 - 已组图像字节数”。仅在该位置恰好落在 USB 包边界、下一包头合法且 FID／PTS 改变、已有图像确实属于当前 EOF 帧时拆分；不在像素中搜索类似包头的任意字节。

支持带 PTS 的 6 字节头，以及带 PTS／SCR 的 12 字节头；要求 EOH、拒绝 ERR 和保留标志。实际缺少像素、行布局不匹配或没有足够边界证据时，保留原处理路径。跨接收组包、短包／ZLP 冲刷和缓存上限与 MJPEG 修复共享。新增 `bulkRawRepairs` 计数，修复日志显示格式及计算的固定帧字节数。

仅凭一个 Bulk 返回没有有效 UVC 包头，可以怀疑边界错位，但无法区分粘包、数据缺失及设备头格式差异。每个 512 字节 USB 底层包本来就不必带 UVC 头。像素也可能偶然形成合法头部字段，所以不能据此直接切帧。

H.264 等可变长度压缩源未加入这套固定帧长修复，也没有新增 H.264／HEVC 采集支持；编码输出的 H.264／HEVC 与 USB 源格式是不同层次。

原生模拟测试在 vivo V2338A 上通过：全部上述格式的两帧粘包组装输出与输入逐字节一致；覆盖 6／12 字节头、像素内伪包头、缺像素、同 FID／PTS、旧帧 PTS 不匹配、ERR、无 EOF，以及行跨度／奇数 YUV420 尺寸／容量不足／溢出检查。MJPEG 原生回归测试继续通过。ARM64 APK 构建通过；这版尚未覆盖安装，非 MJPEG 采集流尚未实测。

## MJPEG 直接输出缓冲池

MJPEG 解码工作线程现在借用 `DirectVideoBufferPool` 中的直接缓冲区。JNI 使用 `GetDirectBufferAddress`，将其作为 `library_owns_data=0` 的目标帧交给原有 JPEG 解码器；最终 I420 直接写到该缓冲区，GPU 从同一块内存上传。移除了原有 native I420 → Java ByteArray、ByteArray → GL 上传缓冲区两次整帧复制，以及每帧 I420 Java 数组分配。压缩 JPEG 输入、JPEG 内部平面处理及 GPU 上传仍有各自开销，不能称为整条链路零复制。

借用的缓冲区在有序结果队列、渲染等待及 GL 上传期间保持独占。预览和录像／串流的消费者均通过 `finally` 归还；解码失败、迟到结果、队列淘汰和关闭也会归还。池关闭不会释放仍在 JNI／渲染线程使用的内存；后续归还不再缓存。空闲缓存最多 32 MiB，缓冲数量受工作线程和输出队列容量约束；完成结果仍保留原有 8 帧／32 MiB 上限，32 MiB 不是整个解码过程的内存上限。

`MjpegDecodePool.Diagnostics.outputBuffers` 提供 allocations／reuses／inUse／cached。固定分辨率稳定运行后应看到 allocations 基本停止增长、reuses 持续增加；停止并归还全部消费者后 inUse／cached 为零。

验证：46 项 JVM 单元测试通过，覆盖乱序、消费者持帧期间不可覆盖、归还后的复用、失败／迟到／关闭释放、缓存边界及分辨率变化。在 vivo V2338A 的独立 `app_process` 中运行 4 项 APK 内测试通过：新旧 I420 输出逐字节一致（33×31 奇数尺寸、前后杂字节、缺 SOI／EOI）、非直接／只读／容量不足缓冲的拒绝与越界哨兵检查、直接缓冲 GPU 像素／方向与复用、原始 YUV 和 JPEG 容错回归。测试不覆盖安装，不停止当前 APP；测试前后 APP PID 均为 11332。

原有 Android DHT 测试在旧版 APK 上同样出现 76 与 75 的像素差异。`Bitmap.compress` 可能输出自定义霍夫曼表，移除 DHT 后用默认表不能要求恢复原始像素；该断言已改为确认新旧输出路径的默认表回退结果一致。

ARM64 APK 构建通过并复制到 `app/build/outputs/apk/debug/app-debug.apk`，SHA256 为 `B16CE807DD00173AB5AAE26D50A2A157C9877626BC4A56F16F66D94F16BE055B`。当前未覆盖安装，实际采集期间的解码耗时、CPU 和 GC 改善尚待运行新版后测量。

## 未压缩格式直接输出

预览和录像／串流的未压缩图像现在通过 `RawVideoConverter` 借用直接缓冲区，在渲染线程调用 `nativeConvertRawToGpuBuffer`。YUYV／UYVY、NV12、P010 转换为 I420 时直接写入该缓冲区，移除 native 转换结果 → Java ByteArray → 上传缓冲区两次复制，以及每帧转换结果数组分配。YUYV／UYVY 的色度两行平均和舍入、NV12 的 UV 拆分、P010 保留高 8 位等算法与旧路径共享；色彩范围、矩阵选择、时间戳保持原语义。

I420、RGB／BGR 使用 `GetByteArrayRegion` 从输入数组直接复制到池中，没有额外的输入临时缓冲或格式转换。RGB／BGR 保持原布局，由 GPU 处理通道顺序。I420／RGB／BGR 原本已有可复用的 GL 上传缓冲，因此此次复制次数没有下降，不应宣称所有未压缩格式均减少两次复制。

以 USB 接收缓冲区起、GPU 上传前的明确 CPU 复制为口径：YUYV／UYVY、NV12、P010 从 5 次降为 3 次，另有一次格式转换；这些格式的 `GetByteArrayElements` 仍可能产生额外输入复制。I420／RGB／BGR 仍为 4 次。libuvc 组帧、回调帧及输入 Java 数组的复制和 GPU 上传仍存在，没有改动 USB 回调的帧内存所有权。Java 输入队列继续有界，转换在出队后执行，因此排队丢帧不持有直接缓冲。结果在完成渲染、预览无效、被中断和异常时均通过 `finally` 归还；关闭池不会破坏仍被转换／渲染持有的帧。

原始图像池最多缓存 1 个缓冲（空闲字节上限 32 MiB），尺寸变化时丢弃不匹配的空闲缓冲。`UsbVideoDiagnostics` 新增 `rawBuffers` 的 allocations／reuses／inUse／cached 计数，固定尺寸运行时应主要增加 reuses。

验证：51 项 JVM 单元测试通过，新增原始格式的输出尺寸／布局／范围／时间戳、源长度和几何验证、持帧独占、复用、转换失败／异常释放及关闭测试。vivo V2338A 独立 `app_process` 中 6 项 JNI／GPU 测试通过，新增覆盖 7 种原始格式、I420／RGB 奇数尺寸、输入不被修改、缓冲切片前后哨兵、只读／非直接／容量不足拒绝、新旧有效图像字节及 GPU 像素一致；4 项 MJPEG／旧格式回归继续通过。测试前后正在运行的 APP PID 均为 19494，未覆盖安装。

最新 ARM64 APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA256 `B1D3875647BED81B6AACA9211F2CBFCC0122C10638C693F424101F4BE8AAA4EB`。实际 USB 未压缩采集流的性能改善仍需运行新版后测量。
