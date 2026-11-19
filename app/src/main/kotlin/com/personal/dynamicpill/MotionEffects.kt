package com.personal.dynamicpill

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/** A ball bounces from one end of the pill to the other, squashing a little each time it lands. */
class BounceEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = fill(frame.accent)
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val radius = dp(RADIUS_DP)
        val margin = radius + dp(MARGIN_DP)
        val progress = cycle(TRAVERSE_MS)
        val there = if (progress < 0.5f) progress * 2f else 2f - progress * 2f
        val x = margin + there * (frame.width - 2 * margin)
        val bouncePhase = (there * BOUNCES) % 1f
        val lift = sin(TAU / 2f * bouncePhase)
        val floor = frame.height - radius - dp(MARGIN_DP)
        val ceiling = frame.height * CEILING_RATIO
        val bottom = floor - lift * (floor - ceiling) + radius
        // Squash only in the moment of contact with the floor.
        val squash = if (lift < CONTACT) 1f - lift / CONTACT else 0f
        val halfWidth = radius * (1f + SQUASH_WIDEN * squash)
        val height = 2 * radius * (1f - SQUASH_FLATTEN * squash)
        oval.set(x - halfWidth, bottom - height, x + halfWidth, bottom)
        canvas.drawOval(oval, paint)
    }

    private companion object {
        const val RADIUS_DP = 3.2f
        const val MARGIN_DP = 3f
        const val TRAVERSE_MS = 4_000L
        const val BOUNCES = 3
        const val CEILING_RATIO = 0.25f
        const val CONTACT = 0.12f
        const val SQUASH_WIDEN = 0.35f
        const val SQUASH_FLATTEN = 0.3f
    }
}

/** Soft blobs of color drift, merge and split like a lava lamp; the camera area stays black. */
class LavaEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paints = BLOB_COLORS.map { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val shade = CameraShade(frame)
    private var builtHeight = -1f

    override fun draw(canvas: Canvas) {
        if (frame.height != builtHeight) buildShaders()
        BLOB_COLORS.indices.forEach { index ->
            val seed = index * SEED_STRIDE
            val periodX = (DRIFT_X_MS * (1f + random(seed))).toLong()
            val periodY = (DRIFT_Y_MS * (1f + random(seed + 1))).toLong()
            val x = frame.width * (MARGIN + SPAN * (0.5f + 0.5f * sin(TAU * cycle(periodX) + random(seed + 2) * TAU)))
            val y = frame.centerY + frame.height * WOBBLE * sin(TAU * cycle(periodY) + random(seed + 3) * TAU)
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, radiusFor(index), paints[index])
            canvas.restore()
        }
        shade.draw(canvas)
    }

    /** Gradients are centred on the origin and moved with the canvas, so they are built once per size. */
    private fun buildShaders() {
        builtHeight = frame.height
        BLOB_COLORS.forEachIndexed { index, color ->
            val center = Color.argb(BLOB_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
            paints[index].shader = RadialGradient(0f, 0f, radiusFor(index), center, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    private fun radiusFor(index: Int): Float = frame.height * (MIN_RADIUS + RADIUS_SPREAD * random(index * SEED_STRIDE + 4))

    private companion object {
        val BLOB_COLORS = listOf(Color.parseColor("#FF5E9C"), Color.parseColor("#FF9F43"), Color.parseColor("#8E54E9"))
        const val BLOB_ALPHA = 210
        const val SEED_STRIDE = 17
        const val MARGIN = 0.12f
        const val SPAN = 0.76f
        const val WOBBLE = 0.18f
        const val DRIFT_X_MS = 9_000L
        const val DRIFT_Y_MS = 5_000L
        const val MIN_RADIUS = 0.45f
        const val RADIUS_SPREAD = 0.2f
    }
}

/** Rings spread outward from the camera, like a pebble dropped in a pond. */
class RippleEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = stroke(frame.accent, STROKE_DP)

    override fun draw(canvas: Canvas) {
        val maxRadius = frame.width / 2f
        for (ring in 0 until RINGS) {
            val progress = cycle(PERIOD_MS, ring * PERIOD_MS / RINGS)
            paint.alpha = alphaOf((1f - progress) * MAX_OPACITY)
            canvas.drawCircle(frame.centerX, frame.centerY, frame.cutoutHalf + progress * (maxRadius - frame.cutoutHalf), paint)
        }
    }

    private companion object {
        const val RINGS = 2
        const val PERIOD_MS = 2_400L
        const val STROKE_DP = 1.5f
        const val MAX_OPACITY = 0.8f
    }
}

/** A tiny bob swings back and forth beneath the camera. */
class PendulumEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val stringPaint = stroke(frame.accent, STRING_DP).apply { alpha = STRING_ALPHA }
    private val bobPaint = fill(frame.accent)

    override fun draw(canvas: Canvas) {
        val angle = MAX_ANGLE_RADIANS * wave(PERIOD_MS)
        val length = frame.cutoutHalf + dp(LENGTH_DP)
        val bobX = frame.centerX + length * sin(angle)
        val bobY = frame.centerY + length * cos(angle)
        canvas.drawLine(frame.centerX, frame.centerY, bobX, bobY, stringPaint)
        canvas.drawCircle(bobX, bobY, dp(BOB_DP), bobPaint)
    }

    private companion object {
        const val PERIOD_MS = 2_000L
        const val MAX_ANGLE_RADIANS = 0.9f
        const val LENGTH_DP = 6f
        const val STRING_DP = 1f
        const val STRING_ALPHA = 160
        const val BOB_DP = 2.5f
    }
}

/** A soft band of light sweeps across the pill every few seconds, like a polished surface. */
class ShimmerEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var builtHeight = -1f

    override fun draw(canvas: Canvas) {
        val bandWidth = frame.height * BAND_RATIO
        if (frame.height != builtHeight) {
            builtHeight = frame.height
            paint.shader = LinearGradient(
                0f,
                0f,
                bandWidth,
                0f,
                intArrayOf(Color.TRANSPARENT, SHINE_COLOR, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        val progress = cycle(PERIOD_MS) / SWEEP_FRACTION
        if (progress > 1f) return
        matrix.setSkew(SKEW, 0f)
        matrix.postTranslate(-bandWidth + progress * (frame.width + 2 * bandWidth), 0f)
        paint.shader.setLocalMatrix(matrix)
        canvas.drawRect(0f, 0f, frame.width, frame.height, paint)
    }

    private companion object {
        val SHINE_COLOR = Color.argb(90, 255, 255, 255)
        const val BAND_RATIO = 1.2f
        const val PERIOD_MS = 3_500L
        const val SWEEP_FRACTION = 0.35f
        const val SKEW = -0.5f
    }
}
