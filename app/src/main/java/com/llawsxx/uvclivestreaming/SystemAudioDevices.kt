package com.llawsxx.uvclivestreaming

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.llawsxx.uvclivestreaming.recording.SystemAudioDevice

@Composable
internal fun rememberSystemAudioInputDevices(): List<SystemAudioDevice> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    fun query() = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).map(SystemAudioDevice::from)
        .sortedWith(compareBy({ it.type }, { it.id }))
    var devices by remember(manager) { mutableStateOf(query()) }
    DisposableEffect(manager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { devices = query() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { devices = query() }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    return devices
}
