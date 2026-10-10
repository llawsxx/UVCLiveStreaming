package com.llawsxx.uvclivestreaming.recording

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGopPreferencesTest {
    @Test fun encoderRequestsPersistAndDefaultsRemoveExplicitSettings() {
        val prefs = preferences(mutableMapOf())
        assertEquals(null, ConfigPreferences.load(prefs).videoEncoderComplexity)
        ConfigPreferences.save(prefs, RecordingConfig(videoEncoderComplexity = 0,
            videoEncoderProfile = 8, videoEncoderLevel = 4096))
        val loaded = ConfigPreferences.load(prefs)
        assertEquals(0, loaded.videoEncoderComplexity)
        assertEquals(8, loaded.videoEncoderProfile)
        assertEquals(4096, loaded.videoEncoderLevel)
        ConfigPreferences.save(prefs, RecordingConfig())
        val defaults = ConfigPreferences.load(prefs)
        assertEquals(null, defaults.videoEncoderComplexity)
        assertEquals(null, defaults.videoEncoderProfile)
        assertEquals(null, defaults.videoEncoderLevel)
    }
    @Test fun httpUploadSettingsPersistAndClamp() {
        val values = mutableMapOf<String, Any?>()
        val prefs = preferences(values)
        assertEquals(false, ConfigPreferences.load(prefs).httpUploadEnabled)
        assertEquals(60, ConfigPreferences.load(prefs).httpUploadCacheSeconds)
        assertEquals(false, ConfigPreferences.load(prefs).httpAutoBitrateEnabled)
        assertEquals(1_000_000, ConfigPreferences.load(prefs).httpMinVideoBitrate)
        ConfigPreferences.save(prefs, RecordingConfig(httpUploadEnabled = true,
            httpUploadUrl = "http://host:8080/upload/live", httpUploadCacheSeconds = 90,
            httpAutoBitrateEnabled = true, httpMinVideoBitrate = 600_000))
        val loaded = ConfigPreferences.load(prefs)
        assertEquals(true, loaded.httpUploadEnabled)
        assertEquals("http://host:8080/upload/live", loaded.httpUploadUrl)
        assertEquals(90, loaded.httpUploadCacheSeconds)
        assertEquals(true, loaded.httpAutoBitrateEnabled)
        assertEquals(600_000, loaded.httpMinVideoBitrate)
        values["httpUploadChunkSeconds"] = 99; values["httpUploadCacheSeconds"] = 1
        assertEquals(30, ConfigPreferences.load(prefs).httpUploadCacheSeconds)
        ConfigPreferences.save(prefs, ConfigPreferences.load(prefs))
        assertEquals(false, values.containsKey("httpUploadChunkSeconds"))
    }
    @Test fun audioDelayAndMuxingWindowPersistAndClampToTheirSupportedRanges() {
        val data = mutableMapOf<String, Any?>()
        val prefs = preferences(data)
        assertEquals(0, ConfigPreferences.load(prefs).audioDelayMs)
        assertEquals(64, ConfigPreferences.load(prefs).muxingQueueSize)
        for (delay in listOf(-500, -200, 0, 200, 500)) {
            for (window in listOf(0, 1, 64, 1024)) {
                ConfigPreferences.save(prefs, RecordingConfig(audioDelayMs = delay, muxingQueueSize = window))
                val restored = ConfigPreferences.load(prefs)
                assertEquals(delay, restored.audioDelayMs)
                assertEquals(window, restored.muxingQueueSize)
            }
        }
        data["audioDelayMs"] = -1000; data["muxingQueueSize"] = 5000
        assertEquals(-500, ConfigPreferences.load(prefs).audioDelayMs)
        assertEquals(1024, ConfigPreferences.load(prefs).muxingQueueSize)
    }

    @Test fun rtmpSendTimeoutDefaultsToTenSecondsAndSurvivesRestart() {
        val prefs = preferences(mutableMapOf())
        assertEquals(10, ConfigPreferences.load(prefs).rtmpSendTimeoutSeconds)
        for (timeout in listOf(3, 5, 10, 15, 30)) {
            ConfigPreferences.save(prefs, RecordingConfig(rtmpSendTimeoutSeconds = timeout))
            assertEquals(timeout, ConfigPreferences.load(prefs).rtmpSendTimeoutSeconds)
        }
    }

    @Test fun uacSelectionAndBitDepthPersistAndOldSettingsFollowVideo() {
        val prefs = preferences(mutableMapOf())
        assertEquals(null, ConfigPreferences.load(prefs).usbAudioDevice)
        assertEquals(UsbAudioBitDepth.AUTO, ConfigPreferences.load(prefs).usbAudioBitDepth)
        val mic = UsbAudioDevice("/dev/bus/usb/001/003", 1234, 5678, "External microphone")
        ConfigPreferences.save(prefs, RecordingConfig(usbAudioDevice = mic, usbAudioBitDepth = UsbAudioBitDepth.PCM24))
        assertEquals(mic, ConfigPreferences.load(prefs).usbAudioDevice)
        assertEquals(UsbAudioBitDepth.PCM24, ConfigPreferences.load(prefs).usbAudioBitDepth)
        ConfigPreferences.save(prefs, RecordingConfig(usbAudioDevice = null, usbAudioBitDepth = UsbAudioBitDepth.PCM32))
        assertEquals(null, ConfigPreferences.load(prefs).usbAudioDevice)
        assertEquals(UsbAudioBitDepth.PCM32, ConfigPreferences.load(prefs).usbAudioBitDepth)
    }

    @Test fun virtualDevicePatternAndFractionalModeSurviveRestart() {
        val prefs = preferences(mutableMapOf())
        val card = TestCardSettings(TestCardPattern.RESOLUTION, 720, 480, 60000.0 / 1001, noisePercent = 35)
        ConfigPreferences.save(prefs, RecordingConfig(cameraId = TestCardSettings.DEVICE_ID, testCard = card))
        val loaded = ConfigPreferences.load(prefs)
        assertEquals(TestCardSettings.DEVICE_ID, loaded.cameraId)
        assertEquals(card, loaded.testCard)
        assertEquals(TestCardSettings(), ConfigPreferences.load(preferences(mutableMapOf())).testCard)
        prefs.edit().putString("testCardFps", "NaN").apply()
        assertEquals(TestCardSettings(), ConfigPreferences.load(prefs).testCard)
    }

    private fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "remove" -> { values.remove(args!![0]); proxy }
                method.name == "clear" -> { values.clear(); proxy }
                method.name == "apply" -> null
                method.name == "commit" -> true
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when {
                method.name == "getAll" -> HashMap(values)
                method.name == "edit" -> editor
                method.name == "contains" -> values.containsKey(args!![0])
                method.name.startsWith("get") -> values[args!![0]] ?: args[1]
                else -> error("Unexpected preference method ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun oldIntegerSecondsAreLoadedWithoutTypeMismatch() {
        val prefs = preferences(mutableMapOf("videoKeyFrameIntervalSeconds" to 3))
        assertEquals(3f, ConfigPreferences.load(prefs).videoKeyFrameIntervalSeconds, 0f)
    }

    @Test fun fractionalAndZeroSecondsSurviveSavingAndReloading() {
        val values = mutableMapOf<String, Any?>("videoKeyFrameIntervalSeconds" to 2)
        val prefs = preferences(values)
        for (interval in listOf(0.5f, 0f, 2.25f)) {
            ConfigPreferences.save(prefs, RecordingConfig(videoKeyFrameIntervalSeconds = interval))
            assertEquals(interval, values["videoKeyFrameIntervalSeconds"])
            assertEquals(interval, ConfigPreferences.load(prefs).videoKeyFrameIntervalSeconds, 0f)
        }
    }

    @Test fun audioInputAndDeviceIdentitySurviveSavingAndReloading() {
        val prefs = preferences(mutableMapOf())
        val input = SystemAudioInputSettings(AudioInputSource.VOICE_RECOGNITION,
            SystemAudioDevice(37, 15, "bottom", "Phone"))
        ConfigPreferences.save(prefs, RecordingConfig(usbAudioInput = UsbAudioInput.SYSTEM, systemAudioInput = input))
        val loaded = ConfigPreferences.load(prefs)
        assertEquals(UsbAudioInput.SYSTEM, loaded.usbAudioInput)
        assertEquals(input, loaded.systemAudioInput)
        ConfigPreferences.save(prefs, loaded.copy(usbAudioInput = UsbAudioInput.USB,
            systemAudioInput = input.copy(device = null)))
        assertEquals(null, ConfigPreferences.load(prefs).systemAudioInput.device)
    }

    @Test fun oldPreferencesContinueUsingUsbAudioAndDefaultSystemMicrophone() {
        val loaded = ConfigPreferences.load(preferences(mutableMapOf()))
        assertEquals(UsbAudioInput.USB, loaded.usbAudioInput)
        assertEquals(SystemAudioInputSettings(), loaded.systemAudioInput)
    }
}
