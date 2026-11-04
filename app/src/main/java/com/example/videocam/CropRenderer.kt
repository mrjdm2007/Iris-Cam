package com.example.videocam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Sits between the camera and the video recorder when a cinematic-ratio resolution is chosen.
 * The camera writes its normal 16:9 picture into [input]; every frame is drawn upright and
 * centre-cropped into the recorder's surface, so the file really is e.g. 3840x1608.
 * Only used in landscape (display rotation 90 or 270).
 */
class CropRenderer(
    private val target: Surface,
    private val outW: Int,
    private val outH: Int,
    private val srcW: Int,
    private val srcH: Int,
    private val rotation: Int
) {
    private val thread = HandlerThread("crop-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texId = 0
    private var program = 0
    private var uTex = 0
    private var aPos = 0
    private var aTc = 0
    private var st: SurfaceTexture? = null
    private val tm = FloatArray(16)
    private lateinit var verts: FloatBuffer

    @Volatile
    private var released = false

    @Volatile
    var input: Surface? = null
        private set

    /** Frames are only passed on to the recorder while this is true (set once recording has started). */
    @Volatile
    var active = false

    init {
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        handler.post {
            try {
                setup()
            } catch (t: Throwable) {
                err = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        val e = err
        if (e != null) {
            release()
            throw RuntimeException("Crop renderer: ${e.message}", e)
        }
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw IllegalStateException("EGL init failed")
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0)
        val cfg = configs[0] ?: throw IllegalStateException("No EGL config")
        context = EGL14.eglCreateContext(
            display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, cfg, target, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("Couldn't use the recorder surface")
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        EGL14.eglSwapInterval(display, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vs = "uniform mat4 uTex;attribute vec4 aPos;attribute vec2 aTc;varying vec2 vTc;" +
            "void main(){gl_Position=aPos;vTc=(uTex*vec4(aTc,0.0,1.0)).xy;}"
        val fs = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;varying vec2 vTc;uniform samplerExternalOES sTex;" +
            "void main(){gl_FragColor=texture2D(sTex,vTc);}"
        program = link(compile(GLES20.GL_VERTEX_SHADER, vs), compile(GLES20.GL_FRAGMENT_SHADER, fs))
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTc = GLES20.glGetAttribLocation(program, "aTc")

        // The camera picture comes in upright as if the phone were held in portrait; turn it to landscape
        // and let the output bounds cut the top and bottom. yh > 1 means the picture is taller than the frame.
        val yh = (outW * srcH.toFloat() / srcW) / outH
        val q = if (rotation == Surface.ROTATION_270) {
            floatArrayOf(
                -1f, -yh, 1f, 0f,
                1f, -yh, 1f, 1f,
                -1f, yh, 0f, 0f,
                1f, yh, 0f, 1f
            )
        } else {
            floatArrayOf(
                -1f, -yh, 0f, 1f,
                1f, -yh, 0f, 0f,
                -1f, yh, 1f, 1f,
                1f, yh, 1f, 0f
            )
        }
        verts = ByteBuffer.allocateDirect(q.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        verts.put(q).position(0)

        val t = SurfaceTexture(texId)
        t.setDefaultBufferSize(srcW, srcH)
        st = t
        input = Surface(t)
        t.setOnFrameAvailableListener({ if (!released) draw() }, handler)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun link(v: Int, f: Int): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Program: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun draw() {
        val t = st ?: return
        try {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            t.updateTexImage()
            if (!active) return
            t.getTransformMatrix(tm)
            GLES20.glViewport(0, 0, outW, outH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(uTex, 1, false, tm, 0)
            verts.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, verts)
            verts.position(2)
            GLES20.glEnableVertexAttribArray(aTc)
            GLES20.glVertexAttribPointer(aTc, 2, GLES20.GL_FLOAT, false, 16, verts)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // The recorder's audio runs on the monotonic clock; frames that reach us straight from the camera are
            // stamped on the boot clock (it keeps counting while the phone sleeps), which made clips look hours long.
            val ts = t.timestamp
            val boot = SystemClock.elapsedRealtimeNanos()
            val mono = System.nanoTime()
            val pts = if (abs(ts - boot) < abs(ts - mono)) ts - (boot - mono) else ts
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, pts)
            EGL14.eglSwapBuffers(display, eglSurface)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                st?.setOnFrameAvailableListener(null)
                input?.release()
                st?.release()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
            latch.countDown()
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }
}
