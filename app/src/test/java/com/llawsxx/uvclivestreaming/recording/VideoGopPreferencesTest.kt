package com.llawsxx.uvclivestreaming.recording

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGopPreferencesTest {
    @Test fun virtualDevicePatternAndFractionalModeSurviveRestart() {
        val prefs = preferences(mutableMapOf())
        val card = TestCardSettings(TestCardPattern.RESOLUTION, 720, 480, 60000.0 / 1001)
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
