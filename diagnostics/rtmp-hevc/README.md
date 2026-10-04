# RTMP HEVC 配置头修复

## 问题与修复

ffplay 能识别 AAC 和 HEVC 类型，但 HEVC 没有分辨率，并报告 `No start code is found` / `unspecified size`。

原 `RtmpStreamSink.makeHevcDecoderConfiguration` 在 hvcC 固定部分多写一个零字节：带数组数量的标准头应是 23 字节，原实现是 24 字节。`general_level_idc` 应位于偏移 12，`lengthSizeMinusOne` 位于偏移 21 的低两位，`numOfArrays` 位于偏移 22；原实现全部后移。播放器因而读取错误的 NAL 长度和参数集数组数量，无法建立解码器。原实现还将 profile/level/位深写死。

`RtmpHevc` 现在从实际 SPS 提取 profile、tier、兼容标志、约束、level、temporal layers、chroma 和位深，生成标准布局，要求 VPS/SPS/PPS 完整。若 MediaCodec 提供现成 hvcC，验证数组结构并将输出长度字段统一为四字节。

视频帧先完整验证长度前缀，再尝试 Annex B，以避免 `00 00 01 01` 这类长度被误判为起始码。支持 1/2/3/4 字节输入长度，输出统一为四字节。无效 CSD 显示错误并不启动发布，无效视频帧记录日志并丢弃。

Enhanced RTMP 封装保持 `hvc1`：SequenceStart 是 `90 68 76 63 31 + hvcC`；CodedFrames 是 `91/A1 68 76 63 31 + CTS(3) + NALs`。SequenceStart 没有 CTS 字段。

## 验证（2026-10-04）

- 全部 57 个 JVM 测试通过，其中 6 个 HEVC 回归测试覆盖真实 Main/Main10 SPS、hvcC 布局、不同输入长度、分离 CSD、起始码歧义及损坏输入。
- 用测试生成的 `fixed.flv` 经 FFprobe 识别为 HEVC Main、256×256、yuv420p，解码 6 帧；与原 HEVC 的逐帧像素 MD5 全部相同。
- `shifted-header.flv` 重建旧偏移错误后，FFprobe 报 `Invalid NAL unit size in extradata` 和 `unspecified size`，无法打开解码器。具体日志措辞随 FFmpeg 版本变化。
- vivo V2338A 的 `c2.qti.hevc.encoder` 通过独立 Surface 编码测试；实际 CSD/6 帧经新实现封装成 `mediacodec.flv`，FFprobe 识别相同尺寸并解码全部 6 帧，FFmpeg `-xerror` 解码成功。
- 构建 Debug APK 和 AndroidTest APK 成功，`git diff --check` 通过。

手机测试通过 `/data/local/tmp/uvc-direct-buffer` 下的独立 `app_process` 运行，没有安装 APK 或停止主 APP。该测试验证手机编码输出与本地 FFmpeg 的格式兼容；尚未验证用户 RTMP 服务器的实际转发或 ffplay 端到端播放。

## 复现

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --no-daemon '-Pandroid.injected.build.abi=arm64-v8a'
ffprobe -v error -count_frames -show_entries stream=codec_name,profile,width,height,pix_fmt,nb_read_frames -of json build/perf-diagnostics/rtmp-hevc/fixed.flv
ffprobe -v warning -count_frames build/perf-diagnostics/rtmp-hevc/shifted-header.flv
ffmpeg -hide_banner -loglevel error -i app/src/test/resources/rtmp/hevc-main.hevc -an -f framemd5 -y build/perf-diagnostics/rtmp-hevc/reference.framemd5
ffmpeg -hide_banner -loglevel error -i build/perf-diagnostics/rtmp-hevc/fixed.flv -an -f framemd5 -y build/perf-diagnostics/rtmp-hevc/fixed.framemd5
```

比较 framemd5 的最后一列（像素 MD5），不要比较不同封装的时间基和时间戳。

测试资源为 FFmpeg `testsrc2=size=256x256:rate=30` 自行生成的 libx265 Annex B 码流，使用 `-preset ultrafast -x265-params bframes=0:aud=1:repeat-headers=1:pools=1:frame-threads=1:log-level=error`。Main 为 6 帧；Main10 为 `-pix_fmt yuv420p10le`、1 帧。

独立手机测试方法为 `GpuVideoPipelineTest.hevcSurfaceOutputProducesDecodableEnhancedFlv`，输出 `RTMP_HEVC_FLV_BASE64=...`，可在主机还原成 FLV 后执行：

```powershell
ffprobe -v error -count_frames -show_entries stream=codec_name,profile,width,height,pix_fmt,nb_read_frames -of json build/perf-diagnostics/rtmp-hevc/mediacodec.flv
ffmpeg -hide_banner -loglevel error -xerror -i build/perf-diagnostics/rtmp-hevc/mediacodec.flv -an -f null -
```
