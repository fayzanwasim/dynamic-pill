package com.personal.dynamicpill

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs

/** A sleeping cat: closed eyes beside the camera, "z"s drifting up, and now and then one eye peeks open. */
class SleepyCatEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val lidPaint = stroke(Color.WHITE, LID_STROKE_DP)
    private val eyePaint = fill(Color.WHITE)
    private val pupilPaint = fill(Color.BLACK)
    private val textPaint = fill(Color.WHITE).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val arc = RectF()

    override fun draw(canvas: Canvas) {
        val radius = dp(EYE_RADIUS_DP)
        val peeking = cycle(PEEK_PERIOD_MS) < PEEK_FRACTION
        if (peeking) drawOpenEye(canvas, frame.leftSideX, radius) else drawClosedEye(canvas, frame.leftSideX, radius)
        drawClosedEye(canvas, frame.rightSideX, radius)
        drawSnores(canvas)
    }

    /** A "‿" lid: the lower part of a circle. */
    private fun drawClosedEye(canvas: Canvas, x: Float, radius: Float) {
        arc.set(x - radius, frame.centerY - radius, x + radius, frame.centerY + radius)
        canvas.drawArc(arc, LID_START_DEGREES, LID_SWEEP_DEGREES, false, lidPaint)
    }

    private fun drawOpenEye(canvas: Canvas, x: Float, radius: Float) {
        canvas.drawCircle(x, frame.centerY, radius, eyePaint)
        canvas.drawCircle(x + radius * PUPIL_OFFSET, frame.centerY, radius * PUPIL_RATIO, pupilPaint)
    }

    /** Three "z"s rise from beside the right eye, growing and fading. */
    private fun drawSnores(canvas: Canvas) {
        for (index in 0 until SNORES) {
            val progress = cycle(SNORE_PERIOD_MS, index * SNORE_PERIOD_MS / SNORES)
            textPaint.textSize = dp(SNORE_MIN_SP + SNORE_GROW_SP * progress)
            textPaint.alpha = alphaOf(1f - progress)
            val x = frame.rightSideX + dp(SNORE_X_DP) + progress * dp(SNORE_DRIFT_DP)
            val y = frame.height * (SNORE_START_Y - SNORE_RISE * progress)
            canvas.drawText(SNORE, x, y, textPaint)
        }
    }

    private companion object {
        const val SNORE = "z"
        const val EYE_RADIUS_DP = 5f
        const val LID_STROKE_DP = 2f
        const val LID_START_DEGREES = 20f
        const val LID_SWEEP_DEGREES = 140f
        const val PUPIL_OFFSET = 0.3f
        const val PUPIL_RATIO = 0.5f
        const val PEEK_PERIOD_MS = 7_000L
        const val PEEK_FRACTION = 0.17f
        const val SNORES = 3
        const val SNORE_PERIOD_MS = 2_400L
        const val SNORE_MIN_SP = 7f
        const val SNORE_GROW_SP = 4f
        const val SNORE_X_DP = 4f
        const val SNORE_DRIFT_DP = 6f
        const val SNORE_START_Y = 0.85f
        const val SNORE_RISE = 0.6f
    }
}

/** A pair of eyes slides out from behind the camera, looks around, and hides again, alternating sides. */
class PeekabooEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val eyePaint = fill(Color.WHITE)
    private val pupilPaint = fill(Color.BLACK)

    override fun draw(canvas: Canvas) {
        val progress = cycle(PERIOD_MS)
        val reveal = smooth(
            when {
                progress < SLIDE_FRACTION -> progress / SLIDE_FRACTION
                progress < HIDE_START -> 1f
                progress < HIDE_START + SLIDE_FRACTION -> 1f - (progress - HIDE_START) / SLIDE_FRACTION
                else -> 0f
            },
        )
        if (reveal <= 0f) return
        val direction = if (cycleIndex(PERIOD_MS) % 2 == 0) -1f else 1f
        // Starting behind the lens, so the eyes really appear to emerge from the camera.
        val x = frame.centerX + direction * (frame.cutoutHalf + reveal * frame.sideWidth * TRAVEL_RATIO)
        val radius = dp(EYE_RADIUS_DP)
        val gap = dp(EYE_GAP_DP)
        for (side in EYE_SIDES) {
            val eyeX = x + side * gap
            canvas.drawCircle(eyeX, frame.centerY, radius, eyePaint)
            canvas.drawCircle(eyeX + direction * radius * PUPIL_OFFSET, frame.centerY, radius * PUPIL_RATIO, pupilPaint)
        }
    }

    private companion object {
        val EYE_SIDES = floatArrayOf(-1f, 1f)
        const val PERIOD_MS = 4_000L
        const val SLIDE_FRACTION = 0.2f
        const val HIDE_START = 0.65f
        const val TRAVEL_RATIO = 0.55f
        const val EYE_RADIUS_DP = 3.2f
        const val EYE_GAP_DP = 4.5f
        const val PUPIL_OFFSET = 0.35f
        const val PUPIL_RATIO = 0.55f
    }
}

