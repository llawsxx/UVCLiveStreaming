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
    initialColorGrade: VideoColorGradeSettings = VideoColorGradeSettings(),
) : AutoCloseable {
    data class PreviewTarget(val surface: Surface?, val revision: Long, val lowFrameRate: Boolean = false,
        val zoom: PreviewZoom = PreviewZoom())

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
    private var testProgram = 0
    private var testLutEnabledLocation = -1
    private var testLutInfoLocation = -1
    private var testLutTextureLocation = -1
    private val textures = IntArray(4)
    private val lutWorker = VideoColorLutWorker(initialColorGrade, onError = {
        Log.e("UsbGpuRenderer", "Color LUT bake failed", it)
    })
    private var uploadedLut: BakedVideoColorLut? = null
    private var lutEnabled = false
    private var halfFloatLut = false
    private var lutWidth = 0
    private var lutHeight = 0
    private var lutEnabledLocation = -1
    private var lutInfoLocation = -1
    private var lutTextureLocation = -1
    private var uploadBuffer: ByteBuffer? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private var textureChromaWidth = 0
    private var textureChromaHeight = 0
    private var textureLayout = -1
    private var lastEncodedTimestamp = Long.MIN_VALUE
    var encodedFrameCount = 0L
        private set
    private var closed = false
    private var colorMatrix = initialMatrix
    private var sourceRange = initialSourceRange
    private var fullRangeMatrix = initialMatrix.conversionMatrix(true)
    private var tvRangeMatrix = initialMatrix.conversionMatrix(false)
    private var fullRangeMatrix10 = initialMatrix.conversionMatrix(true, 10)
    private var tvRangeMatrix10 = initialMatrix.conversionMatrix(false, 10)

    fun setColorSettings(matrix: UsbYuvMatrix, range: UsbSourceRange) {
        check(Thread.currentThread() === ownerThread && !closed)
        if (matrix != colorMatrix) {
            fullRangeMatrix = matrix.conversionMatrix(true)
            tvRangeMatrix = matrix.conversionMatrix(false)
            fullRangeMatrix10 = matrix.conversionMatrix(true, 10)
            tvRangeMatrix10 = matrix.conversionMatrix(false, 10)
            colorMatrix = matrix
        }
        sourceRange = range
    }

    fun setColorGrade(settings: VideoColorGradeSettings) {
        check(Thread.currentThread() === ownerThread && !closed)
        lutWorker.update(settings)
    }
    val colorGradeReady: Boolean get() = lutWorker.ready
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
            lutEnabledLocation = GLES20.glGetUniformLocation(program, "uGradeEnabled")
            lutInfoLocation = GLES20.glGetUniformLocation(program, "uLutInfo")
            lutTextureLocation = GLES20.glGetUniformLocation(program, "uColorLut")
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ').toSet()
            halfFloatLut = "GL_OES_texture_half_float" in extensions && "GL_OES_texture_half_float_linear" in extensions
            GLES20.glGenTextures(4, textures, 0)
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
        updateLut()
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
            draw(frame, w[0], h[0], target?.zoom ?: PreviewZoom())
            check(EGL14.eglSwapBuffers(display, previewWindow))
            true
        } catch (error: RuntimeException) {
            // A destroyed/resized preview must never stop recording or streaming.
            destroyPreview()
            false
        }
    }

    private fun updateLut() {
        lutWorker.failure?.let { throw IllegalStateException("视频调色 LUT 生成失败", it) }
        val lut = lutWorker.current
        lutEnabled = lut != null
        if (lut == null) { uploadedLut = null; return }
        if (lut === uploadedLut) return
        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        check(lut.width <= maxSize[0] && lut.height <= maxSize[0]) { "GPU 不支持所选 LUT 尺寸" }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[3])
        val type = if (halfFloatLut) 0x8D61 else GLES20.GL_UNSIGNED_BYTE // GL_HALF_FLOAT_OES
        val pixels = (if (halfFloatLut) lut.halfPixels else lut.bytePixels).duplicate().apply { clear() }
        if (lutWidth != lut.width || lutHeight != lut.height) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, lut.width, lut.height,
                0, GLES20.GL_RGBA, type, pixels)
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, lut.width, lut.height,
                GLES20.GL_RGBA, type, pixels)
        }
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GPU 调色 LUT 上传失败" }
        lutWidth = lut.width; lutHeight = lut.height
        uploadedLut = lut
        Log.i("UsbGpuRenderer", "Color LUT uploaded: ${lut.size}^3 ${if (halfFloatLut) "RGBA16F" else "RGBA8"}")
    }

    private fun upload(frame: GpuVideoFrame) {
        if (frame.testCard != null) return
        val cw = frame.chromaWidth
        val ch = frame.chromaHeight
        require(cw in 1..frame.width && ch in 1..frame.height)
        require(frame.layout in GpuVideoFrame.I420..GpuVideoFrame.P010)
        val required = frame.byteSize
        val buffer = frame.directBuffer?.let {
            require(it.isDirect && it.limit() >= required)
            it.duplicate().apply { position(0); limit(required) }
        } ?: run {
            val bytes = requireNotNull(frame.bytes)
            require(bytes.size >= required)
            if ((uploadBuffer?.capacity() ?: 0) < required) uploadBuffer = ByteBuffer.allocateDirect(required)
            checkNotNull(uploadBuffer).apply { clear(); put(bytes, 0, required); flip() }
        }
        val changed = textureWidth != frame.width || textureHeight != frame.height || textureLayout != frame.layout ||
            textureChromaWidth != cw || textureChromaHeight != ch
        val planes = when { frame.isRgb || frame.isPacked422 -> 1; frame.isSemiplanar -> 2; else -> 3 }
        var offset = 0
        for (plane in 0 until planes) {
            val w = if (frame.isPacked422) cw else if (plane == 0) frame.width else cw
            val h = if (plane == 0) frame.height else ch
            val pixelFormat = when {
                frame.isRgb -> GLES20.GL_RGB
                frame.isPacked422 || (frame.layout == GpuVideoFrame.P010 && plane == 1) -> GLES20.GL_RGBA
                frame.sampleBytes == 2 || (frame.layout == GpuVideoFrame.NV12 && plane == 1) -> GLES20.GL_LUMINANCE_ALPHA
                else -> GLES20.GL_LUMINANCE
            }
            val channels = when (pixelFormat) { GLES20.GL_RGB -> 3; GLES20.GL_RGBA -> 4; GLES20.GL_LUMINANCE_ALPHA -> 2; else -> 1 }
            val size = w * h * channels
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
        textureChromaWidth = cw; textureChromaHeight = ch
    }

    private fun draw(frame: GpuVideoFrame, width: Int, height: Int, zoom: PreviewZoom = PreviewZoom()) {
        check(width > 0 && height > 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val viewport = PreviewViewport.forAspectRatio(frame.width.toFloat() / frame.height, width.toFloat() / height)
        val viewZoom = zoom.constrained(viewport)
        val horizontal = frame.width * scale * viewZoom.scale / width
        val vertical = frame.height * scale * viewZoom.scale / height
        val card = frame.testCard
        if (card != null && testProgram == 0) {
            testProgram = createProgram(TestCardShader.fragment)
            testLutEnabledLocation = GLES20.glGetUniformLocation(testProgram, "uGradeEnabled")
            testLutInfoLocation = GLES20.glGetUniformLocation(testProgram, "uLutInfo")
            testLutTextureLocation = GLES20.glGetUniformLocation(testProgram, "uColorLut")
        }
        val shader = if (card != null) testProgram else program
        GLES20.glUseProgram(shader)
        // Expand the image into the full surface, including former black bars. GL clips at screen edges.
        // Encoder draws restore the fitted image at 1x on every frame.
        val left = -2f * viewZoom.centerX * horizontal
        val right = 2f * (1f - viewZoom.centerX) * horizontal
        val top = 2f * viewZoom.centerY * vertical
        val bottom = -2f * (1f - viewZoom.centerY) * vertical
        vertices.put(0, left); vertices.put(1, bottom)
        vertices.put(4, right); vertices.put(5, bottom)
        vertices.put(8, left); vertices.put(9, top)
        vertices.put(12, right); vertices.put(13, top)
        val position = GLES20.glGetAttribLocation(shader, "aPosition")
        val uv = GLES20.glGetAttribLocation(shader, "aUv")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        if (card != null) {
            GLES20.glUniform1i(GLES20.glGetUniformLocation(shader, "uTestPattern"), card.pattern.ordinal)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(shader, "uTestMode"), frame.width.toFloat(), frame.height.toFloat(),
                card.fps.toFloat(), (card.index % 1_000_000).toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(shader, "uTestSeconds"), ((card.index / card.fps) % 10_000).toFloat())
        } else {
            for (plane in 0..2) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + plane)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[plane])
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPlane$plane"), plane)
            }
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLayout"), frame.layout)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSourceWidth"), frame.width.toFloat())
            val fullRange = sourceRange.isFullRange(frame.fullRange)
            val tenBit = frame.sampleBytes == 2
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "uYuvToRgb"), 1, false,
                if (tenBit) { if (fullRange) fullRangeMatrix10 else tvRangeMatrix10 }
                else { if (fullRange) fullRangeMatrix else tvRangeMatrix }, 0)
            val maxSample = if (tenBit) 1023f else 255f
            val sampleScale = if (tenBit) 4f else 1f
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uYuvOffset"),
                if (fullRange) 0f else -16f * sampleScale / maxSample,
                -128f * sampleScale / maxSample, -128f * sampleScale / maxSample)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRgbRange"),
                if (fullRange) 0f else -16f / 255f, if (fullRange) 1f else 255f / 219f)
        }
        val gradeLocation = if (card != null) testLutEnabledLocation else lutEnabledLocation
        val infoLocation = if (card != null) testLutInfoLocation else lutInfoLocation
        val samplerLocation = if (card != null) testLutTextureLocation else lutTextureLocation
        GLES20.glUniform1i(gradeLocation, if (lutEnabled) 1 else 0)
        if (lutEnabled) {
            val lut = checkNotNull(uploadedLut)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[3])
            GLES20.glUniform1i(samplerLocation, 3)
            GLES20.glUniform4f(infoLocation, lut.size.toFloat(), lut.columns.toFloat(), lut.width.toFloat(), lut.height.toFloat())
        } else {
            // Keep sampler types/units unambiguous even before the first LUT upload.
            GLES20.glUniform1i(samplerLocation, 3)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun updatePreview(target: PreviewTarget?) {
        // Gesture updates must not recreate the EGL surface or reset the preview frame limiter.
        if (target?.surface === boundPreview?.surface && target?.revision == boundPreview?.revision &&
            previewWindow != EGL14.EGL_NO_SURFACE) return
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
        lutWorker.close()
        check(Thread.currentThread() === ownerThread)
        if (context != EGL14.EGL_NO_CONTEXT && parkingSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, parkingSurface, parkingSurface, context)
            GLES20.glDeleteTextures(4, textures, 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (testProgram != 0) GLES20.glDeleteProgram(testProgram)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        for (window in arrayOf(previewWindow, encoderWindow, parkingSurface))
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        uploadBuffer = null
        uploadedLut = null
    }

    private fun createProgram(fragmentSource: String = FRAGMENT): Int {
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
        val fragment = try { compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource) }
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
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            precision highp sampler2D;
            #else
            precision mediump float;
            precision mediump sampler2D;
            #endif
            varying vec2 vUv;
            uniform sampler2D uPlane0;
            uniform sampler2D uPlane1;
            uniform sampler2D uPlane2;
            uniform int uLayout;
            uniform float uSourceWidth;
            uniform mat3 uYuvToRgb;
            uniform vec3 uYuvOffset;
            uniform vec2 uRgbRange;
            uniform bool uGradeEnabled;
            uniform sampler2D uColorLut;
            // size, tile columns, atlas width, atlas height
            uniform vec4 uLutInfo;
            vec3 grade(vec3 rgb) {
                vec3 p = clamp(rgb, 0.0, 1.0) * (uLutInfo.x - 1.0);
                float lower = floor(p.b);
                float upper = min(lower + 1.0, uLutInfo.x - 1.0);
                vec2 tile0 = vec2(mod(lower, uLutInfo.y), floor(lower / uLutInfo.y));
                vec2 tile1 = vec2(mod(upper, uLutInfo.y), floor(upper / uLutInfo.y));
                // Half-texel centers keep bilinear filtering inside each R/G tile.
                vec2 uv0 = (tile0 * uLutInfo.x + p.rg + 0.5) / uLutInfo.zw;
                vec2 uv1 = (tile1 * uLutInfo.x + p.rg + 0.5) / uLutInfo.zw;
                return mix(texture2D(uColorLut, uv0).rgb, texture2D(uColorLut, uv1).rgb, fract(p.b));
            }
            float packedLuma(float pixel) {
                float index = clamp(pixel, 0.0, uSourceWidth - 1.0);
                vec4 pair = texture2D(uPlane0, vec2((floor(index * 0.5) + 0.5) / (uSourceWidth * 0.5), vUv.y));
                vec2 luma = uLayout == 4 ? pair.rb : pair.ga;
                return mix(luma.x, luma.y, mod(index, 2.0));
            }
            void main() {
                vec3 rgb;
                if (uLayout == 1 || uLayout == 2) {
                    rgb = texture2D(uPlane0, vUv).rgb;
                    rgb = uLayout == 2 ? rgb.bgr : rgb;
                    rgb = (rgb + uRgbRange.x) * uRgbRange.y;
                } else if (uLayout == 4 || uLayout == 5) {
                    float pixel = vUv.x * uSourceWidth - 0.5;
                    float lower = floor(pixel);
                    float y = mix(packedLuma(lower), packedLuma(lower + 1.0), fract(pixel));
                    vec4 pair = texture2D(uPlane0, vUv);
                    vec2 chroma = uLayout == 4 ? pair.ga : pair.rb;
                    rgb = uYuvToRgb * (vec3(y, chroma) + uYuvOffset);
                } else if (uLayout == 6 || uLayout == 7) {
                    vec4 py = texture2D(uPlane0, vUv);
                    vec4 uv = texture2D(uPlane1, vUv);
                    vec2 weights = vec2(255.0 / 65472.0, 65280.0 / 65472.0);
                    vec3 yuv = uLayout == 7 ? vec3(dot(py.ra, weights), dot(uv.rg, weights), dot(uv.ba, weights)) : vec3(py.r, uv.r, uv.a);
                    rgb = uYuvToRgb * (yuv + uYuvOffset);
                } else {
                    vec4 py = texture2D(uPlane0, vUv);
                    vec4 pu = texture2D(uPlane1, vUv);
                    vec4 pv = texture2D(uPlane2, vUv);
                    // LUMINANCE_ALPHA carries both bytes of each P010 sample.
                    // Reconstruction is linear, so filtering retains the low bits too.
                    vec2 weights = vec2(255.0 / 65472.0, 65280.0 / 65472.0);
                    float y = uLayout == 3 ? dot(py.ra, weights) : py.r;
                    float u = uLayout == 3 ? dot(pu.ra, weights) : pu.r;
                    float v = uLayout == 3 ? dot(pv.ra, weights) : pv.r;
                    rgb = uYuvToRgb * (vec3(y, u, v) + uYuvOffset);
                }
                rgb = clamp(rgb, 0.0, 1.0);
                if (uGradeEnabled) rgb = grade(rgb);
                gl_FragColor = vec4(rgb, 1.0);
            }
        """
    }
}
