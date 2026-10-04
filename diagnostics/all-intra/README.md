# GOP 小数和每帧关键帧

USB 页面 GOP 秒数支持0～30的非负小数，例如 `0.5`、`0.25`、`2`。输入 `0` 请求每帧关键帧（All-Intra），自动禁用 B 帧；B 帧输入框显示0，离开 GOP=0 后恢复先前填写值。非法／超范围输入显示错误并阻止启动。设置在下次启动录像或串流时生效，共享编码的各输出使用相同 GOP。

`RecordingConfig.videoKeyFrameIntervalSeconds` 改为 Float；SharedPreferences 保存 Float，并兼容读取旧 Int 值。USB 编码器通过 `MediaFormat.setFloat(KEY_I_FRAME_INTERVAL, ...)` 传递小数，不再把0或不足1秒的值提升为1秒。GOP=0时显式设置 `KEY_MAX_B_FRAMES=0`。界面没有额外的 All-Intra 开关。

每帧关键帧减少帧间依赖，尤其能减少等待下一关键帧的起播／恢复时间；总延迟还取决于编码器处理、USB、网络和播放器缓存。相同目标码率下，All-Intra可能降低画质，需按需要提高码率。运行时如发现编码器输出非关键帧，仅提示一次，避免将不支持的设备输出误称为 All-Intra。

## 验证

通过独立 `app_process` 加载本次 APP 和 Android 测试 APK，运行生产 GPU Surface 渲染和 GOP helper，在 vivo V2338A 上使用实际默认编码器测试720p60，60帧／组：

| 编码器 | GOP 秒 | 输出帧 | 关键帧 |
| --- | --- | --- | --- |
| c2.qti.avc.encoder | 2 | 60 | 1 |
| c2.qti.avc.encoder | 0 | 60 | 60 |
| c2.qti.avc.encoder | 0.5 | 60 | 2 |
| c2.qti.hevc.encoder | 2 | 60 | 1 |
| c2.qti.hevc.encoder | 0 | 60 | 60 |
| c2.qti.hevc.encoder | 0.5 | 60 | 2 |

GOP=0逐帧检查 BUFFER_FLAG_KEY_FRAME 和实际 VCL NAL 类型：AVC 全为 IDR，HEVC 全为 IRAP；所有组均保留60fps源 PTS，输出完整并到达 EOS。测试中配置 GOP=0 且请求3个 B 帧，生产 helper 正确覆盖为0。

未安装或停止当前 APP，测试前后其 PID 均为32600。测试不量化端到端播放延迟，不保证其他硬件编码器对该参数的支持。原生库复用 `/data/local/tmp/uvc-direct-buffer/lib/`；测试文件放在 `/data/local/tmp/uvclive-all-intra/`，入口源为 `VideoAllIntraSmoke.java`，临时构建目录为忽略的 `build/perf-diagnostics/all-intra/`。

新增两项JVM测试验证旧整数迁移、0和小数保存／加载。APK：`app/build/outputs/apk/debug/app-debug.apk`。