/** Rosy cheeks under the eyes (the eyes themselves are glances that sometimes squint happily). */
class BlushEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = fill(BLUSH_COLOR)
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        paint.alpha = alphaOf(MIN_OPACITY + (1f - MIN_OPACITY) * (0.5f + 0.5f * wave(PULSE_MS)))
        val eyeHalf = dp(EYE_WIDTH_DP) / 2f
        val leftEye = frame.edgePadding + eyeHalf
        val rightEye = frame.width - frame.edgePadding - eyeHalf
        drawCheek(canvas, leftEye - dp(OUTWARD_DP))
        drawCheek(canvas, rightEye + dp(OUTWARD_DP))
    }

    private fun drawCheek(canvas: Canvas, x: Float) {
        val y = frame.centerY + dp(BELOW_DP)
        oval.set(x - dp(WIDTH_DP) / 2f, y - dp(HEIGHT_DP) / 2f, x + dp(WIDTH_DP) / 2f, y + dp(HEIGHT_DP) / 2f)
        canvas.drawOval(oval, paint)
    }

    private companion object {
        val BLUSH_COLOR = Color.parseColor("#FF8FAB")
        const val MIN_OPACITY = 0.45f
        const val PULSE_MS = 3_000L
        const val EYE_WIDTH_DP = 14f
        const val OUTWARD_DP = 2f
        const val BELOW_DP = 7f
        const val WIDTH_DP = 8f
        const val HEIGHT_DP = 4f
    }
}

/** A little ghost floats across the pill, bobbing, and turns see-through as it passes the camera. */
class GhostEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val bodyPaint = fill(Color.WHITE)
    private val eyePaint = fill(Color.BLACK)
    private val body = Path()
    private val bounds = RectF()
    private var builtSize = 0f

    override fun draw(canvas: Canvas) {
        val size = frame.height * SIZE_RATIO
        if (size != builtSize) buildBody(size)
        val x = -size + cycle(PERIOD_MS) * (frame.width + 2 * size)
        val y = frame.centerY - size / 2f + wave(BOB_MS) * dp(BOB_DP)
        val distanceFromLens = abs(x + size / 2f - frame.centerX) / (frame.cutoutWidth * FADE_RANGE)
        bodyPaint.alpha = alphaOf(MIN_OPACITY + (1f - MIN_OPACITY) * distanceFromLens)
        canvas.save()
        canvas.translate(x, y)
        canvas.drawPath(body, bodyPaint)
        canvas.drawCircle(size * EYE_LEFT, size * EYE_Y, size * EYE_RADIUS, eyePaint)
        canvas.drawCircle(size * EYE_RIGHT, size * EYE_Y, size * EYE_RADIUS, eyePaint)
        canvas.restore()
    }

    /** Round head, straight sides and a wavy hem, in a [size] x [size] box. */
    private fun buildBody(size: Float) {
        builtSize = size
        bounds.set(0f, 0f, size, size)
        body.reset()
        body.moveTo(0f, size / 2f)
        body.arcTo(bounds, HEAD_START_DEGREES, HEAD_SWEEP_DEGREES)
        body.lineTo(size, size)
        body.quadTo(size * 5f / 6f, size * HEM_UP, size * 2f / 3f, size)
        body.quadTo(size / 2f, size * HEM_DOWN, size / 3f, size)
        body.quadTo(size / 6f, size * HEM_UP, 0f, size)
        body.close()
    }

    private companion object {
        const val SIZE_RATIO = 0.42f
        const val PERIOD_MS = 6_000L
        const val BOB_MS = 1_200L
        const val BOB_DP = 2f
        const val FADE_RANGE = 1.5f
        const val MIN_OPACITY = 0.3f
        const val HEAD_START_DEGREES = 180f
        const val HEAD_SWEEP_DEGREES = 180f
        const val HEM_UP = 0.82f
        const val HEM_DOWN = 1.12f
        const val EYE_LEFT = 0.35f
        const val EYE_RIGHT = 0.65f
        const val EYE_Y = 0.45f
        const val EYE_RADIUS = 0.08f
    }
}

/** Bunny ears peek up beside the camera, swaying, with one ear flopping over now and then. */
class BunnyEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val outerPaint = fill(Color.WHITE)
    private val innerPaint = fill(INNER_COLOR)
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val sway = wave(SWAY_MS) * SWAY_DEGREES
        val flop = flopAngle()
        val baseY = frame.height + dp(BASE_BELOW_DP)
        drawEar(canvas, frame.rightSideX - dp(EAR_SPACING_DP), baseY, -EAR_TILT_DEGREES + sway - flop)
        drawEar(canvas, frame.rightSideX + dp(EAR_SPACING_DP), baseY, EAR_TILT_DEGREES + sway)
    }

    /** The left ear folds over for a moment once per period, easing down and back up. */
    private fun flopAngle(): Float {
        val progress = cycle(FLOP_PERIOD_MS)
        if (progress > FLOP_FRACTION) return 0f
        val half = FLOP_FRACTION / 2f
        val amount = if (progress < half) progress / half else (FLOP_FRACTION - progress) / half
        return smooth(amount) * FLOP_DEGREES
    }

    private fun drawEar(canvas: Canvas, baseX: Float, baseY: Float, degrees: Float) {
        val width = dp(EAR_WIDTH_DP)
        val height = frame.height * EAR_HEIGHT_RATIO
        canvas.save()
        canvas.rotate(degrees, baseX, baseY)
        oval.set(baseX - width / 2f, baseY - height, baseX + width / 2f, baseY)
        canvas.drawOval(oval, outerPaint)
        oval.inset(width * INNER_INSET, height * INNER_INSET)
        canvas.drawOval(oval, innerPaint)
        canvas.restore()
    }

    private companion object {
        val INNER_COLOR = Color.parseColor("#FFB3C7")
        const val EAR_WIDTH_DP = 6f
        const val EAR_HEIGHT_RATIO = 0.75f
        const val EAR_SPACING_DP = 4f
        const val EAR_TILT_DEGREES = 8f
        const val BASE_BELOW_DP = 2f
        const val INNER_INSET = 0.22f
        const val SWAY_MS = 2_400L
        const val SWAY_DEGREES = 4f
        const val FLOP_PERIOD_MS = 4_500L
        const val FLOP_FRACTION = 0.25f
        const val FLOP_DEGREES = 70f
    }
}
