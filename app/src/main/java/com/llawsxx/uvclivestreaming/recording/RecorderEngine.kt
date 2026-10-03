package com.llawsxx.uvclivestreaming.recording

import android.view.Surface

interface RecorderEngine {
    fun start(preview: Surface?, previewEnabled: Boolean, previewRotationDegrees: Int)
    fun stop(onComplete: () -> Unit)
    fun forceRelease()
    fun updatePreview(surface: Surface?, enabled: Boolean, previewRotationDegrees: Int)
    fun switchCamera(cameraId: String)
    fun updateCameraControls(updated: RecordingConfig)
}
