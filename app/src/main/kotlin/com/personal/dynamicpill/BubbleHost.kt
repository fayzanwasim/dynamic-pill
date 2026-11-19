package com.personal.dynamicpill

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.WindowManager

/**
 * Owns the overlay window of the detached bubble. The window only exists while the bubble is on screen,
 * so an idle bubble never swallows touches next to the camera.
 */
class BubbleHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val metrics: IslandMetrics,
    private val onClick: () -> Unit,
) {
    private var bubble: BubbleView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = metrics.offsetY
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = TITLE
    }

    /** Pops the bubble out to the left of a compact pill that is [pillWidth] pixels wide, badged with [count]. */
    fun show(icon: Drawable?, pillWidth: Int, count: Int) {
        val view = bubble ?: createBubble()
        reposition(pillWidth)
        view.setCount(count)
        view.show(icon)
    }

    fun reposition(pillWidth: Int) {
        val view = bubble ?: return
        params.x = metrics.offsetX - (pillWidth / 2 + context.dp(GAP_DP) + metrics.compactHeight / 2)
        windowManager.updateViewLayout(view, params)
    }

    fun hide() {
        bubble?.hide(::release)
    }

    fun release() {
        bubble?.let { windowManager.removeView(it) }
        bubble = null
    }

    private fun createBubble(): BubbleView {
        val speed = metrics.animationSpeed / SPEED_PERCENT
        val view = BubbleView(context, metrics.compactHeight, speed).apply { onBubbleClick = onClick }
        windowManager.addView(view, params)
        bubble = view
        return view
    }

    private companion object {
        const val SPEED_PERCENT = 100f
        const val TITLE = "DynamicPillBubble"
        const val GAP_DP = 6
    }
}
