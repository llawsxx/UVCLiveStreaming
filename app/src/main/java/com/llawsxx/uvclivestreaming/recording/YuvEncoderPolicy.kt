package com.llawsxx.uvclivestreaming.recording

internal object YuvEncoderPolicy {
    fun rejection(config: RecordingConfig, testCard: Boolean, grading: Boolean): String? = when {
        testCard -> "测试卡使用 Surface 输入"
        grading -> "YUV 直送不支持录制调色"
        config.width % 2 != 0 || config.height % 2 != 0 -> "YUV 编码要求偶数宽高"
        config.dynamicRange != VideoDynamicRange.SDR -> "YUV 直送目前仅支持 SDR 8-bit 编码"
        config.colorStandard == VideoColorStandard.BT2020 -> "YUV 直送不执行色域转换"
        config.colorTransfer !in listOf(VideoColorTransfer.DEFAULT, VideoColorTransfer.BT601,
            VideoColorTransfer.BT709, VideoColorTransfer.SRGB) -> "YUV 直送不执行传递函数转换"
        else -> null
    }
}
