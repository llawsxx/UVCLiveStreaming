# HTTP 网络自适应视频码率

USB 页 → 输出 → HTTP 远程分块上传 → 网络自适应视频码率。
开关默认关闭，最低视频码率默认 1000 kbps；视频编码设置中的码率是上限。
设置保存后在下次启动采集时生效，最低值应介于 100 kbps 与上限之间。
输出页显示当前目标、上下限、成功调整次数和调整原因。

生产引擎每秒读取上传统计，以单调时间计时，通过
`MediaCodec.PARAMETER_KEY_VIDEO_BITRATE` 调整正在运行的编码器。
分辨率、帧率、HTTP 会话和 TS 分段序号继续沿用；旧分段保持原编码码率。
同时录制和 RTMP 使用同一个视频编码器，因此一起变化。
HTTP 停止且其他输出继续时恢复配置的上限；重新启动 HTTP 重新评估。
编码器拒绝参数更新时提示并停止本轮自动调整，保留当前输出。

## 判断与调整

- 启动前 5 秒评估；拥堵条件连续维持 3 秒后才调整，两次调整至少相隔 5 秒。
- 原始待确认队列达到 3 秒并持续增长，或达到 6 秒且未明显消退。
- 待确认队列至少 2 秒、至少 3 秒没有新的有效 ACK。
- 原始分段缓存发生淘汰；保留历史和已确认分段的补传队列／淘汰不作为原始上传积压。
- 最近 20 秒内至少两次有效下载慢补救，且最新一次在 5 秒内。
- 每次降低约 25%；各可用服务器都有新鲜的下载速率反馈时，参考其合计吞吐量，
  预留 15% 余量和 6% TS 封装预算，再扣除音频码率；单次最多降低 50%。
- 反馈未知、超过 10 秒或目录反馈已不再携带速率时，不使用旧值／3 Mbps 默认值判断。
  单服务器从上传 ACK 读取 store 反馈；多服务器沿用独立的持久反馈连接。
- 队列不超过 1.5 秒、ACK 持续推进、没有近期补救／原始块淘汰且有可用服务器，
  连续稳定 20 秒后尝试提高 10%。每次提高后重新累计稳定时间。
  某台服务器不可用，但另一台能持续清空队列时，也允许恢复。
- 实际下载速率本身不是容量上限：低复杂度画面可能自然产生很少数据。
  因此只有确认拥堵后才用反馈选降幅，恢复时主动小幅探测。

自动模式配合默认码率模式时，优先选择支持的 CBR，其次 VBR。
显式 CBR／VBR 设置保持用户选择。实际输出取决于编码器、图像复杂度和码率模式，
界面显示的是目标值；极高噪声输入可能超过编码器在最大量化值时能达到的压缩率。
此功能使用现有 ACK／下载反馈／补救协议，服务器程序无需更新。

## 验证与重现

`HttpAdaptiveBitrateControllerTest` 验证降幅、冷却、最低／最高值、稳定恢复、
过期／不完整反馈、原始与补传缓存区分、短暂抖动、重复／中断采样、
单台不可用服务器，以及模拟真实分段队列在 3 Mbps → 8 Mbps 网络下的收敛与恢复。
上传测试验证反馈失效、单服务器 ACK 反馈和 TCP 连接复用。

`AdaptiveBitrateDeviceTest` 运行 AVC／HEVC 的 Surface 和 YUV Image 输入，
在同一编码器中请求 6 → 1.5 → 6 → 1.5 Mbps，使用 CBR 和动态噪点。
每段 3 秒，舍弃首秒后按输出 PTS 统计实际码率，检查恢复后再次降低、帧数和 EOS。
首段可能存在编码器预热，因此两段低码率均与预热后的高码率段比较。

构建 APP 与 androidTest APK，将 `AdaptiveBitrateSmoke.java` 编译为 dex，
把 dex、`app/build/intermediates/apk/debug/app-debug.apk` 和
`app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk` 推送到
`/data/local/tmp/uvclive-adaptive-bitrate/`，然后执行：

```sh
CLASSPATH=/data/local/tmp/uvclive-adaptive-bitrate/classes.dex:/data/local/tmp/uvclive-adaptive-bitrate/app-debug.apk:/data/local/tmp/uvclive-adaptive-bitrate/app-debug-androidTest.apk app_process /system/bin AdaptiveBitrateSmoke
```

该独立进程无需覆盖安装或停止主 APP。主机日志位于忽略目录
`build/perf-diagnostics/adaptive-bitrate/`。

### vivo V2338A 实测（2026-10-10）

52 项相关 JVM 测试通过，正式 ARM64 APP／Android 测试 APK 构建通过。
独立进程真实编码测试四组通过，每组输出完整 360 帧并收到 EOS；
主 APP 测试前后 PID 均为 19064。
目标顺序为 6 → 1.5 → 6 → 1.5 Mbps，实际测量如下：

| 编码器／输入 | 初始高码率（预热） | 降低 | 恢复 | 再次降低 |
| --- | ---: | ---: | ---: | ---: |
| AVC Surface CBR | 2.802 | 1.266 | 5.975 | 1.253 |
| AVC YUV Image CBR | 1.585 | 1.405 | 6.337 | 1.416 |
| HEVC Surface CBR | 3.847 | 1.570 | 6.991 | 1.644 |
| HEVC YUV Image CBR | 1.617 | 1.645 | 5.918 | 1.582 |

单位为 Mbps。初始阶段存在预热，CBR 也可能偏离目标；表中输出属于短期噪点测试，
不代表所有画面和手机都能精准达到目标。此前 AVC Surface 默认模式的同类三段测试
得到 3.994 → 3.307 → 8.143 Mbps，降幅不足，因此自动模式默认优先使用 CBR。
网络变化下的控制收敛使用 JVM 分段队列模型验证；没有在手机上完成真实广域网限速联调。
