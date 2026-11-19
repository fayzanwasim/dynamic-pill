package com.personal.dynamicpill

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.sin

/** Animated decorations drawn on the idle pill itself. */
enum class PillEffect {
    NONE,
    GLOW,
    ORBIT,
    CHOMP,
    HEARTBEAT,
    SLEEPY_CAT,
    PEEKABOO,
    BLUSH,
    GHOST,
    BUNNY,
    FIREFLIES,
    SNOW,
    STARS,
    RAIN,
    SKY,
    BOUNCE,
    LAVA,
    RIPPLE,
    PENDULUM,
    SHIMMER,
    RAINBOW,
    FIREWORKS,
    CRESCENT,
}

/** Geometry and time for one frame. Updated in place by the pill before every draw, so drawing allocates nothing. */
class EffectFrame(val density: Float, val accent: Int) {
    var width = 0f
    var height = 0f
    var cornerRadius = 0f

    /** Width of each area beside the camera. */
    var sideWidth = 0f
    var cutoutWidth = 0f

    /** Space between the pill's edge and the glances, so effects can line up with them. */
    var edgePadding = 0f

    /** Monotonic time in milliseconds; every effect is a pure function of it. */
    var now = 0L

    val centerX: Float get() = width / 2f
    val centerY: Float get() = height / 2f
    val leftSideX: Float get() = sideWidth / 2f
    val rightSideX: Float get() = width - sideWidth / 2f

    /** Half the camera hole's width: anything drawn closer to the centre than this sits behind the lens. */
    val cutoutHalf: Float get() = cutoutWidth / 2f
}

/**
 * One decoration. Implementations derive everything from [EffectFrame.now] and stable pseudo-random seeds,
 * so they keep no state between frames beyond cached paints, paths and shaders.
 */
abstract class IdleEffect(protected val frame: EffectFrame) {

    abstract fun draw(canvas: Canvas)

    protected fun dp(value: Float): Float = value * frame.density

    protected fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    protected fun stroke(color: Int, widthDp: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = dp(widthDp)
        strokeCap = Paint.Cap.ROUND
    }

    /** Fraction 0..1 through a repeating [periodMs], shifted by [offsetMs]. */
    protected fun cycle(periodMs: Long, offsetMs: Long = 0L): Float = ((frame.now + offsetMs) % periodMs).toFloat() / periodMs

    /** Which repetition of [periodMs] we are in, for re-seeding randomness each time. */
    protected fun cycleIndex(periodMs: Long, offsetMs: Long = 0L): Int = ((frame.now + offsetMs) / periodMs).toInt()

    /** A smooth oscillation between -1 and 1. */
    protected fun wave(periodMs: Long, phase: Float = 0f): Float = sin(TAU * cycle(periodMs) + phase)

    protected fun alphaOf(fraction: Float): Int = (MAX_ALPHA * fraction.coerceIn(0f, 1f)).toInt()

    companion object {
        const val TAU = 6.2831855f
        const val MAX_ALPHA = 255
        private const val HASH_MULTIPLIER = -1_640_531_535 // 0x9E3779B1
        private const val HASH_MIX = -2_048_144_777 // 0x85EBCA77
        private const val HASH_SHIFT_A = 15
        private const val HASH_SHIFT_B = 13
        private const val HASH_KEEP_BITS = 8
        private const val HASH_RANGE = 16_777_216f

        /** A stable pseudo-random value in 0..1 for [seed], so particles need no stored positions. */
        fun random(seed: Int): Float {
            var hash = seed * HASH_MULTIPLIER
            hash = hash xor (hash ushr HASH_SHIFT_A)
            hash *= HASH_MIX
            hash = hash xor (hash ushr HASH_SHIFT_B)
            return (hash ushr HASH_KEEP_BITS) / HASH_RANGE
        }

        /** Hermite smoothing for gentle starts and stops. */
        fun smooth(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}

/** Draws whichever [PillEffect] is active. Effect objects are created on first use and reused afterwards. */
class PillEffectRenderer(density: Float, accentColor: Int) {

    /** Set by the pill before each [draw]. */
    val frame = EffectFrame(density, accentColor)

    private val effects = mutableMapOf<PillEffect, IdleEffect>()

    fun draw(effect: PillEffect, canvas: Canvas) {
        if (effect == PillEffect.NONE) return
        effects.getOrPut(effect) { create(effect) }.draw(canvas)
    }

    private fun create(effect: PillEffect): IdleEffect = when (effect) {
        PillEffect.NONE -> NoEffect(frame)
        PillEffect.GLOW -> GlowEffect(frame)
        PillEffect.ORBIT -> OrbitEffect(frame)
        PillEffect.CHOMP -> ChompEffect(frame)
        PillEffect.HEARTBEAT -> HeartbeatEffect(frame)
        PillEffect.SLEEPY_CAT -> SleepyCatEffect(frame)
        PillEffect.PEEKABOO -> PeekabooEffect(frame)
        PillEffect.BLUSH -> BlushEffect(frame)
        PillEffect.GHOST -> GhostEffect(frame)
        PillEffect.BUNNY -> BunnyEffect(frame)
        PillEffect.FIREFLIES -> FirefliesEffect(frame)
        PillEffect.SNOW -> SnowEffect(frame)
        PillEffect.STARS -> StarsEffect(frame)
        PillEffect.RAIN -> RainEffect(frame)
        PillEffect.SKY -> SkyEffect(frame)
        PillEffect.BOUNCE -> BounceEffect(frame)
        PillEffect.LAVA -> LavaEffect(frame)
        PillEffect.RIPPLE -> RippleEffect(frame)
        PillEffect.PENDULUM -> PendulumEffect(frame)
        PillEffect.SHIMMER -> ShimmerEffect(frame)
        PillEffect.RAINBOW -> RainbowEffect(frame)
        PillEffect.FIREWORKS -> FireworksEffect(frame)
        PillEffect.CRESCENT -> CrescentEffect(frame)
    }

    private class NoEffect(frame: EffectFrame) : IdleEffect(frame) {
        override fun draw(canvas: Canvas) = Unit
    }
}
