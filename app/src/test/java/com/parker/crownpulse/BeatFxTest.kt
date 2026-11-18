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

    @Test
    fun latestHitKeepsTheBiggerRipple() {
        val times = floatArrayOf(1f, 1.05f, 2f)
        val strengths = floatArrayOf(.9f, .26f, .1f)
        val out = FloatArray(2)
        BeatFx.latestHit(times, strengths, 1.02f, out)
        assertEquals(.02f, out[0], eps)
        assertEquals(.9f, out[1], eps)
        // A weak hit just after a big one does not cut the big ripple short.
        BeatFx.latestHit(times, strengths, 1.06f, out)
        assertEquals(.06f, out[0], eps)
        assertEquals(.9f, out[1], eps)
        // Hits below RIPPLE_MIN_STRENGTH start nothing; old hits have faded out.
        BeatFx.latestHit(times, strengths, 2.05f, out)
        assertEquals(-1f, out[0], eps)
        BeatFx.latestHit(times, strengths, .5f, out)
        assertEquals(-1f, out[0], eps)
        BeatFx.latestHit(FloatArray(0), FloatArray(0), 1f, out)
        assertEquals(-1f, out[0], eps)
    }

    @Test
    fun rippleIsADampedWaveBehindItsFront() {
        val age = .1f
        val front = age * BeatFx.RIPPLE_SPEED
        assertEquals(0f, BeatFx.rippleShift(front, age, 1f, 1f), eps)
        val quarter = 1f / (4f * BeatFx.RIPPLE_WAVES)
        val expected =
            BeatFx.RIPPLE_DEPTH * BeatFx.decay(age, BeatFx.RIPPLE_LIFE) *
                kotlin.math.exp(-quarter * BeatFx.RIPPLE_FOCUS)
        assertEquals(expected, BeatFx.rippleShift(front + quarter, age, 1f, 1f), eps)
        assertEquals(0f, BeatFx.rippleShift(.3f, BeatFx.RIPPLE_LIFE, 1f, 1f), eps)
        assertEquals(0f, BeatFx.rippleShift(.3f, -1f, 1f, 1f), eps)
        assertEquals(0f, BeatFx.rippleShift(.3f, age, 1f, 0f), eps)
        for (i in 0..100) {
            val v = BeatFx.rippleShift(i / 100f, age, 9f, 9f)
            assertTrue(kotlin.math.abs(v) <= 2 * BeatFx.RIPPLE_DEPTH + eps)
        }
    }

    @Test
    fun centroidIsTheBalancePointOfTheBands() {
        val bands = FloatArray(12)
        assertEquals(0f, BeatFx.spectralCentroid(bands, 2, 10), eps)
        bands[2] = 1f
        assertEquals(0f, BeatFx.spectralCentroid(bands, 2, 10), eps)
        bands[11] = 1f
        assertEquals(.5f, BeatFx.spectralCentroid(bands, 2, 10), eps)
        bands[2] = 0f
        assertEquals(1f, BeatFx.spectralCentroid(bands, 2, 10), eps)
        assertEquals(0f, BeatFx.spectralCentroid(bands, 5, 10), eps)
    }

    @Test
    fun rainbowEdgeOnlyOnBrightSounds() {
        assertEquals(0f, BeatFx.fringeScale(0f, 1f, 1f), eps)
        assertEquals(BeatFx.FRINGE_SCALE, BeatFx.fringeScale(1f, .7f, 1f), eps)
        assertEquals(2 * BeatFx.FRINGE_SCALE, BeatFx.fringeScale(5f, 1f, 9f), eps)
        // Dull sound: loud treble alone gives only a little edge, quiet treble none.
        assertEquals(BeatFx.FRINGE_SCALE * (.1f / .75f), BeatFx.fringeScale(1f, 0f, 1f), eps)
        assertEquals(0f, BeatFx.fringeScale(.3f, 0f, 1f), eps)
        assertEquals(0f, BeatFx.fringeScale(1f, 1f, 0f), eps)
        assertEquals(0f, BeatFx.fringeScale(Float.NaN, 1f, 1f), eps)
    }

    @Test
    fun findsADropWhereTheMusicJumps() {
        // 10 frames per second: 5 s at level .2, then 5 s at .9.
        val level = FloatArray(100) { if (it < 50) .2f else .9f }
        val drops = BeatFx.findDrops(level, 10f, FloatArray(0))
        assertEquals(2, drops.size)
        assertEquals(5f, drops[0], eps)
        assertEquals(1f, drops[1], eps)
        // A beat close by pulls the drop onto it; a far one does not.
        assertEquals(4.95f, BeatFx.findDrops(level, 10f, floatArrayOf(4.95f))[0], eps)
        assertEquals(5f, BeatFx.findDrops(level, 10f, floatArrayOf(4.5f))[0], eps)
    }

    @Test
    fun noDropsInSteadyOrEmptyMusic() {
        assertEquals(0, BeatFx.findDrops(FloatArray(300) { .8f }, 10f, FloatArray(0)).size)
        assertEquals(0, BeatFx.findDrops(FloatArray(0), 10f, FloatArray(0)).size)
        assertEquals(0, BeatFx.findDrops(FloatArray(100) { it / 99f }, 0f, FloatArray(0)).size)
        // A jump that stays quiet is not a drop.
        val soft = FloatArray(100) { if (it < 50) 0f else .4f }
        assertEquals(0, BeatFx.findDrops(soft, 10f, FloatArray(0)).size)
    }

    @Test
    fun dropFlashFadesOut() {
        assertEquals(BeatFx.DROP_FLASH, BeatFx.dropFlash(0f, 1f, 1f), eps)
        assertEquals(2 * BeatFx.DROP_FLASH, BeatFx.dropFlash(0f, 5f, 9f), eps)
        assertEquals(0f, BeatFx.dropFlash(-1f, 1f, 1f), eps)
        assertEquals(0f, BeatFx.dropFlash(BeatFx.DROP_LIFE, 1f, 1f), eps)
        assertEquals(0f, BeatFx.dropFlash(.1f, 1f, 0f), eps)
    }

    @Test
    fun lightRaysFollowTheBass() {
        assertEquals(0f, BeatFx.rayAlpha(BeatFx.RAY_THRESHOLD, 1f), eps)
        assertEquals(BeatFx.RAY_ALPHA, BeatFx.rayAlpha(1f, 1f), eps)
        assertEquals(2 * BeatFx.RAY_ALPHA, BeatFx.rayAlpha(1.8f, 9f), eps)
        assertEquals(0f, BeatFx.rayAlpha(Float.NaN, 1f), eps)
        assertTrue(BeatFx.rayHalfAngle(1f) > BeatFx.rayHalfAngle(0f))
    }
}
