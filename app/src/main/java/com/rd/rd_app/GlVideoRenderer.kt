package com.rd.rd_app

import android.graphics.*
import android.graphics.Paint
import android.opengl.*
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders camera frames with a timestamp text overlay to a MediaRecorder input Surface.
 *
 * The camera writes to a landscape SurfaceTexture. [rotationDegrees] specifies
 * how much to rotate the camera frame counter-clockwise so the final video is upright.
 * Typically [rotationDegrees]=sensorOrientation (e.g. 90 for most back cameras).
 */
class GlVideoRenderer(
    private val outputSurface: Surface,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val cameraWidth: Int = 1280,
    private val cameraHeight: Int = 720,
    private val rotationDegrees: Int = 90
) : AutoCloseable {

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    /** SurfaceTexture fed by the camera; the renderer reads from this each frame. */
    var frameSurfaceTexture: SurfaceTexture? = null
        private set

    private var externalTextureId = 0
    private var cameraProgram = 0
    private var textProgram = 0
    private var textTextureId = 0
    private var lastText = ""
    private var textBitmapWidth = 0
    private var textBitmapHeight = 0

    // ── Vertex geometry (full-screen quad) ──
    private val quadVerts: FloatBuffer = floatBuffer(
        -1f, -1f, 0f,
         1f, -1f, 0f,
        -1f,  1f, 0f,
         1f,  1f, 0f,
    )

    private val quadTex: FloatBuffer = floatBuffer(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f,
    )

    // ── Paint for rendering timestamp to a bitmap ──
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(5f, 2f, 2f, Color.argb(180, 0, 0, 0))
        typeface = Typeface.MONOSPACE
    }

    // ── Initialisation ──

    fun initialize(): SurfaceTexture {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val config = chooseEglConfig()
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        externalTextureId = createExternalTexture()
        frameSurfaceTexture = SurfaceTexture(externalTextureId).also {
            it.setDefaultBufferSize(cameraWidth, cameraHeight)
        }

        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        textProgram = createProgram(TEXT_VERTEX_SHADER, TEXT_FRAGMENT_SHADER)
        textTextureId = createEmptyTexture()

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        return frameSurfaceTexture!!
    }

    // ── Per-frame rendering ──

    fun drawFrame(timestampText: String) {
        val dpy = eglDisplay ?: return
        val surf = eglSurface ?: return
        val ctx = eglContext ?: return

        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)

        // Grab latest camera frame
        val st = frameSurfaceTexture ?: return
        st.updateTexImage()

        // ── Pass 1: camera frame ──
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(cameraProgram)

        val aPos = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        val aTex = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        val uRot = GLES20.glGetUniformLocation(cameraProgram, "uDegrees")
        GLES20.glUniform1i(uRot, rotationDegrees)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, quadVerts)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)

        // ── Pass 2: timestamp overlay ──
        if (timestampText != lastText) {
            lastText = timestampText
            uploadTextTexture(timestampText)
        }

        if (textBitmapWidth > 0 && textBitmapHeight > 0) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(textProgram)

            val glW = (textBitmapWidth.toFloat() / outputWidth.toFloat()) * 2f
            val glH = (textBitmapHeight.toFloat() / outputHeight.toFloat()) * 2f
            val padTop = (20f / outputHeight.toFloat()) * 2f

            val left = -glW / 2f
            val right = glW / 2f
            val top = 1f - padTop
            val bottom = top - glH

            val tv = floatBuffer(
                 left,  bottom, 0f,
                 right, bottom, 0f,
                 left,  top,    0f,
                 right, top,    0f,
            )

            val aPos2 = GLES20.glGetAttribLocation(textProgram, "aPosition")
            val aTex2 = GLES20.glGetAttribLocation(textProgram, "aTexCoord")
            GLES20.glEnableVertexAttribArray(aPos2)
            GLES20.glVertexAttribPointer(aPos2, 3, GLES20.GL_FLOAT, false, 0, tv)
            GLES20.glEnableVertexAttribArray(aTex2)
            GLES20.glVertexAttribPointer(aTex2, 2, GLES20.GL_FLOAT, false, 0, quadTex)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPos2)
            GLES20.glDisableVertexAttribArray(aTex2)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        EGL14.eglSwapBuffers(dpy, surf)
    }

    // ── Cleanup ──

    override fun close() {
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            GLES20.glDeleteTextures(1, intArrayOf(externalTextureId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(textTextureId), 0)
            GLES20.glDeleteProgram(cameraProgram)
            GLES20.glDeleteProgram(textProgram)
            frameSurfaceTexture?.release()
        } catch (_: Exception) {}

        try { if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface) } catch (_: Exception) {}
        try { if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext) } catch (_: Exception) {}
        try { EGL14.eglTerminate(eglDisplay) } catch (_: Exception) {}
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }

    // ── Helpers ──

    private fun chooseEglConfig(): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        return configs[0] ?: throw RuntimeException("eglChooseConfig: no matching config")
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun createEmptyTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val pixel = ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, 0)).also { it.position(0) }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
        return id
    }

    private fun uploadTextTexture(text: String) {
        val bitmap = makeTextBitmap(text)
        textBitmapWidth = bitmap.width
        textBitmapHeight = bitmap.height
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    private fun makeTextBitmap(text: String): Bitmap {
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        val w = bounds.width() + 48
        val h = bounds.height() + 48
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        c.drawText(text, 24f, h - 20f, textPaint)
        return bmp
    }

    // ── Shaders ──

    companion object {
        private const val CAMERA_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform int uDegrees;
            void main() {
                vec2 uv = vTexCoord;
                // Rotate texture coords CCW by uDegrees (0 / 90 / 180 / 270)
                if (uDegrees == 90) {
                    uv = vec2(uv.y, 1.0 - uv.x);
                } else if (uDegrees == 180) {
                    uv = vec2(1.0 - uv.x, 1.0 - uv.y);
                } else if (uDegrees == 270) {
                    uv = vec2(1.0 - uv.y, uv.x);
                }
                gl_FragColor = texture2D(sTexture, uv);
            }
        """

        private const val TEXT_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val TEXT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
            }
        """

        private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        private fun floatBuffer(vararg floats: Float): FloatBuffer =
            ByteBuffer.allocateDirect(floats.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(floats)
                .also { it.position(0) }
    }
}
