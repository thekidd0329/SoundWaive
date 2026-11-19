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
}
