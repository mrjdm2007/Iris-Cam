package com.example.videocam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.pow

/** Per-lens colour trim for phone JPEGs, plus the statistics used by "match lens colour". */
object ColorTrim {

    class Stats(val sat: Float, val rbLog: Float, val gLog: Float)

    fun matrix(t: Trim): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(t.sat)
        val wb = floatArrayOf(
            1f + 0.12f * t.warm, 0f, 0f, 0f, 0f,
            0f, 1f - 0.08f * t.tint, 0f, 0f, 0f,
            0f, 0f, 1f - 0.12f * t.warm, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        m.postConcat(ColorMatrix(wb))
        val c = t.contrast
        val off = (1f - c) * 127.5f
        val ct = floatArrayOf(
            c, 0f, 0f, 0f, off,
            0f, c, 0f, 0f, off,
            0f, 0f, c, 0f, off,
            0f, 0f, 0f, 1f, 0f
        )
        m.postConcat(ColorMatrix(ct))
        return m
    }

    fun apply(src: Bitmap, t: Trim, rotDeg: Int): Bitmap {
        val swap = rotDeg == 90 || rotDeg == 270
        val out = Bitmap.createBitmap(
            if (swap) src.height else src.width, if (swap) src.width else src.height, Bitmap.Config.ARGB_8888
        )
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(matrix(t))
        val m = android.graphics.Matrix()
        if (rotDeg != 0) {
            m.postRotate(rotDeg.toFloat(), src.width / 2f, src.height / 2f)
            if (swap) m.postTranslate((src.height - src.width) / 2f, (src.width - src.height) / 2f)
        }
        c.drawBitmap(src, m, p)
        return out
    }

    /** Average saturation and colour balance of a (downsampled) picture. */
    fun stats(bmp: Bitmap): Stats {
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var ss = 0.0
        var n = 0
        val hsv = FloatArray(3)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                Color.RGBToHSV(r, g, b, hsv)
                sr += r
                sg += g
                sb += b
                ss += hsv[1]
                n++
                x += 3
            }
            y += 3
        }
        if (n == 0) return Stats(1f, 0f, 0f)
        val mr = sr / n + 1.0
        val mg = sg / n + 1.0
        val mb = sb / n + 1.0
        return Stats(
            (ss / n).toFloat().coerceAtLeast(0.01f),
            ln(mr / mb).toFloat(),
            ln(mg / ((mr + mb) / 2.0)).toFloat()
        )
    }
}

/**
 * Develops a RAW_SENSOR frame into a normal picture using the phone's own sensor data for that shot:
 * black/white levels, lens shading map, white balance gains and the colour matrix.
 * Then a gentle S-curve is applied (not a strong "look"), and the lens trim on top.
 */
object RawDeveloper {

    fun develop(image: Image, chars: CameraCharacteristics, result: TotalCaptureResult, trim: Trim): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val sb = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val rowShorts = plane.rowStride / 2

        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        // colour at each position of the 2x2 Bayer cell (index = row * 2 + column): 0 = R, 1 = G, 2 = B
        val ch = when (cfa) {
            1 -> intArrayOf(1, 0, 2, 1) // GRBG
            2 -> intArrayOf(1, 2, 0, 1) // GBRG
            3 -> intArrayOf(2, 1, 1, 0) // BGGR
            else -> intArrayOf(0, 1, 1, 2) // RGGB
        }
        // channel numbering used by gains and the shading map: 0 = R, 1 = G (red row), 2 = G (blue row), 3 = B
        val shCh = IntArray(4) { p ->
            val x = p and 1
            val y = p shr 1
            when (ch[p]) {
                0 -> 0
                2 -> 3
                else -> if (ch[y * 2 + (1 - x)] == 0) 1 else 2
            }
        }

        val whiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        val bp = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = FloatArray(4) { p -> (bp?.getOffsetForIndex(p and 1, p shr 1) ?: 0).toFloat() }

        val gv = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val gains = floatArrayOf(gv?.red ?: 1f, gv?.greenEven ?: 1f, gv?.greenOdd ?: 1f, gv?.blue ?: 1f)

        // lens shading map (removes colour/brightness fall-off towards the corners)
        val lsm = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        var cols = 0
        var rows = 0
        var grid: FloatArray? = null
        if (lsm != null && lsm.columnCount > 1 && lsm.rowCount > 1) {
            cols = lsm.columnCount
            rows = lsm.rowCount
            val g = FloatArray(4 * rows * cols)
            for (c in 0 until 4) {
                for (r in 0 until rows) {
                    for (k in 0 until cols) g[(c * rows + r) * cols + k] = lsm.getGainFactor(c, k, r)
                }
            }
            grid = g
        }

