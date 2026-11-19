package com.personal.dynamicpill

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import kotlin.math.cos
import kotlin.math.sin

/** The glowing outline, slowly cycling through the colors of the rainbow. */
class RainbowEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = stroke(Color.WHITE, STROKE_DP)
    private val bounds = RectF()
    private val matrix = Matrix()
    private var builtKey = -1f

    override fun draw(canvas: Canvas) {
        val key = frame.width * frame.height
        if (key != builtKey) {
            builtKey = key
            paint.shader = SweepGradient(frame.centerX, frame.centerY, RAINBOW, null)
        }
        matrix.setRotate(FULL_TURN_DEGREES * cycle(PERIOD_MS), frame.centerX, frame.centerY)
        paint.shader.setLocalMatrix(matrix)
        val inset = paint.strokeWidth / 2f
        bounds.set(inset, inset, frame.width - inset, frame.height - inset)
        canvas.drawRoundRect(bounds, frame.cornerRadius - inset, frame.cornerRadius - inset, paint)
    }

    private companion object {
        val RAINBOW = intArrayOf(
            Color.parseColor("#FF453A"),
            Color.parseColor("#FF9F0A"),
            Color.parseColor("#FFD60A"),
            Color.parseColor("#30D158"),
            Color.parseColor("#0A84FF"),
            Color.parseColor("#BF5AF2"),
            Color.parseColor("#FF453A"),
        )
        const val STROKE_DP = 2.2f
        const val PERIOD_MS = 4_000L
        const val FULL_TURN_DEGREES = 360f
    }
}

/** Little fireworks burst in colorful sparks around the camera. */
class FireworksEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = fill(Color.WHITE)

    override fun draw(canvas: Canvas) {
        for (burst in 0 until BURSTS) {
            val offset = burst * PERIOD_MS / BURSTS
            val progress = cycle(PERIOD_MS, offset)
            val seed = cycleIndex(PERIOD_MS, offset) * SEED_STRIDE + burst
            val x = frame.width * (MARGIN + SPAN * random(seed))
            val y = frame.height * (TOP + HEIGHT_SPAN * random(seed + 1))
            val spread = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            val radius = spread * frame.height * BURST_RATIO
            val fade = 1f - progress
            paint.color = PALETTE[(random(seed + 2) * PALETTE.size).toInt() % PALETTE.size]
            paint.alpha = alphaOf(fade * fade)
            val rotation = random(seed + 3) * TAU
            for (spark in 0 until SPARKS) {
                val angle = rotation + TAU * spark / SPARKS
                canvas.drawCircle(x + cos(angle) * radius, y + sin(angle) * radius, dp(SPARK_DP) * (1f - progress / 2f), paint)
            }
        }
    }

    private companion object {
        val PALETTE = intArrayOf(
            Color.parseColor("#FFD60A"),
            Color.parseColor("#FF375F"),
            Color.parseColor("#30D158"),
            Color.parseColor("#64D2FF"),
            Color.parseColor("#BF5AF2"),
        )
        const val BURSTS = 2
        const val PERIOD_MS = 1_400L
        const val SEED_STRIDE = 31
        const val MARGIN = 0.12f
        const val SPAN = 0.76f
        const val TOP = 0.35f
        const val HEIGHT_SPAN = 0.3f
        const val BURST_RATIO = 0.42f
        const val SPARKS = 10
        const val SPARK_DP = 1.3f
    }
}

/** A golden crescent moon beside the camera with a few twinkling stars; used for Ramadan. */
class CrescentEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val moonPaint = fill(MOON_COLOR)
    private val starPaint = fill(MOON_COLOR)
    private val crescent = Path()
    private val cutout = Path()
    private var builtRadius = -1f

    override fun draw(canvas: Canvas) {
        val radius = frame.height * MOON_RATIO
        if (radius != builtRadius) buildCrescent(radius)
        moonPaint.alpha = alphaOf(MOON_MIN_OPACITY + (1f - MOON_MIN_OPACITY) * (0.5f + 0.5f * wave(GLOW_MS)))
        canvas.save()
        canvas.translate(frame.leftSideX, frame.centerY)
        canvas.drawPath(crescent, moonPaint)
        canvas.restore()
        for (index in 0 until STARS) {
            val x = frame.rightSideX + (STAR_X[index] * frame.sideWidth)
            val y = frame.centerY + (STAR_Y[index] * frame.height)
            starPaint.alpha = alphaOf(0.5f + 0.5f * wave(TWINKLE_MS, index * STAR_PHASE))
            canvas.drawCircle(x, y, dp(STAR_DP), starPaint)
        }
    }

    /** A full circle minus a slightly smaller, shifted circle leaves the crescent. */
    private fun buildCrescent(radius: Float) {
        builtRadius = radius
        crescent.reset()
        crescent.addCircle(0f, 0f, radius, Path.Direction.CW)
        cutout.reset()
        cutout.addCircle(radius * CUT_SHIFT_X, -radius * CUT_SHIFT_Y, radius * CUT_RATIO, Path.Direction.CW)
        crescent.op(cutout, Path.Op.DIFFERENCE)
    }

    private companion object {
        val MOON_COLOR = Color.parseColor("#FFD86B")
        const val MOON_RATIO = 0.3f
        const val MOON_MIN_OPACITY = 0.8f
        const val GLOW_MS = 3_000L
        const val CUT_SHIFT_X = 0.45f
        const val CUT_SHIFT_Y = 0.15f
        const val CUT_RATIO = 0.85f
        const val STARS = 3
        val STAR_X = floatArrayOf(-0.25f, 0.05f, 0.25f)
        val STAR_Y = floatArrayOf(-0.2f, 0.15f, -0.05f)
        const val STAR_DP = 1.3f
        const val TWINKLE_MS = 1_500L
        const val STAR_PHASE = 2.1f
    }
}
