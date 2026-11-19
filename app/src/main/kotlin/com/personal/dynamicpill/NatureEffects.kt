package com.personal.dynamicpill

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.time.LocalTime
import kotlin.math.min
import kotlin.math.sin

/** Keeps the area right around the camera black, so a colorful background still blends into the lens. */
internal class CameraShade(private val frame: EffectFrame) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadeKey = 0f

    fun draw(canvas: Canvas) {
        val radius = frame.cutoutWidth * RADIUS_RATIO
        if (radius <= 0f) return
        val key = radius * frame.width + frame.height
        if (key != shadeKey) {
            shadeKey = key
            paint.shader = RadialGradient(frame.centerX, frame.centerY, radius, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(frame.centerX, frame.centerY, radius, paint)
    }

    private companion object {
        const val RADIUS_RATIO = 1.4f
    }
}

/** Warm fireflies drift around, each slowly brightening and dimming. */
class FirefliesEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val haloPaint = fill(FIREFLY_COLOR)
    private val corePaint = fill(CORE_COLOR)

    override fun draw(canvas: Canvas) {
        for (index in 0 until COUNT) {
            val seed = index * SEED_STRIDE
            val x = frame.width * (MARGIN + SPAN * (0.5f + 0.5f * sin(TAU * cycle(periodFor(seed, DRIFT_X_MS)) + random(seed) * TAU)))
            val y = frame.height * (MARGIN + SPAN * (0.5f + 0.5f * sin(TAU * cycle(periodFor(seed + 1, DRIFT_Y_MS)) + random(seed + 1) * TAU)))
            val brightness = 0.5f + 0.5f * sin(TAU * cycle(periodFor(seed + 2, TWINKLE_MS)) + random(seed + 2) * TAU)
            haloPaint.alpha = alphaOf(HALO_OPACITY * brightness)
            canvas.drawCircle(x, y, dp(HALO_DP), haloPaint)
            corePaint.alpha = alphaOf(CORE_MIN_OPACITY + (1f - CORE_MIN_OPACITY) * brightness)
            canvas.drawCircle(x, y, dp(CORE_DP), corePaint)
        }
    }

    /** Each firefly moves at its own pace, between [base] and 1.6x [base]. */
    private fun periodFor(seed: Int, base: Long): Long = (base * (1f + PACE_SPREAD * random(seed))).toLong()

    private companion object {
        val FIREFLY_COLOR = Color.parseColor("#FFD54F")
        val CORE_COLOR = Color.parseColor("#FFF8E1")
        const val COUNT = 6
        const val SEED_STRIDE = 11
        const val MARGIN = 0.1f
        const val SPAN = 0.8f
        const val DRIFT_X_MS = 7_000L
        const val DRIFT_Y_MS = 4_000L
        const val TWINKLE_MS = 2_000L
        const val PACE_SPREAD = 0.6f
        const val HALO_DP = 4f
        const val CORE_DP = 1.4f
        const val HALO_OPACITY = 0.3f
        const val CORE_MIN_OPACITY = 0.3f
    }
}

/** Snowflakes drift down, swaying, and melt away at the bottom edge. */
class SnowEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = fill(Color.WHITE)

    override fun draw(canvas: Canvas) {
        for (index in 0 until COUNT) {
            val seed = index * SEED_STRIDE
            val period = (FALL_MS * (1f + PACE_SPREAD * random(seed))).toLong()
            val progress = (cycle(period) + random(seed + 1)) % 1f
            val y = -dp(EDGE_DP) + progress * (frame.height + 2 * dp(EDGE_DP))
            val x = frame.width * random(seed + 2) + sin(TAU * (progress * SWAYS + random(seed + 3))) * dp(SWAY_DP)
            paint.alpha = alphaOf(min(1f, (1f - progress) * MELT_FACTOR))
            canvas.drawCircle(x, y, dp(MIN_FLAKE_DP + random(seed + 4)), paint)
        }
    }

    private companion object {
        const val COUNT = 10
        const val SEED_STRIDE = 7
        const val FALL_MS = 3_200L
        const val PACE_SPREAD = 0.6f
        const val EDGE_DP = 2f
        const val SWAYS = 2f
        const val SWAY_DP = 3f
        const val MELT_FACTOR = 5f
        const val MIN_FLAKE_DP = 0.9f
    }
}

