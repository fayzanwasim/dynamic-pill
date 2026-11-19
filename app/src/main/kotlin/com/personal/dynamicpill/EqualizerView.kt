package com.personal.dynamicpill

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Animated equalizer bars. Bars follow real audio [setLevels] when provided and otherwise
 * move with a smooth procedural pattern, so the pill still feels alive without audio access.
 */
class EqualizerView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val levels = FloatArray(BAR_COUNT) { MIN_LEVEL }
    private var externalLevels: FloatArray? = null

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = TICK_MS
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { step() }
    }

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    var active = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                ticker.start()
            } else {
                ticker.cancel()
                levels.fill(MIN_LEVEL)
                invalidate()
            }
        }

    fun setLevels(newLevels: FloatArray?) {
        externalLevels = newLevels
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active && !ticker.isRunning) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val barWidth = width / (BAR_COUNT * 2 - 1f)
        levels.forEachIndexed { index, level ->
            val barHeight = max(barWidth, height * level)
            val left = index * 2 * barWidth
            val top = (height - barHeight) / 2f
            canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    private fun step() {
        val seconds = SystemClock.uptimeMillis() / MS_PER_SECOND
        val external = externalLevels
        levels.indices.forEach { index ->
            val target = external?.getOrNull(index) ?: simulatedLevel(seconds, index)
            val rate = if (target > levels[index]) ATTACK_RATE else DECAY_RATE
            levels[index] += (target - levels[index]) * rate
        }
        invalidate()
    }

    private fun simulatedLevel(seconds: Float, index: Int): Float =
        MIN_LEVEL + (1f - MIN_LEVEL) * abs(sin(seconds * (BASE_SPEED + SPEED_STEP * index) + index * PHASE_STEP))

    companion object {
        const val BAR_COUNT = 5
        private const val MIN_LEVEL = 0.18f
        private const val ATTACK_RATE = 0.6f
        private const val DECAY_RATE = 0.18f
        private const val BASE_SPEED = 2.1f
        private const val SPEED_STEP = 0.7f
        private const val PHASE_STEP = 1.3f
        private const val TICK_MS = 1_000L
        private const val MS_PER_SECOND = 1_000f
    }
}
