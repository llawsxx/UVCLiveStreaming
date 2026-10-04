# MediaCodec 编码目标颜色

USB 界面新增“编码目标颜色”，在初始化编码器前向 `MediaFormat` 写入所选颜色参数，配置自动保存到 USB 界面偏好及启动时的 `RecordingConfig`。

- 颜色标准：默认、BT.709、BT.601 NTSC／PAL、BT.2020。`KEY_COLOR_STANDARD` 同时描述 Primaries 和 RGB→YUV 矩阵；标准 API 不提供二者完全独立的选择，BT.2020 选项为 NCL。
- Transfer：默认、SDR Video、Linear、PQ、HLG。多个 SDR ISO transfer 值在 MediaCodec 中映射到同一值，因此编码设置只展示一个 SDR 选项。它不会将输入图像自动转换为 HDR 或改变色域。
- Range：默认、TV／Limited、PC／Full。

默认项不写入对应参数，保持现有编码器默认行为。运行期间控件禁用，修改在下次启动录像／串流时生效；共享同一编码器的录像及各串流输出使用相同配置。源 YUV→RGB 矩阵和源范围继续用于输入解释，H.26X VUI 重写继续独立控制编码后的标记。VUI 重写不改变像素，需与实际编码目标匹配。

`UsbVideoDiagnostics` 在初始化时记录 `Encoder color request`，输出格式到达时、VUI 重写前记录 `Encoder reported colors`，用于识别设备是否调整或忽略请求。输出格式报告的参数不能单独证明内部 RGB→YUV 转换实际采用的系数；严格确认需解码测试图并检查像素。

ARM64 APP 和 Android 测试 APK 构建通过。在 vivo V2338A 的独立 `app_process` 中，生产颜色配置 helper 配合真实 Surface 编码测试通过：`c2.qti.avc.encoder` 和 `c2.qti.hevc.encoder` 均接受并报告 BT.709／SDR Video／Limited，分别输出全部 3 帧并达到 EOS；不同的 VUI 重写选项不会影响传入编码器的参数。默认配置不写颜色键。测试使用 256×256；此前 64×64 测试不满足设备能力，AVC 的宽高范围下限为 128、HEVC 为 96。未将此结果泛化为 Full／PQ／HLG 或其他设备均受支持。

未覆盖安装或停止当前 APP。最新 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，SHA256 `DC2A0D69FBE56927A9D444C068AED0322AEF297AEE1CBD0F95CB93AA0F8BDD0A`。
