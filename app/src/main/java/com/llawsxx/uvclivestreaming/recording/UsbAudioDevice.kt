package com.llawsxx.uvclivestreaming.recording

import android.content.SharedPreferences
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.Serializable

enum class UsbAudioBitDepth(val label: String, val nativeValue: Int) : Serializable {
    AUTO("自动（优先 32／24 bit）", 0), PCM16("16 bit PCM", 16), PCM24("24 bit PCM", 24), PCM32("32 bit PCM", 32)
}

/** A pinned UAC device. Null means follow the selected video device, preserving older settings. */
data class UsbAudioDevice(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val name: String,
) : Serializable {
    val label: String get() = "$name · $deviceName"
    companion object {
        fun from(device: UsbDevice) = UsbAudioDevice(device.deviceName, device.vendorId, device.productId,
            device.productName?.takeIf { it.isNotBlank() } ?: "USB 音频设备")
    }
}

/** USB addresses change on reconnect; ambiguous identical devices must be selected again. */
internal fun resolveUsbAudioDevice(selected: UsbAudioDevice, devices: List<UsbAudioDevice>): UsbAudioDevice? {
    val matches = devices.filter { it.vendorId == selected.vendorId && it.productId == selected.productId && it.name == selected.name }
    return matches.firstOrNull { it.deviceName == selected.deviceName } ?: matches.singleOrNull()
}

internal fun usbAudioInputDevices(manager: UsbManager): List<UsbAudioDevice> = manager.deviceList.values
    .filter { device -> (0 until device.interfaceCount).any { index ->
        val intf = device.getInterface(index)
        intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO && intf.interfaceSubclass == 2 &&
            (0 until intf.endpointCount).any { intf.getEndpoint(it).direction == UsbConstants.USB_DIR_IN }
    } }.sortedBy { it.deviceName }.map(UsbAudioDevice::from)

internal object UsbAudioDevicePreferences {
    fun bitDepth(p: SharedPreferences) = runCatching {
        UsbAudioBitDepth.valueOf(p.getString("uacBitDepth", "AUTO").orEmpty())
    }.getOrDefault(UsbAudioBitDepth.AUTO)
    fun load(p: SharedPreferences): UsbAudioDevice? {
        val path = p.getString("uacDeviceName", null)?.takeIf { it.isNotBlank() } ?: return null
        val vendor = p.getInt("uacVendorId", -1)
        val product = p.getInt("uacProductId", -1)
        if (vendor !in 0..65535 || product !in 0..65535) return null
        return UsbAudioDevice(path, vendor, product, p.getString("uacProductName", "USB 音频设备").orEmpty())
    }
    fun save(editor: SharedPreferences.Editor, device: UsbAudioDevice?): SharedPreferences.Editor = editor
        .putString("uacDeviceName", device?.deviceName).putInt("uacVendorId", device?.vendorId ?: -1)
        .putInt("uacProductId", device?.productId ?: -1).putString("uacProductName", device?.name)
}
