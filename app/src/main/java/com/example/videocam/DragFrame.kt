package com.example.videocam

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Wraps a control so it can be dragged around while "Edit layout" is on.
 * When not editing, touches go straight to the control inside.
 */
class DragFrame(ctx: Context, val key: String) : FrameLayout(ctx) {

    private val d = resources.displayMetrics.density
    private val outline = GradientDrawable().apply {
        setStroke((2 * d).toInt(), 0xFFFFB300.toInt())
        setColor(0x22FFB300)
    }
    private var dx = 0f
    private var dy = 0f
    private var downX = 0f
    private var downY = 0f

    var onSelected: (() -> Unit)? = null
    var panelSizePercent: Int = 50
        private set
    var isSelectedForResize: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    fun setPanelSizePercent(percent: Int) {
        panelSizePercent = percent.coerceIn(25, 100)
        val scale = panelSizePercent / 50f
        scaleX = scale
        scaleY = scale
    }

    var editing = false
        set(v) {
            field = v
            background = if (v) outline else null
        if (!v) isSelectedForResize = false
        }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = editing

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(e)
        val p = parent as? View ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dx = x - e.rawX
                dy = y - e.rawY
                downX = e.rawX
                downY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                x = (e.rawX + dx).coerceIn(0f, (p.width - width * scaleX).toFloat().coerceAtLeast(0f))
                y = (e.rawY + dy).coerceIn(0f, (p.height - height * scaleY).toFloat().coerceAtLeast(0f))
            }
            MotionEvent.ACTION_UP -> {
                if (kotlin.math.hypot(e.rawX - downX, e.rawY - downY) < 12f * d) onSelected?.invoke()
            }
        }
        return true
    }
}
