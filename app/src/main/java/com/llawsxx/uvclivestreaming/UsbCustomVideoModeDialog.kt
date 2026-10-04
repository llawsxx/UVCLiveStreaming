package com.llawsxx.uvclivestreaming

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun UsbCustomVideoModeDialog(initial: UsbCustomVideoMode, onDismiss: () -> Unit,
                                      onApply: (UsbCustomVideoMode) -> Unit) {
    var width by rememberSaveable { mutableStateOf(initial.width.toString()) }
    var height by rememberSaveable { mutableStateOf(initial.height.toString()) }
    var fps by rememberSaveable { mutableStateOf(initial.fps.toString()) }
    var format by rememberSaveable { mutableStateOf(initial.format) }
    var expanded by remember { mutableStateOf(false) }
    val mode = UsbCustomVideoMode(width.toIntOrNull() ?: 0, height.toIntOrNull() ?: 0,
        fps.toDoubleOrNull() ?: Double.NaN, format)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义采集模式") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(width, { width = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f),
                    label = { Text("宽度") }, singleLine = true, isError = mode.width !in 1..3840,
                    supportingText = { Text("1～3840") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(height, { height = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f),
                    label = { Text("高度") }, singleLine = true, isError = mode.height !in 1..2160,
                    supportingText = { Text("1～2160") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(fps, { fps = it.filter { c -> c.isDigit() || c == '.' } }, modifier = Modifier.fillMaxWidth(),
                label = { Text("帧率 fps") }, singleLine = true, isError = !mode.fps.isFinite() || mode.fps !in 1.0..240.0,
                supportingText = { Text("1～240，支持 59.94、29.97 等小数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("采集格式：${format.label}") }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    UsbCustomVideoMode.formats.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        format = option; expanded = false
                    }) }
                }
            }
            if (format in UsbCustomVideoMode.evenDimensionFormats && (mode.width % 2 != 0 || mode.height % 2 != 0)) {
                Text("当前格式要求宽度和高度为偶数。", color = MaterialTheme.colorScheme.error)
            }
            Text("未列出的帧率会尝试与设备协商；采集卡仍需支持所选格式和分辨率。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onApply(mode) }, enabled = mode.valid) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
