package com.parker.crownpulse

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure timing and easing rules for the beat effects (shockwave rings, the punch-in on the
 * first beat of each bar, the snare color split, the note color glow and the hi-hat
 * sparkles). Everything is a function of music time,
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

    /** How far back the note color glow listens, in seconds. */
    const val GLOW_WINDOW = 1.2f

    /** Glow brightness with no bass, and the extra brightness at full bass, at amount 1. */
    const val GLOW_BASE = .16f
    const val GLOW_BASS = .30f

    /** Hi-hat level below which no sparkles appear. */
    const val SPARKLE_THRESHOLD = .35f

    /** Most sparkles on screen at once (amount 2, loudest hi-hats). */
    const val SPARKLE_MAX = 14

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

    /**
     * Hue in degrees (0 until 360) for MIDI note [midi] on the circle of fifths: C is red,
     * G orange, D yellow and so on, 30 degrees per fifth. Notes a fifth apart (the most
     * closely related keys and chords) get neighboring colors, and every octave of a note
     * gets the same color. Idea from the MIT-licensed dedo1911/music-visualizer key colors.
     */
    fun fifthsHue(midi: Int): Float = (((midi % 12) + 12) % 12 * 7 % 12) * 30f

    /**
     * Blends the notes heard in the last [GLOW_WINDOW] seconds before [t] into one color on
     * the circle of fifths. Each note pulls toward its [fifthsHue], weighted by its velocity
     * and a cubic fade with age; the pulls are added as vectors so hues wrap correctly.
     * [times] must be sorted. Writes into [out] (size 3, no allocation):
     * - out[0]: hue in degrees, 0 until 360
     * - out[1]: agreement 0..1 (1 = all recent notes share a color, 0 = they cancel out)
     * - out[2]: presence 0..1 (how much recent note weight there is; 0 = no notes)
     */
    fun noteColor(
        times: FloatArray,
        midi: IntArray,
        velocity: FloatArray,
        t: Float,
        out: FloatArray,
    ) {
        var x = 0f
        var y = 0f
        var total = 0f
        var i = lastAtOrBefore(times, t)
        while (i >= 0) {
            val age = t - times[i]
            if (age >= GLOW_WINDOW) break
            val w = (.25f + .75f * velocity[i].coerceIn(0f, 1f)) * decay(age, GLOW_WINDOW)
            val angle = fifthsHue(midi[i]) * (PI.toFloat() / 180f)
            x += w * cos(angle)
            y += w * sin(angle)
            total += w
            i--
        }
        if (total <= 1e-6f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
            return
        }
        var hue = atan2(y, x) * (180f / PI.toFloat())
        if (hue < 0f) hue += 360f
        if (hue >= 360f) hue -= 360f
        out[0] = hue
        out[1] = (sqrt(x * x + y * y) / total).coerceIn(0f, 1f)
        out[2] = min(total, 1f)
    }

    /** Opacity 0..1 of the note color glow for bass level [bass] (0..1.8) at [amount] (0..2). */
    fun glowAlpha(bass: Float, amount: Float): Float =
        (amount.coerceIn(0f, 2f) * (GLOW_BASE + GLOW_BASS * bass.coerceIn(0f, 1f))).coerceIn(0f, 1f)

    /**
     * How many sparkles to draw for hi-hat level [hat] (0..1.3) at [amount] (0..2). None
     * below [SPARKLE_THRESHOLD]; at amount 1 the loudest hats give half of [SPARKLE_MAX].
     */
    fun sparkleCount(hat: Float, amount: Float): Int {
        if (!(hat > SPARKLE_THRESHOLD)) return 0
        val level = ((hat - SPARKLE_THRESHOLD) / (1f - SPARKLE_THRESHOLD)).coerceIn(0f, 1f)
        val n = (SPARKLE_MAX * .5f * amount.coerceIn(0f, 2f) * level).roundToInt()
        return n.coerceIn(0, SPARKLE_MAX)
    }

    /**
     * Repeatable pseudo-random number in [0, 1) for [n], so sparkles and grain land in the
     * same places in the preview and the export without a Random object per frame. Integer
     * hash "lowbias32" by Chris Wellons (public domain).
     */
    fun hash01(n: Int): Float {
        var x = n
        x = x xor (x ushr 16)
        x *= 0x7feb352d
        x = x xor (x ushr 15)
        x *= 0x846ca68b.toInt()
        x = x xor (x ushr 16)
        return (x ushr 8) / 16777216f
    }
}
