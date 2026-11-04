package com.example.videocam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/** Photo looks applied to the saved picture: the flat profile (same curve as video) and high-contrast black and white. */
object Look {
    /** Tone curve points (input, output) used by the flat profile in video and photo. */
    val FLAT_POINTS = floatArrayOf(0f, 0.10f, 0.25f, 0.32f, 0.5f, 0.54f, 0.75f, 0.74f, 1f, 0.92f)

    private const val BW_CONTRAST = 1.45f

    fun bwFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val c = BW_CONTRAST
        val t = 128f * (1f - c)
        val ct = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(ct)
        return ColorMatrixColorFilter(cm)
    }

    private fun flatLut(): IntArray {
        val lut = IntArray(256)
        val p = FLAT_POINTS
        for (i in 0..255) {
            val x = i / 255f
            var y = p[p.size - 1]
            var k = 0
            while (k + 2 < p.size) {
                val x0 = p[k]
                val y0 = p[k + 1]
                val x1 = p[k + 2]
                val y1 = p[k + 3]
                if (x <= x1) {
                    val f = if (x1 > x0) (x - x0) / (x1 - x0) else 0f
                    y = y0 + (y1 - y0) * f
                    break
                }
                k += 2
            }
            lut[i] = (y * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /** Returns [src] untouched when no look is wanted, otherwise a new bitmap with the look applied. */
    fun apply(src: Bitmap, bw: Boolean, flat: Boolean): Bitmap {
        if (bw) {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.colorFilter = bwFilter()
            Canvas(out).drawBitmap(src, 0f, 0f, p)
            return out
        }
        if (!flat) return src
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lut = flatLut()
        val band = 64
        val row = IntArray(w * band)
        var y = 0
        while (y < h) {
            val n = minOf(band, h - y)
            src.getPixels(row, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val px = row[i]
                row[i] = (px and 0xFF000000.toInt()) or (lut[(px shr 16) and 0xFF] shl 16) or
                    (lut[(px shr 8) and 0xFF] shl 8) or lut[px and 0xFF]
            }
            out.setPixels(row, 0, w, 0, y, w, n)
            y += n
        }
        return out
    }

    /** Scales a picture up to about [targetPixels] (never down), then sharpens a little to offset the softness. */
    fun upscale(src: Bitmap, targetPixels: Long): Bitmap {
        val area = src.width.toLong() * src.height
        if (area >= targetPixels * 98 / 100) return src
        val f = Math.sqrt(targetPixels.toDouble() / area)
        val w = (src.width * f).toInt().coerceAtLeast(src.width)
        val h = (src.height * f).toInt().coerceAtLeast(src.height)
        val big = Bitmap.createScaledBitmap(src, w, h, true)
        // light unsharp mask, in bands so it stays within memory
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val amount = 0.45f
        val band = 48
        val buf = IntArray(w * (band + 2))
        val res = IntArray(w * band)
        var y = 0
        while (y < h) {
            val y0 = maxOf(0, y - 1)
            val y1 = minOf(h, y + band + 1)
            big.getPixels(buf, 0, w, 0, y0, w, y1 - y0)
            val n = minOf(band, h - y)
            for (r in 0 until n) {
                val ry = y + r - y0
                val up = maxOf(0, ry - 1)
                val dn = minOf(y1 - y0 - 1, ry + 1)
                for (x in 0 until w) {
                    val xl = if (x > 0) x - 1 else 0
                    val xr = if (x < w - 1) x + 1 else w - 1
                    val c = buf[ry * w + x]
                    val n0 = buf[ry * w + xl]
                    val n1 = buf[ry * w + xr]
                    val n2 = buf[up * w + x]
                    val n3 = buf[dn * w + x]
                    var px = c and 0xFF000000.toInt()
                    var sh = 16
                    while (sh >= 0) {
                        val cv = (c shr sh) and 0xFF
                        val avg = (((n0 shr sh) and 0xFF) + ((n1 shr sh) and 0xFF) + ((n2 shr sh) and 0xFF) + ((n3 shr sh) and 0xFF)) / 4f
                        val v = (cv + (cv - avg) * amount).toInt().coerceIn(0, 255)
                        px = px or (v shl sh)
                        sh -= 8
                    }
                    res[r * w + x] = px
                }
            }
            out.setPixels(res, 0, w, 0, y, w, n)
            y += n
        }
        big.recycle()
        return out
    }
}
