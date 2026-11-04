package com.example.videocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Simple luma waveform: x = image column, y = brightness (bottom = black, top = white). */
class WaveformView(ctx: Context) : View(ctx) {

    private var luma = IntArray(0)
    private var cols = 0
    private var rows = 0
    private var pts = FloatArray(0)

    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = 0x88000000.toInt() }
    private val guide = Paint().apply {
        color = 0x55FFFFFF
        strokeWidth = 1f * density
    }
    private val dot = Paint().apply {
        color = 0xCC00FF66.toInt()
        strokeWidth = 2f * density
    }

    fun update(pixels: IntArray, w: Int, h: Int) {
        if (luma.size != w * h) luma = IntArray(w * h)
        cols = w
        rows = h
        for (i in 0 until w * h) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = (77 * r + 150 * g + 29 * b) shr 8
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        for (i in 0..4) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, guide)
        }
        if (cols == 0 || rows == 0) return
        if (pts.size != cols * rows * 2) pts = FloatArray(cols * rows * 2)
        var n = 0
        val sx = w / cols
        for (x in 0 until cols) {
            for (y in 0 until rows) {
                pts[n++] = x * sx + sx / 2f
                pts[n++] = h - luma[y * cols + x] / 255f * h
            }
        }
        canvas.drawPoints(pts, 0, n, dot)
    }
}
