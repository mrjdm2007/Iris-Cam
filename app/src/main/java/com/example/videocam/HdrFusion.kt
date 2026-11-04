package com.example.videocam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.graphics.Matrix
import android.os.Build
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * HDR mode: blends a dark, a normal and a bright frame of the same scene so both the highlights and the shadows keep detail.
 * Each pixel is taken mostly from whichever frame exposed it best (exposure fusion), with smooth transitions and a
 * small hand-shake alignment first. The result is an ordinary JPEG that looks right on every screen.
 */
object HdrFusion {
    /** [jpegs] must be in the order dark, normal, bright. Returns an un-rotated bitmap in sensor orientation. */
    fun merge(jpegs: List<ByteArray>): Bitmap? {
        if (jpegs.size == 2) return mergeTwo(jpegs[0], jpegs[1])
        if (jpegs.size < 3) return null
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegs[1], 0, jpegs[1].size, bo)
        val fw = bo.outWidth
        val fh = bo.outHeight
        if (fw <= 0 || fh <= 0) return null
        var sample = 1
        while (max(fw, fh) / sample > 640) sample *= 2

        // ---- small copies: alignment and blend weights ----
        val smalls = ArrayList<Bitmap>()
        for (j in jpegs.take(3)) {
            val o = BitmapFactory.Options()
            o.inSampleSize = sample
            val b = BitmapFactory.decodeByteArray(j, 0, j.size, o) ?: return null
            smalls.add(b)
        }
        val sw = smalls[0].width
        val sh = smalls[0].height
        if (smalls.any { it.width != sw || it.height != sh }) return null
        val lum = Array(3) { k ->
            val px = IntArray(sw * sh)
            smalls[k].getPixels(px, 0, sw, 0, 0, sw, sh)
            smalls[k].recycle()
            IntArray(sw * sh) { i ->
                val p = px[i]
                (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
            }
        }
        val grads = Array(3) { grad(lum[it], sw, sh) }
        val shifts = Array(3) { intArrayOf(0, 0) }
        shifts[0] = align(grads[1], grads[0], sw, sh)
        shifts[2] = align(grads[1], grads[2], sw, sh)

        // weights: well-exposed and detailed pixels count more
        val wt = Array(3) { FloatArray(sw * sh) }
        for (k in 0..2) {
            val dx = shifts[k][0]
            val dy = shifts[k][1]
            val l = lum[k]
            for (y in 0 until sh) {
                val yy = (y + dy).coerceIn(0, sh - 1)
                for (x in 0 until sw) {
                    val xx = (x + dx).coerceIn(0, sw - 1)
                    val v = l[yy * sw + xx] / 255f
                    val we = exp(-((v - 0.5f) * (v - 0.5f)) / (2f * 0.2f * 0.2f))
                    val xl = (xx - 1).coerceAtLeast(0)
                    val xr = (xx + 1).coerceAtMost(sw - 1)
                    val yu = (yy - 1).coerceAtLeast(0)
                    val yd = (yy + 1).coerceAtMost(sh - 1)
                    val lap = abs(4f * l[yy * sw + xx] - l[yy * sw + xl] - l[yy * sw + xr] - l[yu * sw + xx] - l[yd * sw + xx]) / 255f
                    wt[k][y * sw + x] = we * (0.3f + 3f * lap) + 1e-4f
                }
            }
            wt[k] = box(box(wt[k], sw, sh, 4), sw, sh, 4)
        }
        for (i in 0 until sw * sh) {
            val s = wt[0][i] + wt[1][i] + wt[2][i]
            wt[0][i] /= s
            wt[1][i] /= s
            wt[2][i] /= s
        }

        // ---- full-size blend, one row at a time ----
        val full = ArrayList<Bitmap>()
        for (j in jpegs.take(3)) {
            val b = BitmapFactory.decodeByteArray(j, 0, j.size) ?: return null
            if (b.width != fw || b.height != fh) {
                b.recycle()
                return null
            }
            full.add(b)
        }
        val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val sx = fw.toFloat() / sw
        val sy = fh.toFloat() / sh
        val ox = IntArray(3) { (shifts[it][0] * sx).toInt() }
        val oy = IntArray(3) { (shifts[it][1] * sy).toInt() }
        val xi = IntArray(fw)
        val xf = FloatArray(fw)
        for (x in 0 until fw) {
            val fx = ((x + 0.5f) / sx - 0.5f).coerceIn(0f, sw - 1.001f)
            xi[x] = fx.toInt()
            xf[x] = fx - xi[x]
        }
        val rows = Array(3) { IntArray(fw) }
        val outRow = IntArray(fw)
        for (y in 0 until fh) {
            val fy = ((y + 0.5f) / sy - 0.5f).coerceIn(0f, sh - 1.001f)
            val yi = fy.toInt()
            val yf = fy - yi
            for (k in 0..2) {
                val yy = (y + oy[k]).coerceIn(0, fh - 1)
                full[k].getPixels(rows[k], 0, fw, 0, yy, fw, 1)
            }
            for (x in 0 until fw) {
                val a = yi * sw + xi[x]
                val f = xf[x]
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in 0..2) {
                    val w = wt[k]
                    val top = w[a] * (1f - f) + w[a + 1] * f
                    val bot = w[a + sw] * (1f - f) + w[a + sw + 1] * f
                    val wk = top * (1f - yf) + bot * yf
                    val xx = (x + ox[k]).coerceIn(0, fw - 1)
                    val p = rows[k][xx]
                    r += wk * ((p shr 16) and 0xFF)
                    g += wk * ((p shr 8) and 0xFF)
                    b += wk * (p and 0xFF)
                }
                outRow[x] = (0xFF shl 24) or (min(255, (r + 0.5f).toInt()) shl 16) or
                    (min(255, (g + 0.5f).toInt()) shl 8) or min(255, (b + 0.5f).toInt())
            }
            out.setPixels(outRow, 0, fw, 0, y, fw, 1)
        }
        full.forEach { it.recycle() }
        return out
    }


    /** Two-exposure fusion: the first frame protects highlights; the second lifts shadows. */
    private fun mergeTwo(darkJpeg: ByteArray, brightJpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(darkJpeg, 0, darkJpeg.size, bounds)
        val fw = bounds.outWidth
        val fh = bounds.outHeight
        if (fw <= 0 || fh <= 0) return null
        var sample = 1
        while (max(fw, fh) / sample > 480) sample *= 2
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        val dSmall = BitmapFactory.decodeByteArray(darkJpeg, 0, darkJpeg.size, opt) ?: return null
        val bSmall = BitmapFactory.decodeByteArray(brightJpeg, 0, brightJpeg.size, opt) ?: run { dSmall.recycle(); return null }
        if (dSmall.width != bSmall.width || dSmall.height != bSmall.height) {
            dSmall.recycle(); bSmall.recycle(); return null
        }
        val sw = dSmall.width
        val sh = dSmall.height
        val dp = IntArray(sw * sh)
        val bp = IntArray(sw * sh)
        dSmall.getPixels(dp, 0, sw, 0, 0, sw, sh)
        bSmall.getPixels(bp, 0, sw, 0, 0, sw, sh)
        dSmall.recycle(); bSmall.recycle()
        fun luma(p: Int): Int = (77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8
        val dl = IntArray(dp.size) { luma(dp[it]) }
        val bl = IntArray(bp.size) { luma(bp[it]) }
        val shift = align(grad(dl, sw, sh), grad(bl, sw, sh), sw, sh)
        var ds = 0L; var bs = 0L; var dn = 0; var bn = 0
        for (i in dl.indices) {
            if (dl[i] in 12..243) { ds += dl[i]; dn++ }
            if (bl[i] in 12..243) { bs += bl[i]; bn++ }
        }
        // Normalize the brighter exposure into the darker frame's luminance domain.
        val ratio = if (bn > 0 && dn > 0) (ds.toDouble() / dn / (bs.toDouble() / bn)).toFloat().coerceIn(0.12f, 1f) else 0.5f
        val dark = BitmapFactory.decodeByteArray(darkJpeg, 0, darkJpeg.size) ?: return null
        val bright = BitmapFactory.decodeByteArray(brightJpeg, 0, brightJpeg.size) ?: run { dark.recycle(); return null }
        if (dark.width != fw || dark.height != fh || bright.width != fw || bright.height != fh) {
            dark.recycle(); bright.recycle(); return null
        }
        val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val drow = IntArray(fw); val brow = IntArray(fw); val row = IntArray(fw)
        val dx = (shift[0] * fw.toFloat() / sw).toInt()
        val dy = (shift[1] * fh.toFloat() / sh).toInt()
        for (y in 0 until fh) {
            dark.getPixels(drow, 0, fw, 0, y, fw, 1)
            bright.getPixels(brow, 0, fw, 0, (y + dy).coerceIn(0, fh - 1), fw, 1)
            for (x in 0 until fw) {
                val pd = drow[x]
                val pb = brow[(x + dx).coerceIn(0, fw - 1)]
                val ld = luma(pd).toFloat()
                // Smoothly favour the bright frame in deep shadows and the dark frame near highlights.
                val t = ((145f - ld) / 100f).coerceIn(0f, 1f)
                val wBright = t * t * (3f - 2f * t)
                val r1 = (((pb shr 16) and 255) * ratio).coerceIn(0f, 255f)
                val g1 = (((pb shr 8) and 255) * ratio).coerceIn(0f, 255f)
                val b1 = ((pb and 255) * ratio).coerceIn(0f, 255f)
                val r = (((pd shr 16) and 255) * (1f - wBright) + r1 * wBright).toInt().coerceIn(0, 255)
                val g = (((pd shr 8) and 255) * (1f - wBright) + g1 * wBright).toInt().coerceIn(0, 255)
                val b = ((pd and 255) * (1f - wBright) + b1 * wBright).toInt().coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(row, 0, fw, 0, y, fw, 1)
        }
        dark.recycle(); bright.recycle()
        return out
    }

    /**
     * Builds an Ultra HDR gain map from the brighter bracket exposure and attaches it to
     * the fused SDR base. The map stores highlight headroom (1x..4x) at reduced resolution.
     * Returns false on pre-Android-14 devices or if a usable map cannot be produced.
     */
    fun attachGainmap(sdr: Bitmap, brightJpeg: ByteArray, rotationDegrees: Int): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        var bright = BitmapFactory.decodeByteArray(brightJpeg, 0, brightJpeg.size) ?: return false
        var brightSmall: Bitmap? = null
        var sdrSmall: Bitmap? = null
        var gainBitmap: Bitmap? = null
        return try {
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bright, 0, 0, bright.width, bright.height, matrix, false)
                if (rotated !== bright) bright.recycle()
                bright = rotated
            }
            val gw = min(512, max(64, sdr.width / 16))
            val gh = min(512, max(64, sdr.height / 16))
            sdrSmall = Bitmap.createScaledBitmap(sdr, gw, gh, true)
            brightSmall = Bitmap.createScaledBitmap(bright, gw, gh, true)
            val basePixels = IntArray(gw * gh)
            val hdrPixels = IntArray(gw * gh)
            sdrSmall.getPixels(basePixels, 0, gw, 0, 0, gw, gh)
            brightSmall.getPixels(hdrPixels, 0, gw, 0, 0, gw, gh)
            val gainPixels = IntArray(gw * gh)
            var useful = 0
            for (i in gainPixels.indices) {
                val a = basePixels[i]
                val b = hdrPixels[i]
                val baseLuma = (77 * ((a shr 16) and 255) + 150 * ((a shr 8) and 255) + 29 * (a and 255)) / 256f
                val hdrLuma = (77 * ((b shr 16) and 255) + 150 * ((b shr 8) and 255) + 29 * (b and 255)) / 256f
                val ratio = (hdrLuma / max(2f, baseLuma)).coerceIn(1f, 4f)
                val normalized = (kotlin.math.ln(ratio) / kotlin.math.ln(4f)).coerceIn(0f, 1f)
                val v = (normalized * 255f + 0.5f).toInt()
                if (v > 4) useful++
                gainPixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            if (useful < gainPixels.size / 200) return false
            gainBitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            gainBitmap.setPixels(gainPixels, 0, gw, 0, 0, gw, gh)
            val gainmap = Gainmap(gainBitmap)
            gainmap.setRatioMin(floatArrayOf(1f, 1f, 1f))
            gainmap.setRatioMax(floatArrayOf(4f, 4f, 4f))
            gainmap.setGamma(floatArrayOf(1f, 1f, 1f))
            gainmap.setEpsilonSdr(floatArrayOf(0.0001f, 0.0001f, 0.0001f))
            gainmap.setEpsilonHdr(floatArrayOf(0.0001f, 0.0001f, 0.0001f))
            gainmap.setDisplayRatioForFullHdr(4f)
            sdr.setGainmap(gainmap)
            true
        } catch (_: Throwable) {
            false
        } finally {
            bright.recycle()
            brightSmall?.recycle()
            sdrSmall?.recycle()
            gainBitmap?.recycle()
        }
    }

    private fun grad(y: IntArray, w: Int, h: Int): IntArray {
        val g = IntArray(w * h)
        for (j in 1 until h - 1) for (i in 1 until w - 1) {
            val k = j * w + i
            g[k] = abs(y[k + 1] - y[k - 1]) + abs(y[k + w] - y[k - w])
        }
        return g
    }

    /** Shift (in small-image pixels) to read [other] at so it lines up with [ref]. */
    private fun align(ref: IntArray, other: IntArray, w: Int, h: Int): IntArray {
        var sr = 1L
        var so = 1L
        for (i in ref.indices) {
            sr += ref[i]
            so += other[i]
        }
        val norm = sr.toFloat() / so.toFloat()
        val r = min(8, min(w, h) / 8)
        var bx = 0
        var by = 0
        var best = Long.MAX_VALUE
        for (dy in -r..r) for (dx in -r..r) {
            var sad = 0L
            var y = r
            while (y < h - r) {
                var x = r
                val row = y * w
                val rowO = (y + dy) * w + dx
                while (x < w - r) {
                    sad += abs(ref[row + x] - (other[rowO + x] * norm).toInt())
                    x += 2
                }
                y += 2
            }
            if (sad < best) {
                best = sad
                bx = dx
                by = dy
            }
        }
        return intArrayOf(bx, by)
    }

    private fun box(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(w * h)
        val o = FloatArray(w * h)
        val n = (2 * r + 1).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += a[y * w + (x + k).coerceIn(0, w - 1)]
            t[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += t[(y + k).coerceIn(0, h - 1) * w + x]
            o[y * w + x] = s / n
        }
        return o
    }
}
