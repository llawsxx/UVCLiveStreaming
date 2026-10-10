# 编码器复杂度、Profile、Level

USB 页 → 编码提供 `KEY_COMPLEXITY`、`KEY_PROFILE`、`KEY_LEVEL` 设置，
用于 USB 视频和测试卡的录像及串流，Surface／YUV 输入共用同一配置。

三项默认不向 MediaCodec 指定参数。复杂度留空表示默认，0 是有效的显式值；
界面读取当前编码器的复杂度范围和 Profile 能力，Level 选项按 Profile 能力筛选。
选择显式 Profile、Level 保持自动时，按实际输出分辨率、帧率和码率选择能容纳它们的最低可用等级，
向 MediaCodec 同时传入 Profile 和 Level；平台无法推导该 Profile 的等级限制时，使用声明的最高等级。
显式选择的 Level 是提示，编码器可能按分辨率／帧率／码率调整实际输出等级。
不支持的请求在开始录像／串流时提示，不静默删掉参数。录制期间禁止修改。
设置自动保存，切换 H.264／HEVC 时三项恢复默认，避免混用不同编码格式的参数值。

Android 接口说明：<https://developer.android.com/reference/android/media/MediaFormat#KEY_COMPLEXITY>、
<https://developer.android.com/reference/android/media/MediaFormat#KEY_PROFILE>、
<https://developer.android.com/reference/android/media/MediaFormat#KEY_LEVEL>。

JVM 测试覆盖默认参数、复杂度 0、Profile 与 Level 配对、范围校验、HEVC Tier 筛选和配置持久化。
`VideoEncoderSettingsDeviceTest` 对 AVC／HEVC 的 Surface／YUV 输入执行真实编码并检查帧数和 EOS。
`EncoderSettingsSmoke.java` 可打包为 dex，与主 APK、androidTest APK 一起通过独立 app_process 执行，
用于保留手机上当前运行的采集／推流进程。