/** A sky of twinkling stars with the occasional shooting star. */
class StarsEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val starPaint = fill(Color.WHITE)
    private val sparklePaint = stroke(Color.WHITE, SPARKLE_STROKE_DP)
    private val trailPaint = stroke(Color.WHITE, TRAIL_STROKE_DP)

    override fun draw(canvas: Canvas) {
        for (index in 0 until COUNT) {
            val seed = index * SEED_STRIDE
            val x = frame.width * (MARGIN + SPAN * random(seed))
            val y = frame.height * (MARGIN + SPAN * random(seed + 1))
            val period = (TWINKLE_MS * (1f + random(seed + 2))).toLong()
            val twinkle = 0.5f + 0.5f * sin(TAU * cycle(period) + random(seed + 3) * TAU)
            val radius = dp(MIN_STAR_DP + random(seed + 4))
            starPaint.alpha = alphaOf(MIN_OPACITY + (1f - MIN_OPACITY) * twinkle)
            canvas.drawCircle(x, y, radius, starPaint)
            // The brightest stars get a little cross-shaped sparkle.
            if (random(seed + 5) > SPARKLE_THRESHOLD) {
                sparklePaint.alpha = alphaOf(twinkle * SPARKLE_OPACITY)
                val arm = radius * SPARKLE_ARM
                canvas.drawLine(x - arm, y, x + arm, y, sparklePaint)
                canvas.drawLine(x, y - arm, x, y + arm, sparklePaint)
            }
        }
        drawShootingStar(canvas)
    }

    private fun drawShootingStar(canvas: Canvas) {
        val progress = cycle(SHOOT_PERIOD_MS) / SHOOT_FRACTION
        if (progress > 1f) return
        val seed = cycleIndex(SHOOT_PERIOD_MS) * SEED_STRIDE
        val startX = frame.width * (SHOOT_START_MIN + SHOOT_START_SPAN * random(seed))
        val headX = startX + progress * frame.width * SHOOT_TRAVEL
        val headY = frame.height * (SHOOT_TOP + progress * SHOOT_DROP)
        trailPaint.alpha = alphaOf(1f - progress)
        canvas.drawLine(headX - dp(TRAIL_DP), headY - dp(TRAIL_DP) * SHOOT_SLOPE, headX, headY, trailPaint)
    }

    private companion object {
        const val COUNT = 9
        const val SEED_STRIDE = 5
        const val MARGIN = 0.1f
        const val SPAN = 0.8f
        const val TWINKLE_MS = 1_800L
        const val MIN_STAR_DP = 0.7f
        const val MIN_OPACITY = 0.2f
        const val SPARKLE_THRESHOLD = 0.7f
        const val SPARKLE_STROKE_DP = 0.8f
        const val SPARKLE_OPACITY = 0.8f
        const val SPARKLE_ARM = 2.6f
        const val SHOOT_PERIOD_MS = 6_000L
        const val SHOOT_FRACTION = 0.12f
        const val SHOOT_START_MIN = 0.05f
        const val SHOOT_START_SPAN = 0.4f
        const val SHOOT_TRAVEL = 0.45f
        const val SHOOT_TOP = 0.15f
        const val SHOOT_DROP = 0.6f
        const val SHOOT_SLOPE = 0.6f
        const val TRAIL_DP = 10f
        const val TRAIL_STROKE_DP = 1.2f
    }
}

