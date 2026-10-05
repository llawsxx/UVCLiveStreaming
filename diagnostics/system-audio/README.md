# 系统音频输入

设备 Tab 的“启用音频”下可选择采集卡音频（USB UAC）或系统麦克风（AudioRecord）。默认保持 USB UAC。系统输入提供 DEFAULT、MIC、CAMCORDER、VOICE_RECOGNITION、VOICE_COMMUNICATION、UNPROCESSED，以及 Android 10 以上的 VOICE_PERFORMANCE；Android 决定各输入源实际使用的前处理。

输入设备来自 `AudioManager.getDevices(GET_DEVICES_INPUTS)`，随设备插拔更新。按实际类型显示内置麦克风、电话音频、耳机、USB 等名称，不生成不存在的设备选项。设备 ID、类型、地址、名称与输入源分别持久化。重新连接后 ID 可以变化，只有类型、地址和名称唯一匹配时才恢复选择；缺失或歧义时不会自动改选其他设备。

使用 `AudioRecord.setPreferredDevice` 请求输入设备，并在开始采集后及路由变化时检查 `routedDevice`。系统拒绝选择、设备已断开或实际路由与选择不一致时明确报错。电话音频出现在列表中并不等于普通应用有通话音频录制权限；设备和 Android 策略决定可用性，不添加需要签名权限的 VOICE_CALL/VOICE_UPLINK/VOICE_DOWNLINK 源。

系统采集使用独立音频线程读取 PCM16，约每 10 ms 送入现有有界 `UsbAudioPipeline` 队列。该线程不执行 DSP、播放或编码。DSP 内部仍为 float；处理结果共享给电平/近 1 秒峰值、异步监听和 AAC 编码，录像与串流继续共享同一份编码输出。选择系统输入时不会同时启动采集卡 UAC 音频。停止时先 stop AudioRecord 唤醒阻塞读取，等待采集线程退出后 release，然后排空 DSP 队列。

`AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` 与累计读取的采样帧定位每块 PCM 的起始 PTS，与 USB 视频 CLOCK_MONOTONIC 同一时基；读取回调的晚到不会直接变成 PTS 晚到。硬件时间戳暂时不可用时使用按采样数累计的时间线。现有音频时间戳平滑仍适用。

自动采样率尝试 48000、44100、32000、16000 Hz，优先双声道，不支持时尝试单声道。手动指定采样率时只尝试该采样率。预览中的音频选择变更会重新打开采集；录像/串流期间锁定输入配置，DSP 和监听仍可实时调整。

验证：

- `SystemAudioInputTest` 检查读取延迟、硬件时间戳暂不可用、44100 Hz 舍入不累积、设备 ID 变化及设备缺失/歧义。
- 配置测试检查输入源/设备身份保存与恢复，以及旧配置继续使用 USB UAC。
- `SystemAudioCaptureDeviceTest` 在前台测试 Activity 中检查 MIC 默认设备、MIC 指定内置麦克风、CAMCORDER，以及实际 PCM → float DSP → AAC、时间戳和停止释放；PCM/AAC 均不写文件或网络。测试需要麦克风权限，建议使用独立包名运行。

2026-10-05 验证结果：构建通过，99 项 JVM 测试中 97 项通过、2 项原有网络测试跳过。vivo V2338A 的实际 HTTP 串流使用 SYSTEM/CAMCORDER，AudioFlinger 路由到 `AUDIO_DEVICE_IN_BACK_MIC`，客户端 48000 Hz 双声道，`silenced=false`。通过 ADB 临时端口转发读取串流的音频包元数据，3 秒内得到 141 个 AAC 包，PTS 步长 21.333～21.334 ms，符合 1024/48000 秒。元数据汇总在 ignored `build/perf-diagnostics/system-audio/http-aac-summary.json`。

独立测试包的初次 instrumentation 尝试阻塞在框架启动阶段，随后因用户已开启系统麦克风串流而停止测试，改为上述现有串流验证；未完成所有输入源和指定设备的真机覆盖。测试包已卸载，临时端口转发已移除。
