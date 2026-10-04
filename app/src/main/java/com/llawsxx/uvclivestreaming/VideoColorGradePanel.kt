package com.llawsxx.uvclivestreaming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.llawsxx.uvclivestreaming.recording.GradePrimaries
import com.llawsxx.uvclivestreaming.recording.GradeTransfer
import com.llawsxx.uvclivestreaming.recording.VideoColorGradeSettings
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun VideoColorGradePanel(settings: VideoColorGradeSettings, onChange: (VideoColorGradeSettings) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) })
        Text("视频调色（LUT）")
    }
    if (!settings.enabled) return
    Column {
        Text("实时应用到预览、录像和推流；6500 K / 色调 0 为中性白平衡。", style = MaterialTheme.typography.bodySmall)
        GradeChoice("输入 Transfer", GradeTransfer.entries, settings.transfer, { it.label }) { onChange(settings.copy(transfer = it)) }
        GradeChoice("输入色域", GradePrimaries.entries, settings.primaries, { it.label }) { onChange(settings.copy(primaries = it)) }
        Text("按输入信号选择 Transfer 和色域；编码目标颜色仍由下方设置控制。", style = MaterialTheme.typography.bodySmall)
        GradeChoice("LUT 精度", listOf(33, 65), settings.lutSize, { "$it³" }) { onChange(settings.copy(lutSize = it)) }
        GradeSlider("曝光", settings.exposureEv, -4f..4f, "EV") { onChange(settings.copy(exposureEv = (it * 100).roundToInt() / 100f)) }
        GradeSlider("对比度", settings.contrast, 0f..2f, "×") { onChange(settings.copy(contrast = (it * 100).roundToInt() / 100f)) }
        GradeSlider("白平衡色温", settings.temperatureKelvin.toFloat(), 2000f..12000f, "K") {
            onChange(settings.copy(temperatureKelvin = (it / 50).roundToInt() * 50))
        }
        GradeSlider("白平衡色调（正值偏洋红）", settings.tint, -100f..100f, "") { onChange(settings.copy(tint = it.roundToInt().toFloat())) }
        GradeSlider("饱和度", settings.saturation, 0f..2f, "×") { onChange(settings.copy(saturation = (it * 100).roundToInt() / 100f)) }
        OutlinedButton(onClick = {
            onChange(VideoColorGradeSettings(enabled = true, transfer = settings.transfer,
                primaries = settings.primaries, lutSize = settings.lutSize))
        }) { Text("重置调色") }
    }
}

@Composable
private fun GradeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    Text("$label：${String.format(Locale.US, "%.2f", value)} $unit", style = MaterialTheme.typography.bodySmall)
    Slider(value = value.coerceIn(range.start, range.endInclusive), valueRange = range, onValueChange = onChange)
}

@Composable
private fun <T> GradeChoice(label: String, options: List<T>, value: T, text: (T) -> String, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row {
        OutlinedButton(onClick = { expanded = true }) { Text("$label：${text(value)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(text(option)) }, onClick = {
                expanded = false; onChange(option)
            }) }
        }
    }
}
