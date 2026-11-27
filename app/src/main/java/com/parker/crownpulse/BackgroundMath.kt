package com.parker.crownpulse

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure time, size and placement rules for media backgrounds and render scaling. No Android
 * types, so every rule is covered by JVM tests.
 */
object BackgroundMath {
    const val COLOR = "Color"
    const val PHOTO = "Photo"
    const val GIF = "GIF"
    const val VIDEO = "Video"
    val TYPES = listOf(COLOR, PHOTO, GIF, VIDEO)

    const val FILL = "Fill (crop)"
    const val FIT = "Fit (blurred edges)"
    val FITS = listOf(FILL, FIT)

    const val LOOP = "Loop"
    const val HOLD = "Hold last frame"
    val ENDS = listOf(LOOP, HOLD)

    const val FAST = "Fast (360p, upscaled)"
    const val BALANCED = "Balanced (720p)"
    const val FULL = "Full (original)"
    val QUALITIES = listOf(FAST, BALANCED, FULL)

    data class Size(val width: Int, val height: Int)

    /**
     * Media time in seconds shown at music time [t] for a clip lasting [duration] seconds.
     * Loop wraps; hold stops on the last instant. Invalid input shows the first frame.
     */
    fun mediaTime(t: Double, duration: Double, end: String): Double {
        if (!(duration > 0) || !t.isFinite() || t <= 0) return 0.0
        return if (end == HOLD) min(t, duration) else t % duration
    }

    /** Index of the frame on screen at [time]: the last frame starting at or before it. */
    fun frameIndex(starts: DoubleArray, time: Double): Int {
        if (starts.isEmpty()) return -1
        var lo = 0
        var hi = starts.size - 1
        if (time < starts[0]) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= time) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun even(v: Double) = max(2, (v / 2).roundToInt() * 2)

    /** Scales [w]x[h] down (never up) so its short side is at most [shortSide]; even sizes. */
    fun capShortSide(w: Int, h: Int, shortSide: Int): Size {
        require(w > 0 && h > 0) { "Empty image" }
        val short = min(w, h)
        if (short <= shortSide) return Size(w, h)
        val k = shortSide.toDouble() / short
        return Size(even(w * k), even(h * k))
    }

    /** Short side the quality setting asks for, before capping to source/output size. */
    fun qualityShortSide(quality: String): Int? =
        when (quality) {
            FAST -> 360
            BALANCED -> 720
            else -> null
        }

    /**
     * Working size for the whole rendered frame of an [outW]x[outH] export. The encoder's
     * GPU pass upscales it to the output with bilinear filtering.
     */
    fun renderSize(outW: Int, outH: Int, quality: String): Size =
        qualityShortSide(quality)?.let { capShortSide(outW, outH, it) } ?: Size(outW, outH)

    /**
     * Decode size for background media of [srcW]x[srcH]. "Full" keeps the source but never
     * exceeds what an [outW]x[outH] frame can show (its long side as the short side covers
     * any crop).
     */
    fun backgroundSize(srcW: Int, srcH: Int, outW: Int, outH: Int, quality: String): Size =
        capShortSide(srcW, srcH, qualityShortSide(quality) ?: max(outW, outH))

    /**
     * Destination rectangle (left, top, right, bottom) for drawing a [srcW]x[srcH] image into
     * a [dstW]x[dstH] frame. Fill covers the frame (cropping), fit shows all of it. [zoom]
     * scales around the frame centre.
     */
    fun place(
        srcW: Int,
        srcH: Int,
        dstW: Float,
        dstH: Float,
        fit: String,
        zoom: Float = 1f,
    ): FloatArray {
        val sx = dstW / srcW
        val sy = dstH / srcH
        val k = (if (fit == FIT) min(sx, sy) else max(sx, sy)) * zoom
        val w = srcW * k
        val h = srcH * k
        return floatArrayOf((dstW - w) / 2, (dstH - h) / 2, (dstW + w) / 2, (dstH + h) / 2)
    }

    /**
     * Symbol scale for a frame [height] units tall in the renderer's 1080-unit-wide space.
     * Looks are designed for frames at least as tall as they are wide (9:16, 4:5, 1:1); a
     * wider frame scales the symbol by its height so it stays centred and uncropped.
     */
    fun symbolFit(height: Float): Float =
        if (height.isFinite() && height > 0f) (height / 1080f).coerceIn(.25f, 1f) else 1f

    /** Subtle bass zoom: at most +4% at full bass. */
    fun pulseZoom(bass: Float): Float = 1f + .04f * bass.coerceIn(0f, 1f)

    /** Background opacity after dimming; a beat lifts up to 35% of the dimming. */
    fun visibleAlpha(dim: Float, beat: Float, pulse: Boolean): Float {
        val d = dim.coerceIn(0f, 1f)
        val lift = if (pulse) .35f * beat.coerceIn(0f, 1f) else 0f
        return (1f - d + d * lift).coerceIn(0f, 1f)
    }
}
