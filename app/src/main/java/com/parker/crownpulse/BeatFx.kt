package com.parker.crownpulse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure timing and easing rules for the beat effects (shockwave rings, the punch-in on the
 * first beat of each bar, the snare color split, the note color glow, the hi-hat
 * sparkles, the ripple on hits, the rainbow edge on bright sounds, the flash on drops and
 * the light rays on bass). Everything is a function of music time,
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

    /** How long the symbol ripples after a hit, in seconds. */
    const val RIPPLE_LIFE = .55f

    /** Largest ripple displacement at amount 1, as a fraction of the symbol's height. */
    const val RIPPLE_DEPTH = .03f

    /** Waves per symbol height in the ripple wave train. */
    const val RIPPLE_WAVES = 3f

    /** How fast the ripple front leaves the center, in symbol heights per second. */
    const val RIPPLE_SPEED = 1.6f

    /** How tightly the wave train hugs its front (larger = fewer visible rings). */
    const val RIPPLE_FOCUS = 7f

    /** Hits weaker than this (0..1) start no ripple. */
    const val RIPPLE_MIN_STRENGTH = .25f

    /** Brightness (0..1) below which the rainbow edge stays off. */
    const val FRINGE_THRESHOLD = .25f

    /** Extra size of the cyan copy at amount 1 and full brightness (the red copy gets twice). */
    const val FRINGE_SCALE = .035f

    /** Seconds of music before a moment that set its "before" level for drop finding. */
    const val DROP_BEFORE = 2f

    /** Seconds of music after a moment that set its "after" level for drop finding. */
    const val DROP_AFTER = .35f

    /** Smallest jump in level (0..1) from before to after that counts as a drop. */
    const val DROP_RISE = .22f

    /** Level (0..1) the music must reach right after the jump to count as a drop. */
    const val DROP_LEVEL = .5f

    /** Fewest seconds between two drops; within this, the bigger jump wins. */
    const val DROP_GAP = 6f

    /** A beat this close to a drop (seconds) pulls the drop onto the beat. */
    const val DROP_SNAP = .12f

    /** How long the flash and the drop wave last, in seconds. */
    const val DROP_LIFE = .7f

    /** Brightest flash at amount 1 and full drop strength. */
    const val DROP_FLASH = .45f

    /** Number of light rays around the symbol. */
    const val RAYS = 12

    /** Bass level below which the light rays stay off. */
    const val RAY_THRESHOLD = .3f

    /** Ray opacity at amount 1 and full bass. */
    const val RAY_ALPHA = .18f

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

    /**
     * Picks the hit that should ripple the symbol at time [t]: among the hits of sorted
     * [times] in the last [RIPPLE_LIFE] seconds with a strength of at least
     * [RIPPLE_MIN_STRENGTH], the one whose faded strength is largest, so a weak hit right
     * after a big one does not cut the big ripple short. Writes into [out] (size 2, no
     * allocation): out[0] = its age in seconds (-1 when there is none), out[1] = its
     * strength 0..1.
     */
    fun latestHit(times: FloatArray, strengths: FloatArray, t: Float, out: FloatArray) {
        out[0] = -1f
        out[1] = 0f
        var best = 0f
        var i = lastAtOrBefore(times, t)
        while (i >= 0) {
            val age = t - times[i]
            if (age >= RIPPLE_LIFE) break
            val strength = strengths[i].coerceIn(0f, 1f)
            if (strength >= RIPPLE_MIN_STRENGTH) {
                val weight = strength * decay(age, RIPPLE_LIFE)
                if (weight > best) {
                    best = weight
                    out[0] = age
                    out[1] = strength
                }
            }
            i--
        }
    }

    /**
     * Outward shift, in symbol heights, of the point [r] symbol heights from the symbol's center
     * for a ripple [age] seconds old from a hit of [strength] (0..1) at [amount] (0..2). A
     * damped radial wave train sin(k(r - front)) whose front leaves the center at
     * [RIPPLE_SPEED] and which fades with a cubic decay, like a pebble dropped in water.
     * Ripple idea from the MIT-licensed baditaflorin/synaesthete (onsets drive radial
     * ripples); no code copied. 0 when there is no ripple.
     */
    fun rippleShift(r: Float, age: Float, strength: Float, amount: Float): Float {
        val fade = decay(age, RIPPLE_LIFE)
        if (fade <= 0f || !(r >= 0f)) return 0f
        val gap = r - age * RIPPLE_SPEED
        val wave = sin(2f * PI.toFloat() * RIPPLE_WAVES * gap) * exp(-abs(gap) * RIPPLE_FOCUS)
        return RIPPLE_DEPTH * amount.coerceIn(0f, 2f) * strength.coerceIn(0f, 1f) * fade * wave
    }

    /**
     * Spectral centroid of [count] spectrum bands starting at [offset] in [data], as a
     * position 0..1 from the lowest to the highest band (the "brightness" of the sound).
     * 0 when the bands are silent.
     */
    fun spectralCentroid(data: FloatArray, offset: Int, count: Int): Float {
        if (count < 2 || offset < 0 || offset + count > data.size) return 0f
        var sum = 0f
        var weighted = 0f
        for (b in 0 until count) {
            val v = data[offset + b]
            if (v > 0f) {
                sum += v
                weighted += v * b
            }
        }
        if (sum <= 1e-6f) return 0f
        return (weighted / sum / (count - 1)).coerceIn(0f, 1f)
    }

    /**
     * Extra size (fraction) of the cyan copy behind the symbol for the rainbow edge, for treble
     * level [high] (0..) and spectral centroid [centroid] (0..1) at [amount] (0..2). Bright,
     * airy sounds (cymbals, synth leads, vocals' "s") push it out; dull or quiet ones keep
     * it off. Treble and centroid driving chromatic aberration follows the MIT-licensed
     * baditaflorin/synaesthete; no code copied.
     */
    fun fringeScale(high: Float, centroid: Float, amount: Float): Float {
        val tilt = ((centroid - .2f) / .5f).coerceIn(0f, 1f)
        val bright = high.coerceIn(0f, 1f) * (.35f + .65f * tilt)
        if (!(bright > FRINGE_THRESHOLD)) return 0f
        val level = ((bright - FRINGE_THRESHOLD) / (1f - FRINGE_THRESHOLD)).coerceIn(0f, 1f)
        return FRINGE_SCALE * amount.coerceIn(0f, 2f) * level
    }

    /**
     * Finds the drops in a song: moments where the music jumps from a quieter stretch to a
     * loud one. [level] is one loudness value (0..1) per analysis frame at [fps] frames per
     * second. For every frame the mean of the [DROP_BEFORE] seconds before it is compared
     * with the mean of the [DROP_AFTER] seconds after it (prefix sums, so one pass); a jump of
     * at least [DROP_RISE] up to at least [DROP_LEVEL] is a drop. Drops closer than
     * [DROP_GAP] keep only the bigger jump, and a drop within [DROP_SNAP] of a beat in sorted
     * [beats] moves onto that beat. The short-against-long window contrast follows the
     * MIT-licensed le-director/director-signals ("drop impact"); the flash and wave on the
     * drop follow the MIT-licensed 5ymph0en1x/Sythm. No code copied.
     *
     * Runs once when a song is loaded (it allocates); returns [time, strength 0..1] pairs.
     */
    fun findDrops(level: FloatArray, fps: Float, beats: FloatArray): FloatArray {
        val n = level.size
        if (n == 0 || !(fps > 0f) || !fps.isFinite()) return FloatArray(0)
        val before = max(1, (DROP_BEFORE * fps).roundToInt())
        val after = max(1, (DROP_AFTER * fps).roundToInt())
        val history = max(1, fps.roundToInt())
        val gap = DROP_GAP * fps
        val sum = DoubleArray(n + 1)
        for (i in 0 until n) {
            val v = level[i]
            sum[i + 1] = sum[i] + if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
        }
        val frames = ArrayList<Int>()
        val rises = ArrayList<Float>()
        for (i in history..n - after) {
            val lo = max(0, i - before)
            val pre = ((sum[i] - sum[lo]) / (i - lo)).toFloat()
            val post = ((sum[i + after] - sum[i]) / after).toFloat()
            val rise = post - pre
            if (rise < DROP_RISE || post < DROP_LEVEL) continue
            val last = frames.size - 1
            if (last >= 0 && i - frames[last] < gap) {
                if (rise > rises[last]) {
                    frames[last] = i
                    rises[last] = rise
                }
                continue
            }
            frames.add(i)
            rises.add(rise)
        }
        val out = FloatArray(frames.size * 2)
        for (k in frames.indices) {
            var time = frames[k] / fps
            val j = lastAtOrBefore(beats, time + DROP_SNAP)
            if (j >= 0 && abs(beats[j] - time) <= DROP_SNAP) time = beats[j]
            out[2 * k] = time
            out[2 * k + 1] = (rises[k] / .5f).coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * Opacity 0..1 of the full-screen flash [age] seconds after a drop of [strength] (0..1) at
     * [amount] (0..2). 0 before the drop and after [DROP_LIFE].
     */
    fun dropFlash(age: Float, strength: Float, amount: Float): Float =
        (DROP_FLASH * amount.coerceIn(0f, 2f) * strength.coerceIn(0f, 1f) * decay(age, DROP_LIFE))
            .coerceIn(0f, 1f)

    /** Opacity 0..1 of the light rays for bass level [bass] (0..1.8) at [amount] (0..2). */
    fun rayAlpha(bass: Float, amount: Float): Float {
        if (!(bass > RAY_THRESHOLD)) return 0f
        val level = ((bass - RAY_THRESHOLD) / (1f - RAY_THRESHOLD)).coerceIn(0f, 1f)
        return (RAY_ALPHA * amount.coerceIn(0f, 2f) * level).coerceIn(0f, 1f)
    }

    /** Half the angle (radians) of each light ray: rays widen with the bass. */
    fun rayHalfAngle(bass: Float): Float = .035f + .05f * bass.coerceIn(0f, 1f)
}
