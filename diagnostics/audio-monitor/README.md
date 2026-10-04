# USB 音频预览（监听）

界面增加“音频预览（监听）”开关，默认关闭，通过 USB UI preferences 保存。勾选 USB 麦克风后可用，可在空闲视频预览、录像、HTTP 串流及 RTMP 推流时动态切换。开关只控制本地播放，AAC 编码与输出继续使用已有采集 PCM。

`UsbAudioMonitor` 共用既有 PCM16 数组，数组按只读方式分享。UAC 回调仅以无容量等待的 `offer` 投递至8包有界队列；队列满时淘汰旧包。回调不创建、写入、暂停或释放 AudioTrack，也不等待监听线程。设备约10ms一包时，软件队列通常最多保存约80ms PCM；另有单包大小上限和100ms待播年龄限制，避免异常批次或播放停顿造成积压。

专用 `usb-audio-monitor` 线程创建 AudioTrack，按实际 USB 采样率和单／双声道播放，使用 `WRITE_NON_BLOCKING`。返回0时仅该线程短暂等待；部分写入会按偏移继续，超过待播年龄就丢弃剩余监听数据。Android自己的AudioTrack缓冲还受系统最小值和输出设备影响，因此100ms不是端到端播放延迟保证。输出按系统媒体路由和媒体音量播放。

关闭时更新开关代际、清空队列并唤醒播放线程；旧代际数据在快速关／开后不能重新进入播放。pause／flush／release均由播放线程完成，UI切换不等驱动。设备移交和采集关闭可在其清理线程短暂等待监听线程退出；等待异常不会跳过USB句柄释放。监听输出失败仅提示“音频预览已停止”，不会停止采集或录像／串流，重新关／开可重试。

67项JVM测试通过，新增5项针对：播放被阻塞时仍可投递PCM及队列淘汰、动态关闭／重开清空旧音频及释放线程、部分写入完整性、播放缓冲满时淘汰过期音频、输出失败隔离和重试。ARM64 APK构建通过。

独立ADB静音PCM测试未完成：vivo ROM的PlayerBase需要正常Application上下文，直接app_process出现上下文异常；补充上下文后仍无法创建AudioTrack，AudioFlinger返回权限错误。该结果不能验证正常APP中的播放，未覆盖安装或停止当前APP；实际USB音频监听、媒体路由和听感仍需安装新版后验证。测试临时文件位于忽略的 `build/perf-diagnostics/audio-monitor/`。

APK：`app/build/outputs/apk/debug/app-debug.apk`。
