package com.parker.crownpulse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    private fun word(text: String, line: Int, onset: Float, offset: Float = onset + .3f, flagged: Boolean = false) =
        LyricWord(text, text.lowercase(), line, onset, offset, .5f, flagged)

    /** Two lines: "one two three" at 10 s, "four five" at 20 s. */
    private fun timings() =
        LyricTimings(
            "one two three\nfour five",
            listOf(
                word("one", 0, 10f),
                word("two", 0, 10.5f),
                word("three", 0, 11f, 11.6f),
                word("four", 1, 20f),
                word("five", 1, 20.4f, 21f),
            ),
        )

    @Test
    fun linesAppearBeforeTheirFirstWordAndClearAfterTheLast() {
        val c = KaraokeClock(timings())
        assertEquals(-1, c.lineAt(9f))
        assertEquals(0, c.lineAt(10f - KaraokeClock.LEAD + .01f))
        assertEquals(0, c.lineAt(12f))
        // The instrumental gap: the first line is gone HOLD seconds after its last word.
        assertEquals(-1, c.lineAt(11.6f + KaraokeClock.HOLD + .1f))
        assertEquals(1, c.lineAt(19.5f))
        assertEquals(-1, c.lineAt(30f))
    }

    @Test
    fun activeWordFollowsOnsets() {
        val t = timings()
        val c = KaraokeClock(t)
        assertEquals(0, c.sung(0, 9.6f))
        assertFalse(c.active(0, 9.6f))
        assertEquals(1, c.sung(0, 10.1f))
        assertTrue(c.active(0, 10.1f))
        assertEquals(2, c.sung(0, 10.6f))
        // After the last word's end plus the tail, nothing is highlighted.
        assertEquals(3, c.sung(0, 12.5f))
        assertFalse(c.active(0, 12.5f))
        // The key changes only when the picture does.
        assertEquals(c.key(10.1f), c.key(10.2f))
        assertNotEquals(c.key(10.2f), c.key(10.6f))
        assertEquals(-1L, c.key(5f))
    }

    @Test
    fun tapFixKeepsWordsInOrder() {
        val t = timings()
        // A tap far after the next word is clamped before it.
        TapFix.apply(t.words, 1, 12f)
        assertTrue(t.words[1].onset < t.words[2].onset)
        assertEquals(t.words[2].onset - TapFix.MIN_GAP, t.words[1].onset, 1e-5f)
        assertTrue(t.words[1].fixed)
        assertFalse(t.words[1].flagged)
        // An earlier tap moves it and trims the previous word's end.
        TapFix.apply(t.words, 1, 10.2f)
        assertEquals(10.2f, t.words[1].onset, 1e-5f)
        assertTrue(t.words[0].offset <= 10.2f)
        assertTrue(t.words[1].offset <= t.words[2].onset)
        for (i in 1 until t.words.size) assertTrue(t.words[i - 1].onset < t.words[i].onset)
    }

    @Test
    fun snapFindsTheNearestOnsetWithin120Ms() {
        val rate = 16000
        // Quiet noise floor, then a tone burst starting at 0.30 s into the window.
        val n = rate / 2
        val x =
            FloatArray(n) { i ->
                val t = i.toFloat() / rate
                val noise = (((i * 1103515245 + 12345) ushr 16) and 0x7fff) / 32768f - .5f
                val burst = if (t >= .30f) sin(2 * PI * 440 * t).toFloat() * exp(-(t - .30f) * 4) else 0f
                .00005f * noise + .6f * burst
            }
        val start = 10f
        // Tap 80 ms late: snaps back to the onset (within a hop or so).
        val snapped = TapFix.snap(x, rate, start, start + .38f)
        assertEquals(start + .30f, snapped, .02f)
        // Tap 200 ms away: outside the window, so the tap is kept.
        assertEquals(start + .10f, TapFix.snap(x, rate, start, start + .10f), 1e-6f)
    }

    @Test
    fun latencyIsTheMedianOffsetFromTheBeat() {
        val beats = FloatArray(16) { it * .5f }
        val taps = List(8) { it * .5f + .07f + (if (it % 2 == 0) .01f else -.01f) }
        val latency = TapFix.latency(taps, beats)
        assertNotNull(latency)
        assertTrue(abs(latency!! - .07f) < .015f)
        assertNull(TapFix.latency(listOf(1f, 2f), beats))
    }

    @Test
    fun lineStartsSplitWordsByLine() {
        val t = timings()
        assertArrayEquals(intArrayOf(0, 3, 5), t.lineStarts)
        assertEquals(2, t.lineCount)
    }
}
