package com.example.videocam

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Lightweight still-photo bokeh enhancement using the existing on-device subject-mask model. */
object PhotoBokehEnhancement {
    private val segmenter: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }

    private data class Highlight(val x: Int, val y: Int, val color: Int, val brightness: Int)

    fun apply(source: Bitmap, strength: Int, glow: Int, shape: Int): Bitmap? {
        if (source.width < 16 || source.height < 16 || strength <= 0) return null
        val scale = min(1f, 1024f / max(source.width, source.height))
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        val small = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        try {
            val result = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)), 60, TimeUnit.SECONDS)
            val buffer = result.foregroundConfidenceMask?.duplicate() ?: return null
            val mask = FloatArray(w * h)
            if (buffer.remaining() < mask.size) return null
            buffer.get(mask)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            val candidates = ArrayList<Highlight>()
            val occupied = ArrayList<Pair<Int, Int>>()
            // Sparse local-maximum search avoids treating large bright surfaces as bokeh.
            for (y in 3 until h - 3 step 3) {
                for (x in 3 until w - 3 step 3) {
                    if (mask[y * w + x] > 0.28f) continue
                    val color = pixels[y * w + x]
                    val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    if (lum < 205) continue
                    var localMax = true
                    var localMin = 255
                    for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                        if (dx == 0 && dy == 0) continue
                        val p = pixels[(y + dy) * w + x + dx]
                        val q = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                        if (q > lum + 3) localMax = false
                        localMin = min(localMin, q)
                    }
                    if (!localMax || lum - localMin < 18) continue
                    if (occupied.any { (ox, oy) -> (ox - x) * (ox - x) + (oy - y) * (oy - y) < 13 * 13 }) continue
                    occupied.add(x to y)
                    candidates.add(Highlight(x, y, color, lum))
                    if (candidates.size >= 70) break
                }
                if (candidates.size >= 70) break
            }
            if (candidates.isEmpty()) return null
            val output = small.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(output)
            val baseRadius = 2.5f + strength.coerceIn(0, 100) * 0.075f
            val glowAmount = glow.coerceIn(0, 100) / 100f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val bounds = RectF()
            candidates.forEach { point ->
                val radius = baseRadius * (0.72f + (point.brightness - 205).coerceIn(0, 50) / 125f)
                val x = point.x.toFloat(); val y = point.y.toFloat()
                val color = Color.rgb(Color.red(point.color), Color.green(point.color), Color.blue(point.color))
                if (glowAmount > 0f) {
                    val gr = radius * (1.8f + glowAmount * 1.7f)
                    glowPaint.shader = RadialGradient(x, y, gr,
                        intArrayOf(Color.argb((75 * glowAmount).roundToInt(), Color.red(color), Color.green(color), Color.blue(color)),
                            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))),
                        null, Shader.TileMode.CLAMP)
                    canvas.drawCircle(x, y, gr, glowPaint)
                    glowPaint.shader = null
                }
                val path = shapePath(shape, x, y, radius)
                fill.color = Color.argb(34 + (strength * 0.42f).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
                // Feather the highlight itself; do not draw a separate hard outline.
                fill.maskFilter = BlurMaskFilter(max(1f, radius * 0.32f), BlurMaskFilter.Blur.NORMAL)
                if (shape.coerceIn(0, 3) == 1) {
                    bounds.set(x - radius * 1.35f, y - radius * 0.78f, x + radius * 1.35f, y + radius * 0.78f)
                    canvas.drawOval(bounds, fill)
                } else {
                    canvas.drawPath(path, fill)
                }
                fill.maskFilter = null
            }
            return if (small === source) output else Bitmap.createScaledBitmap(output, source.width, source.height, true).also { output.recycle() }
        } finally {
            if (small !== source) small.recycle()
        }
    }

    private fun shapePath(shape: Int, cx: Float, cy: Float, radius: Float): Path {
        val path = Path()
        when (shape.coerceIn(0, 3)) {
            2 -> {
                for (i in 0 until 8) {
                    val a = -Math.PI / 2.0 + i * Math.PI / 4.0
                    val x = cx + cos(a).toFloat() * radius
                    val y = cy + sin(a).toFloat() * radius
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            3 -> {
                for (i in 0..36) {
                    val t = i / 36f
                    val a = t * Math.PI * 2.2
                    val rr = radius * (0.18f + 0.82f * t)
                    val x = cx + cos(a).toFloat() * rr
                    val y = cy + sin(a).toFloat() * rr
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            else -> path.addCircle(cx, cy, radius, Path.Direction.CW)
        }
        return path
    }
}
