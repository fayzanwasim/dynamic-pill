package com.personal.dynamicpill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders one [Glance]: an icon, short text, a live timer, equalizer bars or an eye.
 * The content is anchored to the edge given by [edgeGravity] (START or END) with [edgePadding] pixels
 * of space, so it sits at the outer edge of the pill rather than floating in the middle of its slot.
 */
@SuppressLint("ViewConstructor")
class GlanceView(
    context: Context,
    accentColor: Int,
    private val edgeGravity: Int,
    private val edgePadding: Int,
) : FrameLayout(context) {

    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val text = TextView(context).apply { styleAsLabel(this) }
    private val timer = Chronometer(context).apply { styleAsLabel(this) }
    private val bars = EqualizerView(context).apply { color = accentColor }
    private val eye = EyeView(context)
    private var glance: Glance = Glance.None

    init {
        val gravity = Gravity.CENTER_VERTICAL or edgeGravity
        addView(icon, LayoutParams(dp(ICON_DP), dp(ICON_DP), gravity))
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
        addView(timer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
        addView(bars, LayoutParams(dp(BARS_WIDTH_DP), dp(BARS_HEIGHT_DP), gravity))
        addView(eye, LayoutParams(dp(EYE_WIDTH_DP), dp(EYE_HEIGHT_DP), gravity))
        if (edgeGravity == Gravity.START) setPadding(edgePadding, 0, 0, 0) else setPadding(0, 0, edgePadding, 0)
        bind(Glance.None)
    }

    fun bind(newGlance: Glance) {
        glance = newGlance
        icon.visibility = if (newGlance is Glance.Icon) VISIBLE else GONE
        text.visibility = if (newGlance is Glance.Text) VISIBLE else GONE
        timer.visibility = if (newGlance is Glance.Timer) VISIBLE else GONE
        bars.visibility = if (newGlance is Glance.Bars) VISIBLE else GONE
        bars.active = newGlance is Glance.Bars
        eye.visibility = if (newGlance is Glance.Eye) VISIBLE else GONE
        eye.active = newGlance is Glance.Eye

        when (newGlance) {
            is Glance.Icon -> icon.setImageDrawable(newGlance.drawable)
            is Glance.Text -> text.text = newGlance.text
            is Glance.Timer -> {
                timer.isCountDown = newGlance.countDown
                timer.base = newGlance.baseElapsedRealtime
                timer.start()
            }
            Glance.Bars, Glance.Eye, Glance.None -> Unit
        }
        if (newGlance !is Glance.Timer) timer.stop()
    }

    fun setAccent(color: Int) {
        bars.color = color
    }

    fun setAudioLevels(levels: FloatArray?) {
        bars.setLevels(levels)
    }

    /** Width this glance needs, so the compact pill can grow when its content is wider than the default. */
    fun preferredWidth(): Int {
        val content = when (val current = glance) {
            Glance.None -> return 0
            is Glance.Icon -> dp(ICON_DP)
            is Glance.Text -> text.paint.measureText(current.text.toString()).roundToInt()
            is Glance.Timer -> timer.paint.measureText(timerSample(current)).roundToInt()
            Glance.Bars -> dp(BARS_WIDTH_DP)
            Glance.Eye -> dp(EYE_WIDTH_DP)
        }
        return content + edgePadding + dp(INNER_GAP_DP)
    }

    private fun timerSample(timerGlance: Glance.Timer): String {
        val now = SystemClock.elapsedRealtime()
        val shownMs = if (timerGlance.countDown) timerGlance.baseElapsedRealtime - now else now - timerGlance.baseElapsedRealtime
        return if (abs(shownMs) >= MS_PER_HOUR) LONG_TIMER_SAMPLE else SHORT_TIMER_SAMPLE
    }

    private fun styleAsLabel(label: TextView) {
        label.setTextColor(Color.WHITE)
        label.textSize = LABEL_SP
        label.gravity = Gravity.CENTER
        label.isSingleLine = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val ICON_DP = 18
        const val BARS_WIDTH_DP = 18
        const val BARS_HEIGHT_DP = 14
        const val EYE_WIDTH_DP = 14
        const val EYE_HEIGHT_DP = 18
        const val INNER_GAP_DP = 6
        const val LABEL_SP = 12f
        const val MS_PER_HOUR = 3_600_000L
        const val SHORT_TIMER_SAMPLE = "00:00"
        const val LONG_TIMER_SAMPLE = "0:00:00"
    }
}
