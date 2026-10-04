package com.llawsxx.uvclivestreaming

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

internal enum class UsbSettingsTab(val label: String) {
    DEVICE("设备"), COLOR("视频调色"), DSP("音频 DSP"), ENCODING("编码"),
    OUTPUT("输出"), TIMING("时序"), DISPLAY("显示"),
}

/** Only settings scroll. Both panels keep their composition when orientation changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsbCameraWorkspace(
    aspectRatio: Float,
    includeAudio: Boolean,
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
    preview: @Composable (Modifier, () -> Unit) -> Unit,
    fullscreenControls: @Composable () -> Unit,
    audioMeter: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    settings: @Composable (UsbSettingsTab) -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var selectedTab by rememberSaveable { mutableStateOf(UsbSettingsTab.DEVICE) }
    var showFullscreenControls by remember { mutableStateOf(false) }
    val scrollStates = UsbSettingsTab.entries.map { rememberScrollState() }
    Layout(
        modifier = if (fullscreen) Modifier.fillMaxSize() else
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(8.dp),
        content = {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BoxWithConstraints(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val width = minOf(maxWidth, maxHeight * aspectRatio)
                    Box(Modifier.width(width).height(width / aspectRatio)) {
                        preview(Modifier.fillMaxSize()) { showFullscreenControls = !showFullscreenControls }
                        if (showFullscreenControls) {
                            Row(Modifier.align(Alignment.TopEnd).padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                fullscreenControls()
                                if (fullscreen) Button(onClick = onExitFullscreen,
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.Black.copy(alpha = .65f), contentColor = Color.White)) {
                                    Text("退出全屏", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                if (includeAudio && !fullscreen) audioMeter()
            }
            Column(Modifier.fillMaxSize()) {
                PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 0.dp) {
                    UsbSettingsTab.entries.forEach { tab ->
                        Tab(selected = selectedTab == tab, onClick = { selectedTab = tab },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(tab.label, maxLines = 1) })
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(scrollStates[selectedTab.ordinal]).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Local dropdown/slider state belongs to its tab, not its position.
                    key(selectedTab) { settings(selectedTab) }
                    Spacer(Modifier.height(8.dp))
                }
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
            }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val previewWidth = if (landscape && !fullscreen) (width * .44f).roundToInt() else width
        // Reserve space for the tabs/actions even with tall sources or an open keyboard.
        val previewHeight = if (landscape || fullscreen) height else
            minOf((previewWidth / aspectRatio).roundToInt() + if (includeAudio) 28.dp.roundToPx() else 0,
                (height * .40f).roundToInt())
        val monitor = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(previewWidth, previewHeight))
        val settingsWidth = if (landscape && !fullscreen) (width - previewWidth - gap).coerceAtLeast(0) else width
        val settingsHeight = if (landscape || fullscreen) height else (height - previewHeight - gap).coerceAtLeast(0)
        // Measuring hidden settings at fullscreen dimensions would clamp scroll offsets.
        val controls = if (fullscreen) null else
            measurables[1].measure(androidx.compose.ui.unit.Constraints.fixed(settingsWidth, settingsHeight))
        layout(width, height) {
            monitor.place(0, 0)
            // Leave settings composed but unplaced so fullscreen preserves tab/scroll state.
            controls?.place(if (landscape) previewWidth + gap else 0, if (landscape) 0 else previewHeight + gap)
        }
    }
}

@Composable
internal fun UsbCompactAudioMeter(levelDb: Float, peakDb: Float) {
    val level = levelDb.coerceIn(-60f, 0f)
    val peak = peakDb.coerceIn(-60f, 0f)
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { (level + 60f) / 60f },
            modifier = Modifier.weight(1f).height(6.dp), drawStopIndicator = {})
        Text(String.format(Locale.US, "%.0f / P %.0f dBFS", level, peak),
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            maxLines = 1, softWrap = false)
    }
}
