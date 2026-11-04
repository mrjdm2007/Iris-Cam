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
    private val rotation: Int,
    private val filterId: Int = 0,
    private val filterIntensity: Int = 100
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
            "precision mediump float;varying vec2 vTc;uniform samplerExternalOES sTex;uniform int uFilter;uniform float uIntensity;" +
            "vec3 grade(vec3 c){vec3 x=c;float y=dot(c,vec3(0.299,0.587,0.114));" +
            "if(uFilter==1){x=mix(c,vec3(y),uIntensity);x=(x-0.5)*mix(1.0,1.42,uIntensity)+0.5;}" +
            "else if(uFilter==2){x=(c-0.5)*mix(1.0,1.12,uIntensity)+0.5+vec3(0.020,0.008,-0.020)*uIntensity;x=mix(vec3(y),x,mix(1.0,1.08,uIntensity));}" +
            "else if(uFilter==3){x=mix(vec3(y),c,mix(1.0,1.04,uIntensity));x+=vec3(0.020,0.008,-0.012)*uIntensity;}" +
            "else if(uFilter==4){x=mix(vec3(y),c,mix(1.0,1.02,uIntensity));x+=vec3(-0.012,0.0,0.024)*uIntensity;}" +
            "else if(uFilter==5){x=mix(vec3(y),c,mix(1.0,0.84,uIntensity));x=(x-0.5)*mix(1.0,0.91,uIntensity)+0.5+vec3(0.004,0.016,0.020)*uIntensity;}" +
            "else if(uFilter==6){x+=vec3(0.020,0.0,0.028)*uIntensity;x.r+=(-c.b)*0.04*uIntensity;x.b+=(-c.r)*0.03*uIntensity;}" +
            "else if(uFilter==7){x=mix(vec3(y),c,mix(1.0,0.48,uIntensity));x=(x-0.5)*mix(1.0,1.34,uIntensity)+0.5;}" +
            "else if(uFilter==8){x=mix(vec3(y),c,mix(1.0,0.78,uIntensity));x=(x-0.5)*mix(1.0,0.82,uIntensity)+0.5+vec3(0.059,0.035,0.004)*uIntensity;}" +
            "else if(uFilter==9){x=mix(c,vec3(y),uIntensity);x=(x-0.5)*mix(1.0,1.62,uIntensity)+0.5;}" +
            "else if(uFilter==10){x+=vec3(0.020,0.004,0.012)*uIntensity;x.r*=mix(1.0,1.13,uIntensity);x.b*=mix(1.0,1.10,uIntensity);}" +
            "else if(uFilter==11){x+=vec3(-0.008,0.004,0.028)*uIntensity;x.b*=mix(1.0,1.12,uIntensity);}" +
            "else if(uFilter==12){x+=vec3(0.047,0.016,-0.039)*uIntensity;x.r*=mix(1.0,1.08,uIntensity);}" +
            "else if(uFilter==13){x+=vec3(0.020,0.008,0.028)*uIntensity;x=(x-0.5)*mix(1.0,0.96,uIntensity)+0.5;}" +
            "else if(uFilter==14){x+=max(y-0.58,0.0)*vec3(0.10,0.08,0.06)*uIntensity;}" +
            "else if(uFilter==15){x=mix(c,vec3(y),0.02*uIntensity);x=(x-0.5)*mix(1.0,0.94,uIntensity)+0.5;}" +
            "else if(uFilter==16){float n=fract(sin(dot(gl_FragCoord.xy,vec2(12.9898,78.233)))*43758.5453)-0.5;x+=n*0.035*uIntensity;}" +
            "else if(uFilter==17){float flare=max(0.0,1.0-abs(vTc.y-0.5)*18.0);x+=vec3(flare*0.18*uIntensity);}else if(uFilter==18){x=mix(vec3(y),x,1.18);x=(x-0.5)*1.12+0.5;x+=vec3(0.008,0.006,0.012);}else if(uFilter==19){float band=step(0.985,fract(gl_FragCoord.y*0.19));float noise=fract(sin(dot(gl_FragCoord.xy,vec2(12.9898,78.233)))*43758.5453);float tear=step(0.992,fract(gl_FragCoord.y*0.013));x.r=texture2D(sTex,vTc+vec2(0.0025*uIntensity/100.0,0.0)).r;x.b=texture2D(sTex,vTc-vec2(0.0025*uIntensity/100.0,0.0)).b;x*=1.0-(band*0.10*uIntensity/100.0);x+=vec3((noise-0.5)*0.045*uIntensity/100.0);x=mix(x,vec3(y),0.12*uIntensity/100.0);x+=vec3(tear*0.025*uIntensity/100.0);}" +
            "return clamp(x,0.0,1.0);}" +
            "void main(){vec4 color=texture2D(sTex,vTc);color.rgb=grade(color.rgb);gl_FragColor=color;}"
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
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFilter"), filterId.coerceIn(0, 19))
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uIntensity"), filterIntensity.coerceIn(0, 100) / 100f)
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
