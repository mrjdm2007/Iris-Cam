package com.example.videocam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

/**
 * Listens to the microphone for the level meter and the Wi-Fi monitor's audio.
 * Delivers 16-bit mono PCM at 48 kHz in small chunks. It is never started before a recording has begun.
 */
class AudioTap(private val ctx: Context, private val sink: (ByteArray, Int, Int) -> Unit) {
    companion object {
        const val RATE = 48000
    }

    private var rec: AudioRecord? = null
    private var th: Thread? = null

    @Volatile
    private var run = false

    @Volatile
    var peak = 0
        private set

    val running: Boolean get() = run

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (run) return true
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return false
        }
        rec = r
        run = true
        th = Thread { loop(r) }.also {
            it.isDaemon = true
            it.start()
        }
        return true
    }

    private fun loop(r: AudioRecord) {
        val buf = ByteArray(2048)
        while (run) {
            val n = try {
                r.read(buf, 0, buf.size)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) break
            if (n == 0) continue
            var pk = 0
            var i = 0
            while (i + 1 < n) {
                val v = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                val a = abs(v)
                if (a > pk) pk = a
                i += 2
            }
            peak = pk
            sink(buf, n, pk)
        }
    }

    @Synchronized
    fun stop() {
        run = false
        peak = 0
        val r = rec
        rec = null
        try {
            r?.stop()
        } catch (_: Exception) {
        }
        try {
            r?.release()
        } catch (_: Exception) {
        }
        th = null
    }
}
