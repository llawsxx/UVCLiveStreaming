package com.llawsxx.uvclivestreaming.recording

import java.io.Serializable
import kotlin.math.floor
import kotlin.math.roundToLong
import java.util.concurrent.locks.LockSupport
import android.content.SharedPreferences

enum class TestCardPattern(val label: String) : Serializable {
    SMPTE("SMPTE 风格（75% 三段彩条）"),
    EBU75("EBU 75% 彩条"), EBU100("EBU 100% 彩条"),
    LEVELS("灰阶／渐变／黑白电平"), RESOLUTION("分辨率（细线／楔形／棋盘）"),
    MOTION("帧率（运动标尺／逐帧闪烁）"), PM5544("PM5544 风格（圆形综合图）")
}

data class TestCardSettings(
    val pattern: TestCardPattern = TestCardPattern.SMPTE,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Double = 60.0,
    val noisePercent: Int = 0,
) : Serializable {
    val valid: Boolean get() = width in 1..3840 && height in 1..2160 && fps.isFinite() && fps in 1.0..240.0 && noisePercent in 0..100
    companion object { const val DEVICE_ID = "virtual:test-card" }
}

/** Absolute deadlines retain fractional fps. Late frames are skipped rather than emitted in a burst. */
internal class TestCardTimeline(val fps: Double, val startNs: Long) {
    init { require(fps.isFinite() && fps in 1.0..240.0) }
    fun timestamp(index: Long): Long = startNs + (index * 1_000_000_000.0 / fps).roundToLong()
    fun latestIndex(nowNs: Long): Long = floor(((nowNs - startNs).coerceAtLeast(0) + 0.5) * fps / 1_000_000_000.0).toLong()
}

internal data class TestCardFrame(val pattern: TestCardPattern, val fps: Double, val index: Long, val noisePercent: Int = 0)

internal object TestCardPreferences {
    fun load(p: SharedPreferences): TestCardSettings = TestCardSettings(
        runCatching { TestCardPattern.valueOf(p.getString("testCardPattern", "SMPTE").orEmpty()) }.getOrDefault(TestCardPattern.SMPTE),
        p.getInt("testCardWidth", 1920), p.getInt("testCardHeight", 1080),
        p.getString("testCardFps", "60")?.toDoubleOrNull() ?: 60.0,
        p.getInt("testCardNoisePercent", 0).coerceIn(0, 100),
    ).takeIf { it.valid } ?: TestCardSettings()

    fun save(editor: SharedPreferences.Editor, settings: TestCardSettings): SharedPreferences.Editor = editor
        .putString("testCardPattern", settings.pattern.name).putInt("testCardWidth", settings.width)
        .putInt("testCardHeight", settings.height).putString("testCardFps", settings.fps.toString())
        .putInt("testCardNoisePercent", settings.noisePercent)
}

internal fun runTestCardFrames(settings: TestCardSettings, startNs: Long, running: () -> Boolean,
                              render: (GpuVideoFrame) -> Unit) {
    require(settings.valid)
    val timeline = TestCardTimeline(settings.fps, startNs)
    var nextIndex = 0L
    while (running() && !Thread.currentThread().isInterrupted) {
        val remaining = timeline.timestamp(nextIndex) - System.nanoTime()
        if (remaining > 0) { LockSupport.parkNanos(remaining); continue }
        val index = maxOf(nextIndex, timeline.latestIndex(System.nanoTime()))
        render(GpuVideoFrame(null, settings.width, settings.height, timeline.timestamp(index),
            layout = GpuVideoFrame.RGB, fullRange = true,
            testCard = TestCardFrame(settings.pattern, settings.fps, index, settings.noisePercent)))
        nextIndex = index + 1
    }
}
