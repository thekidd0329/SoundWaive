package com.parker.crownpulse

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Picks the song windows for a Release Pack. Pure and deterministic (no Android types, no
 * randomness, ties broken by the earlier start), so the same song and settings always give
 * the same clips and every rule is covered by JVM tests.
 *
 * Inputs come from the analysis SoundWaive already made at import: the loudness and bass
 * curve (the same `.6 * loudness + .4 * bass` level the drop flash uses), the tracked
 * downbeats and the drops found by [BeatFx.findDrops]. Nothing is analyzed again.
 *
 * - Every clip starts on a downbeat (the first beat of a bar), so cuts land on the beat.
 *   Songs without a beat grid fall back to a bar grid from the tempo.
 * - "Strength" of a window is its mean level. A drop in the first 35% of a window adds up to
 *   [DROP_BONUS], so drop-led choruses win over long loud plateaus.
 * - Vertical A: the strongest 15 s (chorus or drop).
 * - Vertical B: the hook. The strongest 15 s that ends before A (when A leaves room),
 *   lightly weighted toward the start (x `1 - .3 * start / duration`), not overlapping A.
 * - Vertical C: the strongest 15 s at least [SECTION_GAP] s away from A and B (usually the
 *   second chorus, a later drop or the bridge's lift; a true bridge detector would need
 *   harmonic analysis).
 * - Reel: the strongest 30 s.
 * - Square: the strongest window of its length that avoids the reel when the song allows.
 * - Canvas: a whole number of bars between 3 and 8 s, inside Vertical A when it fits, so it
 *   loops on the bar line.
 *
 * Overlap is avoided first and strength second: when a song is too short for separate
 * windows, the picker takes the least-overlapping one instead of failing.
 */
object ReleasePlan {
    const val DROP_BONUS = .25f
    /** Seconds before a loop window the Canvas render needs for its crossfade. */
    const val LOOP_CROSSFADE = .5f
    const val CANVAS_MAX = 8f
    const val CANVAS_MIN = 3f
    /** Seconds Vertical C keeps away from A and B so it comes from another section. */
    const val SECTION_GAP = 10f

    data class Window(val start: Float, val end: Float) {
        val length: Float
            get() = end - start

        fun overlap(o: Window): Float = max(0f, min(end, o.end) - max(start, o.start))

        fun padded(by: Float) = Window(start - by, end + by)
    }

    /** What one output is, independent of how it is rendered. */
    enum class Kind(val label: String, val suffix: String) {
        YOUTUBE("YouTube 16:9", "YouTube_16x9"),
        VERTICAL_A("Vertical 15s A", "Vertical15_A"),
        VERTICAL_B("Vertical 15s B", "Vertical15_B"),
        VERTICAL_C("Vertical 15s C", "Vertical15_C"),
        REEL("Reel 30s", "Reel_30s"),
        SQUARE("Square", "Square"),
        CANVAS("Spotify Canvas 8s", "Canvas_8s"),
    }

    data class Item(
        val kind: Kind,
        val width: Int,
        val height: Int,
        val window: Window,
        /** Why this window was chosen, for the docs and the log. */
        val why: String,
    ) {
        val audio: Boolean
            get() = kind != Kind.CANVAS

        val loop: Boolean
            get() = kind == Kind.CANVAS

        /**
         * Whether the lyrics layer is on by default for this clip. Every shape gets karaoke
         * lyrics framed for it except the silent Spotify Canvas loop (Spotify shows its own
         * lyrics there); the "Lyrics on Spotify Canvas" switch overrides that.
         */
        val lyricsByDefault: Boolean
            get() = kind != Kind.CANVAS

        /** Different variation seed per vertical clip so the three promos look distinct. */
        val seedOffset: Int
            get() =
                when (kind) {
                    Kind.VERTICAL_B -> 1
                    Kind.VERTICAL_C -> 2
                    else -> 0
                }
    }

    class Song(
        val duration: Float,
        /** Level per analysis frame (0..1). */
        val level: FloatArray,
        /** Analysis frames per second. */
        val fps: Float,
        val downbeats: FloatArray,
        /** Fallback tempo when there is no beat grid. */
        val bpm: Float,
        val dropTimes: FloatArray,
        val dropStrengths: FloatArray,
    ) {
        private val prefix =
            DoubleArray(level.size + 1).also {
                for (i in level.indices) {
                    val v = level[i]
                    it[i + 1] = it[i] + if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
                }
            }

        /** Seconds per bar: the median downbeat spacing, else four beats at [bpm], else 2 s. */
        val bar: Float = ReleasePlan.barSeconds(downbeats, bpm)

        /** Mean level over [w] (0 for an empty window or no analysis). */
        fun strength(w: Window): Float {
            if (level.isEmpty() || !(fps > 0f)) return 0f
            val a = (w.start * fps).roundToInt().coerceIn(0, level.size)
            val b = (w.end * fps).roundToInt().coerceIn(0, level.size)
            if (b <= a) return 0f
            return ((prefix[b] - prefix[a]) / (b - a)).toFloat()
        }

        /** Bonus for a drop that lands early in [w]. */
        fun dropBonus(w: Window): Float {
            var best = 0f
            val until = w.start + .35f * w.length
            for (i in dropTimes.indices) {
                val t = dropTimes[i]
                if (t >= w.start - .05f && t <= until)
                    best = max(best, ReleasePlan.DROP_BONUS * dropStrengths.getOrElse(i) { 1f }.coerceIn(0f, 1f))
            }
            return best
        }

        /**
         * Candidate start times for a window of [length]: downbeats from [from] on, where the
         * window still fits; else a bar grid from 0; else just [from]. Ascending.
         */
        fun starts(length: Float, from: Float = 0f, until: Float = duration): FloatArray {
            val last = min(until, duration) - length
            val lo = max(0f, from)
            if (last < lo - 1e-3f) return floatArrayOf(lo.coerceAtMost(max(0f, duration - length)))
            val beats = downbeats.filter { it >= lo - 1e-3f && it <= last + 1e-3f }
            if (beats.isNotEmpty()) return beats.map { max(0f, it) }.toFloatArray()
            val out = ArrayList<Float>()
            var k = (lo / bar).toInt()
            while (k * bar <= last + 1e-3f && out.size < 10000) {
                if (k * bar >= lo - 1e-3f) out.add(k * bar)
                k++
            }
            if (out.isEmpty()) out.add(lo)
            return out.toFloatArray()
        }
    }

