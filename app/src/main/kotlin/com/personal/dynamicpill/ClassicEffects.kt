package com.personal.dynamicpill

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The outline breathes in and out in the accent color. */
class GlowEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = stroke(frame.accent, STROKE_DP)
    private val bounds = RectF()

    override fun draw(canvas: Canvas) {
        val pulse = 0.5f - 0.5f * cos(TAU * cycle(PERIOD_MS))
        paint.alpha = alphaOf(MIN_OPACITY + (1f - MIN_OPACITY) * pulse)
        val inset = paint.strokeWidth / 2f
        bounds.set(inset, inset, frame.width - inset, frame.height - inset)
        canvas.drawRoundRect(bounds, frame.cornerRadius - inset, frame.cornerRadius - inset, paint)
    }

    private companion object {
        const val STROKE_DP = 2f
        const val PERIOD_MS = 2_800L
        const val MIN_OPACITY = 0.2f
    }
}

/** A dot with a fading trail runs around the inside edge of the pill. */
class OrbitEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = fill(frame.accent)
    private val bounds = RectF()
    private val track = Path()
    private val measure = PathMeasure()
    private val point = FloatArray(2)
    private var trackKey = Triple(0f, 0f, 0f)

    override fun draw(canvas: Canvas) {
        prepareTrack()
        val length = measure.length
        if (length <= 0f) return
        val head = cycle(PERIOD_MS) * length
        for (index in 0 until TRAIL) {
            val distance = ((head - index * dp(TRAIL_GAP_DP)) % length + length) % length
            measure.getPosTan(distance, point, null)
            paint.alpha = alphaOf(1f - index.toFloat() / TRAIL)
            canvas.drawCircle(point[0], point[1], dp(DOT_DP) * (1f - index * TRAIL_SHRINK), paint)
        }
    }

    private fun prepareTrack() {
        val key = Triple(frame.width, frame.height, frame.cornerRadius)
        if (key == trackKey) return
        trackKey = key
        val inset = dp(INSET_DP)
        bounds.set(inset, inset, frame.width - inset, frame.height - inset)
        track.reset()
        track.addRoundRect(bounds, frame.cornerRadius - inset, frame.cornerRadius - inset, Path.Direction.CW)
        measure.setPath(track, true)
    }

    private companion object {
        const val PERIOD_MS = 3_000L
        const val INSET_DP = 3f
        const val DOT_DP = 2.2f
        const val TRAIL = 5
        const val TRAIL_GAP_DP = 3.5f
        const val TRAIL_SHRINK = 0.15f
    }
}

/** Dots flow in from both edges and vanish into the camera, which "eats" them. */
class ChompEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = fill(frame.accent)

    override fun draw(canvas: Canvas) {
        val start = dp(EDGE_DP)
        val end = frame.centerX - frame.cutoutHalf
        if (end <= start) return
        for (index in 0 until DOTS) {
            val progress = (cycle(PERIOD_MS) + index.toFloat() / DOTS) % 1f
            val x = start + progress * (end - start)
            paint.alpha = alphaOf(min(1f, (1f - progress) * FADE_FACTOR))
            canvas.drawCircle(x, frame.centerY, dp(DOT_DP), paint)
            canvas.drawCircle(frame.width - x, frame.centerY, dp(DOT_DP), paint)
        }
    }

    private companion object {
        const val PERIOD_MS = 1_600L
        const val DOTS = 3
        const val DOT_DP = 2f
        const val EDGE_DP = 8f
        const val FADE_FACTOR = 4f
    }
}

/** Two hearts beat "lub-dub" on either side of the camera. */
class HeartbeatEffect(frame: EffectFrame) : IdleEffect(frame) {
    private val paint = fill(HEART_COLOR)
    private val heart = Path()

    override fun draw(canvas: Canvas) {
        val size = frame.height * SIZE_RATIO * beatScale()
        drawHeart(canvas, heart, paint, frame.leftSideX, frame.centerY, size)
        drawHeart(canvas, heart, paint, frame.rightSideX, frame.centerY, size)
    }

    private fun beatScale(): Float {
        val phase = cycle(BEAT_PERIOD_MS) * BEAT_PERIOD_MS
        return when {
            phase < BEAT_LENGTH_MS -> 1f + LUB_STRENGTH * sin(TAU / 2f * phase / BEAT_LENGTH_MS)
            phase in DUB_START_MS..(DUB_START_MS + BEAT_LENGTH_MS) ->
                1f + DUB_STRENGTH * sin(TAU / 2f * (phase - DUB_START_MS) / BEAT_LENGTH_MS)
            else -> 1f
        }
    }

    companion object {
        val HEART_COLOR = Color.parseColor("#FF375F")
        private const val SIZE_RATIO = 0.32f
        private const val BEAT_PERIOD_MS = 1_000L
        private const val BEAT_LENGTH_MS = 150f
        private const val DUB_START_MS = 220f
        private const val LUB_STRENGTH = 0.25f
        private const val DUB_STRENGTH = 0.15f

        /** Draws a heart of [size] centred on ([x], [y]), reusing [path]. */
        fun drawHeart(canvas: Canvas, path: Path, paint: Paint, x: Float, y: Float, size: Float) {
            path.reset()
            path.moveTo(x, y + size * 0.45f)
            path.cubicTo(x - size * 0.9f, y - size * 0.05f, x - size * 0.45f, y - size * 0.65f, x, y - size * 0.3f)
            path.cubicTo(x + size * 0.45f, y - size * 0.65f, x + size * 0.9f, y - size * 0.05f, x, y + size * 0.45f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}
