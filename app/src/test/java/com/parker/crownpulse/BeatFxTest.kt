package com.parker.crownpulse

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

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

    @Test
    fun buildUpRisesIntoTheDrop() {
        val drops = floatArrayOf(10f, 30f)
        val strong = floatArrayOf(1f, 1f)
        // Starts BUILD_TIME before the drop, rises as a square, gone on and after the drop.
        assertEquals(0f, BeatFx.buildTension(drops, strong, 10f - BeatFx.BUILD_TIME - .1f), eps)
        assertEquals(.25f, BeatFx.buildTension(drops, strong, 10f - BeatFx.BUILD_TIME / 2), eps)
        assertTrue(BeatFx.buildTension(drops, strong, 9.99f) > .99f)
        assertEquals(0f, BeatFx.buildTension(drops, strong, 10f), eps)
        assertEquals(0f, BeatFx.buildTension(drops, strong, 40f), eps)
        // A weak drop gets half the build-up.
        assertTrue(BeatFx.buildTension(drops, floatArrayOf(0f, 0f), 9.99f) < .51f)
        assertEquals(0f, BeatFx.buildTension(FloatArray(0), FloatArray(0), 5f), eps)
        assertEquals(0f, BeatFx.buildTension(drops, strong, Float.NaN), eps)
    }

    @Test
    fun noBuildUpRightAfterAnotherDrop() {
        // The second drop comes 1.5 s after the first: its build-up would start when the
        // first flash ends (0.8 s left), shorter than BUILD_MIN, so there is none.
        val drops = floatArrayOf(10f, 11.5f)
        val strong = floatArrayOf(1f, 1f)
        assertEquals(0f, BeatFx.buildTension(drops, strong, 11.4f), eps)
        assertEquals(1f + BeatFx.BUILD_ZOOM, BeatFx.buildZoom(1f, 1f), eps)
        assertEquals(1f, BeatFx.buildZoom(0f, 2f), eps)
        assertEquals(2 * BeatFx.BUILD_DARK, BeatFx.buildDark(1f, 9f), eps)
        assertEquals(0f, BeatFx.buildRingFall(-1f), eps)
        assertEquals(1f, BeatFx.buildRingFall(BeatFx.BUILD_RING_LIFE), eps)
    }

    @Test
    fun tiltRocksBothWaysAndSettles() {
        assertEquals(BeatFx.TILT_DEGREES, BeatFx.tiltDegrees(0f, false, 1f, 1f), eps)
        assertEquals(-BeatFx.TILT_DEGREES, BeatFx.tiltDegrees(0f, true, 1f, 1f), eps)
        assertEquals(BeatFx.TILT_DEGREES / 2, BeatFx.tiltDegrees(0f, false, 0f, 1f), eps)
        assertEquals(0f, BeatFx.tiltDegrees(BeatFx.TILT_LIFE, false, 1f, 1f), eps)
        assertEquals(0f, BeatFx.tiltDegrees(-1f, true, 1f, 1f), eps)
    }

    @Test
    fun bounceSquashesOnTheBeatAndSettles() {
        val sq = BeatFx.BOUNCE_SQUASH
        // Squashed on the beat, stronger beats squash more, weak ones half as much.
        assertEquals(-sq, BeatFx.bounceStretch(0f, .5f, 1f, 1f), eps)
        assertEquals(-sq / 2, BeatFx.bounceStretch(0f, .5f, 0f, 1f), eps)
        // Half a swing later it has rebounded above its rest shape.
        assertTrue(BeatFx.bounceStretch(BeatFx.BOUNCE_SWING / 2, 0f, 1f, 1f) > 0f)
        // Flat once the bounce is over, before the beat and when turned off.
        assertEquals(0f, BeatFx.bounceStretch(BeatFx.BOUNCE_LIFE, 0f, 1f, 1f), eps)
        assertEquals(0f, BeatFx.bounceStretch(-1f, .5f, 1f, 1f), eps)
        assertEquals(0f, BeatFx.bounceStretch(Float.NaN, .5f, 1f, 1f), eps)
        assertEquals(0f, BeatFx.bounceStretch(0f, .5f, 1f, 0f), eps)
        // Never more than 30 % either way, even at amount 2.
        assertTrue(abs(BeatFx.bounceStretch(0f, .5f, 1f, 9f)) <= .3f)
    }

    @Test
    fun bounceStretchesJustBeforeTheNextBeat() {
        // 120 BPM: beats every 0.5 s. Just before the next one it stretches up.
        val v = BeatFx.bounceStretch(.5f - .005f, .5f, 1f, 1f)
        assertTrue(v > .4f * BeatFx.BOUNCE_SQUASH)
        // Earlier than BOUNCE_LEAD before the next beat there is no stretch.
        assertEquals(0f, BeatFx.bounceStretch(.5f - BeatFx.BOUNCE_LEAD - .01f, .5f, 1f, 1f), eps)
    }

    @Test
    fun orbitStepsLandOnTheBeatAndJoinUp() {
        assertEquals(-1f, BeatFx.orbitSteps(-1, .1f), eps)
        assertEquals(-1f, BeatFx.orbitSteps(3, -1f), eps)
        // One step per beat: at the beat it starts from its index, then lands one step on.
        assertEquals(3f, BeatFx.orbitSteps(3, 0f), eps)
        assertEquals(4f, BeatFx.orbitSteps(3, BeatFx.ORBIT_STEP_TIME), eps)
        assertEquals(4f, BeatFx.orbitSteps(3, 5f), eps)
        // Overshoots a little on the way, then settles.
        assertTrue(BeatFx.orbitSteps(3, BeatFx.ORBIT_STEP_TIME * .7f) > 4f)
        // Swell on the beat, more on the first beat of a bar, gone after the pulse.
        assertEquals(1f, BeatFx.orbitPulse(0f, 1f, false), eps)
        assertEquals(1.5f, BeatFx.orbitPulse(0f, 1f, true), eps)
        assertEquals(0f, BeatFx.orbitPulse(BeatFx.ORBIT_PULSE_LIFE, 1f, true), eps)
    }

    @Test
    fun blobBandsMirrorWithBassAtTheBottom() {
        val n = BeatFx.BLOB_POINTS
        assertEquals(0, BeatFx.blobBand(0, n, 80))
        assertEquals(79, BeatFx.blobBand(n / 2, n, 80))
        // Left and right halves show the same bands.
        for (j in 1 until n) assertEquals(BeatFx.blobBand(j, n, 80), BeatFx.blobBand(n - j, n, 80))
        // Wraps around and survives empty input.
        assertEquals(BeatFx.blobBand(3, n, 80), BeatFx.blobBand(n + 3, n, 80))
        assertEquals(0, BeatFx.blobBand(5, 0, 80))
        assertEquals(0, BeatFx.blobBand(5, n, 0))
    }

    @Test
    fun blobRestsAsACircleAndSwellsWithTheSound() {
        assertEquals(1f, BeatFx.blobRadius(0f, 0f, 1f), eps)
        assertEquals(1f, BeatFx.blobRadius(1f, 1f, 0f), eps)
        assertEquals(1f + BeatFx.BLOB_BAND + BeatFx.BLOB_BASS, BeatFx.blobRadius(1f, 1f, 1f), eps)
        assertTrue(BeatFx.blobRadius(.5f, 0f, 1f) < BeatFx.blobRadius(.8f, 0f, 1f))
    }

    @Test
    fun warpStarsTravelWithTheFlowAndFadeIn() {
        // Depth grows with the flow and wraps into the next trip.
        assertEquals(.25f, BeatFx.warpTravel(.25f, 0f), eps)
        assertEquals(.25f + BeatFx.WARP_RATE, BeatFx.warpTravel(.25f, 1f), eps)
        assertEquals(0f, BeatFx.warpTravel(.25f, Float.NaN), eps)
        // Invisible at the very start of a trip and outside 0..1, fading in, then steady.
        assertEquals(0f, BeatFx.warpAlpha(0f, 1f, 1f), eps)
        assertEquals(0f, BeatFx.warpAlpha(1f, 1f, 1f), eps)
        assertEquals(0f, BeatFx.warpAlpha(Float.NaN, 1f, 1f), eps)
        val early = BeatFx.warpAlpha(BeatFx.WARP_FADE_IN / 2, 1f, 1f)
        assertTrue(early > 0f && early < BeatFx.warpAlpha(.5f, 1f, 1f))
        assertEquals(.7f, BeatFx.warpAlpha(.5f, 1f, 1f), eps)
        assertEquals(0f, BeatFx.warpAlpha(.5f, 1f, 0f), eps)
    }

    @Test
    fun waveTriggerFindsTheFirstRisingZeroCrossing() {
        // Two stored values before the window, then a falling and a rising crossing.
        val d = floatArrayOf(9f, 9f, .5f, -.2f, -.4f, .3f, .6f, -.1f, .2f, .4f)
        assertEquals(3, BeatFx.waveTrigger(d, 2, 8, 4))
        // The window must still fit after the crossing: none in range gives 0.
        assertEquals(0, BeatFx.waveTrigger(d, 2, 8, 6))
        assertEquals(0, BeatFx.waveTrigger(floatArrayOf(.1f, .2f, .3f, .4f), 0, 4, 2))
        assertEquals(0, BeatFx.waveTrigger(d, 5, 8, 2))
    }

    @Test
    fun wavePeakAndSmoothing() {
        val d = floatArrayOf(.2f, -.8f, .4f, Float.NaN)
        assertEquals(.8f, BeatFx.wavePeak(d, 0, 3), eps)
        assertEquals(.8f, BeatFx.wavePeak(d, 0, 4), eps)
        assertEquals(0f, BeatFx.wavePeak(d, 2, 4), eps)
        // 1-2-1 weights; the ends reuse the end sample.
        assertEquals(.25f * .2f + .5f * -.8f + .25f * .4f, BeatFx.waveAt(d, 0, 3, 1), eps)
        assertEquals(.75f * .2f + .25f * -.8f, BeatFx.waveAt(d, 0, 3, 0), eps)
        assertEquals(BeatFx.waveAt(d, 0, 3, 2), BeatFx.waveAt(d, 0, 3, 99), eps)
        assertEquals(0f, BeatFx.waveAt(d, 0, 4, 3), eps)
    }

    @Test
    fun waveGainScalesToThePeakAndTheEnergy() {
        // A full-energy frame reaches full height at its peak.
        assertEquals(1f, .5f * BeatFx.waveGain(.5f, 1f, 1f), eps)
        // Quiet frames are not magnified past the floor peak.
        assertEquals(1f / BeatFx.WAVE_MIN_PEAK, BeatFx.waveGain(.01f, 1f, 1f), eps)
        assertTrue(BeatFx.waveGain(.5f, .2f, 1f) < BeatFx.waveGain(.5f, 1f, 1f))
        assertEquals(0f, BeatFx.waveGain(.5f, 1f, 0f), eps)
        assertEquals(1f / BeatFx.WAVE_MIN_PEAK, BeatFx.waveGain(Float.NaN, 1f, 1f), eps)
    }

    @Test
    fun waveTaperAndMirroredRing() {
        assertEquals(0f, BeatFx.waveTaper(0f), eps)
        assertEquals(0f, BeatFx.waveTaper(1f), eps)
        assertEquals(0f, BeatFx.waveTaper(Float.NaN), eps)
        assertEquals(1f, BeatFx.waveTaper(.5f), eps)
        assertEquals(1f, BeatFx.waveTaper(BeatFx.WAVE_EDGE), eps)
        assertEquals(.5f, BeatFx.waveTaper(BeatFx.WAVE_EDGE / 2), eps)
        val half = BeatFx.WAVE_RING_HALF
        assertEquals(0, BeatFx.waveRingSample(0, half))
        assertEquals(half, BeatFx.waveRingSample(half, half))
        assertEquals(1, BeatFx.waveRingSample(2 * half - 1, half))
        assertEquals(0, BeatFx.waveRingSample(2 * half, half))
        for (j in 1 until half)
            assertEquals(BeatFx.waveRingSample(j, half), BeatFx.waveRingSample(2 * half - j, half))
    }
}
