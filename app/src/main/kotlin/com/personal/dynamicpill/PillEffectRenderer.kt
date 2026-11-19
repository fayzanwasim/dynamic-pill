package com.personal.dynamicpill

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Animated decorations drawn on the idle pill itself, as pure functions of the clock. */
enum class PillEffect { NONE, GLOW, ORBIT, CHOMP, HEARTBEAT }

/** Draws a [PillEffect] over the pill. Allocation-free per frame: paths and buffers are reused. */
class PillEffectRenderer(private val density: Float, accentColor: Int) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GLOW_STROKE_DP * density
        color = accentColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HEART_COLOR }
    private val bounds = RectF()
    private val heartPath = Path()
    private val trackPath = Path()
    private val trackMeasure = PathMeasure()
    private val point = FloatArray(2)
    private var trackKey = Triple(0, 0, 0f)

    /** The pill is [width] x [height] with [cornerRadius]; each side of the camera is [sideWidth] wide. */
    fun draw(effect: PillEffect, canvas: Canvas, width: Int, height: Int, cornerRadius: Float, sideWidth: Int, cutoutWidth: Int, now: Long) {
        when (effect) {
            PillEffect.NONE -> Unit
            PillEffect.GLOW -> drawGlow(canvas, width, height, cornerRadius, now)
            PillEffect.ORBIT -> drawOrbit(canvas, width, height, cornerRadius, now)
            PillEffect.CHOMP -> drawChomp(canvas, width, height, cutoutWidth, now)
            PillEffect.HEARTBEAT -> drawHeartbeat(canvas, width, height, sideWidth, now)
        }
    }

    /** The outline breathes in and out. */
    private fun drawGlow(canvas: Canvas, width: Int, height: Int, cornerRadius: Float, now: Long) {
        val pulse = 0.5f - 0.5f * cos(2f * PI.toFloat() * (now % GLOW_PERIOD_MS) / GLOW_PERIOD_MS)
        strokePaint.alpha = (GLOW_MIN_ALPHA + (MAX_ALPHA - GLOW_MIN_ALPHA) * pulse).toInt()
        val inset = strokePaint.strokeWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(bounds, cornerRadius - inset, cornerRadius - inset, strokePaint)
    }

    /** A dot with a fading trail runs around the inside edge of the pill. */
    private fun drawOrbit(canvas: Canvas, width: Int, height: Int, cornerRadius: Float, now: Long) {
        prepareTrack(width, height, cornerRadius)
        val length = trackMeasure.length
        if (length <= 0f) return
        val head = (now % ORBIT_PERIOD_MS).toFloat() / ORBIT_PERIOD_MS * length
        val gap = ORBIT_TRAIL_GAP_DP * density
        for (index in 0 until ORBIT_TRAIL) {
            val distance = ((head - index * gap) % length + length) % length
            trackMeasure.getPosTan(distance, point, null)
            fillPaint.alpha = (MAX_ALPHA * (1f - index.toFloat() / ORBIT_TRAIL)).toInt()
            canvas.drawCircle(point[0], point[1], ORBIT_DOT_DP * density * (1f - index * ORBIT_TRAIL_SHRINK), fillPaint)
        }
        fillPaint.alpha = MAX_ALPHA
    }

    private fun prepareTrack(width: Int, height: Int, cornerRadius: Float) {
        val key = Triple(width, height, cornerRadius)
        if (key == trackKey) return
        trackKey = key
        val inset = ORBIT_INSET_DP * density
        bounds.set(inset, inset, width - inset, height - inset)
        trackPath.reset()
        trackPath.addRoundRect(bounds, cornerRadius - inset, cornerRadius - inset, Path.Direction.CW)
        trackMeasure.setPath(trackPath, true)
    }

    /** Dots flow in from both edges and vanish into the camera, which "eats" them. */
    private fun drawChomp(canvas: Canvas, width: Int, height: Int, cutoutWidth: Int, now: Long) {
        val centerY = height / 2f
        val start = CHOMP_EDGE_DP * density
        val end = width / 2f - cutoutWidth / 2f
        if (end <= start) return
        val cycle = (now % CHOMP_PERIOD_MS).toFloat() / CHOMP_PERIOD_MS
        val radius = CHOMP_DOT_DP * density
        for (index in 0 until CHOMP_DOTS) {
            val progress = (cycle + index.toFloat() / CHOMP_DOTS) % 1f
            val x = start + progress * (end - start)
            // Fade the dot out over its last stretch so it disappears into the camera.
            fillPaint.alpha = (MAX_ALPHA * min(1f, (1f - progress) * CHOMP_FADE_FACTOR)).toInt()
            canvas.drawCircle(x, centerY, radius, fillPaint)
            canvas.drawCircle(width - x, centerY, radius, fillPaint)
        }
        fillPaint.alpha = MAX_ALPHA
    }

    /** Two hearts beat "lub-dub" on either side of the camera. */
    private fun drawHeartbeat(canvas: Canvas, width: Int, height: Int, sideWidth: Int, now: Long) {
        val size = height * HEART_SIZE_RATIO * beatScale(now)
        val centerY = height / 2f
        drawHeart(canvas, sideWidth / 2f, centerY, size)
        drawHeart(canvas, width - sideWidth / 2f, centerY, size)
    }

    private fun beatScale(now: Long): Float {
        val phase = (now % BEAT_PERIOD_MS).toFloat()
        return when {
            phase < BEAT_LENGTH_MS -> 1f + LUB_STRENGTH * sin(PI.toFloat() * phase / BEAT_LENGTH_MS)
            phase in DUB_START_MS..(DUB_START_MS + BEAT_LENGTH_MS) ->
                1f + DUB_STRENGTH * sin(PI.toFloat() * (phase - DUB_START_MS) / BEAT_LENGTH_MS)
            else -> 1f
        }
    }

    private fun drawHeart(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        heartPath.reset()
        heartPath.moveTo(centerX, centerY + size * HEART_BOTTOM)
        heartPath.cubicTo(
            centerX - size * HEART_WIDTH, centerY - size * HEART_SHOULDER,
            centerX - size * HEART_LOBE, centerY - size * HEART_TOP_CONTROL,
            centerX, centerY - size * HEART_DIP,
        )
        heartPath.cubicTo(
            centerX + size * HEART_LOBE, centerY - size * HEART_TOP_CONTROL,
            centerX + size * HEART_WIDTH, centerY - size * HEART_SHOULDER,
            centerX, centerY + size * HEART_BOTTOM,
        )
        heartPath.close()
        canvas.drawPath(heartPath, heartPaint)
    }

    private companion object {
        const val MAX_ALPHA = 255
        const val GLOW_STROKE_DP = 2f
        const val GLOW_PERIOD_MS = 2_800L
        const val GLOW_MIN_ALPHA = 50

        const val ORBIT_PERIOD_MS = 3_000L
        const val ORBIT_INSET_DP = 3f
        const val ORBIT_DOT_DP = 2.2f
        const val ORBIT_TRAIL = 5
        const val ORBIT_TRAIL_GAP_DP = 3.5f
        const val ORBIT_TRAIL_SHRINK = 0.15f

        const val CHOMP_PERIOD_MS = 1_600L
        const val CHOMP_DOTS = 3
        const val CHOMP_DOT_DP = 2f
        const val CHOMP_EDGE_DP = 8f
        const val CHOMP_FADE_FACTOR = 4f

        const val BEAT_PERIOD_MS = 1_000L
        const val BEAT_LENGTH_MS = 150f
        const val DUB_START_MS = 220f
        const val LUB_STRENGTH = 0.25f
        const val DUB_STRENGTH = 0.15f
        const val HEART_SIZE_RATIO = 0.32f
        const val HEART_BOTTOM = 0.45f
        const val HEART_WIDTH = 0.9f
        const val HEART_SHOULDER = 0.05f
        const val HEART_LOBE = 0.45f
        const val HEART_TOP_CONTROL = 0.65f
        const val HEART_DIP = 0.3f
        val HEART_COLOR = Color.parseColor("#FF375F")
    }
}
