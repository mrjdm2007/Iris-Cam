package com.example.videocam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader

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
    fun apply(src: Bitmap, bw: Boolean, flat: Boolean, photoLook: Int = 0, blue: Boolean = false): Bitmap {
        if (bw) {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.colorFilter = bwFilter()
            Canvas(out).drawBitmap(src, 0f, 0f, p)
            return out
        }
        if (blue) {
            // Cool cinematic blue grade: gently cools shadows and midtones without
            // crushing the image or applying a fake depth/subject mask.
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val matrix = ColorMatrix(floatArrayOf(
                0.88f, 0.04f, 0.02f, 0f, -2f,
                0.02f, 0.94f, 0.04f, 0f, 1f,
                0.02f, 0.08f, 1.12f, 0f, 7f,
                0f, 0f, 0f, 1f, 0f
            ))
            Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
            return out
        }
        if (photoLook == 1) {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val saturation = ColorMatrix().apply { setSaturation(1.08f) }
            val warm = ColorMatrix(floatArrayOf(
                1.02f, 0f, 0f, 0f, 12f,
                0f, 1.01f, 0f, 0f, 4f,
                0f, 0f, 0.94f, 0f, -10f,
                0f, 0f, 0f, 1f, 0f
            ))
            saturation.postConcat(warm)
            Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(saturation)
            })
            return out
        }
        if (photoLook == 2) {
            // Memory-conscious dreamy bloom: create a small low-frequency image and blend it
            // softly over the original. This is a photo look, not a depth-aware portrait blur.
            val w = src.width
            val h = src.height
            val smallW = (w / 8).coerceAtLeast(1)
            val smallH = (h / 8).coerceAtLeast(1)
            val low = Bitmap.createScaledBitmap(src, smallW, smallH, true)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawBitmap(low, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 34 })
            low.recycle()
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


    /** Highlight-selective soft bloom. The source remains sharp; only bright areas bleed softly. */
    fun glow(src: Bitmap, strength: Int = 78): Bitmap {
        val w = src.width
        val h = src.height
        val sw = (w / 8).coerceAtLeast(1)
        val sh = (h / 8).coerceAtLeast(1)
        val low = Bitmap.createScaledBitmap(src, sw, sh, true)
        val pixels = IntArray(sw * sh)
        low.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val luma = (r * 54 + g * 183 + b * 19) shr 8
            val gate = ((luma - 150).coerceAtLeast(0) * 255 / 105).coerceIn(0, 255)
            pixels[i] = (gate shl 24) or (r shl 16) or (g shl 8) or b
        }
        low.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = strength.coerceIn(0, 120)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        }
        canvas.drawBitmap(low, null, android.graphics.Rect(0, 0, w, h), bloomPaint)
        bloomPaint.xfermode = null
        low.recycle()
        return out
    }

    /** Stable filter IDs shared by the Filters menu, saved configuration and photo renderer. */
    val FILTER_NAMES = listOf(
        "Natural", "Black and White", "Kodak 2383", "Kodak 250D", "Kodak 500T",
        "Fuji Eterna", "Teal & Orange", "Bleach Bypass", "Vintage Fade", "Noir",
        "Leica Colour", "Blue Cinematic", "Golden Hour", "Dream Glow",
        "Halation", "Soft Diffusion", "Film Grain", "Anamorphic Flare"
    )

    private fun filterMatrix(id: Int): ColorMatrix {
        fun basic(sat: Float, contrast: Float, r: Float, g: Float, b: Float): ColorMatrix {
            val m = ColorMatrix().apply { setSaturation(sat) }
            val off = 127.5f * (1f - contrast)
            m.postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, off + r,
                0f, contrast, 0f, 0f, off + g,
                0f, 0f, contrast, 0f, off + b,
                0f, 0f, 0f, 1f, 0f
            )))
            return m
        }
        return when (id.coerceIn(0, FILTER_NAMES.lastIndex)) {
            1 -> basic(0f, 1.42f, 0f, 0f, 0f)
            2 -> basic(1.08f, 1.12f, 5f, 2f, -5f) // print-film contrast, warm highlights
            3 -> basic(1.04f, 1.04f, 5f, 2f, -3f) // daylight negative
            4 -> basic(1.02f, 1.08f, -3f, 0f, 6f) // tungsten-balanced night stock
            5 -> basic(0.84f, 0.91f, 1f, 4f, 5f) // soft, muted cinema palette
            6 -> ColorMatrix(floatArrayOf(
                1.08f, 0.02f, -0.08f, 0f, 5f,
                0.00f, 0.98f, 0.02f, 0f, 0f,
                -0.06f, 0.02f, 1.10f, 0f, 7f,
                0f, 0f, 0f, 1f, 0f
            ))
            7 -> basic(0.48f, 1.34f, 0f, 0f, 0f)
            8 -> basic(0.78f, 0.82f, 15f, 9f, 1f)
            9 -> basic(0f, 1.62f, -2f, -2f, -2f)
            10 -> ColorMatrix(floatArrayOf(
                1.13f, 0.02f, -0.04f, 0f, 5f,
                0.00f, 1.06f, 0.00f, 0f, 1f,
                -0.03f, 0.01f, 1.10f, 0f, 3f,
                0f, 0f, 0f, 1f, 0f
            )) // Leica-inspired vivid reds, clean greens and rich blues; not an official Leica profile
            11 -> ColorMatrix(floatArrayOf(
                0.88f, 0.04f, 0.02f, 0f, -2f,
                0.02f, 0.94f, 0.04f, 0f, 1f,
                0.02f, 0.08f, 1.12f, 0f, 7f,
                0f, 0f, 0f, 1f, 0f
            ))
            12 -> basic(1.08f, 1.04f, 12f, 4f, -10f)
            13 -> basic(1.02f, 0.96f, 5f, 2f, 7f)
            14 -> basic(1.03f, 1.02f, 4f, 0f, -3f)
            15 -> basic(0.98f, 0.94f, 2f, 2f, 2f)
            16 -> basic(1.02f, 1.00f, 0f, 0f, 0f)
            17 -> basic(1.00f, 1.00f, 0f, 0f, 0f)
            else -> ColorMatrix()
        }
    }

    private fun intensityMatrix(id: Int, intensity: Int): ColorMatrix {
        val target = filterMatrix(id).array
        val identity = ColorMatrix().array
        val amount = intensity.coerceIn(0, 100) / 100f
        return ColorMatrix(FloatArray(20) { i -> identity[i] + (target[i] - identity[i]) * amount })
    }

    /** Preview-compatible colour grade. TextureView preview only; video encoder output is not modified here. */
    fun previewFilter(id: Int, intensity: Int): ColorMatrixColorFilter =
        ColorMatrixColorFilter(intensityMatrix(id, intensity))

    /** 4x5 Android ColorMatrix values consumed by the EGL video renderer. */
    fun videoFilterMatrix(id: Int, intensity: Int): FloatArray =
        intensityMatrix(id, intensity).array.copyOf()

    /** Render a selected look into a saved still. Effects are deliberately photo-only until a GPU video path exists. */
    fun applyPreset(src: Bitmap, id: Int, intensity: Int): Bitmap {
        if (id <= 0 || intensity <= 0) return src
        val strength = intensity.coerceIn(0, 100)
        var base = src
        val matrix = intensityMatrix(id, intensity)
        if (id != 16) {
            val graded = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(graded).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
            base = graded
        }
        val result = when (id) {
            14 -> glow(base, (strength * 0.9f).toInt())
            15 -> {
                val sw = (base.width / 10).coerceAtLeast(1)
                val sh = (base.height / 10).coerceAtLeast(1)
                val low = Bitmap.createScaledBitmap(base, sw, sh, true)
                val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawBitmap(low, null, android.graphics.Rect(0, 0, base.width, base.height),
                    Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (strength * 0.16f).toInt() })
                low.recycle()
                out
            }
            16 -> {
                val sw = (base.width / 2).coerceAtLeast(1)
                val sh = (base.height / 2).coerceAtLeast(1)
                val noise = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                val px = IntArray(sw * sh)
                var seed = 0x13579B
                val alpha = (strength * 0.12f).toInt().coerceIn(0, 12)
                for (i in px.indices) {
                    seed = seed * 1103515245 + 12345
                    val n = ((seed ushr 16) and 31) - 15
                    val v = (128 + n).coerceIn(0, 255)
                    px[i] = (alpha shl 24) or (v shl 16) or (v shl 8) or v
                }
                noise.setPixels(px, 0, sw, 0, 0, sw, sh)
                val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawBitmap(noise, null, android.graphics.Rect(0, 0, base.width, base.height),
                    Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.OVERLAY) })
                noise.recycle()
                out
            }
            17 -> {
                val sw = (base.width / 32).coerceAtLeast(1)
                val sh = (base.height / 32).coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(base, sw, sh, true)
                val pixels = IntArray(sw * sh)
                small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                small.recycle()
                var bestRow = -1
                var bestLuma = 0
                for (y in 0 until sh) {
                    var sum = 0L
                    for (x in 0 until sw) {
                        val c = pixels[y * sw + x]
                        sum += (((c shr 16) and 255) * 54 + ((c shr 8) and 255) * 183 + (c and 255) * 19) shr 8
                    }
                    val avg = (sum / sw).toInt()
                    if (avg > bestLuma) {
                        bestLuma = avg
                        bestRow = y
                    }
                }
                val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                if (bestRow >= 0 && bestLuma > 170) {
                    val y = (bestRow + 0.5f) * base.height / sh
                    val flare = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = (base.height * 0.0035f).coerceAtLeast(2f)
                        alpha = (strength * 1.2f).toInt().coerceIn(0, 150)
                        shader = LinearGradient(
                            0f, y, base.width.toFloat(), y,
                            intArrayOf(0x00FFFFFF, 0xBFFFFFFF.toInt(), 0x00FFFFFF),
                            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawLine(0f, y, base.width.toFloat(), y, flare)
                }
                out
            }
            else -> base
        }
        if (base !== src && base !== result) base.recycle()
        return result
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
