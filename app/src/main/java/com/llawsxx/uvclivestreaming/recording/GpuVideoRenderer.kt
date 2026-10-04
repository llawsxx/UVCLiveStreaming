package com.llawsxx.uvclivestreaming.recording

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Created, drawn and closed on the caller's video render thread. No frame queue here. */
internal class GpuVideoRenderer(
    private val encoderSurface: Surface? = null,
    initialMatrix: UsbYuvMatrix = UsbYuvMatrix.BT601,
    initialSourceRange: UsbSourceRange = UsbSourceRange.AUTO,
    private val previewClockNs: () -> Long = System::nanoTime,
) : AutoCloseable {
    data class PreviewTarget(val surface: Surface?, val revision: Long, val lowFrameRate: Boolean = false)

    private val ownerThread = Thread.currentThread()
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var parkingSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWindow: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewWindow: EGLSurface = EGL14.EGL_NO_SURFACE
    private var boundPreview: PreviewTarget? = null
    private val previewFrameLimiter = PreviewFrameLimiter()
    private var program = 0
    private val textures = IntArray(3)
    private var uploadBuffer: ByteBuffer? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private var textureLayout = -1
    private var lastEncodedTimestamp = Long.MIN_VALUE
    var encodedFrameCount = 0L
        private set
    private var closed = false
    private var colorMatrix = initialMatrix
    private var sourceRange = initialSourceRange
    private var fullRangeMatrix = initialMatrix.conversionMatrix(true)
    private var tvRangeMatrix = initialMatrix.conversionMatrix(false)

    fun setColorSettings(matrix: UsbYuvMatrix, range: UsbSourceRange) {
        check(Thread.currentThread() === ownerThread && !closed)
        if (matrix != colorMatrix) {
            fullRangeMatrix = matrix.conversionMatrix(true)
            tvRangeMatrix = matrix.conversionMatrix(false)
            colorMatrix = matrix
        }
        sourceRange = range
    }
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // Uploaded rows start at the top; GL window coordinates start at the bottom.
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }

    init {
        try {
            check(display != EGL14.EGL_NO_DISPLAY)
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            parkingSurface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(parkingSurface != EGL14.EGL_NO_SURFACE)
            makeCurrent(parkingSurface)
            program = createProgram()
            GLES20.glGenTextures(3, textures, 0)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            for (texture in textures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            encoderSurface?.let {
                encoderWindow = createWindow(it)
                check(encoderWindow != EGL14.EGL_NO_SURFACE) { "无法创建编码 EGL Surface" }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Returns true only after preview swap succeeds. Encoder failure is fatal to the session. */
    fun render(frame: GpuVideoFrame, target: PreviewTarget? = null, drawPreview: Boolean = true): Boolean {
        check(Thread.currentThread() === ownerThread && !closed)
        updatePreview(target)
        val showPreview = drawPreview && previewWindow != EGL14.EGL_NO_SURFACE &&
            previewFrameLimiter.shouldRender(previewClockNs(), target?.lowFrameRate == true)
        if (encoderWindow == EGL14.EGL_NO_SURFACE && !showPreview) return false
        makeCurrent(parkingSurface)
        upload(frame)
        if (encoderWindow != EGL14.EGL_NO_SURFACE && frame.timestampNs > lastEncodedTimestamp) {
            makeCurrent(encoderWindow)
            draw(frame, frame.width, frame.height)
            check(EGLExt.eglPresentationTimeANDROID(display, encoderWindow, frame.timestampNs))
            check(EGL14.eglSwapBuffers(display, encoderWindow)) { "编码 EGL 提交失败" }
            lastEncodedTimestamp = frame.timestampNs
            encodedFrameCount++
        }
        if (!showPreview) return false
        return try {
            makeCurrent(previewWindow)
            val w = IntArray(1)
            val h = IntArray(1)
            check(EGL14.eglQuerySurface(display, previewWindow, EGL14.EGL_WIDTH, w, 0))
            check(EGL14.eglQuerySurface(display, previewWindow, EGL14.EGL_HEIGHT, h, 0))
            draw(frame, w[0], h[0])
            check(EGL14.eglSwapBuffers(display, previewWindow))
            true
        } catch (error: RuntimeException) {
            // A destroyed/resized preview must never stop recording or streaming.
            destroyPreview()
            false
        }
    }

    private fun upload(frame: GpuVideoFrame) {
        val cw = (frame.width + 1) / 2
        val ch = (frame.height + 1) / 2
        val ySize = frame.width * frame.height
        val required = if (frame.layout == GpuVideoFrame.I420) ySize + 2 * cw * ch else ySize * 3
        val buffer = frame.directBuffer?.let {
            require(it.isDirect && it.capacity() >= required)
            it.duplicate().apply { clear(); limit(required) }
        } ?: run {
            val bytes = requireNotNull(frame.bytes)
            require(bytes.size >= required)
            if ((uploadBuffer?.capacity() ?: 0) < required) uploadBuffer = ByteBuffer.allocateDirect(required)
            checkNotNull(uploadBuffer).apply { clear(); put(bytes, 0, required); flip() }
        }
        val changed = textureWidth != frame.width || textureHeight != frame.height || textureLayout != frame.layout
        val planes = if (frame.layout == GpuVideoFrame.I420) 3 else 1
        var offset = 0
        for (plane in 0 until planes) {
            val w = if (plane == 0) frame.width else cw
            val h = if (plane == 0) frame.height else ch
            val pixelFormat = if (planes == 3) GLES20.GL_LUMINANCE else GLES20.GL_RGB
            val size = w * h * if (planes == 3) 1 else 3
            val pixels = buffer.duplicate().apply { position(offset); limit(offset + size) }.slice()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + plane)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[plane])
            if (changed) GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, pixelFormat, w, h, 0,
                pixelFormat, GLES20.GL_UNSIGNED_BYTE, pixels)
            else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                pixelFormat, GLES20.GL_UNSIGNED_BYTE, pixels)
            offset += size
        }
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GPU 视频纹理上传失败" }
        textureWidth = frame.width; textureHeight = frame.height; textureLayout = frame.layout
    }

    private fun draw(frame: GpuVideoFrame, width: Int, height: Int) {
        check(width > 0 && height > 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val dw = (frame.width * scale).toInt().coerceAtLeast(1)
        val dh = (frame.height * scale).toInt().coerceAtLeast(1)
        GLES20.glViewport((width - dw) / 2, (height - dh) / 2, dw, dh)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        for (plane in 0..2) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + plane)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[plane])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPlane$plane"), plane)
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLayout"), frame.layout)
        val fullRange = sourceRange.isFullRange(frame.fullRange)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uYuvToRgb"), 1, false,
            if (fullRange) fullRangeMatrix else tvRangeMatrix, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uYuvOffset"),
            if (fullRange) 0f else -16f / 255f, -128f / 255f, -128f / 255f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRgbRange"),
            if (fullRange) 0f else -16f / 255f, if (fullRange) 1f else 255f / 219f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun updatePreview(target: PreviewTarget?) {
        if (target == boundPreview && previewWindow != EGL14.EGL_NO_SURFACE) return
        destroyPreview()
        if (target?.surface?.isValid != true) return
        previewWindow = createWindow(target.surface)
        if (previewWindow != EGL14.EGL_NO_SURFACE) boundPreview = target
    }

    private fun createWindow(surface: Surface): EGLSurface = try {
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
    } catch (_: IllegalArgumentException) { EGL14.EGL_NO_SURFACE }

    private fun makeCurrent(window: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "EGL Surface 已失效" }
        EGL14.eglSwapInterval(display, 0)
    }

    private fun destroyPreview() {
        previewFrameLimiter.reset()
        if (previewWindow != EGL14.EGL_NO_SURFACE) {
            makeCurrent(parkingSurface)
            EGL14.eglDestroySurface(display, previewWindow)
            previewWindow = EGL14.EGL_NO_SURFACE
        }
        boundPreview = null
    }

    override fun close() {
        if (closed) return
        closed = true
        check(Thread.currentThread() === ownerThread)
        if (context != EGL14.EGL_NO_CONTEXT && parkingSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, parkingSurface, parkingSurface, context)
            GLES20.glDeleteTextures(3, textures, 0)
            if (program != 0) GLES20.glDeleteProgram(program)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        for (window in arrayOf(previewWindow, encoderWindow, parkingSurface))
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        uploadBuffer = null
    }

    private fun createProgram(): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error(message)
            }
            return shader
        }
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fragment = try { compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT) }
            catch (error: Throwable) { GLES20.glDeleteShader(vertex); throw error }
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex); GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetProgramInfoLog(result)
            GLES20.glDeleteProgram(result)
            error(message)
        }
        Log.i("UsbGpuRenderer", "GPU YUV renderer ready")
        return result
    }

    companion object {
        private const val VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vUv = aUv; }
        """
        private const val FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uPlane0;
            uniform sampler2D uPlane1;
            uniform sampler2D uPlane2;
            uniform int uLayout;
            uniform mat3 uYuvToRgb;
            uniform vec3 uYuvOffset;
            uniform vec2 uRgbRange;
            void main() {
                if (uLayout != 0) {
                    vec3 rgb = texture2D(uPlane0, vUv).rgb;
                    rgb = uLayout == 2 ? rgb.bgr : rgb;
                    gl_FragColor = vec4(clamp((rgb + uRgbRange.x) * uRgbRange.y, 0.0, 1.0), 1.0);
                } else {
                    float y = texture2D(uPlane0, vUv).r;
                    float u = texture2D(uPlane1, vUv).r;
                    float v = texture2D(uPlane2, vUv).r;
                    vec3 rgb = uYuvToRgb * (vec3(y, u, v) + uYuvOffset);
                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
                }
            }
        """
    }
}
