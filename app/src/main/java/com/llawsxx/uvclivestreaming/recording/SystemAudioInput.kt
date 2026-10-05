package com.llawsxx.uvclivestreaming.recording

import android.media.AudioDeviceInfo
import android.content.SharedPreferences
import java.io.Serializable

enum class UsbAudioInput(val label: String) : Serializable {
    USB("采集卡音频（USB UAC）"), SYSTEM("系统麦克风（AudioRecord）")
}

data class SystemAudioDevice(
    val id: Int,
    val type: Int,
    val address: String,
    val name: String,
) : Serializable {
    val label: String get() = "${systemAudioDeviceTypeLabel(type)} · $name (#$id)"

    companion object {
        fun from(info: AudioDeviceInfo) = SystemAudioDevice(info.id, info.type, info.address, info.productName.toString())
    }
}

data class SystemAudioInputSettings(
    val source: AudioInputSource = AudioInputSource.MIC,
    val device: SystemAudioDevice? = null,
) : Serializable

/** Device IDs can change after reconnection; never accept an ID reused by another input. */
internal fun resolveSystemAudioDevice(selected: SystemAudioDevice, devices: List<SystemAudioDevice>): SystemAudioDevice? =
    devices.firstOrNull { it.id == selected.id && it.type == selected.type && it.address == selected.address && it.name == selected.name }
        ?: devices.filter { it.type == selected.type && it.address == selected.address && it.name == selected.name }.singleOrNull()

internal object SystemAudioInputPreferences {
    fun load(p: SharedPreferences): SystemAudioInputSettings {
        val id = p.getInt("systemAudioDeviceId", -1)
        val type = p.getInt("systemAudioDeviceType", -1)
        return SystemAudioInputSettings(
            source = runCatching { AudioInputSource.valueOf(p.getString("systemAudioSource", "MIC").orEmpty()) }
                .getOrDefault(AudioInputSource.MIC),
            device = if (id >= 0 && type >= 0) SystemAudioDevice(id, type,
                p.getString("systemAudioDeviceAddress", "").orEmpty(), p.getString("systemAudioDeviceName", "").orEmpty()) else null,
        )
    }

    fun save(editor: SharedPreferences.Editor, settings: SystemAudioInputSettings): SharedPreferences.Editor = editor
        .putString("systemAudioSource", settings.source.name)
        .putInt("systemAudioDeviceId", settings.device?.id ?: -1)
        .putInt("systemAudioDeviceType", settings.device?.type ?: -1)
        .putString("systemAudioDeviceAddress", settings.device?.address.orEmpty())
        .putString("systemAudioDeviceName", settings.device?.name.orEmpty())
}

internal fun systemAudioDeviceTypeLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
    AudioDeviceInfo.TYPE_TELEPHONY -> "电话音频"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦克风"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话麦克风"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 耳机麦克风"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频设备"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机麦克风"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音频附件"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI 音频"
    AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC 音频"
    AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC 音频"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟音频输入"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字音频输入"
    AudioDeviceInfo.TYPE_IP -> "网络音频输入"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "远程混音输入"
    AudioDeviceInfo.TYPE_BUS -> "音频总线"
    AudioDeviceInfo.TYPE_FM_TUNER -> "FM 调谐器"
    AudioDeviceInfo.TYPE_TV_TUNER -> "电视调谐器"
    else -> "音频输入（类型 $type）"
}

/** All timestamps use CLOCK_MONOTONIC, matching USB video and System.nanoTime(). */
internal class SystemAudioPcmClock(private val sampleRate: Int) {
    private var readFrames = 0L
    private var anchorNs: Long? = null

    init { require(sampleRate > 0) }

    fun timestamp(frames: Int, nowNs: Long, hardwareFrame: Long? = null, hardwareTimeNs: Long? = null): Long {
        require(frames > 0)
        val pts = if (hardwareFrame != null && hardwareFrame >= 0 && hardwareTimeNs != null && hardwareTimeNs > 0) {
            hardwareTimeNs + (readFrames - hardwareFrame) * 1_000_000_000L / sampleRate
        } else {
            val anchor = anchorNs ?: (nowNs - frames * 1_000_000_000L / sampleRate).also { anchorNs = it }
            anchor + readFrames * 1_000_000_000L / sampleRate
        }
        anchorNs = pts - readFrames * 1_000_000_000L / sampleRate
        readFrames += frames
        return pts
    }
}