    fun barSeconds(downbeats: FloatArray, bpm: Float): Float {
        val gaps =
            (1 until downbeats.size)
                .map { downbeats[it] - downbeats[it - 1] }
                .filter { it in .8f..8f }
                .sorted()
        if (gaps.isNotEmpty()) return gaps[gaps.size / 2]
        if (bpm in 40f..240f) return 4 * 60f / bpm
        return 2f
    }

    /**
     * The best window of [length] among [starts] by [score], avoiding [avoid]: least total
     * overlap first (to 0.01 s), then the highest score, then the earliest start.
     */
    fun pick(
        song: Song,
        length: Float,
        starts: FloatArray,
        avoid: List<Window>,
        score: (Window) -> Float,
    ): Window {
        val len = min(length, song.duration)
        var best: Window? = null
        var bestOverlap = Float.MAX_VALUE
        var bestScore = -Float.MAX_VALUE
        for (s in starts) {
            val start = s.coerceIn(0f, max(0f, song.duration - len))
            val w = Window(start, start + len)
            val overlap = (avoid.sumOf { w.overlap(it).toDouble() } * 100).roundToInt() / 100f
            val value = score(w)
            val better =
                overlap < bestOverlap - 1e-4f ||
                    (abs(overlap - bestOverlap) <= 1e-4f && value > bestScore + 1e-6f)
            if (better) {
                best = w
                bestOverlap = overlap
                bestScore = value
            }
        }
        return best ?: Window(0f, len)
    }

    /** Canvas length: a whole number of bars (or half bars for very slow songs) in 3..8 s. */
    fun canvasLength(bar: Float): Float {
        var unit = if (bar > 0f && bar.isFinite()) bar else 2f
        while (unit > CANVAS_MAX) unit /= 2
        val n = max(1, floor(CANVAS_MAX / unit + 1e-4f).toInt())
        return (n * unit).coerceIn(CANVAS_MIN.coerceAtMost(unit), CANVAS_MAX)
    }

    /**
     * The seven outputs in render order (short clips first so they are ready soonest, the
     * full-length YouTube video last). [youTube] is the landscape size the encoder accepted.
     */
    fun plan(song: Song, youTube: Pair<Int, Int>, squareSeconds: Float): List<Item> {
        val d = song.duration
        val strong = { w: Window -> song.strength(w) + song.dropBonus(w) }
        val a = pick(song, 15f, song.starts(15f), emptyList(), strong)
        // The hook comes before the main chorus when the song leaves room for it.
        val beforeA = if (a.start >= 15f - 1e-3f) song.starts(15f, 0f, a.start) else song.starts(15f)
        val b =
            pick(song, 15f, beforeA, listOf(a)) { w ->
                song.strength(w) * (1f - .3f * (w.start / d.coerceAtLeast(1f)))
            }
        // A different section: keep clear of A and B by SECTION_GAP so C is not just the
        // other half of the same chorus.
        val c =
            pick(song, 15f, song.starts(15f), listOf(a.padded(SECTION_GAP), b.padded(SECTION_GAP))) {
                song.strength(it)
            }
        val reel = pick(song, 30f, song.starts(30f), emptyList(), strong)
        val square = pick(song, squareSeconds, song.starts(squareSeconds), listOf(reel), strong)
        val loopLength = min(canvasLength(song.bar), max(1f, d - LOOP_CROSSFADE))
        // Inside Vertical A when it fits, after enough song for the crossfade.
        val inA = song.starts(loopLength, max(a.start, LOOP_CROSSFADE), a.end)
        val canvasStarts =
            if (inA.any { it >= LOOP_CROSSFADE - 1e-3f && it + loopLength <= a.end + 1e-3f }) inA
            else song.starts(loopLength, LOOP_CROSSFADE)
        val canvas = pick(song, loopLength, canvasStarts, emptyList()) { song.strength(it) }
        return listOf(
            Item(Kind.VERTICAL_A, 1080, 1920, a, "strongest 15 s (chorus or drop)"),
            Item(Kind.VERTICAL_B, 1080, 1920, b, "hook: strength weighted toward the start"),
            Item(Kind.VERTICAL_C, 1080, 1920, c, "next strongest 15 s, apart from A and B"),
            Item(Kind.REEL, 1080, 1920, reel, "strongest 30 s"),
            Item(Kind.SQUARE, 1080, 1080, square, "strongest ${squareSeconds.toInt()} s away from the reel"),
            Item(Kind.CANVAS, 1080, 1920, canvas, "${String.format(Locale.US, "%.2f", loopLength)} s = whole bars, inside A"),
            Item(Kind.YOUTUBE, youTube.first, youTube.second, Window(0f, d), "full song"),
        )
    }
}
