package com.example.videocam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Builds an Ultra HDR JPEG (SDR picture + gain map, standard Google/Adobe format) from two frames of the same scene:
 * the normal exposure and one that is [factor] times darker (keeps the highlights the normal frame lost).
 * The normal frame's JPEG bytes are kept untouched; only metadata and the gain map are added.
 */
object UltraHdr {
    private const val OFFSET = 0.015625f

    fun build(base: ByteArray, dark: ByteArray, factor: Float): ByteArray? {
        if (factor < 1.5f) return null
        val gain = makeGainMap(base, dark, factor) ?: return null
        return mux(base, gain, factor)
    }

    private fun lin(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun decode(b: ByteArray, sample: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeByteArray(b, 0, b.size, o)
    }

    private fun makeGainMap(base: ByteArray, dark: ByteArray, factor: Float): ByteArray? {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(base, 0, base.size, bo)
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return null
        var sample = 1
        while (max(bo.outWidth, bo.outHeight) / sample > 640) sample *= 2
        val bb = decode(base, sample) ?: return null
        val db = decode(dark, sample)
        if (db == null || db.width != bb.width || db.height != bb.height) {
            bb.recycle()
            db?.recycle()
            return null
        }
        val w = bb.width
        val h = bb.height
        val pb = IntArray(w * h)
        val pd = IntArray(w * h)
        bb.getPixels(pb, 0, w, 0, 0, w, h)
        db.getPixels(pd, 0, w, 0, 0, w, h)
        bb.recycle()
        db.recycle()

        val yb = IntArray(w * h)
        val yd = IntArray(w * h)
        for (i in pb.indices) {
            val p = pb[i]
            yb[i] = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
            val q = pd[i]
            yd[i] = (77 * ((q shr 16) and 0xFF) + 150 * ((q shr 8) and 0xFF) + 29 * (q and 0xFF)) shr 8
        }

        // line the dark frame up with the normal one (small hand shake between the two shots)
        val gb = grad(yb, w, h)
        val gd = grad(yd, w, h)
        var sb = 1L
        var sd = 1L
        for (i in gb.indices) {
            sb += gb[i]
            sd += gd[i]
        }
        val norm = sb.toFloat() / sd.toFloat()
        val r = min(10, min(w, h) / 8)
        var bestDx = 0
        var bestDy = 0
        var best = Long.MAX_VALUE
        for (dy in -r..r) {
            for (dx in -r..r) {
                var sad = 0L
                var y = r
                while (y < h - r) {
                    var x = r
                    val row = y * w
                    val rowD = (y + dy) * w + dx
                    while (x < w - r) {
                        sad += abs(gb[row + x] - (gd[rowD + x] * norm).toInt())
                        x += 2
                    }
                    y += 2
                }
                if (sad < best) {
                    best = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val g = FloatArray(w * h)
        val lf = ln(factor)
        for (y in 0 until h) {
            val yy = (y + bestDy).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val xx = (x + bestDx).coerceIn(0, w - 1)
                val i = y * w + x
                val p = pb[i]
                val maxc = max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF)) / 255f
                val t = ((maxc - 0.55f) / 0.40f).coerceIn(0f, 1f)
                val wgt = t * t * (3f - 2f * t)
                if (wgt <= 0f) continue
                val lb = lin(yb[i] / 255f)
                val ld = lin(yd[yy * w + xx] / 255f) * factor
                val hdr = lb + (max(ld, lb) - lb) * wgt
                val gain = ((hdr + OFFSET) / (lb + OFFSET)).coerceIn(1f, factor)
                g[i] = ln(gain) / lf
            }
        }
        val sm = blur(blur(g, w, h, 2), w, h, 2)

        val px = IntArray(w * h)
        for (i in px.indices) {
            val v = (sm[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val gm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        gm.setPixels(px, 0, w, 0, 0, w, h)
        val bos = ByteArrayOutputStream()
        gm.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        gm.recycle()
        return bos.toByteArray()
    }

    private fun grad(y: IntArray, w: Int, h: Int): IntArray {
        val g = IntArray(w * h)
        for (j in 1 until h - 1) {
            for (i in 1 until w - 1) {
                val k = j * w + i
                g[k] = abs(y[k + 1] - y[k - 1]) + abs(y[k + w] - y[k - w])
            }
        }
        return g
    }

    private fun blur(a: FloatArray, w: Int, h: Int, rad: Int): FloatArray {
        val t = FloatArray(w * h)
        val o = FloatArray(w * h)
        val n = (2 * rad + 1).toFloat()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (k in -rad..rad) s += a[y * w + (x + k).coerceIn(0, w - 1)]
                t[y * w + x] = s / n
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (k in -rad..rad) s += t[(y + k).coerceIn(0, h - 1) * w + x]
                o[y * w + x] = s / n
            }
        }
        return o
    }

    private fun app(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        val o = ByteArray(payload.size + 4)
        o[0] = 0xFF.toByte()
        o[1] = marker.toByte()
        o[2] = (len shr 8).toByte()
        o[3] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, o, 4, payload.size)
        return o
    }

    private fun xmpPayload(xml: String): ByteArray = ("http://ns.adobe.com/xap/1.0/\u0000" + xml).toByteArray(Charsets.UTF_8)

    /** Position just after the SOI marker and any APPn segments (where new metadata goes). */
    private fun insertAt(j: ByteArray): Int {
        var i = 2
        while (i + 4 <= j.size && (j[i].toInt() and 0xFF) == 0xFF) {
            val m = j[i + 1].toInt() and 0xFF
            if (m in 0xE0..0xEF) {
                val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
                i += 2 + len
            } else {
                break
            }
        }
        return i
    }

    private fun mux(base: ByteArray, gainJpeg: ByteArray, factor: Float): ByteArray? {
        if (base.size < 4 || (base[0].toInt() and 0xFF) != 0xFF || (base[1].toInt() and 0xFF) != 0xD8) return null
        if (gainJpeg.size < 4 || (gainJpeg[0].toInt() and 0xFF) != 0xFF || (gainJpeg[1].toInt() and 0xFF) != 0xD8) return null
        val maxLog = ln(factor) / ln(2f)
        val ml = String.format(Locale.US, "%.3f", maxLog)

        val gainXmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" " +
            "hdrgm:Version=\"1.0\" hdrgm:GainMapMin=\"0\" hdrgm:GainMapMax=\"$ml\" hdrgm:Gamma=\"1\" " +
            "hdrgm:OffsetSDR=\"0.015625\" hdrgm:OffsetHDR=\"0.015625\" hdrgm:HDRCapacityMin=\"0\" " +
            "hdrgm:HDRCapacityMax=\"$ml\" hdrgm:BaseRenditionIsHDR=\"False\"/></rdf:RDF></x:xmpmeta>"
        val gseg = app(0xE1, xmpPayload(gainXmp))
        val gainFull = ByteArrayOutputStream().apply {
            write(gainJpeg, 0, 2)
            write(gseg)
            write(gainJpeg, 2, gainJpeg.size - 2)
        }.toByteArray()

        val priXmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" " +
            "xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" " +
            "xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\">" +
            "<Container:Directory><rdf:Seq>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" " +
            "Item:Length=\"${gainFull.size}\"/></rdf:li>" +
            "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"
        val pseg = app(0xE1, xmpPayload(priXmp))

        val ins = insertAt(base)
        val mpfSegSize = 90 // marker 2 + length 2 + payload 86
        val primaryTotal = base.size + pseg.size + mpfSegSize
        val iiPos = ins + pseg.size + 2 + 2 + 4
        val secondaryOffset = primaryTotal - iiPos

        val bb = ByteBuffer.allocate(86).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'F'.code.toByte(), 0))
        bb.put(byteArrayOf('I'.code.toByte(), 'I'.code.toByte()))
        bb.putShort(0x002A)
        bb.putInt(8)
        bb.putShort(3)
        bb.putShort(0xB000.toShort()); bb.putShort(7); bb.putInt(4)
        bb.put(byteArrayOf('0'.code.toByte(), '1'.code.toByte(), '0'.code.toByte(), '0'.code.toByte()))
        bb.putShort(0xB001.toShort()); bb.putShort(4); bb.putInt(1); bb.putInt(2)
        bb.putShort(0xB002.toShort()); bb.putShort(7); bb.putInt(32); bb.putInt(50)
        bb.putInt(0)
        bb.putInt(0x20030000); bb.putInt(primaryTotal); bb.putInt(0); bb.putShort(0); bb.putShort(0)
        bb.putInt(0); bb.putInt(gainFull.size); bb.putInt(secondaryOffset); bb.putShort(0); bb.putShort(0)
        val mseg = app(0xE2, bb.array())

        val out = ByteArrayOutputStream(primaryTotal + gainFull.size)
        out.write(base, 0, ins)
        out.write(pseg)
        out.write(mseg)
        out.write(base, ins, base.size - ins)
        out.write(gainFull)
        return out.toByteArray()
    }

    /** True/false if this phone's decoder recognises the gain map in the file, null if it can't tell. */
    fun recognised(bytes: ByteArray): Boolean? {
        if (Build.VERSION.SDK_INT < 34) return null
        return try {
            val o = BitmapFactory.Options()
            o.inSampleSize = 16
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return null
            val r = b.hasGainmap()
            b.recycle()
            r
        } catch (_: Throwable) {
            null
        }
    }
}
