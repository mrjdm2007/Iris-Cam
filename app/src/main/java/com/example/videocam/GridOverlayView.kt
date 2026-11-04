package com.example.videocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Composition grids, plus letterbox bars that show the frame of a cinematic-ratio resolution drawn over the preview. Not clickable. */
class GridOverlayView(ctx: Context) : View(ctx) {

    companion object {
        val GRID_NAMES = listOf("Off", "Thirds", "Golden ratio", "Golden spiral", "Center cross", "Diagonals", "4\u00D74", "Fibonacci")
                private const val PHI = 1.618034
    }

    var grid = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var ratio = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var flip = 0
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEEFFFFFF.toInt()
        strokeWidth = 0.8f * density
        style = Paint.Style.STROKE
    }
    private val lineShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        strokeWidth = 1.8f * density
        style = Paint.Style.STROKE
    }
    private val spiralShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
    }
    private val spiral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        strokeWidth = 1.1f * density
        style = Paint.Style.STROKE
    }
    private val bar = Paint().apply { color = 0xCC000000.toInt() }
    private val edge = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 1f * density
    }

    private fun seg(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, lineShadow)
        c.drawLine(x1, y1, x2, y2, line)
    }

    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        c.drawRect(l, t, r, b, lineShadow)
        c.drawRect(l, t, r, b, line)
    }

    private fun vLine(c: Canvas, f: Float, w: Float, h: Float) = seg(c, w * f, 0f, w * f, h)
    private fun hLine(c: Canvas, f: Float, w: Float, h: Float) = seg(c, 0f, h * f, w, h * f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        when (grid) {
            1 -> {
                vLine(c, 1 / 3f, w, h); vLine(c, 2 / 3f, w, h)
                hLine(c, 1 / 3f, w, h); hLine(c, 2 / 3f, w, h)
            }
            2, 3 -> {
                vLine(c, 0.382f, w, h); vLine(c, 0.618f, w, h)
                hLine(c, 0.382f, w, h); hLine(c, 0.618f, w, h)
                if (grid == 3) drawSpiral(c, w, h)
            }
            4 -> {
                seg(c, w / 2, h * 0.4f, w / 2, h * 0.6f)
                seg(c, w * 0.45f, h / 2, w * 0.55f, h / 2)
            }
            5 -> {
                seg(c, 0f, 0f, w, h)
                seg(c, w, 0f, 0f, h)
            }
            7 -> drawFibonacci(c, w, h)
            6 -> {
                for (i in 1..3) {
                    vLine(c, i / 4f, w, h)
                    hLine(c, i / 4f, w, h)
                }
            }
        }

        val ar = Ratios.VALUES.getOrElse(ratio) { 0f }
        if (ar > 0f) {
            val frameAr = w / h
            if (ar > frameAr) {
                val visH = w / ar
                val b = (h - visH) / 2f
                c.drawRect(0f, 0f, w, b, bar)
                c.drawRect(0f, h - b, w, h, bar)
                c.drawLine(0f, b, w, b, edge)
                c.drawLine(0f, h - b, w, h - b, edge)
            } else {
                val visW = h * ar
                val b = (w - visW) / 2f
                c.drawRect(0f, 0f, b, h, bar)
                c.drawRect(w - b, 0f, w, h, bar)
                c.drawLine(b, 0f, b, h, edge)
                c.drawLine(w - b, 0f, w - b, h, edge)
            }
        }
    }

    /** Fibonacci squares in a golden rectangle with the spiral running through them. */
    private fun drawFibonacci(c: Canvas, w: Float, h: Float) {
        val phi = PHI.toFloat()
        val gw = min(w, h * phi)
        val gh = gw / phi
        var l = (w - gw) / 2f
        var t = (h - gh) / 2f
        var r = l + gw
        var b = t + gh
        val path = Path()
        c.save()
        c.scale(if ((flip and 1) != 0) -1f else 1f, if ((flip and 2) != 0) -1f else 1f, w / 2f, h / 2f)
        for (i in 0 until 9) {
            when (i % 4) {
                0 -> {
                    val s = b - t
                    box(c, l, t, l + s, b)
                    path.arcTo(RectF(l, b - s, l + 2 * s, b + s), 180f, 90f, i == 0)
                    l += s
                }
                1 -> {
                    val s = r - l
                    box(c, l, t, r, t + s)
                    path.arcTo(RectF(l - s, t, l + s, t + 2 * s), 270f, 90f, false)
                    t += s
                }
                2 -> {
                    val s = b - t
                    box(c, r - s, t, r, b)
                    path.arcTo(RectF(r - 2 * s, t - s, r, t + s), 0f, 90f, false)
                    r -= s
                }
                else -> {
                    val s = r - l
                    box(c, l, b - s, r, b)
                    path.arcTo(RectF(r - s, b - 2 * s, r + s, b), 90f, 90f, false)
                    b -= s
                }
            }
        }
        c.drawPath(path, spiralShadow)
        c.drawPath(path, spiral)
        c.restore()
    }

    /** Logarithmic golden spiral winding clockwise in toward the lower-right phi intersection. */
    private fun drawSpiral(c: Canvas, w: Float, h: Float) {
        val ex = 0.618f * w
        val ey = 0.618f * h
        val r0 = min(ex * 0.95, ey * PHI * 0.98)
        val path = Path()
        var a = PI
        var first = true
        val end = PI + 6 * (PI / 2)
        while (a < end) {
            val r = r0 * PHI.pow(-((a - PI) / (PI / 2)))
            val x = (ex + r * cos(a)).toFloat()
            val y = (ey + r * sin(a)).toFloat()
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            a += 0.04
        }
        c.drawPath(path, spiralShadow)
        c.drawPath(path, spiral)
    }
}
