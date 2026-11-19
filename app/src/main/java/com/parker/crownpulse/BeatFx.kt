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
 * sparkles, the ripple on hits, the rainbow edge on bright sounds, the flash on drops,
 * the light rays on bass, the build-up before drops, the tilt on beats, the bounce on beats,
 * the orbit dots, the sound blob and the star warp). Everything is a function of music time,
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

    /** Longest build-up before a drop, in seconds. */
    const val BUILD_TIME = 4f

    /** Shortest build-up worth showing, in seconds (drops close together get none). */
    const val BUILD_MIN = 1f

    /** Extra zoom at the very end of a build-up at amount 1 (6 %). */
    const val BUILD_ZOOM = .06f

    /** Darkest the frame gets at the end of a build-up at amount 1. */
    const val BUILD_DARK = .22f

    /** How long a ring takes to fall into the symbol during a build-up, in seconds. */
    const val BUILD_RING_LIFE = .45f

    /** Largest tilt on a beat at amount 1, in degrees. */
    const val TILT_DEGREES = 1.5f

    /** How long the tilt on a beat takes to settle, in seconds. */
    const val TILT_LIFE = .35f

    /** Largest squash of the symbol on a beat at amount 1 (12 % shorter, 12 % wider). */
    const val BOUNCE_SQUASH = .12f

    /** One squash-and-rebound swing of the bounce, in seconds. */
    const val BOUNCE_SWING = .32f

    /** How fast the bounce settles: the swing shrinks as exp(-BOUNCE_DAMP * age). */
    const val BOUNCE_DAMP = 8f

    /** The bounce is over (and drawn flat) this long after its beat, in seconds. */
    const val BOUNCE_LIFE = .6f

    /** How early before the next beat the symbol starts stretching up, in seconds. */
    const val BOUNCE_LEAD = .09f

    /** Dots that orbit the symbol, evenly spaced. */
    const val ORBIT_DOTS = 4

    /** Steps per full turn of the orbit: one step per beat, a full turn every two bars. */
    const val ORBIT_STEPS = 8

    /** How long one orbit step takes after its beat, in seconds. */
    const val ORBIT_STEP_TIME = .22f

    /** How long the orbit dots stay swollen after a beat, in seconds. */
    const val ORBIT_PULSE_LIFE = .35f

    /** Points around the sound blob's outline (it is drawn as a smooth curve through them). */
    const val BLOB_POINTS = 48

    /** How far one band at full level pushes the blob out at amount 1, in base radii. */
    const val BLOB_BAND = .28f

    /** How far full bass swells the whole blob at amount 1, in base radii. */
    const val BLOB_BASS = .12f

    /** Stars in the star warp at full detail. */
    const val WARP_STARS = 48

    /** Trips from the center to the edge per unit of music flow (see [Analysis.flow]). */
    const val WARP_RATE = .35f

    /** Fraction of a trip over which a new star fades in. */
    const val WARP_FADE_IN = .15f

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

    /**
     * Tension 0..1 of the build-up before the next drop at time [t]. Sorted [dropTimes] and
     * [dropStrengths] come from [findDrops], so the build-up is known ahead of time and the
     * preview and export match. The build-up starts [BUILD_TIME] seconds before a drop (or
     * when the previous drop's flash ends, if that is later) and rises as the square of its
     * progress, so most of the tension comes in the last second. Strong drops get a full
     * build-up, weak ones half. 0 at and after the drop, and when the build-up would be
     * shorter than [BUILD_MIN]. The idea of the music "drawing inward and darkening" before
     * the drop follows the MIT-licensed 5ymph0en1x/Sythm; no code copied.
     */
    fun buildTension(dropTimes: FloatArray, dropStrengths: FloatArray, t: Float): Float {
        if (!t.isFinite()) return 0f
        val next = lastAtOrBefore(dropTimes, t) + 1
        if (next >= dropTimes.size || next >= dropStrengths.size) return 0f
        val drop = dropTimes[next]
        var start = drop - BUILD_TIME
        if (next > 0) start = max(start, dropTimes[next - 1] + DROP_LIFE)
        if (drop - start < BUILD_MIN || t < start || t >= drop) return 0f
        val u = (t - start) / (drop - start)
        return (u * u * (.5f + .5f * dropStrengths[next].coerceIn(0f, 1f))).coerceIn(0f, 1f)
    }

    /** Canvas scale for the build-up push-in at [tension] (0..1) and [amount] (0..2). */
    fun buildZoom(tension: Float, amount: Float): Float =
        1f + BUILD_ZOOM * amount.coerceIn(0f, 2f) * tension.coerceIn(0f, 1f)

    /** Opacity 0..1 of the build-up darkening at [tension] (0..1) and [amount] (0..2). */
    fun buildDark(tension: Float, amount: Float): Float =
        (BUILD_DARK * amount.coerceIn(0f, 2f) * tension.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    /**
     * How far (0..1 of its travel) a build-up ring has fallen toward the symbol [age] seconds
     * after its beat: 0 far out, 1 at the symbol's edge. Same ease-out as the beat rings, in
     * reverse.
     */
    fun buildRingFall(age: Float): Float =
        if (age >= 0f) easeOut(age / BUILD_RING_LIFE) else 0f

    /**
     * Tilt in degrees [age] seconds after a beat of [strength] (0..1) at [amount] (0..2).
     * Every other beat ([odd]) leans the other way, so the picture rocks left and right with
     * the beat and settles within [TILT_LIFE].
     */
    fun tiltDegrees(age: Float, odd: Boolean, strength: Float, amount: Float): Float {
        val v =
            TILT_DEGREES * amount.coerceIn(0f, 2f) * (.5f + .5f * strength.coerceIn(0f, 1f)) *
                decay(age, TILT_LIFE)
        return if (odd) -v else v
    }

    /**
     * Vertical stretch of the symbol [age] seconds after a beat of [strength] (0..1) at
     * [amount] (0..2), with the next beat expected [period] seconds after the latest one
     * (0 when unknown). Negative = squashed, positive = stretched; the symbol is drawn
     * 1 + v tall and 1 / (1 + v) wide, so it keeps its area. It squashes on the beat, swings
     * back up past its rest shape (the rebound) and settles as a damped spring; in the last
     * [BOUNCE_LEAD] seconds before the next beat it stretches up a little, so the hit lands
     * with a snap. Squash and stretch is the classic animation principle; the "stretch as the
     * beat comes, squash on it, hop on the rebound" order follows the Bounce move described
     * in the IKANDY user guide. No code copied.
     */
    fun bounceStretch(age: Float, period: Float, strength: Float, amount: Float): Float {
        if (!(age >= 0f)) return 0f
        val a = BOUNCE_SQUASH * amount.coerceIn(0f, 2f) * (.5f + .5f * strength.coerceIn(0f, 1f))
        var v = 0f
        if (age < BOUNCE_LIFE)
            v -= a * exp(-BOUNCE_DAMP * age) * cos(2f * PI.toFloat() * age / BOUNCE_SWING)
        if (period > BOUNCE_LEAD * 2f) {
            val lead = period - age
            if (lead > 0f && lead < BOUNCE_LEAD) {
                val u = 1f - lead / BOUNCE_LEAD
                v += .5f * a * u * u
            }
        }
        return v.coerceIn(-.3f, .3f)
    }

    /**
     * Where the orbit dots are, in steps of a 1 / [ORBIT_STEPS] turn, [age] seconds after
     * beat number [index] (0 = first beat). On each beat they move one step with an
     * overshoot-and-settle (ease-out-back) and then hold still, so they visibly land on the
     * beat. Continuous across beats: the end of one step is the start of the next. -1 with
     * no beat yet.
     */
    fun orbitSteps(index: Int, age: Float): Float {
        if (index < 0 || !(age >= 0f)) return -1f
        val x = (age / ORBIT_STEP_TIME).coerceIn(0f, 1f) - 1f
        val c1 = 1.70158f
        return index + 1f + (c1 + 1f) * x * x * x + c1 * x * x
    }

    /**
     * Size boost 0..1.5 of the orbit dots [age] seconds after a beat of [strength] (0..1):
     * they swell on each beat and shrink back within [ORBIT_PULSE_LIFE]; the first beat of a
     * bar ([downbeat]) swells them half again as much.
     */
    fun orbitPulse(age: Float, strength: Float, downbeat: Boolean): Float =
        decay(age, ORBIT_PULSE_LIFE) * (.5f + .5f * strength.coerceIn(0f, 1f)) *
            (if (downbeat) 1.5f else 1f)

    /**
     * Spectrum band (0 until [bands]) shown at outline point [point] of [points]. Point 0 is
     * the bottom of the blob and the bands climb both sides to the top, so the left and right
     * halves mirror each other: bass at the bottom, treble at the top. Circular "blob" spectrum
     * idea from the MIT-licensed ntrogh/spectrum-visualizer and Pahiro/avs; no code copied.
     */
    fun blobBand(point: Int, points: Int, bands: Int): Int {
        if (points <= 0 || bands <= 0) return 0
        val u = ((point % points) + points) % points / points.toFloat()
        val m = 1f - abs(2f * u - 1f)
        return (m * (bands - 1)).roundToInt().coerceIn(0, bands - 1)
    }

    /**
     * Radius of the sound blob, in base radii, where the band under it is at [band] (0..1)
     * with [bass] (0..1.8) at [amount] (0..2). 1 = resting circle.
     */
    fun blobRadius(band: Float, bass: Float, amount: Float): Float {
        val k = amount.coerceIn(0f, 2f)
        return 1f + k * (BLOB_BAND * band.coerceIn(0f, 1.5f) + BLOB_BASS * bass.coerceIn(0f, 1.8f))
    }

    /**
     * How far (0 until 1) a star whose start is [offset] (0..1) has traveled from the center
     * at music flow [flow], plus the trip it is on (a new trip gets a new direction). Written
     * as trip + depth into one number: the trip is its whole part, the depth its fraction.
     * The flow grows faster in loud, busy parts and jumps on kicks, so the stars surge with
     * the music. Music-pushed starfield idea from the MIT-licensed slastra/hyprglaze; no code
     * copied.
     */
    fun warpTravel(offset: Float, flow: Float): Float {
        val d = offset + flow * WARP_RATE
        return if (d.isFinite()) d else 0f
    }

    /** Opacity of a star at depth [z] (0..1) with [energy] (0..1.5) at [amount] (0..2). */
    fun warpAlpha(z: Float, energy: Float, amount: Float): Float {
        if (!(z >= 0f) || z >= 1f) return 0f
        val fadeIn = (z / WARP_FADE_IN).coerceIn(0f, 1f)
        return (fadeIn * (.25f + .45f * energy.coerceIn(0f, 1.5f)) * amount.coerceIn(0f, 2f))
            .coerceIn(0f, 1f)
    }
}
