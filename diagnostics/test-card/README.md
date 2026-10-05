# 虚拟测试卡

在「设备」Tab 的摄像头选择器中选择「测试卡（虚拟设备）」，再选择样式、分辨率／帧率并启动预览、录像或串流。没有 UVC 设备时默认选中测试卡；USB 插拔不会结束正在运行的测试卡。

提供 7 种程序生成的图案：SMPTE 风格 75% 三段彩条、EBU 75% 彩条、EBU 100% 彩条、灰阶／渐变／黑白电平、分辨率细线／楔形／棋盘、运动标尺／逐帧闪烁，以及 PM5544 风格圆形综合图。SMPTE 和 PM5544 是便于调试的风格图案，没有声明完整符合标准测试信号规范。

可选常见 480p、720p、1080p、4K 模式，也可自定义 1～3840 × 1～2160、1～240 fps，小数帧率会保留。尺寸和帧率范围是输入参数范围，实际录像／串流仍受手机编码器支持的尺寸、对齐和吞吐量限制。开启现有 NTSC 选项时，60／30 按 60000/1001、30000/1001 生成；已经输入的小数帧率不会再次换算。

画面底部标注 W/H（源像素尺寸）、FPS（生成目标帧率）、F（帧号）和 T（源时间，秒）。帧号每 1,000,000 帧回绕，秒表每 10,000 秒回绕。分辨率图的细线按源像素计算；判断单像素细节时应以原尺寸观看，避免播放器／预览缩放影响结论。低帧率预览仍只显示 5 fps，编码继续使用源帧率。

时间轴采用绝对纳秒截止时间，避免每帧四舍五入后累计漂移。渲染迟到时跳过过期帧并保留帧号和时间的跳跃，不突发补帧。HUD 的 FPS 是源目标；录像／串流的近期 FPS 是实际编码输出，性能不足时后者会降低。

图案由独立 GPU fragment shader 直接生成 RGB，无整帧 CPU 图片生成、复制或上传。它复用调色 LUT、GPU Surface 编码、VUI 元数据重写以及录像／HTTP／RTMP 的共享编码输出路由。真实 USB 输入仍使用原 shader。测试卡不经过 YUV 输入转换，因此设备 Tab 的 YUV→RGB 矩阵和源范围在此模式禁用；编码器的颜色选项及调色仍有效。测试卡不用于测量 USB 接收、采集卡损坏帧或 MJPEG 解码性能。

测试卡自身只提供视频，不申请 USB／相机权限。选择系统麦克风后可以按原音频链路加入音频，并申请麦克风权限；保留 USB 音频输入时只输出视频。虚拟录像／串流的前台服务使用 dataSync 类型，真实采集仍使用 camera；Android 15+ 的后台 dataSync 超时会结束测试卡服务并提示重新启动。

## 验证

vivo V2338A：

- 独立 `app_process` 加载正式 APP／Android 测试 APK，5 个 GPU／编码测试全部通过，无覆盖安装、无停止原 APP。
- 7 个图案实际渲染成功；验证 75%／100% RGB 彩条、0/4/8/16/235/247/251/255 电平、相邻单像素黑白线、运动帧闪烁和 HUD 更新。生成并查看了 GPU 输出 PNG；圆形综合图保持圆形。验证测试卡切回真实 RGB 输入后 shader 正常。
- AVC／HEVC 各编码 30 帧，60000/1001 纳秒时间轴的输出 PTS 均保留至微秒精度。原 RGB/YUV 范围、方向、矩阵及 LUT 中性旁路回归通过。
- 独立 `.layoutcheck` 安装包的 `TestCardCaptureDeviceTest` 在不启动 Activity、不访问真实设备或麦克风的情况下运行生产引擎：720p60 HTTP 串流期间添加 MP4 录像，停止录像后 HTTP 继续，最后停止引擎；测试通过。HTTP 使用独立端口 13409。
- FFprobe 完整解码得到 MP4 1280×720、60/1 fps、3 秒 180 帧；HTTP 去除 chunked 传输分块后得到可解码 TS，1280×720、60/1 fps、260 帧。测试录制的 MediaStore 文件已删除，诊断包卸载。测试前后原 APP PID 均为 27324。
- JVM 共 103 项测试，101 项通过、2 项跳过，无失败；新增覆盖分数帧率长时间无漂移、迟到帧定位、参数边界、图案／尺寸／小数 FPS 设置保存与默认回退。正式 ARM64 APP／Android 测试 APK 构建通过。

没有验证测试卡服务在所有 ROM 上的后台生命周期、长时间 RTMP 网络行为或 4K60 编码性能。

## 重现

构建正式 APP 和 Android 测试 APK。使用 `javac --release 11` 编译本目录 `TestCardSmoke.java`，用 SDK D8 生成入口 dex，将入口 dex 与两个 APK 推送到 `/data/local/tmp/uvclive-test-card/`。

```sh
CLASSPATH=/data/local/tmp/uvclive-test-card/classes.dex:/data/local/tmp/uvclive-test-card/app-debug.apk:/data/local/tmp/uvclive-test-card/app-debug-androidTest.apk app_process /system/bin TestCardSmoke
```

独立包引擎测试需将 applicationId 临时设为 `com.llawsxx.uvclivestreaming.layoutcheck`，安装该 APP／测试 APK 后运行：

```sh
am instrument -w -r -e class com.llawsxx.uvclivestreaming.recording.TestCardCaptureDeviceTest com.llawsxx.uvclivestreaming.layoutcheck.test/androidx.test.runner.AndroidJUnitRunner
```

此测试在普通 applicationId 下会跳过，以免操作正常 APP 的输出。HTTP 测试保存的是原始 chunked body；外部解码前需去除传输分块。测试 MP4 和 HTTP body 留在独立包的私有 files 目录，可在卸载前通过 `run-as` 提取。主机验证日志、PNG、录像与临时入口位于忽略目录 `build/perf-diagnostics/test-card/`。
