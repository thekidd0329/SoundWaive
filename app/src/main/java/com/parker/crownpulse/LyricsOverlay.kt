package com.parker.crownpulse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Where the lyrics layer sits in a frame. Preview, the CPU export path and the GPU post pass
 * all read [LayerOrder.lyrics], so changing it there moves the layer everywhere at once.
 *
 * Frame layers, bottom to top:
 * 1. background (color, photo, GIF or video) and paper grain
 * 2. effects (rings, particles, notes, waves and the rest of the scene)
 * 3. the symbol (the pulsing crown or logo) with its glow, bounce and ripple
 * 4. **[ABOVE_SYMBOL]**: lyrics directly over the scene
 * 5. build-up darkening, drop flash, grain specks and vignette
 * 6. titles
 * 7. **[TOP]**: lyrics over everything except the clip fade
 * 8. the clip's fade in and out
 *
 * Either way the lyrics are above the crown; the crown's own position, size and animation are
 * unchanged. Lyrics are never mirrored and never shaken, so they stay readable.
 */
enum class LyricsZ {
    ABOVE_SYMBOL,
    TOP,
}

/** The z-order hook (see [LyricsZ]). */
object LayerOrder {
    /** TOP keeps words readable through drop flashes, build-up darkening and the vignette. */
    val lyrics: LyricsZ = LyricsZ.TOP
}

/**
 * Karaoke lyrics: the current line, with sung words in full color, the word being sung in the
 * highlight color and the words still to come dimmer; the next line is shown smaller below.
 * Timing (which line, which word) comes from [KaraokeClock]. Everything is in frame units
 * (the frame is 1080 units wide, like [VisualRenderer]); one code path draws the preview, the
 * CPU export and the GPU strip.
 */
