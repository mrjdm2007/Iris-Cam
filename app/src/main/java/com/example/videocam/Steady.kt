package com.example.videocam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.graphics.SurfaceTexture
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Gyro readings and the first frame's time, recorded while filming in super steady mode. */
class SteadyLog {
    var t0Ns = 0L          // camera time of the first frame after recording started
    var fPx = 0f           // focal length in pixels for a 3840 px wide picture (zoom included)
    var gt = LongArray(8192)
    var gx = FloatArray(8192)
    var gy = FloatArray(8192)
    var gz = FloatArray(8192)
    var n = 0

    @Synchronized
    fun add(t: Long, x: Float, y: Float, z: Float) {
        if (n == gt.size) {
            val m = n * 2
            gt = gt.copyOf(m)
            gx = gx.copyOf(m)
            gy = gy.copyOf(m)
            gz = gz.copyOf(m)
        }
        gt[n] = t
        gx[n] = x
        gy[n] = y
        gz[n] = z
        n++
    }
}

/** Collects the gyro while recording. */
class SteadyRecorder(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var log: SteadyLog? = null

    val available: Boolean get() = gyro != null

    fun start(l: SteadyLog): Boolean {
        val g = gyro ?: return false
        log = l
        return try {
            sm.registerListener(this, g, 2500)
        } catch (e: Exception) {
            false
        }
    }

    fun stop(): SteadyLog? {
        try {
            sm.unregisterListener(this)
        } catch (_: Exception) {
        }
        val l = log
        log = null
        return l
    }

    override fun onSensorChanged(e: SensorEvent) {
        log?.add(e.timestamp, e.values[0], e.values[1], e.values[2])
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

/**
 * Makes a stabilised 1080p copy of a 4K clip: the gyro tells how the phone moved, the path is smoothed (using the
 * frames before and after each one), and every frame is re-drawn as if the camera had followed the smooth path.
 */
class SteadyProcessor(private val ctx: Context) {
    private class Q(val w: Double, val x: Double, val y: Double, val z: Double) {
        operator fun times(o: Q) = Q(
            w * o.w - x * o.x - y * o.y - z * o.z,
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w
        )

        fun conj() = Q(w, -x, -y, -z)

        fun norm(): Q {
            val l = sqrt(w * w + x * x + y * y + z * z)
            return Q(w / l, x / l, y / l, z / l)
        }

        fun log(): DoubleArray {
            val q = if (w < 0) Q(-w, -x, -y, -z) else this
            val v = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
            if (v < 1e-12) return doubleArrayOf(0.0, 0.0, 0.0)
            val k = 2.0 * atan2(v, q.w) / v
            return doubleArrayOf(q.x * k, q.y * k, q.z * k)
        }

        companion object {
            fun exp(rx: Double, ry: Double, rz: Double): Q {
                val a = sqrt(rx * rx + ry * ry + rz * rz)
                if (a < 1e-12) return Q(1.0, rx / 2, ry / 2, rz / 2).norm()
                val s = sin(a / 2) / a
                return Q(cos(a / 2), rx * s, ry * s, rz * s)
            }
        }
    }

    // ---- camera orientation over time, integrated from the gyro ----
    private lateinit var tg: LongArray
    private lateinit var qs: Array<Q>
    private lateinit var wc: Array<DoubleArray>

    private fun buildPath(l: SteadyLog) {
        val n = l.n
        tg = l.gt.copyOf(n)
        // phone axes (x right, y up the screen, z out of the screen) -> sensor axes (x right, y down, z forward)
        wc = Array(n) { doubleArrayOf(-l.gy[it].toDouble(), -l.gx[it].toDouble(), -l.gz[it].toDouble()) }
        qs = Array(n) { Q(1.0, 0.0, 0.0, 0.0) }
        for (k in 1 until n) {
            val dt = (tg[k] - tg[k - 1]) * 1e-9
            val a = wc[k - 1]
            val b = wc[k]
            val rx = (a[0] + b[0]) * 0.5 * dt
            val ry = (a[1] + b[1]) * 0.5 * dt
            val rz = (a[2] + b[2]) * 0.5 * dt
            qs[k] = (qs[k - 1] * Q.exp(rx, ry, rz)).norm()
        }
    }

    private fun qAt(t: Long): Q {
        if (t <= tg[0]) return qs[0]
        val last = tg.size - 1
        if (t >= tg[last]) return qs[last]
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (tg[mid] <= t) lo = mid else hi = mid
        }
        val dt = (t - tg[lo]) * 1e-9
        val w = wc[lo]
        return (qs[lo] * Q.exp(w[0] * dt, w[1] * dt, w[2] * dt)).norm()
    }

    /** Rotation (as a 3x3, column-major) that takes a ray of the smoothed camera into the real camera at time [t]. */
    private fun correction(t: Long, tFirst: Long, tLast: Long, framePeriodNs: Long, sigma: Double): FloatArray {
        val qi = qAt(t).conj()
        val half = (2.0 * sigma).toInt()
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        var sw = 0.0
        for (j in -half..half) {
            val tj = (t + j * framePeriodNs).coerceIn(tFirst, tLast)
            val r = (qi * qAt(tj)).log()
            val w = exp(-(j * j) / (2.0 * sigma * sigma))
            sx += w * r[0]
            sy += w * r[1]
            sz += w * r[2]
            sw += w
        }
        var rx = sx / sw
        var ry = sy / sw
        var rz = sz / sw
        // never ask for more than the crop margin can hide
        val lim = 0.085
        val scale = minOf(1.0, lim / maxOf(1e-9, kotlin.math.abs(rx)), lim / maxOf(1e-9, kotlin.math.abs(ry)), 0.07 / maxOf(1e-9, kotlin.math.abs(rz)))
        rx *= scale
        ry *= scale
        rz *= scale
        val q = Q.exp(rx, ry, rz)
        val w = q.w
        val x = q.x
        val y = q.y
        val z = q.z
        val r00 = 1 - 2 * (y * y + z * z)
        val r01 = 2 * (x * y - z * w)
        val r02 = 2 * (x * z + y * w)
        val r10 = 2 * (x * y + z * w)
        val r11 = 1 - 2 * (x * x + z * z)
        val r12 = 2 * (y * z - x * w)
        val r20 = 2 * (x * z - y * w)
        val r21 = 2 * (y * z + x * w)
        val r22 = 1 - 2 * (x * x + y * y)
        return floatArrayOf(
            r00.toFloat(), r10.toFloat(), r20.toFloat(),
            r01.toFloat(), r11.toFloat(), r21.toFloat(),
            r02.toFloat(), r12.toFloat(), r22.toFloat()
        )
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

    /** Returns null when done, or a short reason if it failed. */
    fun process(inUri: Uri, log: SteadyLog, out: ParcelFileDescriptor, progress: (Int) -> Unit): String? {
        if (log.n < 50 || log.t0Ns == 0L || log.fPx <= 0f) return "No gyro data was recorded"
        buildPath(log)

        val ex = MediaExtractor()
        val ex2 = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var st: SurfaceTexture? = null
        var decSurface: Surface? = null
        var encSurface: Surface? = null
        val ht = HandlerThread("steady-frames").apply { start() }
        try {
            ex.setDataSource(ctx, inUri, null)
            ex2.setDataSource(ctx, inUri, null)
            var vt = -1
            var at = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/") && vt < 0) vt = i else if (m.startsWith("audio/") && at < 0) at = i
            }
            if (vt < 0) return "No video track"
            val vf = ex.getTrackFormat(vt)
            val inW = vf.getInteger(MediaFormat.KEY_WIDTH)
            val inH = vf.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rot != 0 && rot != 180) return "Super steady only works on landscape clips"
            val durUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L
            ex.selectTrack(vt)
            val firstPts = ex.sampleTime
            val fps = if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) vf.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            val periodNs = 1_000_000_000L / maxOf(1, fps)
            val tLast = log.t0Ns + (durUs - firstPts) * 1000L
            val outW = 1920
            val outH = 1080

            // ---- encoder ----
            val ef = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 24_000_000)
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc = e
            e.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encSurface = e.createInputSurface()
            e.start()

            // ---- GL on the encoder's surface ----
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(display, ver, 0, ver, 1)
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val nc = IntArray(1)
            EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, nc, 0)
            val eglCfg = cfgs[0] ?: return "No EGL config"
            context = EGL14.eglCreateContext(
                display, eglCfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            eglSurface = EGL14.eglCreateWindowSurface(display, eglCfg, encSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
            EGL14.eglSwapInterval(display, 0)

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val tex = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val vs = "attribute vec2 aPos;varying vec2 vOut;void main(){gl_Position=vec4(aPos,0.0,1.0);vOut=aPos;}"
            val fs = "#extension GL_OES_EGL_image_external : require\n" +
                "precision highp float;varying vec2 vOut;uniform samplerExternalOES sTex;uniform mat4 uTex;uniform mat3 uRot;" +
                "uniform vec2 uHalfOut;uniform float uFo;uniform vec2 uInSize;uniform float uFi;" +
                "vec3 at(vec2 po){vec3 d=uRot*normalize(vec3(po/uFo,1.0));if(d.z<=0.01)return vec3(0.0);" +
                "vec2 pin=uInSize*0.5+uFi*d.xy/d.z;vec2 uv=pin/uInSize;" +
                "if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0)return vec3(0.0);" +
                "vec4 t=uTex*vec4(uv.x,1.0-uv.y,0.0,1.0);return texture2D(sTex,t.xy).rgb;}" +
                "void main(){vec2 po=vec2(vOut.x*uHalfOut.x,-vOut.y*uHalfOut.y);" +
                "vec3 c=at(po+vec2(-0.25,-0.25))+at(po+vec2(0.25,-0.25))+at(po+vec2(-0.25,0.25))+at(po+vec2(0.25,0.25));" +
                "gl_FragColor=vec4(c*0.25,1.0);}"
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(prog)
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            val uTex = GLES20.glGetUniformLocation(prog, "uTex")
            val uRot = GLES20.glGetUniformLocation(prog, "uRot")
            val uHalf = GLES20.glGetUniformLocation(prog, "uHalfOut")
            val uFo = GLES20.glGetUniformLocation(prog, "uFo")
            val uIn = GLES20.glGetUniformLocation(prog, "uInSize")
            val uFi = GLES20.glGetUniformLocation(prog, "uFi")
            val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)

            val frameLock = Object()
            var frameReady = false
            val t = SurfaceTexture(tex)
            st = t
            t.setDefaultBufferSize(inW, inH)
            t.setOnFrameAvailableListener({
                synchronized(frameLock) {
                    frameReady = true
                    frameLock.notifyAll()
                }
            }, android.os.Handler(ht.looper))
            decSurface = Surface(t)

            val d = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(vf, decSurface, null, 0)
            d.start()

            val m = MediaMuxer(out.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            m.setOrientationHint(rot)
            var vTrack = -1
            var aTrack = -1
            var muxStarted = false

            val fi = log.fPx * (inW / 3840f)
            val cropZoom = 1.15f
            val fo = fi * (outW / inW.toFloat()) * cropZoom
            val sigma = 9.0
            val tm = FloatArray(16)
            val dInfo = MediaCodec.BufferInfo()
            val eInfo = MediaCodec.BufferInfo()
            var inDone = false
            var decDone = false
            var encDone = false
            var lastPct = -1

            while (!encDone) {
                var worked = false
                if (!inDone) {
                    val ii = d.dequeueInputBuffer(2000)
                    if (ii >= 0) {
                        val buf = d.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            d.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                        worked = true
                    }
                }
                if (!decDone) {
                    val di = d.dequeueOutputBuffer(dInfo, 0)
                    if (di >= 0) {
                        worked = true
                        val render = dInfo.size > 0
                        d.releaseOutputBuffer(di, render)
                        if (render) {
                            synchronized(frameLock) {
                                var waited = 0
                                while (!frameReady && waited < 30) {
                                    frameLock.wait(100)
                                    waited++
                                }
                                if (!frameReady) throw IllegalStateException("Decoder gave no picture")
                                frameReady = false
                            }
                            t.updateTexImage()
                            t.getTransformMatrix(tm)
                            val abs = log.t0Ns + (dInfo.presentationTimeUs - firstPts) * 1000L
                            val rotM = correction(abs, log.t0Ns, tLast, periodNs, sigma)
                            GLES20.glViewport(0, 0, outW, outH)
                            GLES20.glUseProgram(prog)
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                            GLES20.glUniformMatrix4fv(uTex, 1, false, tm, 0)
                            GLES20.glUniformMatrix3fv(uRot, 1, false, rotM, 0)
                            GLES20.glUniform2f(uHalf, outW / 2f, outH / 2f)
                            GLES20.glUniform1f(uFo, fo)
                            GLES20.glUniform2f(uIn, inW.toFloat(), inH.toFloat())
                            GLES20.glUniform1f(uFi, fi)
                            GLES20.glEnableVertexAttribArray(aPos)
                            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                            EGLExt.eglPresentationTimeANDROID(display, eglSurface, dInfo.presentationTimeUs * 1000L)
                            EGL14.eglSwapBuffers(display, eglSurface)
                            if (durUs > 0) {
                                val pct = (dInfo.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 99)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    progress(pct)
                                }
                            }
                        }
                        if ((dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            e.signalEndOfInputStream()
                            decDone = true
                        }
                    }
                }
                val ei = e.dequeueOutputBuffer(eInfo, if (worked) 0 else 5000)
                if (ei == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = m.addTrack(e.outputFormat)
                    if (at >= 0) aTrack = m.addTrack(ex2.getTrackFormat(at))
                    m.start()
                    muxStarted = true
                } else if (ei >= 0) {
                    val eb = e.getOutputBuffer(ei)!!
                    if ((eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) eInfo.size = 0
                    if (eInfo.size > 0 && muxStarted) {
                        eb.position(eInfo.offset)
                        eb.limit(eInfo.offset + eInfo.size)
                        m.writeSampleData(vTrack, eb, eInfo)
                    }
                    e.releaseOutputBuffer(ei, false)
                    if ((eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true
                }
            }

            // ---- sound: copied over unchanged ----
            if (aTrack >= 0 && muxStarted) {
                ex2.selectTrack(at)
                val buf = ByteBuffer.allocate(256 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val sz = ex2.readSampleData(buf, 0)
                    if (sz < 0) break
                    info.set(0, sz, ex2.sampleTime, ex2.sampleFlags)
                    m.writeSampleData(aTrack, buf, info)
                    ex2.advance()
                }
            }
            progress(100)
            return null
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        } finally {
            try { dec?.stop() } catch (_: Throwable) {}
            try { dec?.release() } catch (_: Throwable) {}
            try { enc?.stop() } catch (_: Throwable) {}
            try { enc?.release() } catch (_: Throwable) {}
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { st?.release() } catch (_: Throwable) {}
            try { decSurface?.release() } catch (_: Throwable) {}
            try { encSurface?.release() } catch (_: Throwable) {}
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
            try { ex2.release() } catch (_: Throwable) {}
            ht.quitSafely()
        }
    }
}
