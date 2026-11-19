package com.parker.crownpulse

import com.parker.crownpulse.BackgroundMath.Size
import org.junit.Assert.*
import org.junit.Test

class BackgroundMathTest {
    private val eps = 1e-9

    @Test
    fun loopWrapsAndHoldFreezes() {
        val m = BackgroundMath
        assertEquals(1.5, m.mediaTime(1.5, 4.0, m.LOOP), eps)
        assertEquals(1.0, m.mediaTime(9.0, 4.0, m.LOOP), eps)
        assertEquals(0.0, m.mediaTime(8.0, 4.0, m.LOOP), eps)
        assertEquals(4.0, m.mediaTime(9.0, 4.0, m.HOLD), eps)
        assertEquals(2.0, m.mediaTime(2.0, 4.0, m.HOLD), eps)
    }

    @Test
    fun invalidTimesShowFirstFrame() {
        val m = BackgroundMath
        assertEquals(0.0, m.mediaTime(-1.0, 4.0, m.LOOP), eps)
        assertEquals(0.0, m.mediaTime(3.0, 0.0, m.LOOP), eps)
        assertEquals(0.0, m.mediaTime(Double.NaN, 4.0, m.HOLD), eps)
    }

    @Test
    fun frameIndexPicksLastStartedFrame() {
        val starts = doubleArrayOf(0.0, .1, .25, .5)
        assertEquals(0, BackgroundMath.frameIndex(starts, 0.0))
        assertEquals(0, BackgroundMath.frameIndex(starts, .099))
        assertEquals(1, BackgroundMath.frameIndex(starts, .1))
        assertEquals(2, BackgroundMath.frameIndex(starts, .4))
        assertEquals(3, BackgroundMath.frameIndex(starts, 9.0))
        assertEquals(0, BackgroundMath.frameIndex(doubleArrayOf(.2, .3), .1))
        assertEquals(-1, BackgroundMath.frameIndex(DoubleArray(0), 1.0))
    }

    @Test
    fun renderSizesFollowQuality() {
        val m = BackgroundMath
        assertEquals(Size(360, 640), m.renderSize(1080, 1920, m.FAST))
        assertEquals(Size(720, 1280), m.renderSize(1080, 1920, m.BALANCED))
        assertEquals(Size(1080, 1920), m.renderSize(1080, 1920, m.FULL))
        // Never upscale: a 720p export at Balanced renders at full size.
        assertEquals(Size(720, 1280), m.renderSize(720, 1280, m.BALANCED))
        assertEquals(Size(640, 360), m.renderSize(1920, 1080, m.FAST))
        assertEquals(Size(360, 450), m.renderSize(1080, 1350, m.FAST))
        assertEquals(Size(360, 360), m.renderSize(1080, 1080, m.FAST))
    }

    @Test
    fun backgroundSizesCapToQualityAndOutput() {
        val m = BackgroundMath
        assertEquals(Size(640, 360), m.backgroundSize(3840, 2160, 1080, 1920, m.FAST))
        assertEquals(Size(1280, 720), m.backgroundSize(3840, 2160, 1080, 1920, m.BALANCED))
        // Full: short side capped at the export's long side.
        assertEquals(Size(3414, 1920), m.backgroundSize(3840, 2160, 1080, 1920, m.FULL))
        // Small sources are never enlarged.
        assertEquals(Size(320, 240), m.backgroundSize(320, 240, 1080, 1920, m.BALANCED))
    }

    @Test
    fun sizesAreEvenAndPositive() {
        val s = BackgroundMath.capShortSide(1001, 3, 1)
        assertTrue(s.width % 2 == 0 && s.height >= 2)
    }

    @Test
    fun fillCoversAndFitContains() {
        val m = BackgroundMath
        // 16:9 video into a 9:16 frame of 1080 x 1920.
        val fill = m.place(1920, 1080, 1080f, 1920f, m.FILL)
        assertEquals(1920f, fill[3] - fill[1], 1e-3f)
        assertTrue(fill[0] < 0 && fill[2] > 1080)
        assertEquals(540f, (fill[0] + fill[2]) / 2, 1e-3f)
        val fit = m.place(1920, 1080, 1080f, 1920f, m.FIT)
        assertEquals(0f, fit[0], 1e-3f)
        assertEquals(1080f, fit[2], 1e-3f)
        assertEquals(607.5f, fit[3] - fit[1], 1e-3f)
        val zoomed = m.place(1080, 1920, 1080f, 1920f, m.FILL, 1.04f)
        assertEquals(1123.2f, zoomed[2] - zoomed[0], 1e-2f)
    }

    @Test
    fun reactivityStaysSubtle() {
        val m = BackgroundMath
        assertEquals(1f, m.pulseZoom(0f), 0f)
        assertEquals(1.04f, m.pulseZoom(5f), 1e-6f)
        assertEquals(.35f, m.visibleAlpha(.65f, 0f, true), 1e-6f)
        assertEquals(.35f + .65f * .35f, m.visibleAlpha(.65f, 1f, true), 1e-6f)
        assertEquals(.35f, m.visibleAlpha(.65f, 1f, false), 1e-6f)
        assertEquals(1f, m.visibleAlpha(0f, 1f, true), 1e-6f)
    }
}
