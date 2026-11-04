package com.example.videocam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.pow

/** Adds several frames together in linear light (for astro: many long exposures making up one very long one). */
object FrameStack {
    private const val ONE = 16384 // 1.0 in linear light

    private val LIN = IntArray(256) { i ->
        val v = i / 255f
        val l = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        (l * ONE + 0.5f).toInt()
    }

    private val SRGB = IntArray(ONE + 1) { i ->
        val l = i / ONE.toFloat()
        val v = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
        (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    fun sum(jpegs: List<ByteArray>): Bitmap? {
        if (jpegs.isEmpty()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegs[0], 0, jpegs[0].size, o)
        val w = o.outWidth
        val h = o.outHeight
        if (w <= 0 || h <= 0) return null
        val acc = ShortArray(w * h * 3)
        var used = 0
        val row = IntArray(w)
        for (j in jpegs) {
            val b = BitmapFactory.decodeByteArray(j, 0, j.size) ?: continue
            if (b.width != w || b.height != h) {
                b.recycle()
                continue
            }
            for (y in 0 until h) {
                b.getPixels(row, 0, w, 0, y, w, 1)
                var k = y * w * 3
                for (x in 0 until w) {
                    val p = row[x]
                    acc[k] = minOf(ONE, acc[k] + LIN[(p shr 16) and 0xFF]).toShort()
                    acc[k + 1] = minOf(ONE, acc[k + 1] + LIN[(p shr 8) and 0xFF]).toShort()
                    acc[k + 2] = minOf(ONE, acc[k + 2] + LIN[p and 0xFF]).toShort()
                    k += 3
                }
            }
            b.recycle()
            used++
        }
        if (used == 0) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            var k = y * w * 3
            for (x in 0 until w) {
                row[x] = (0xFF shl 24) or (SRGB[acc[k].toInt()] shl 16) or (SRGB[acc[k + 1].toInt()] shl 8) or SRGB[acc[k + 2].toInt()]
                k += 3
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }
}
