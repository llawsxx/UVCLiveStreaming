package com.llawsxx.uvclivestreaming.recording

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGopPreferencesTest {
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
}
