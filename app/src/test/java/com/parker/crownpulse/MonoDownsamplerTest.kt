package com.parker.crownpulse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class MonoDownsamplerTest {
    private fun run(rate: Int, seconds: Double, signal: (Double) -> Double): List<Int> {
        val d = MonoDownsampler(rate)
        return (0 until (rate * seconds).toInt()).mapNotNull { d.push(signal(it.toDouble() / rate)) }
    }

    @Test
    fun outputRateMatchesForCommonInputs() {
        for (rate in listOf(11025, 22050, 44100, 48000, 96000)) {
            val n = run(rate, 2.0) { 0.0 }.size
            assertTrue("$rate -> $n", abs(n - 22050) <= 1)
        }
    }

    @Test
    fun lowFrequenciesPassUnchanged() {
        val out = run(44100, 1.0) { 20000 * sin(2 * PI * 60 * it) }
        assertTrue(out.maxOrNull()!! in 19900..20000)
    }

    @Test
    fun nyquistEnergyIsRemovedInsteadOfAliased() {
        // A 11025 Hz tone sampled at 44.1 kHz is +A, 0, -A, 0 ... Picking every fourth
        // sample turns it into a constant; averaging removes it.
        val out = run(44100, .5) { 20000 * sin(2 * PI * 11025 * it + .3) }
        assertTrue(out.all { abs(it) <= 1 })
    }

    @Test
    fun outputIsClampedTo16Bits() {
        assertTrue(run(44100, .1) { 1e6 }.all { it == Short.MAX_VALUE.toInt() })
    }
}
