package com.llawsxx.uvclivestreaming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.llawsxx.uvclivestreaming.recording.AudioDspSettings
import java.util.Locale

@Composable
internal fun AudioDspPanel(settings: AudioDspSettings, enabled: Boolean, onChange: (AudioDspSettings) -> Unit) {
    DspToggle("音频 DSP", settings.enabled, enabled) { onChange(settings.copy(enabled = it)) }
    if (!settings.enabled) return
    Column {
        Text("处理后的音频用于监听、录像和推流；参数可在运行中调节。", style = MaterialTheme.typography.bodySmall)
        DspToggle("响度标准化", settings.loudnessEnabled, enabled) { onChange(settings.copy(loudnessEnabled = it)) }
        if (settings.loudnessEnabled) {
            DspSlider("目标响度", settings.targetLufs, -70f..-5f, "LUFS", enabled) { onChange(settings.copy(targetLufs = it)) }
            DspSlider("目标响度范围", settings.loudnessRangeLu, 1f..50f, "LU", enabled) { onChange(settings.copy(loudnessRangeLu = it)) }
            DspSlider("峰值上限", settings.loudnessPeakDb, -9f..0f, "dBFS", enabled) { onChange(settings.copy(loudnessPeakDb = it)) }
            DspSlider("响度前瞻", settings.loudnessLookaheadMs, 5f..50f, "ms", enabled) { onChange(settings.copy(loudnessLookaheadMs = it)) }
            DspSlider("响度更新间隔", settings.loudnessUpdateMs, 100f..3000f, "ms", enabled) { onChange(settings.copy(loudnessUpdateMs = it)) }
            DspToggle("仅提升响度（峰值保护可能不再压低增益）", settings.boostOnly, enabled) { onChange(settings.copy(boostOnly = it)) }
        }
        DspToggle("限制器", settings.limiterEnabled, enabled) { onChange(settings.copy(limiterEnabled = it)) }
        if (settings.limiterEnabled) {
            DspSlider("限制器输入增益", settings.limiterInputDb, -24f..24f, "dB", enabled) { onChange(settings.copy(limiterInputDb = it)) }
            DspSlider("限制阈值", settings.limiterThresholdDb, -30f..0f, "dBFS", enabled) { onChange(settings.copy(limiterThresholdDb = it)) }
            DspSlider("输出峰值上限", settings.limiterCeilingDb, -30f..0f, "dBFS", enabled) { onChange(settings.copy(limiterCeilingDb = it)) }
            DspSlider("释放时间", settings.limiterReleaseMs, 10f..10000f, "ms", enabled) { onChange(settings.copy(limiterReleaseMs = it)) }
            DspSlider("限制器前瞻", settings.limiterLookaheadMs, 0f..50f, "ms", enabled) { onChange(settings.copy(limiterLookaheadMs = it)) }
            DspToggle("自适应释放", settings.adaptiveRelease, enabled) { onChange(settings.copy(adaptiveRelease = it)) }
        }
    }
}

@Composable
private fun DspToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun DspSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String,
                      enabled: Boolean, onChange: (Float) -> Unit) {
    Text("$label：${String.format(Locale.US, "%.1f", value)} $unit", style = MaterialTheme.typography.bodySmall)
    Slider(value = value.coerceIn(range.start, range.endInclusive), valueRange = range,
        enabled = enabled, onValueChange = onChange)
}
