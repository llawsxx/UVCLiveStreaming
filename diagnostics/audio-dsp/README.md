# USB 输入响度标准化和限制器

参照 `D:/AndroidProject/LiveAudioProcess/app/src/main/cpp/native_audio.c` 的响度与限制器实现，以及同目录的 `loudness_peak.h`，只移植这两个效果。新增“音频 DSP”总开关，默认关闭，两个效果可独立启用；设置保存到 USB 页面 preferences，在空闲预览、录像和串流期间均可调整。

链路：USB PCM16 → 有界队列 → `usb-audio-dsp` 工作线程 → float32 PCM → 响度标准化 → 限制器 → PCM16 → 同一份处理结果送本地监听、电平计和 AAC 编码。录像、HTTP、RTMP 共用后续编码。两个效果之间不量化为 PCM16；响度 K 加权滤波、能量统计和部分增益计算沿用参考代码的 double，以保证数值精度。没有移植 EQ、混响或其他 DSP。

响度标准化保留 K 加权、100 ms 子块、400 ms 门控块、3 秒短时响度、30 秒滚动积分历史、绝对／相对门限、LRA 测量与平滑联动增益。默认目标 −16 LUFS、LRA 7 LU、峰值 −1 dBFS、5 ms 前瞻、1000 ms 更新间隔。可选“仅提升”延续参考语义：总增益不低于 1，因此该模式自身可能不能保证峰值上限；需要限制峰值时可同时开启限制器。

限制器保留参考实现基于 FFmpeg af_alimiter 的峰值调度和联动前瞻增益。默认输入增益 0 dB、阈值／上限 −0.5 dBFS、释放 80 ms、前瞻 1 ms，可选自适应释放。最后的峰值保护应用同一增益到两个声道，避免浮点包络舍入导致单声道削波、改变声道比例。两个效果的峰值检测均为采样峰值，未加入过采样真峰值检测，界面使用 dBFS，不宣称 dBTP。

USB 回调仅执行 `offer`，不运行 DSP、计算电平或操作 AudioTrack。处理队列最多16包；当前约10 ms/包，约160 ms，过载时淘汰旧包并计数。检测到队列丢包后重置 DSP 历史。监听保留独立播放线程和有界队列，不等待播放器；DSP 处理后数组只读共享。

前瞻启动的静音填充不输出，保留源帧时间戳；队列中的时间戳跨度随实际输出帧数消费。停用、调整前瞻或结束采集时先排空延迟尾部，再销毁原生状态；AAC 等待 DSP 和所有已处理 PCM 排空后才发送 EOS。默认组合在 48 kHz 的实际延迟是 240 + 47 = 287 个采样帧（约5.98 ms，限制器遵循参考环形缓冲延迟定义）。目标响度、阈值等参数热更新保留原有测量和增益历史；修改前瞻或效果开关时重建状态。

## 验证

- 73项 JVM 测试通过，新增6项覆盖 PCM 旁路、跨包前瞻／尾部／源时间戳、停用时尾部顺序、处理线程阻塞时 USB 投递仍能完成及有界淘汰、失败时完成退出通知、参数热更新不重建 DSP。
- ARM64 原生信号测试通过：旁路、精确前瞻延迟、分块与整块一致、原地处理、联动声道、限制器与响度峰值上限、单／双声道 LUFS 收敛及目标热更新、静音、8–192 kHz 采样率、50 ms 前瞻。单／双声道1 kHz测试音均收敛到 −16.001 LUFS。
- vivo V2338A 上独立运行 ARM64 测试程序，20秒合成音频的 DSP 加信号生成／验证总耗时：单声道0.103秒，双声道0.110秒。该结果不等同于实际 USB、AAC 和监听的总 CPU 开销。
- 将 APK 构建出的 `libuvclivestreaming_usb.so`、`libc++_shared.so` 与独立测试 dex 通过 ADB 放到 `/data/local/tmp/uvclive-audio-dsp/`，用 `app_process` 验证实际 JNI 接口。全部65536种 PCM16 值经过 float 转换往返完全一致，create/process/update/delay/destroy 和限幅后的 PCM16 数值均通过。
- `:app:testDebugUnitTest :app:assembleDebug --offline --no-daemon -Pandroid.injected.build.abi=arm64-v8a` 成功。未覆盖安装、停止当前 APP 或改变其串流设置；实际采集、监听和编码后的听感待安装验证。

原生测试源：`audio_dsp_test.c`；JNI测试源：`NativeAudioDsp.java`；临时构建文件：忽略的 `build/perf-diagnostics/audio-dsp/`。

原生测试复现（PowerShell）：

```powershell
& 'D:/AndroidSDK/ndk/30.0.15729638/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe' --target=aarch64-linux-android24 -O2 -Wall -Wextra -Werror '-Iapp/src/main/cpp' diagnostics/audio-dsp/audio_dsp_test.c app/src/main/cpp/audio_dsp.c -lm -o build/perf-diagnostics/audio-dsp/audio_dsp_test
& 'D:/AndroidSDK/platform-tools/adb.exe' -t 1 push build/perf-diagnostics/audio-dsp/audio_dsp_test /data/local/tmp/uvclive_audio_dsp_test
& 'D:/AndroidSDK/platform-tools/adb.exe' -t 1 shell chmod 755 /data/local/tmp/uvclive_audio_dsp_test
& 'D:/AndroidSDK/platform-tools/adb.exe' -t 1 shell /data/local/tmp/uvclive_audio_dsp_test
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。
