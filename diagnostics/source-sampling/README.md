# 保留输入色度采样和位深（2026-10-04）

USB 预览、录像和推流使用同一套源采样保留路径。用户选择在转换到 RGB 时使用 P010 的全部 10 位数据，最终编码保持现有 8 位输出。

| 输入 | 上传给 GPU 的数据 |
| --- | --- |
| YUYV / UYVY | 三个 8 位 Y/U/V 平面，U/V 宽度减半、高度保持；每一行色度都保留 |
| NV12 | 三个 8 位平面，拆开 UV，保持源 4:2:0 样本 |
| P010 | 三个 16 位 little-endian 平面，保留 10 个有效高位，包括原来丢掉的低两位 |
| I420 | 三个原始 8 位 4:2:0 平面 |
| MJPEG | 按 JPEG SOF 中的实际采样因子分配缓冲区；解码并复制原始组件尺寸，不主动降到 4:2:0 |
| RGB / BGR | 保持原始 RGB / BGR |

`GpuVideoFrame.chromaWidth/chromaHeight` 描述色度几何。8 位平面布局的旧枚举值仍为 `I420=0`，但实际色度尺寸由这些字段决定，可以表示 4:2:2、4:4:4 等。MJPEG 灰度输入使用中性色度。

P010 的每个 16 位样本以 GLES2 `GL_LUMINANCE_ALPHA` 的两个 8 位通道上传，两字节均参与线性过滤，然后在高精度 fragment shader 中重建归一化 10 位数值。矩阵及 TV/Full range 偏移按照 10 位码值计算（例如 TV 黑/白 64/940、色度中点 512）。具备 fragment highp 的设备使用 highp；其他设备回退 mediump。最终 RGB framebuffer 仍为 8 位。

既有 `nativeDecodeToI420` / `nativeDecodeMjpegToI420` 保留为显式 I420 兼容接口和回归参考；实时预览和录像都使用新的保留采样路径。JPEG SOI/EOI 修复及解码诊断继续有效。

这消除了进入 RGB 之前的额外色度降采样及位深截断，不代表端到端无损：JPEG 本身是有损压缩，矩阵计算和 8 位 RGB 输出有量化，现有硬件 H.264/HEVC 编码通常输出 8 位 4:2:0。

保留更多样本会增加纹理上传量：YUYV/UYVY 从每像素 1.5 字节增加到 2 字节，P010 从 1.5 字节增加到 3 字节；JPEG 按源采样分配。继续使用独占 direct buffer 和 32 MiB 缓存限制。

## 验证

- 62 个 JVM 测试全部通过，包括 JPEG 色度几何、奇数尺寸、无 SOI、截断/非法头、队列传递及 10 位矩阵码值。
- vivo V2338A 上独立 `app_process` 执行 8 个 JNI/GPU/编码测试，全部通过，没有安装 APK 或停止主 APP。
- 原始格式逐样本内容、输入不被改写、目标缓冲区前后保护字节、短输入、非 direct 和只读目标均验证。
- 4:2:2 测试确认上下行不同色度在 RGB 中仍不同，避免旧上下行平均导致的细节损失。
- P010 测试仅将输入码值从 512 改为 515，GPU RGB 的红/蓝输出发生变化，证实低两位参与转换；64/940 仍正确显示黑/白。
- 真正的 4:2:0、4:2:2、4:4:4 JPEG 经新路径解码后，原始组件做平均与旧 I420 输出一致；缺 SOI/EOI 及缓冲区边界测试通过。
- 现有矩阵、源范围、direct buffer 路径及 H.264/HEVC Surface 编码颜色设置回归通过。
- Debug APK、AndroidTest APK 构建及 `git diff --check` 通过。

真机日志位于忽略的 `build/perf-diagnostics/source-sampling-smoke/results.log`。这次验证覆盖功能和像素结果，未重新测量 USB 采集 1080p60 的持续性能。

测试 JPEG 是用本地 FFmpeg `testsrc=size=32x24:rate=1` 生成的测试图，`-frames:v 1 -c:v mjpeg -pix_fmt yuvj420p/yuvj422p/yuvj444p -q:v 2 -update 1`，位于 `app/src/androidTest/resources/mjpeg/`。