class LyricsOverlay(val timings: LyricTimings) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    // Layout cache: rows of the current line for one size and line.
    private var layoutLine = -1
    private var layoutSize = -1f
    private var rowBreaks = IntArray(0)
    private var rowSize = 0f

    init {
        stroke.style = Paint.Style.STROKE
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.color = Color.BLACK
        fill.typeface = bold
        stroke.typeface = bold
        fill.textAlign = Paint.Align.LEFT
        stroke.textAlign = Paint.Align.LEFT
    }

    /** Which line and words are up at a time (pure Kotlin, unit tested). */
    val clock = KaraokeClock(timings)

    /** See [KaraokeClock.key]: the GPU strip is redrawn only when this changes. */
    fun key(t: Float): Long = clock.key(t)

    /** Text size in frame units for a frame [h] units tall: smaller in wide frames. */
    fun size(h: Float, s: Settings): Float = BASE_SIZE * s.f("lyricsSize") * (h / 1300f).coerceIn(.62f, 1f)

    /** Baseline of the current line's first row. Avoids titles at the bottom of the frame. */
    fun anchor(h: Float, s: Settings): Float {
        val size = size(h, s)
        val titleAtBottom =
            (s.s("title").isNotEmpty() || s.s("subtitle").isNotEmpty()) &&
                s.s("textPosition") != "Top" && s.s("textPosition") != "Center"
        val wide = h < 1000f
        return when (s.s("lyricsPosition")) {
            "Top" -> max(h * (if (wide) .14f else .16f), size * 1.3f)
            "Center" -> h * .5f
            else ->
                when {
                    titleAtBottom -> h * (if (wide) .66f else .70f)
                    h > 1500f -> h * .74f // 9:16: above the app buttons and captions of Reels/TikTok
                    else -> h * .80f
                }
        }
    }

    /** Vertical band (frame units) the layer can draw into: top and bottom. Used by the GPU strip. */
    fun band(h: Float, s: Settings): FloatArray {
        val size = size(h, s)
        val a = anchor(h, s)
        val top = (a - size * 1.35f).coerceAtLeast(0f)
        val bottom = (a + size * ROW * (MAX_ROWS - 1) + size * (NEXT_GAP + NEXT_SIZE) + size * .4f).coerceAtMost(h)
        return floatArrayOf(top, max(bottom, top + 1f))
    }

    /** Draws the lyrics at [t] onto [c], whose coordinates are frame units. */
    fun draw(c: Canvas, h: Float, t: Float, s: Settings) {
        val line = clock.lineAt(t)
        if (line < 0) return
        val base = s.color("lyricsColor")
        val highlight = s.color("lyricsHighlight")
        val size0 = size(h, s)
        layout(line, size0)
        val sung = clock.sung(line, t)
        val active = clock.active(line, t)
        val from = timings.lineStarts[line]
        var y = anchor(h, s)
        val rows = rowBreaks.size - 1
        for (r in 0 until rows) {
            val a = rowBreaks[r]
            val b = rowBreaks[r + 1]
            fill.textSize = rowSize
            stroke.textSize = rowSize
            stroke.strokeWidth = rowSize * .14f
            var x = 540f - rowWidth(a, b) / 2
            val gap = fill.measureText(" ")
            for (i in a until b) {
                val w = timings.words[i]
                val k = i - from
                val color = if (k == sung - 1 && active) highlight else base
                val alpha = if (k < sung) 1f else UPCOMING_ALPHA
                stroke.alpha = (170 * alpha).toInt()
                fill.color = color
                fill.alpha = (255 * alpha).toInt()
                c.drawText(w.text, x, y, stroke)
                c.drawText(w.text, x, y, fill)
                x += fill.measureText(w.text) + gap
            }
            y += rowSize * ROW
        }
        // The next line, small and dim, while this one is up.
        if (line + 1 < timings.lineCount) {
            val next = timings.lineStarts[line + 1]
            val end = timings.lineStarts[line + 2]
            val nextSize = rowSize * NEXT_SIZE
            fill.textSize = nextSize
            stroke.textSize = nextSize
            stroke.strokeWidth = nextSize * .14f
            fill.color = base
            fill.alpha = (255 * NEXT_ALPHA).toInt()
            stroke.alpha = (140 * NEXT_ALPHA).toInt()
            var text = (next until end).joinToString(" ") { timings.words[it].text }
            val maxWidth = 1080f * MAX_WIDTH
            if (fill.measureText(text) > maxWidth) {
                val count = fill.breakText(text, true, maxWidth - fill.measureText("…"), null)
                text = text.take(count).trimEnd() + "…"
            }
            val x = 540f - fill.measureText(text) / 2
            y += rowSize * (NEXT_GAP - ROW) + nextSize
            c.drawText(text, x, y, stroke)
            c.drawText(text, x, y, fill)
        }
    }

    private fun rowWidth(a: Int, b: Int): Float {
        var width = 0f
        val gap = fill.measureText(" ")
        for (i in a until b) width += fill.measureText(timings.words[i].text) + if (i > a) gap else 0f
        return width
    }

    /** Breaks the line into at most [MAX_ROWS] centered rows, shrinking the text if it must. */
    private fun layout(line: Int, size: Float) {
        if (line == layoutLine && size == layoutSize) return
        layoutLine = line
        layoutSize = size
        val from = timings.lineStarts[line]
        val to = timings.lineStarts[line + 1]
        val maxWidth = 1080f * MAX_WIDTH
        var current = size
        while (true) {
            fill.textSize = current
            val gap = fill.measureText(" ")
            val breaks = ArrayList<Int>()
            breaks.add(from)
            var width = 0f
            for (i in from until to) {
                val w = fill.measureText(timings.words[i].text)
                if (i > breaks.last() && width + gap + w > maxWidth) {
                    breaks.add(i)
                    width = w
                } else width += (if (i > breaks.last()) gap else 0f) + w
            }
            breaks.add(to)
            val fits = breaks.size - 1 <= MAX_ROWS
            if (fits || current <= size * .6f) {
                rowBreaks = breaks.toIntArray()
                rowSize = current
                // A single word wider than the frame: shrink so it fits.
                for (r in 0 until rowBreaks.size - 1) {
                    val wRow = rowWidth(rowBreaks[r], rowBreaks[r + 1])
                    if (wRow > maxWidth) rowSize = min(rowSize, current * maxWidth / wRow)
                }
                if (rowBreaks.size - 1 > MAX_ROWS) {
                    // Still too long: keep the first rows (the rest of the line is rare).
                    rowBreaks = rowBreaks.copyOf(MAX_ROWS + 1)
                    rowBreaks[MAX_ROWS] = to
                }
                return
            }
            current *= .9f
        }
    }

    /**
     * The layer as a transparent bitmap strip for the GPU post pass: full frame width, the
     * [band] rows only, drawn by [draw] so it matches the Canvas path. Redrawn only when [key]
     * changes.
     */
    class Strip(private val overlay: LyricsOverlay, private val s: Settings, frameH: Float, pixelWidth: Int) {
        private val h = frameH
        private val band = overlay.band(frameH, s)
        private val scale = pixelWidth / 1080f
        val bitmap: Bitmap =
            Bitmap.createBitmap(
                pixelWidth.coerceAtLeast(1),
                ((band[1] - band[0]) * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        private val canvas = Canvas(bitmap)
        private var key = Long.MIN_VALUE

        /** Band top and bottom as 0..1 of the frame height. */
        val top: Float
            get() = band[0] / h

        val bottom: Float
            get() = (band[0] + bitmap.height / scale) / h

        /** Redraws for [t]; true when the picture changed (the texture must be re-uploaded). */
        fun update(t: Float): Boolean {
            val next = overlay.key(t)
            if (next == key) return false
            key = next
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (next != -1L) {
                canvas.save()
                canvas.scale(scale, scale)
                canvas.translate(0f, -band[0])
                overlay.draw(canvas, h, t, s)
                canvas.restore()
            }
            return true
        }

        fun release() {
            bitmap.recycle()
        }
    }

    companion object {
        const val BASE_SIZE = 62f
        const val ROW = 1.22f
        const val MAX_ROWS = 2
        const val MAX_WIDTH = .86f
        const val NEXT_SIZE = .72f
        /** Space between the current line's last baseline and the next line's top, in sizes. */
        const val NEXT_GAP = .35f
        const val NEXT_ALPHA = .5f
        const val UPCOMING_ALPHA = .62f

        /** True when [s] shows lyrics and there are timings to show. */
        fun visible(s: Settings, overlay: LyricsOverlay?): Boolean =
            overlay != null && s.b("lyrics") && overlay.timings.words.isNotEmpty()
    }
}
