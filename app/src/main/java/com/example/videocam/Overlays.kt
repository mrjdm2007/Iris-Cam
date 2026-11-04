package com.example.videocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Thin red line around the screen edges while recording. */
class EdgeLineView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        val h = p.strokeWidth / 2f
        c.drawRect(h, h, width - h, height - h, p)
    }
}

/** Small vertical audio level meter (segments, peak hold). Level is 0..100. */
class AudioMeterView(ctx: Context) : View(ctx) {
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0
    private var peak = 0f

    fun setLevel(pct: Int) {
        val l = pct.coerceIn(0, 100)
        if (l == level && peak <= l) return
        level = l
        peak = if (l > peak) l.toFloat() else (peak - 2.5f).coerceAtLeast(l.toFloat())
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val n = 16
        val gap = 2f * resources.displayMetrics.density
        val sh = (height - gap * (n - 1)) / n
        for (i in 0 until n) {
            val frac = (i + 1) / n.toFloat() * 100f
            val on = level >= frac - 3f
            val isPeak = !on && peak >= frac - 3f && peak < frac + 100f / n
            val base = when {
                frac > 88f -> 0xFFE53935.toInt()
                frac > 70f -> 0xFFFFB300.toInt()
                else -> 0xFF43A047.toInt()
            }
            seg.color = if (on) base else if (isPeak) base else Color.argb(70, 255, 255, 255)
            val bottom = height - i * (sh + gap)
            c.drawRoundRect(0f, bottom - sh, width.toFloat(), bottom, 2f, 2f, seg)
        }
    }
}
