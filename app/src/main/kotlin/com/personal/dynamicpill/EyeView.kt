package com.personal.dynamicpill

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs

/**
 * A cartoon eye. Gaze and blinking are pure functions of the clock, so two eyes placed on either side of
 * the camera always look the same way and blink together without sharing any state.
 */
class EyeView(context: Context) : View(context) {

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val eyeBounds = RectF()
    private var gazeX = 0f
    private var gazeY = 0f

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = TICK_MS
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }

    var active = false
        set(value) {
            if (field == value) return
            field = value
            if (value) ticker.start() else ticker.cancel()
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
        val now = SystemClock.uptimeMillis()
        val openness = opennessAt(now)
        val eyeHeight = height * openness
        eyeBounds.set(0f, (height - eyeHeight) / 2f, width.toFloat(), (height + eyeHeight) / 2f)
        canvas.drawOval(eyeBounds, whitePaint)
        if (openness < PUPIL_MIN_OPENNESS) return

        updateGaze(now)
        val pupilRadius = width * PUPIL_RATIO
        val travelX = width / 2f - pupilRadius
        val travelY = eyeHeight / 2f - pupilRadius
        canvas.drawCircle(width / 2f + gazeX * travelX, height / 2f + gazeY * travelY, pupilRadius, pupilPaint)
    }

    /** 1 when open, dipping briefly towards [BLINK_MIN_OPENNESS] once per [BLINK_PERIOD_MS]. */
    private fun opennessAt(now: Long): Float {
        val phase = now % BLINK_PERIOD_MS
        if (phase >= BLINK_MS) return 1f
        val closeness = 1f - abs(phase - BLINK_MS / 2f) / (BLINK_MS / 2f)
        return 1f - (1f - BLINK_MIN_OPENNESS) * closeness
    }

    private fun updateGaze(now: Long) {
        val segment = now / GAZE_MS
        val progress = smoothstep(((now % GAZE_MS) / GAZE_TRANSITION_MS.toFloat()).coerceIn(0f, 1f))
        gazeX = targetX(segment - 1) + (targetX(segment) - targetX(segment - 1)) * progress
        gazeY = targetY(segment - 1) + (targetY(segment) - targetY(segment - 1)) * progress
    }

    private fun targetX(segment: Long): Float {
        val value = hash(segment)
        return if (value % CENTER_ODDS == 0) 0f else (value / HASH_STEP % GAZE_RANGE) / GAZE_HALF_RANGE - 1f
    }

    private fun targetY(segment: Long): Float =
        ((hash(segment + Y_SEED_OFFSET) / HASH_STEP % GAZE_RANGE) / GAZE_HALF_RANGE - 1f) * VERTICAL_TRAVEL

    private fun hash(segment: Long): Int = ((segment * HASH_MULTIPLIER) ushr HASH_SHIFT).toInt() and HASH_MASK

    private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

    private companion object {
        const val TICK_MS = 1_000L
        const val BLINK_PERIOD_MS = 4_300L
        const val BLINK_MS = 180L
        const val BLINK_MIN_OPENNESS = 0.1f
        const val PUPIL_MIN_OPENNESS = 0.35f
        const val PUPIL_RATIO = 0.3f
        const val GAZE_MS = 2_200L
        const val GAZE_TRANSITION_MS = 350L
        const val VERTICAL_TRAVEL = 0.6f
        const val CENTER_ODDS = 4
        const val HASH_MULTIPLIER = 2_654_435_761L
        const val HASH_SHIFT = 16
        const val HASH_MASK = 0xFFFF
        const val HASH_STEP = 4
        const val GAZE_RANGE = 200
        const val GAZE_HALF_RANGE = 100f
        const val Y_SEED_OFFSET = 97L
    }
}
