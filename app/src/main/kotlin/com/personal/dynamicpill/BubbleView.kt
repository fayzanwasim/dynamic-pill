package com.personal.dynamicpill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The small detached circle that pops out beside the pill when something new arrives while another
 * activity (such as music) owns the pill. It springs out from behind the pill and back.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, private val size: Int, private val speed: Float) : FrameLayout(context) {

    var onBubbleClick: (() -> Unit)? = null

    private var shown = false
    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = circle()
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private val badge = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = BADGE_TEXT_SP
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BADGE_COLOR)
        }
        visibility = GONE
    }

    private val scaleXSpring = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f)
    private val scaleYSpring = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f)
    private val shiftSpring = SpringAnimation(this, DynamicAnimation.TRANSLATION_X, 0f)

    init {
        background = circle()
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        val inner = (size * ICON_RATIO).roundToInt()
        addView(icon, LayoutParams(inner, inner, Gravity.CENTER))
        // The badge sits on the upper-right diagonal, fully inside the circle so the outline never clips it.
        val badgeSize = (size * BADGE_RATIO).roundToInt()
        addView(
            badge,
            LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.START).apply {
                leftMargin = (size * BADGE_LEFT_RATIO).roundToInt()
                topMargin = (size * BADGE_TOP_RATIO).roundToInt()
            },
        )
        visibility = INVISIBLE
        setOnClickListener { onBubbleClick?.invoke() }
    }

    /** Shows how many events are waiting behind the bubble; a single event shows no badge. */
    fun setCount(count: Int) {
        badge.visibility = if (count > 1) VISIBLE else GONE
        badge.text = NumberFormat.getIntegerInstance().format(count)
    }

    fun show(drawable: Drawable?) {
        icon.setImageDrawable(drawable)
        shown = true
        animate().cancel()
        if (visibility != VISIBLE) {
            scaleX = HIDDEN_SCALE
            scaleY = HIDDEN_SCALE
            translationX = hiddenShift()
            alpha = 0f
            visibility = VISIBLE
        }
        animate().alpha(1f).setDuration((FADE_MS / speed).toLong()).start()
        scaleXSpring.springTo(1f)
        scaleYSpring.springTo(1f)
        shiftSpring.springTo(0f)
    }

    /** Springs back behind the pill, then calls [onHidden] if the bubble has not been shown again meanwhile. */
    fun hide(onHidden: () -> Unit) {
        shown = false
        scaleXSpring.springTo(HIDDEN_SCALE)
        scaleYSpring.springTo(HIDDEN_SCALE)
        shiftSpring.springTo(hiddenShift())
        animate().cancel()
        animate().alpha(0f).setDuration((FADE_MS / speed).toLong()).withEndAction {
            if (!shown) {
                visibility = INVISIBLE
                onHidden()
            }
        }.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
        )
    }

    /** The bubble sits left of the pill, so "behind the pill" is to its right. */
    private fun hiddenShift(): Float = size * HIDDEN_SHIFT_RATIO

    private fun SpringAnimation.springTo(target: Float) {
        spring.setStiffness(STIFFNESS * speed * speed).setDampingRatio(DAMPING_RATIO)
        animateToFinalPosition(target)
    }

    private fun circle() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.BLACK)
    }

    private companion object {
        const val ICON_RATIO = 0.64f
        const val BADGE_RATIO = 0.36f
        const val BADGE_LEFT_RATIO = 0.53f
        const val BADGE_TOP_RATIO = 0.11f
        const val BADGE_TEXT_SP = 9f
        val BADGE_COLOR = Color.parseColor("#FF453A")
        const val HIDDEN_SCALE = 0.3f
        const val HIDDEN_SHIFT_RATIO = 1.2f
        const val STIFFNESS = 380f
        const val DAMPING_RATIO = 0.55f
        const val FADE_MS = 160L
    }
}
