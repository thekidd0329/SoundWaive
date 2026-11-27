package com.parker.crownpulse

import com.parker.crownpulse.ReleasePlan.Kind
import org.junit.Assert.*
import org.junit.Test

class ReleasePlanTest {
    private val eps = 1e-4f

    /**
     * A 3-minute song at 120 BPM (a bar every 2 s from 0): quiet verse level .2, a hook at
     * .6 from 20 to 35 s, the main chorus at .9 from 60 to 90 s and a second lift at .7 from
     * 130 to 150 s.
     */
    private fun song(duration: Float = 180f): ReleasePlan.Song {
        val fps = 50f
        val frames = (duration * fps).toInt()
        val level =
            FloatArray(frames) {
                val t = it / fps
                when {
                    t >= 60f && t < 90f -> .9f
                    t >= 130f && t < 150f -> .7f
                    t >= 20f && t < 35f -> .6f
                    else -> .2f
                }
            }
        val downbeats = FloatArray((duration / 2f).toInt()) { it * 2f }
        return ReleasePlan.Song(duration, level, fps, downbeats, 120f, FloatArray(0), FloatArray(0))
    }

    private fun plan(s: ReleasePlan.Song = song()) = ReleasePlan.plan(s, 3840 to 2160, 30f)

    private fun List<ReleasePlan.Item>.of(kind: Kind) = first { it.kind == kind }

    @Test
    fun barLengthFromDownbeatsOrTempo() {
        assertEquals(2f, song().bar, eps)
        assertEquals(2f, ReleasePlan.barSeconds(FloatArray(0), 120f), eps)
        assertEquals(2f, ReleasePlan.barSeconds(FloatArray(0), 0f), eps)
        assertEquals(2.5f, ReleasePlan.barSeconds(floatArrayOf(1f, 3.5f, 6f, 8.5f), 0f), eps)
    }

    @Test
    fun canvasIsWholeBarsBetweenThreeAndEightSeconds() {
        assertEquals(8f, ReleasePlan.canvasLength(2f), eps)
        assertEquals(7.5f, ReleasePlan.canvasLength(2.5f), eps)
        assertEquals(6f, ReleasePlan.canvasLength(3f), eps)
        assertEquals(7.5f, ReleasePlan.canvasLength(1.5f), eps)
        assertEquals(5f, ReleasePlan.canvasLength(10f), eps)
        for (bar in listOf(.9f, 1.7f, 2.2f, 3.3f, 4.4f, 6f, 7.9f, 12f)) {
            val len = ReleasePlan.canvasLength(bar)
            assertTrue("$bar -> $len", len >= 3f - eps && len <= 8f + eps)
        }
    }

    @Test
    fun picksDistinctSectionsOnBarLines() {
        val p = plan()
        val a = p.of(Kind.VERTICAL_A).window
        val b = p.of(Kind.VERTICAL_B).window
        val c = p.of(Kind.VERTICAL_C).window
        assertEquals(60f, a.start, eps) // main chorus
        assertEquals(20f, b.start, eps) // hook before it
        assertEquals(130f, c.start, eps) // the other lift, not the chorus's second half
        for (w in listOf(a, b, c)) assertEquals(15f, w.length, eps)
        assertEquals(0f, a.overlap(b), eps)
        assertEquals(0f, a.overlap(c), eps)
        assertEquals(0f, b.overlap(c), eps)
        for (item in p) if (item.kind != Kind.YOUTUBE)
            assertEquals("${item.kind} on a bar", 0f, item.window.start % 2f, eps)
    }

    @Test
    fun reelSquareCanvasAndYouTube() {
        val p = plan()
        val reel = p.of(Kind.REEL).window
        val square = p.of(Kind.SQUARE).window
        val canvas = p.of(Kind.CANVAS)
        val a = p.of(Kind.VERTICAL_A).window
        assertEquals(60f, reel.start, eps)
        assertEquals(30f, reel.length, eps)
        assertEquals(30f, square.length, eps)
        assertEquals(0f, reel.overlap(square), eps)
        assertEquals(8f, canvas.window.length, eps)
        assertTrue(canvas.window.start >= a.start - eps && canvas.window.end <= a.end + eps)
        assertFalse(canvas.audio)
        assertTrue(canvas.loop)
        assertEquals(1080 to 1920, canvas.width to canvas.height)
        assertEquals(1080 to 1080, p.of(Kind.SQUARE).let { it.width to it.height })
        val yt = p.of(Kind.YOUTUBE)
        assertEquals(3840 to 2160, yt.width to yt.height)
        assertEquals(0f, yt.window.start, eps)
        assertEquals(180f, yt.window.end, eps)
        assertEquals(7, p.size)
        assertEquals(7, p.map { it.kind }.toSet().size)
    }

    @Test
    fun deterministic() {
        assertEquals(plan(), plan())
    }

    @Test
    fun shortSongStillGivesEveryClip() {
        val p = plan(song(20f))
        assertEquals(7, p.size)
        for (item in p) {
            assertTrue("${item.kind} start", item.window.start >= -eps)
            assertTrue("${item.kind} end", item.window.end <= 20f + eps)
            assertTrue("${item.kind} length", item.window.length > 0f)
        }
        assertTrue(p.of(Kind.CANVAS).window.length <= 8f + eps)
    }

    @Test
    fun lyricsAreOnForEveryClipButTheCanvas() {
        val items = ReleasePlan.plan(song(), 3840 to 2160, 30f)
        for (item in items) assertEquals(item.kind != Kind.CANVAS, item.lyricsByDefault)
    }
}
