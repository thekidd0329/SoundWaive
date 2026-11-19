package com.parker.crownpulse

import kotlin.math.floor
import kotlin.math.max

/**
 * Pure timing and easing rules for the beat effects (shockwave rings, the punch-in on the
 * first beat of each bar and the snare color split). Everything is a function of music time,
 * so the preview and the export draw identical frames. No Android types and no allocation,
 * so the rules are covered by JVM tests and are safe to call every frame.
 */
object BeatFx {
    /** How long a ring from an ordinary beat stays visible, in seconds. */
    const val BEAT_RING_LIFE = .5f

    /** How long a ring from the first beat of a bar stays visible, in seconds. */
    const val DOWNBEAT_RING_LIFE = .9f

    /** How long the punch-in on the first beat of a bar takes to settle, in seconds. */
    const val PUNCH_LIFE = .42f

    /** Largest punch-in at amount 1 (5 % zoom). */
    const val PUNCH_ZOOM = .05f

    /** Snare level below which the color split stays off. */
    const val SPLIT_THRESHOLD = .3f

    /** Largest split at amount 1, in 1080-wide layout pixels. */
    const val SPLIT_PIXELS = 24f

    /** Index of the last entry of sorted [times] at or before [t], or -1 when none is. */
    fun lastAtOrBefore(times: FloatArray, t: Float): Int {
        if (times.isEmpty() || !(t >= times[0])) return -1
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Seconds since the [back]-th most recent beat of [times] at time [t] (0 = latest), or -1
     * when there is no such beat.
     */
    fun ageOf(times: FloatArray, t: Float, back: Int): Float {
        val i = lastAtOrBefore(times, t) - back
        return if (i < 0) -1f else t - times[i]
    }

    /**
     * Same as [ageOf] for a fixed grid starting at 0 s with one beat every [period] seconds,
     * used for older analysis files without a beat grid and for a manual tempo override.
     */
    fun gridAge(t: Float, period: Float, back: Int): Float {
        if (!(period > 0f) || !(t >= 0f)) return -1f
        val k = floor(t / period) - back
        return if (k < 0) -1f else t - k * period
    }

    /** Quadratic ease-out on 0..1: starts fast, settles gently. */
    fun easeOut(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * (2f - v)
    }

    /**
     * Fade for something born [age] seconds ago that lives [life] seconds: 1 at birth, then a
     * cubic decay to 0. Negative ages (not born yet) and ages past [life] give 0.
     */
    fun decay(age: Float, life: Float): Float {
        if (!(age >= 0f) || !(life > 0f) || age >= life) return 0f
        val v = 1f - age / life
        return v * v * v
    }

    /** Canvas scale for the punch-in [age] seconds after a bar line, at [amount] (0..2). */
    fun punchScale(age: Float, amount: Float): Float =
        1f + PUNCH_ZOOM * amount.coerceIn(0f, 2f) * decay(age, PUNCH_LIFE)

    /**
     * Ring radius growth (0..1 of the full travel) for a ring [age] seconds old that lives
     * [life] seconds.
     */
    fun ringTravel(age: Float, life: Float): Float = if (life > 0f) easeOut(age / life) else 0f

    /**
     * Horizontal offset in layout pixels of the red and cyan copies of the symbol for snare
     * level [snare] (0..1.3) at [amount] (0..2). Zero below [SPLIT_THRESHOLD] so quiet passages
     * stay clean.
     */
    fun splitOffset(snare: Float, amount: Float): Float {
        if (!(snare > SPLIT_THRESHOLD)) return 0f
        val level = ((snare - SPLIT_THRESHOLD) / (1f - SPLIT_THRESHOLD)).coerceIn(0f, 1f)
        return SPLIT_PIXELS * amount.coerceIn(0f, 2f) * level
    }

    /** Beat period in seconds for [bpm], or 0 when the tempo is unusable. */
    fun period(bpm: Float): Float = if (bpm >= 30f && bpm.isFinite()) 60f / max(bpm, 30f) else 0f
}