/** Raindrops land and spread out as fading rings. */
class RainEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val ringPaint = stroke(RAIN_COLOR, RING_STROKE_DP)
    private val dropPaint = fill(RAIN_COLOR)

    override fun draw(canvas: Canvas) {
        for (drop in 0 until DROPS) {
            val offset = drop * PERIOD_MS / DROPS
            val progress = cycle(PERIOD_MS, offset)
            val seed = cycleIndex(PERIOD_MS, offset) * SEED_STRIDE + drop
            val x = frame.width * (MARGIN + SPAN * random(seed))
            val y = frame.height * (TOP + HEIGHT_SPAN * random(seed + 1))
            if (progress < SPLASH_FRACTION) canvas.drawCircle(x, y, dp(DROP_DP), dropPaint)
            for (ring in 0 until RINGS) {
                val ringProgress = progress - ring * RING_DELAY
                if (ringProgress !in 0f..1f) continue
                ringPaint.alpha = alphaOf((1f - ringProgress) * RING_OPACITY)
                canvas.drawCircle(x, y, dp(DROP_DP) + ringProgress * dp(RING_GROWTH_DP), ringPaint)
            }
        }
    }

    private companion object {
        val RAIN_COLOR = Color.parseColor("#9ED8FF")
        const val DROPS = 3
        const val PERIOD_MS = 1_800L
        const val SEED_STRIDE = 13
        const val MARGIN = 0.08f
        const val SPAN = 0.84f
        const val TOP = 0.3f
        const val HEIGHT_SPAN = 0.4f
        const val SPLASH_FRACTION = 0.08f
        const val DROP_DP = 1.2f
        const val RINGS = 2
        const val RING_DELAY = 0.25f
        const val RING_GROWTH_DP = 9f
        const val RING_OPACITY = 0.9f
        const val RING_STROKE_DP = 1f
    }
}

/**
 * The pill takes on the sky's colors through the day: dawn orange, daytime blue, sunset pink and purple,
 * deep navy at night. Purely cosmetic, from the clock; the camera area stays black.
 */
class SkyEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = CameraShade(frame)
    private var builtWidth = -1f
    private var builtAt = Long.MIN_VALUE

    override fun draw(canvas: Canvas) {
        // The colors change over hours, so re-reading the clock every few seconds (or on resize) is plenty.
        if (frame.width != builtWidth || frame.now - builtAt > REFRESH_MS) {
            builtWidth = frame.width
            builtAt = frame.now
            val time = LocalTime.now()
            val (start, end) = colorsAt(time.hour + time.minute / MINUTES_PER_HOUR)
            paint.shader = LinearGradient(0f, 0f, frame.width, 0f, start, end, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, frame.width, frame.height, paint)
        shade.draw(canvas)
    }

    private fun colorsAt(hour: Float): Pair<Int, Int> {
        val nextIndex = KEYFRAMES.indexOfFirst { it.hour > hour }.coerceAtLeast(1)
        val from = KEYFRAMES[nextIndex - 1]
        val to = KEYFRAMES[nextIndex]
        val fraction = smooth((hour - from.hour) / (to.hour - from.hour))
        return blend(from.start, to.start, fraction) to blend(from.end, to.end, fraction)
    }

    private class Keyframe(val hour: Float, val start: Int, val end: Int)

    private companion object {
        const val MINUTES_PER_HOUR = 60f
        const val REFRESH_MS = 10_000L
        val KEYFRAMES = listOf(
            Keyframe(0f, Color.parseColor("#0B1026"), Color.parseColor("#1B2A4A")),
            Keyframe(5f, Color.parseColor("#1B2A4A"), Color.parseColor("#3B3B6D")),
            Keyframe(6.5f, Color.parseColor("#FF9A5A"), Color.parseColor("#FFD27D")),
            Keyframe(9f, Color.parseColor("#4FA3F7"), Color.parseColor("#8FD3FE")),
            Keyframe(16f, Color.parseColor("#4FA3F7"), Color.parseColor("#8FD3FE")),
            Keyframe(18f, Color.parseColor("#FF6B6B"), Color.parseColor("#8E54E9")),
            Keyframe(20f, Color.parseColor("#141E46"), Color.parseColor("#2E1E5B")),
            Keyframe(24f, Color.parseColor("#0B1026"), Color.parseColor("#1B2A4A")),
        )

        fun blend(from: Int, to: Int, fraction: Float): Int = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).toInt(),
        )
    }
}
