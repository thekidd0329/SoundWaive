package com.parker.crownpulse

import org.junit.Assert.*
import org.junit.Test

class BeatFxTest {
    private val eps = 1e-5f
    private val beats = floatArrayOf(.5f, 1f, 1.5f, 2f)

    @Test
    fun lastAtOrBeforeFindsTheLatestBeat() {
        assertEquals(-1, BeatFx.lastAtOrBefore(beats, .2f))
        assertEquals(0, BeatFx.lastAtOrBefore(beats, .5f))
        assertEquals(1, BeatFx.lastAtOrBefore(beats, 1.4f))
        assertEquals(3, BeatFx.lastAtOrBefore(beats, 9f))
        assertEquals(-1, BeatFx.lastAtOrBefore(FloatArray(0), 1f))
        assertEquals(-1, BeatFx.lastAtOrBefore(beats, Float.NaN))
    }

    @Test
    fun ageCountsFromLatestAndPreviousBeat() {
        assertEquals(.2f, BeatFx.ageOf(beats, 1.2f, 0), eps)
        assertEquals(.7f, BeatFx.ageOf(beats, 1.2f, 1), eps)
        assertEquals(-1f, BeatFx.ageOf(beats, .6f, 1), eps)
    }

    @Test
    fun fixedGridMatchesTempo() {
        val period = BeatFx.period(120f)
        assertEquals(.5f, period, eps)
        assertEquals(.1f, BeatFx.gridAge(1.1f, period, 0), eps)
        assertEquals(.6f, BeatFx.gridAge(1.1f, period, 1), eps)
        assertEquals(-1f, BeatFx.gridAge(.2f, period, 1), eps)
        assertEquals(-1f, BeatFx.gridAge(1f, 0f, 0), eps)
        assertEquals(0f, BeatFx.period(0f), eps)
    }

    @Test
    fun decayStartsFullAndEndsAtLife() {
        assertEquals(1f, BeatFx.decay(0f, .5f), eps)
        assertEquals(.125f, BeatFx.decay(.25f, .5f), eps)
        assertEquals(0f, BeatFx.decay(.5f, .5f), eps)
        assertEquals(0f, BeatFx.decay(-1f, .5f), eps)
    }

    @Test
    fun ringsEaseOutAndStayInRange() {
        assertEquals(0f, BeatFx.ringTravel(0f, 1f), eps)
        assertEquals(.75f, BeatFx.ringTravel(.5f, 1f), eps)
        assertEquals(1f, BeatFx.ringTravel(3f, 1f), eps)
    }

    @Test
    fun punchIsBoundedAndSettles() {
        assertEquals(1f + BeatFx.PUNCH_ZOOM, BeatFx.punchScale(0f, 1f), eps)
        assertEquals(1f + 2 * BeatFx.PUNCH_ZOOM, BeatFx.punchScale(0f, 9f), eps)
        assertEquals(1f, BeatFx.punchScale(BeatFx.PUNCH_LIFE, 1f), eps)
        assertEquals(1f, BeatFx.punchScale(-1f, 1f), eps)
        assertEquals(1f, BeatFx.punchScale(0f, 0f), eps)
    }

    @Test
    fun colorSplitOnlyOnLoudSnares() {
        assertEquals(0f, BeatFx.splitOffset(.2f, 1f), eps)
        assertEquals(0f, BeatFx.splitOffset(BeatFx.SPLIT_THRESHOLD, 1f), eps)
        assertEquals(BeatFx.SPLIT_PIXELS, BeatFx.splitOffset(1.3f, 1f), eps)
        assertEquals(BeatFx.SPLIT_PIXELS / 2, BeatFx.splitOffset(.65f, 1f), eps)
        assertEquals(0f, BeatFx.splitOffset(1f, 0f), eps)
    }

