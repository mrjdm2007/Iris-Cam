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
        "Halation", "Soft Diffusion", "Film Grain", "Anamorphic Flare", "Vivid", "VHS"
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
            13 -> basic(1.16f, 1.06f, 18f, 12f, 6f) // visible warm lifted bloom base
            14 -> basic(1.10f, 1.08f, 16f, 2f, -12f) // warm highlight separation for halation
            15 -> basic(0.98f, 0.94f, 2f, 2f, 2f)
            16 -> basic(1.02f, 1.00f, 0f, 0f, 0f)
            17 -> basic(1.00f, 1.00f, 0f, 0f, 0f)
            18 -> basic(1.22f, 1.12f, 3f, 2f, 8f) // vivid colour with controlled saturation
            19 -> basic(0.72f, 1.16f, 5f, 3f, 10f) // visible faded/chromatically cool VHS base grade
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

    /** Build a broad highlight-only bloom layer at low resolution to keep memory use bounded. */
    private fun bloomLayer(src: Bitmap, strength: Int, threshold: Int, tint: Int): Bitmap {
        val sw = (src.width / 8).coerceAtLeast(1)
        val sh = (src.height / 8).coerceAtLeast(1)
        val low = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        low.getPixels(px, 0, sw, 0, 0, sw, sh)
        val tr = (tint shr 16) and 255
        val tg = (tint shr 8) and 255
        val tb = tint and 255
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val l = (r * 54 + g * 183 + b * 19) shr 8
            val gate = ((l - threshold) * 255 / (255 - threshold).coerceAtLeast(1)).coerceIn(0, 255)
            val a = (gate * strength / 100 * 0.88f).toInt().coerceIn(0, 210)
            val rr = (r * (255 - a) + tr * a) / 255
            val gg = (g * (255 - a) + tg * a) / 255
            val bb = (b * (255 - a) + tb * a) / 255
            px[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        low.setPixels(px, 0, sw, 0, 0, sw, sh)
        return low
    }

    /** Soft, visible dream glow: a broad highlight bloom over the still-sharp original. */
    private fun dreamGlow(src: Bitmap, strength: Int): Bitmap {
        val layer = bloomLayer(src, strength, 105, 0xFFFFF1E5.toInt())
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (strength * 1.15f).toInt().coerceIn(0, 220)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        }
        canvas.drawBitmap(layer, null, android.graphics.Rect(0, 0, src.width, src.height), paint)
        layer.recycle()
        return out
    }

    /** Warm red/orange halation concentrated around bright highlights, not a global colour tint. */
    private fun halation(src: Bitmap, strength: Int): Bitmap {
        val layer = bloomLayer(src, strength, 132, 0xFFFF6848.toInt())
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (strength * 1.25f).toInt().coerceIn(0, 235)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        }
        canvas.drawBitmap(layer, null, android.graphics.Rect(0, 0, src.width, src.height), paint)
        layer.recycle()
        return out
    }

    /** Row-wise VHS treatment: chroma bleed, scan lines, tape jitter and restrained analogue noise. */
    private fun vhs(src: Bitmap, strength: Int): Bitmap {
        val amount = strength.coerceIn(0, 100) / 100f
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        val dst = IntArray(w)
        val period = (h / 11).coerceAtLeast(24)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val tear = ((y * 37 + 11) % period) < (2 + (amount * 3).toInt())
            val shift = if (tear) (2 + amount * 10).toInt() else 0
            val scan = if (y % 3 == 0) 1f - 0.30f * amount else 1f - 0.07f * amount
            for (x in 0 until w) {
                val sx = (x + shift).coerceIn(0, w - 1)
                val left = row[(sx - (1 + (amount * 2).toInt())).coerceAtLeast(0)]
                val center = row[sx]
                val right = row[(sx + (1 + (amount * 2).toInt())).coerceAtMost(w - 1)]
                val noise = (((x * 1103515245L + y * 12345L + 0x13579B).ushr(16) and 15L).toInt() - 7)
                val r = ((((left shr 16) and 255) * 0.72f + ((center shr 16) and 255) * 0.28f) + noise * amount * 1.8f).toInt()
                val g = (((center shr 8) and 255) + noise * amount).toInt()
                val b = (((right and 255) * 0.72f + (center and 255) * 0.28f) - noise * amount * 1.4f).toInt()
                val rr = (r * scan).toInt().coerceIn(0, 255)
                val gg = (g * scan).toInt().coerceIn(0, 255)
                val bb = (b * scan).toInt().coerceIn(0, 255)
                dst[x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.setPixels(dst, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** Render a selected look into a saved still. */
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
            13 -> dreamGlow(base, strength)
            14 -> halation(base, strength)
            15 -> {
                val sw = (base.width / 10).coerceAtLeast(1)
                val sh = (base.height / 10).coerceAtLeast(1)
                val low = Bitmap.createScaledBitmap(base, sw, sh, true)
                val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawBitmap(low, null, android.graphics.Rect(0, 0, base.width, base.height),
                    Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (strength * 0.24f).toInt() })
                low.recycle()
                out
            }
            16 -> {
                val sw = (base.width / 2).coerceAtLeast(1)
                val sh = (base.height / 2).coerceAtLeast(1)
                val noise = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                val px = IntArray(sw * sh)
                var seed = 0x13579B
                val alpha = (strength * 0.18f).toInt().coerceIn(0, 20)
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
            19 -> vhs(base, strength)
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
