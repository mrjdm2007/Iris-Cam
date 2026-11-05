package com.example.videocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.ln
import kotlin.math.pow

/**
 * Horizontal slider used for zoom (log scale) and exposure (EV linear / ISO log) with A/B marks.
 * Class name kept as ZoomSlider so the file replaces the old one on GitHub.
 */
class ZoomSlider(ctx: Context) : View(ctx) {

    var onChange: ((Float) -> Unit)? = null
    var markA: Float? = null
    var markB: Float? = null
    var minZ = 1f
        private set
    var maxZ = 1f
        private set
    var zoom = 1f
        private set

    private var log = true
    private var ticks: List<Float> = emptyList()
    private var fmt: (Float) -> String = { "$it" }

    private val d = resources.displayMetrics.density
    private val pad = 20f * d

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD91C1F25.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99D6D2C8.toInt()
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = 1.5f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -1
        textSize = 9f * d
        textAlign = Paint.Align.CENTER
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        strokeWidth = 2.5f * d
        textSize = 10f * d
        textAlign = Paint.Align.CENTER
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun frac(z: Float): Float {
        if (maxZ <= minZ) return 0f
        val f = if (log) ln(z.coerceAtLeast(minZ) / minZ) / ln(maxZ / minZ) else (z - minZ) / (maxZ - minZ)
        return f.coerceIn(0f, 1f)
    }

    private fun xFor(f: Float) = pad + f * (width - 2 * pad)

    fun configure(lo: Float, hi: Float, logScale: Boolean, tickValues: List<Float>, formatter: (Float) -> String) {
        minZ = lo
        maxZ = hi
        log = logScale
        ticks = tickValues
        fmt = formatter
        isEnabled = hi > lo * (if (logScale) 1.02f else 1f) + (if (logScale) 0f else 0.01f)
        invalidate()
    }

    fun setZoomSilently(z: Float) {
        zoom = z
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h * 0.58f
        c.drawRoundRect(0f, 0f, w, h, 14 * d, 14 * d, bg)
        c.drawLine(pad, cy, w - pad, cy, track)
        for (z in ticks) {
            if (z < minZ - 0.01f || z > maxZ + 0.01f) continue
            val x = xFor(frac(z))
            c.drawLine(x, cy - 5 * d, x, cy + 5 * d, tick)
            c.drawText(fmt(z), x, cy - 8 * d, text)
        }
        markA?.let {
            val x = xFor(frac(it))
            c.drawLine(x, cy - 11 * d, x, cy + 11 * d, mark)
            c.drawText("A", x, h - 2 * d, mark)
        }
        markB?.let {
            val x = xFor(frac(it))
            c.drawLine(x, cy - 11 * d, x, cy + 11 * d, mark)
            c.drawText("B", x, h - 2 * d, mark)
        }
        thumb.color = if (isEnabled) 0xFFFFB300.toInt() else 0xFF777777.toInt()
        c.drawCircle(xFor(frac(zoom)), cy, 11 * d, thumb)
        if (isEnabled) {
            thumb.color = 0xFFFFF1CC.toInt()
            c.drawCircle(xFor(frac(zoom)), cy, 4 * d, thumb)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                zoom = if (log) minZ * (maxZ / minZ).pow(f) else minZ + f * (maxZ - minZ)
                invalidate()
                onChange?.invoke(zoom)
            }
        }
        return true
    }
}
