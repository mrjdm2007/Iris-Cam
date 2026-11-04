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
        setStroke((2 * d).toInt(), 0xFF00E5FF.toInt())
        setColor(0x2200E5FF)
    }
    private var dx = 0f
    private var dy = 0f

    var editing = false
        set(v) {
            field = v
            background = if (v) outline else null
        }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = editing

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(e)
        val p = parent as? View ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dx = x - e.rawX
                dy = y - e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                x = (e.rawX + dx).coerceIn(0f, (p.width - width).toFloat().coerceAtLeast(0f))
                y = (e.rawY + dy).coerceIn(0f, (p.height - height).toFloat().coerceAtLeast(0f))
            }
        }
        return true
    }
}
