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
        pivotX = width / 2f
        pivotY = height / 2f
        scaleX = scale
        scaleY = scale
        post { clampIntoParent() }
    }

    /** Keep the scaled visual bounds within the preview root. */
    fun clampIntoParent() {
        val p = parent as? View ?: return
        if (width <= 0 || height <= 0 || p.width <= 0 || p.height <= 0) return
        val requestedScale = panelSizePercent / 50f
        val fitScale = minOf(p.width.toFloat() / width, p.height.toFloat() / height)
        val safeScale = requestedScale.coerceAtMost(fitScale.coerceAtLeast(0.25f))
        pivotX = width / 2f
        pivotY = height / 2f
        scaleX = safeScale
        scaleY = safeScale
        val minX = width * (scaleX - 1f) / 2f
        val minY = height * (scaleY - 1f) / 2f
        val maxX = p.width - width * (scaleX + 1f) / 2f
        val maxY = p.height - height * (scaleY + 1f) / 2f
        x = x.coerceIn(minX.coerceAtMost(maxX), maxX.coerceAtLeast(minX))
        y = y.coerceIn(minY.coerceAtMost(maxY), maxY.coerceAtLeast(minY))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { clampIntoParent() }
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
                x = e.rawX + dx
                y = e.rawY + dy
                clampIntoParent()
            }
            MotionEvent.ACTION_UP -> {
                if (kotlin.math.hypot(e.rawX - downX, e.rawY - downY) < 12f * d) onSelected?.invoke()
            }
        }
        return true
    }
}
