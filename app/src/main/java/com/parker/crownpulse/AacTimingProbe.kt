package com.parker.crownpulse

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Measure an unfamiliar AAC encoder's priming without guessing how much music to trim. */
internal object AacTimingProbe {
    private const val START = 1024
    private val pulse = ShortArray(256) { n ->
        val envelope = sin(PI * n / 255)
        (20000 * envelope * envelope * sin(2 * PI * (.02 * n + .00035 * n * n))).toInt().toShort()
    }

    fun measure(
        context: Context, track: Track, directory: File, cancel: AtomicBoolean,
        encode: (Track, Float, File) -> Unit,
    ): Int {
        val temp = Files.createTempDirectory(directory.toPath(), "aac-timing-").toFile()
        var decoded: Track? = null
        try {
            val frames = maxOf(track.rate / 4, 8192)
            val pcm = ByteBuffer.allocate(frames * track.channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.position(START * track.channels * 2)
            for (sample in pulse) repeat(track.channels) { pcm.putShort(sample) }
            val source = File(temp, "probe.pcm").apply { writeBytes(pcm.array()) }
            val encoded = File(temp, "probe.m4a")
            encode(track.copy(pcm = source), frames.toFloat() / track.rate, encoded)
            decoded = AudioEngine(context).decode(Uri.fromFile(encoded), "AAC timing", cancel) { _, _ -> }
            check(decoded.rate == track.rate && decoded.channels == track.channels)
            val samples = ByteBuffer.wrap(checkNotNull(decoded.pcm).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val energy = pulse.sumOf { it.toDouble() * it }
            var best = 0.0
            var delay = -1
            val last = minOf(START + 16384, samples.limit() / track.channels - pulse.size)
            for (offset in START..last) {
                var dot = 0.0
                var power = 0.0
                for (i in pulse.indices) {
                    val sample = samples[(offset + i) * track.channels].toDouble()
                    dot += pulse[i] * sample
                    power += sample * sample
                }
                if (power == 0.0) continue
                val score = dot / sqrt(energy * power)
                if (score > best) {
                    best = score
                    delay = offset - START
                }
            }
            check(best >= .7) { "Could not measure this AAC encoder's timing." }
            return delay
        } finally {
            decoded?.pcm?.parentFile?.deleteRecursively()
            temp.deleteRecursively()
        }
    }
}
