package com.example.videocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Vertical slider. fraction 0 = top (far / infinity), 1 = bottom (nearest focus). */
class VerticalSlider(ctx: Context) : View(ctx) {

    var onChange: ((Float) -> Unit)? = null
    var fraction = 0f
        private set
    var markA: Float? = null
    var markB: Float? = null

    private val density = resources.displayMetrics.density
    private val pad = 40f * density

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = 1.5f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color_WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        strokeWidth = 2.5f * density
        textSize = 13f * density
        textAlign = Paint.Align.RIGHT
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun yFor(f: Float) = pad + f * (height - 2 * pad)

    fun setFraction(f: Float) {
        fraction = f.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 28 * density, 28 * density, bg)
        canvas.drawLine(cx, pad, cx, height - pad, track)
        for (i in 0..10) {
            val y = yFor(i / 10f)
            val half = if (i % 5 == 0) 11 * density else 6 * density
            canvas.drawLine(cx - half, y, cx + half, y, tick)
        }
        canvas.drawText("\u221E", cx, pad - 14 * density, text)
        canvas.drawText("NEAR", cx, height - pad + 26 * density, text)

        markA?.let {
            val y = yFor(it)
            canvas.drawLine(cx - 20 * density, y, cx + 20 * density, y, markPaint)
            canvas.drawText("A", cx - 24 * density, y + 5 * density, markPaint)
        }
        markB?.let {
            val y = yFor(it)
            canvas.drawLine(cx - 20 * density, y, cx + 20 * density, y, markPaint)
            canvas.drawText("B", cx - 24 * density, y + 5 * density, markPaint)
        }

        thumb.color = if (isEnabled) 0xFFFFB300.toInt() else 0xFF777777.toInt()
        canvas.drawCircle(cx, yFor(fraction), 16 * density, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fraction = ((e.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
                invalidate()
                onChange?.invoke(fraction)
            }
        }
        return true
    }

    private companion object {
        const val Color_WHITE = -1 // 0xFFFFFFFF
    }
}
