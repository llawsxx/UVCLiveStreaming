# 独立 UAC 输入与 24／32 bit PCM

「设备」Tab 的「音频采集」选择 USB 音频（UAC）后，可在「USB 音频设备」中选择外置 USB 麦克风／声卡，或保留「跟随视频设备」。只有含 AudioStreaming IN 端点的设备会列出，扬声器专用设备不会列入输入列表。音频设备无需与视频采集卡相同，也可搭配虚拟测试卡。

「UAC 输入位深」可选择自动、16、24、32 bit，均为有符号整数 PCM。自动模式在所选路由、声道和采样率内按 32→24→16 bit 尝试；指定不受设备支持的位深／采样率会明确报错，不冒充指定格式。现有处理／编码链路支持单声道或双声道，自动采样率仍限制为 8～96 kHz。该设置不改变系统 AudioRecord 输入。

选择同一视频设备时复用现有 UVC/UAC 共享 libusb context；选择其他设备或搭配测试卡时，创建独立 UAC-only handle、libusb context 和事件线程。音频设备单独通过 Android USB 权限授权，不协商 UVC、不访问其视频接口。独立音频设备如果也是 UVC 复合设备，Android 的 USB 授权仍可能要求相机权限。

设备选择和位深会保存。重新连接后可匹配唯一的同 VID/PID/名称设备；多个同型号设备无法唯一匹配时需要重新选择。设备缺失不会静默改用其他麦克风。USB 插拔更新设备列表，前台服务也监听正在使用的 USB 设备断开，即使界面不在前台也会结束本次采集。

## PCM 精度与线程

USB 回调把原始、交织、little-endian 的 2／3／4 字节子槽打包后送入有界音频队列，不在 USB 线程进行 DSP／播放／AAC 编码。UAC 有效位遵循子槽 MSB 对齐，支持 packed 24 bit 和 4 字节子槽。JNI 报告实际有效位深及子槽宽度。

开启 DSP 时，24／32 bit 输入在 JNI 中直接转为 float，经过响度标准化／限制器后才转换为 PCM16，交给现有电平、播放、AAC 以及共享录像／串流输出。32 bit 整数输入受 float 本身有效精度限制；没有声称保留每一个 32 bit 整数的最低位。DSP 关闭时直接转换为输出 PCM16。当前播放和 MediaCodec AAC 输入保持 PCM16，没有增加 24／32 bit 文件录制或 32 bit IEEE float UAC 格式。

输入帧计数和 DSP 时间跨度按输入子槽宽度计算，输出／延迟尾部按 PCM16 宽度计算，保持源时间戳和帧数。系统 AudioRecord 和旧 PCM16 回调继续使用原路径。

## 验证记录

- JVM 新增测试：独立设备重新连接／USB 地址复用／同型号歧义、设置保存／旧设置默认跟随、24／32 bit 符号／量程／舍入／饱和、原始宽位深数据不提前转换、DSP 延迟尾部帧数／PTS。原 PCM16 旁路、丢队列及延迟测试继续通过。
- vivo V2338A 独立 `app_process` 使用本次 APK 的真实 JNI 库运行 3 项测试，全部通过：24／32 bit 输入低于 PCM16 分辨率的信号经 float DSP 增益后输出非零；2／3／4 字节子槽满量程与 PCM16 回转／不修改输入；UAC-only 无效 fd 多次失败后正确释放，不打开视频。
- 手机系统保存的 BOYA CastMic G30（VID 0x0A67／PID 0xD2C2，UAC1）描述符显示：麦克风单声道，48／96／192 kHz 各提供 16／24 bit，分别 2／3 字节子槽，没有 32 bit。这里只验证了描述符，未声称实际收到 PCM。
- 独立 `.layoutcheck` 诊断包被手机安装器以 `INSTALL_FAILED_ABORTED: User rejected permissions` 拒绝；因此连接设备的 `UsbUacCaptureDeviceTest` 没有运行，实际 UAC→DSP→AAC 串流、设备热插拔和授权 UI 尚待实测。没有覆盖安装或停止原 APP。

## 重现

正式 APP／Android 测试 APK 构建后，编译 `UacSmoke.java` 为 dex。将入口 dex、两个 APK，以及 APK 内的 ARM64 `libuvclivestreaming_usb.so`／`libc++_shared.so` 推送至独立临时目录：

```sh
LD_LIBRARY_PATH=/data/local/tmp/uvclive-uac CLASSPATH=/data/local/tmp/uvclive-uac/classes.dex:/data/local/tmp/uvclive-uac/app-debug.apk:/data/local/tmp/uvclive-uac/app-debug-androidTest.apk app_process /system/bin UacSmoke
```

实机设备测试需使用独立 applicationId `com.llawsxx.uvclivestreaming.layoutcheck`，安装诊断 APP／测试 APK，然后执行：

```sh
am instrument -w -r -e class com.llawsxx.uvclivestreaming.recording.UsbUacCaptureDeviceTest com.llawsxx.uvclivestreaming.layoutcheck.test/androidx.test.runner.AndroidJUnitRunner
```

测试请求独立 USB 授权，只选择不含 UVC 接口的音频设备。循环验证自动／16／24／32 bit（设备不支持的显式模式记录为不支持），检查 PCM 实际子槽宽度、帧数、DSP 队列和延迟尾部，再启动测试卡＋外置 UAC 的 HTTP/AAC 串流，使用独立端口 13411。测试结果和原始 chunked HTTP body 保存到诊断包 files 目录，可用 `run-as` 提取；外部解码前需移除 HTTP 分块。普通 applicationId 下跳过此项，避免操作正式 APP。

主机临时文件、构建入口和日志位于忽略目录 `build/perf-diagnostics/uac-devices/`。系统描述符可只读获取：`adb shell dumpsys usb dump-descriptors -dump-list`。
