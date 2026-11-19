package com.parker.crownpulse

/**
 * Converts decoded PCM frames to the 11025 Hz mono stream the Rust engine analyzes.
 *
 * Each output sample is the average of every input frame since the previous output (a box
 * low-pass). Picking single samples instead folds cymbal and hat energy above 5.5 kHz back
 * into the snare/body band, which made hats trigger snare reactions.
 */
class MonoDownsampler(inputRate: Int, private val outputRate: Int = 11025) {
    private val step = outputRate.toDouble() / inputRate
    private var accumulator = 0.0
    private var sum = 0.0
    private var count = 0

    init {
        require(inputRate >= outputRate) { "Input rate $inputRate is below $outputRate Hz" }
    }

    /** Adds one frame (already channel-averaged); returns the next output sample or null. */
    fun push(mono: Double): Int? {
        sum += mono
        count++
        accumulator += step
        if (accumulator < 1) return null
        accumulator -= 1.0
        val v = (sum / count).roundToShort()
        sum = 0.0
        count = 0
        return v
    }

    private fun Double.roundToShort(): Int =
        Math.round(this).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
}