    @Test
    fun fifthsHueWalksTheCircleOfFifths() {
        assertEquals(0f, BeatFx.fifthsHue(60), eps) // C
        assertEquals(30f, BeatFx.fifthsHue(67), eps) // G, a fifth up
        assertEquals(60f, BeatFx.fifthsHue(62), eps) // D
        assertEquals(330f, BeatFx.fifthsHue(65), eps) // F, a fifth down
        assertEquals(BeatFx.fifthsHue(48), BeatFx.fifthsHue(72), eps) // octaves match
        assertEquals(BeatFx.fifthsHue(11), BeatFx.fifthsHue(-1), eps) // B, negative safe
    }

    @Test
    fun noteColorBlendsRecentNotes() {
        val out = FloatArray(3)
        val times = floatArrayOf(1f, 1.1f, 5f)
        val vel = floatArrayOf(1f, 1f, 1f)
        // Two Cs: pure red, full agreement.
        BeatFx.noteColor(times, intArrayOf(60, 48, 67), vel, 1.1f, out)
        assertEquals(0f, out[0], 1e-3f)
        assertEquals(1f, out[1], 1e-4f)
        assertTrue(out[2] > .5f)
        // C and G blend halfway (15 degrees) when equally weighted.
        BeatFx.noteColor(floatArrayOf(1f, 1f), intArrayOf(60, 67), floatArrayOf(1f, 1f), 1f, out)
        assertEquals(15f, out[0], 1e-3f)
        // Wrap: F (330) and C (0) average to 345, not 165.
        BeatFx.noteColor(floatArrayOf(1f, 1f), intArrayOf(65, 60), floatArrayOf(1f, 1f), 1f, out)
        assertEquals(345f, out[0], 1e-3f)
        // C and F# are opposite: they cancel, so agreement is near 0.
        BeatFx.noteColor(floatArrayOf(1f, 1f), intArrayOf(60, 66), floatArrayOf(1f, 1f), 1f, out)
        assertEquals(0f, out[1], 1e-4f)
        // Nothing heard yet, or the notes are too old.
        BeatFx.noteColor(times, intArrayOf(60, 48, 67), vel, .5f, out)
        assertEquals(0f, out[2], eps)
        BeatFx.noteColor(times, intArrayOf(60, 48, 67), vel, 1.1f + BeatFx.GLOW_WINDOW, out)
        assertEquals(0f, out[2], eps)
        BeatFx.noteColor(FloatArray(0), IntArray(0), FloatArray(0), 1f, out)
        assertEquals(0f, out[2], eps)
    }

    @Test
    fun glowIsBoundedAndFollowsBass() {
        assertEquals(BeatFx.GLOW_BASE, BeatFx.glowAlpha(0f, 1f), eps)
        assertEquals(BeatFx.GLOW_BASE + BeatFx.GLOW_BASS, BeatFx.glowAlpha(1.8f, 1f), eps)
        assertEquals(0f, BeatFx.glowAlpha(1f, 0f), eps)
        assertTrue(BeatFx.glowAlpha(9f, 9f) <= 1f)
    }

    @Test
    fun sparklesOnlyOnLoudHats() {
        assertEquals(0, BeatFx.sparkleCount(.2f, 1f))
        assertEquals(0, BeatFx.sparkleCount(BeatFx.SPARKLE_THRESHOLD, 1f))
        assertEquals(BeatFx.SPARKLE_MAX / 2, BeatFx.sparkleCount(1.3f, 1f))
        assertEquals(BeatFx.SPARKLE_MAX, BeatFx.sparkleCount(1.3f, 9f))
        assertEquals(0, BeatFx.sparkleCount(1f, 0f))
        assertEquals(0, BeatFx.sparkleCount(Float.NaN, 1f))
    }

    @Test
    fun hashIsRepeatableAndInRange() {
        assertEquals(BeatFx.hash01(12345), BeatFx.hash01(12345), 0f)
        assertNotEquals(BeatFx.hash01(1), BeatFx.hash01(2), 0f)
        for (n in -1000..1000) {
            val v = BeatFx.hash01(n * 7919)
            assertTrue(v >= 0f && v < 1f)
        }
    }
}