        // 1) black level, shading, white balance -> 16-bit linear plane
        val gridA = grid
        val colsA = cols
        val rowsA = rows
        val lin = ShortArray(w * h)
        java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
            val py = y and 1
            var r0 = 0
            var r1 = 0
            var rf = 0f
            if (gridA != null) {
                val fy = y.toFloat() / (h - 1) * (rowsA - 1)
                r0 = fy.toInt().coerceAtMost(rowsA - 2)
                r1 = r0 + 1
                rf = fy - r0
            }
            val rowBase = y * rowShorts
            for (x in 0 until w) {
                val p = (py shl 1) or (x and 1)
                var v = (sb.get(rowBase + x).toInt() and 0xFFFF).toFloat()
                v = (v - black[p]) / (whiteLevel - black[p])
                if (v < 0f) v = 0f
                val sc = shCh[p]
                if (gridA != null) {
                    val fx = x.toFloat() / (w - 1) * (colsA - 1)
                    val c0 = fx.toInt().coerceAtMost(colsA - 2)
                    val cf = fx - c0
                    val b0 = sc * rowsA
                    val g00 = gridA[(b0 + r0) * colsA + c0]
                    val g01 = gridA[(b0 + r0) * colsA + c0 + 1]
                    val g10 = gridA[(b0 + r1) * colsA + c0]
                    val g11 = gridA[(b0 + r1) * colsA + c0 + 1]
                    val top = g00 + (g01 - g00) * cf
                    val bot = g10 + (g11 - g10) * cf
                    v *= top + (bot - top) * rf
                }
                v *= gains[sc]
                if (v > 1f) v = 1f
                lin[y * w + x] = (v * 65535f).toInt().toShort()
            }
        }

        // 2) colour matrix from the shot: white-balanced sensor RGB -> linear sRGB
        val tr = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val m = FloatArray(9)
        if (tr != null) {
            for (r in 0 until 3) for (c in 0 until 3) m[r * 3 + c] = tr.getElement(c, r).toFloat()
        } else {
            m[0] = 1f
            m[4] = 1f
            m[8] = 1f
        }

        // 3) tone curve: sRGB encoding with a gentle S-curve and the lens contrast trim
        val lut = FloatArray(4096) { i ->
            val x = i / 4095f
            var y = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
            val ss = y * y * (3f - 2f * y)
            y += 0.35f * (ss - y)
            y = 0.5f + (y - 0.5f) * trim.contrast
            y.coerceIn(0f, 1f) * 255f
        }
        val rW = 1f + 0.12f * trim.warm
        val gW = 1f - 0.08f * trim.tint
        val bW = 1f - 0.12f * trim.warm
        val sat = trim.sat
        val inv = 1f / 65535f

        // 4) demosaic (bilinear), colour matrix, tone, trim; written in bands to save memory
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val band = 32
        val nBands = (h + band - 1) / band
        java.util.stream.IntStream.range(0, nBands).parallel().forEach { bi ->
            val y0 = bi * band
            val buf = IntArray(w * band)
            val rowsN = minOf(band, h - y0)
            for (by in 0 until rowsN) {
                val y = y0 + by
                val ym = if (y > 0) y - 1 else y + 1
                val yp = if (y < h - 1) y + 1 else y - 1
                val rc = y * w
                val rn = ym * w
                val rs = yp * w
                val py = y and 1
                for (x in 0 until w) {
                    val xm = if (x > 0) x - 1 else x + 1
                    val xp = if (x < w - 1) x + 1 else x - 1
                    val px = x and 1
                    val c = ch[py * 2 + px]
                    val ctr = lin[rc + x].toInt() and 0xFFFF
                    val r: Int
                    val g: Int
                    val b: Int
                    when (c) {
                        0 -> {
                            r = ctr
                            g = ((lin[rc + xm].toInt() and 0xFFFF) + (lin[rc + xp].toInt() and 0xFFFF) +
                                (lin[rn + x].toInt() and 0xFFFF) + (lin[rs + x].toInt() and 0xFFFF)) shr 2
                            b = ((lin[rn + xm].toInt() and 0xFFFF) + (lin[rn + xp].toInt() and 0xFFFF) +
                                (lin[rs + xm].toInt() and 0xFFFF) + (lin[rs + xp].toInt() and 0xFFFF)) shr 2
                        }
                        2 -> {
                            b = ctr
                            g = ((lin[rc + xm].toInt() and 0xFFFF) + (lin[rc + xp].toInt() and 0xFFFF) +
                                (lin[rn + x].toInt() and 0xFFFF) + (lin[rs + x].toInt() and 0xFFFF)) shr 2
                            r = ((lin[rn + xm].toInt() and 0xFFFF) + (lin[rn + xp].toInt() and 0xFFFF) +
                                (lin[rs + xm].toInt() and 0xFFFF) + (lin[rs + xp].toInt() and 0xFFFF)) shr 2
                        }
                        else -> {
                            g = ctr
                            val hAvg = ((lin[rc + xm].toInt() and 0xFFFF) + (lin[rc + xp].toInt() and 0xFFFF)) shr 1
                            val vAvg = ((lin[rn + x].toInt() and 0xFFFF) + (lin[rs + x].toInt() and 0xFFFF)) shr 1
                            if (ch[py * 2 + (px xor 1)] == 0) {
                                r = hAvg
                                b = vAvg
                            } else {
                                b = hAvg
                                r = vAvg
                            }
                        }
                    }
                    val rf = r * inv * rW
                    val gf = g * inv * gW
                    val bf = b * inv * bW
                    val cr = (m[0] * rf + m[1] * gf + m[2] * bf).coerceIn(0f, 1f)
                    val cg = (m[3] * rf + m[4] * gf + m[5] * bf).coerceIn(0f, 1f)
                    val cb = (m[6] * rf + m[7] * gf + m[8] * bf).coerceIn(0f, 1f)
                    var rr = lut[(cr * 4095f).toInt()]
                    var gg = lut[(cg * 4095f).toInt()]
                    var bb = lut[(cb * 4095f).toInt()]
                    if (sat != 1f) {
                        val l = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                        rr = (l + (rr - l) * sat).coerceIn(0f, 255f)
                        gg = (l + (gg - l) * sat).coerceIn(0f, 255f)
                        bb = (l + (bb - l) * sat).coerceIn(0f, 255f)
                    }
                    buf[by * w + x] = (0xFF shl 24) or (rr.toInt() shl 16) or (gg.toInt() shl 8) or bb.toInt()
                }
            }
            synchronized(bmp) { bmp.setPixels(buf, 0, w, 0, y0, w, rowsN) }
        }
        return bmp
    }
}
