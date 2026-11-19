package com.parker.crownpulse

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AnalysisFormatTest {
    private fun analysisBytes(beats: List<Triple<Float, Float, Float>>?, extra: ByteArray = ByteArray(0)): ByteArray {
        val frames = 3
        val stride = 8 + 80 + 256
        val out = ByteArrayOutputStream()
        fun floats(vararg v: Float) {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { b.putFloat(it) }
            out.write(b.array())
        }
        fun int(v: Int) =
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        out.write("CPULSE01".toByteArray())
        floats(1f, 43.066f, 120f, frames.toFloat(), 80f, 256f)
        floats(*FloatArray(frames * stride))
        int(1)
        floats(.1f, 40f, .25f, .5f)
        int(1)
        floats(.2f, .9f)
        if (beats != null) {
            out.write("SWBEAT01".toByteArray())
            int(beats.size)
            beats.forEach { floats(it.first, it.second, it.third) }
        }
        out.write(extra)
        return out.toByteArray()
    }

    private fun load(bytes: ByteArray): Analysis {
        val f = File.createTempFile("analysis", ".bin")
        try {
            f.writeBytes(bytes)
            return Analysis.load(f)
        } finally {
            f.delete()
        }
    }

    @Test
    fun legacyFileWithoutBeatGridStillLoads() {
        val a = load(analysisBytes(null))
        assertEquals(1, a.notes.size)
        assertEquals(1, a.onsets.size)
        assertEquals(1, a.noteTimes.size)
        assertEquals(a.notes[0].time, a.noteTimes[0], 1e-6f)
        assertEquals(a.notes[0].midi, a.noteMidi[0])
        assertEquals(a.notes[0].velocity, a.noteVelocity[0], 1e-6f)
        assertEquals(1, a.onsetTimes.size)
        assertEquals(a.onsets[0].time, a.onsetTimes[0], 1e-6f)
        assertEquals(a.onsets[0].strength, a.onsetStrengths[0], 1e-6f)
        assertEquals(a.dropTimes.size, a.dropStrengths.size)
        assertTrue(a.beats.isEmpty())
        assertNull(a.barIndex(5f))
        assertEquals(0, a.beatTimes.size)
        assertEquals(0, a.downbeatTimes.size)
    }

    @Test
    fun beatGridIsParsedAndCountsBars() {
        val grid = (0 until 12).map { Triple(.5f * it, 1f, if (it % 4 == 0) 1f else 0f) }
        val a = load(analysisBytes(grid))
        assertEquals(12, a.beats.size)
        assertTrue(a.beats[4].downbeat)
        assertFalse(a.beats[5].downbeat)
        assertEquals(1, a.barIndex(.1f))
        assertEquals(2, a.barIndex(2.0f))
        assertEquals(3, a.barIndex(5.9f))
        // Primitive copies used by the beat effects.
        assertEquals(12, a.beatTimes.size)
        assertEquals(2.5f, a.beatTimes[5], 1e-6f)
        assertTrue(a.beatIsDownbeat[8])
        assertArrayEquals(floatArrayOf(0f, 2f, 4f), a.downbeatTimes, 1e-6f)
    }

    @Test
    fun truncatedOrForeignTrailerIsIgnored() {
        val truncated = analysisBytes(listOf(Triple(0f, 1f, 1f))).dropLast(4).toByteArray()
        assertTrue(load(truncated).beats.isEmpty())
        val foreign = analysisBytes(null, "NOTBEATS\u0000\u0000\u0000\u0000".toByteArray())
        assertTrue(load(foreign).beats.isEmpty())
    }
}
