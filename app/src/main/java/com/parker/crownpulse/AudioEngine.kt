package com.parker.crownpulse

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.*
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class Track(
    val uri: Uri,
    val name: String,
    val analysis: Analysis,
    val pcm: File?,
    val rate: Int,
    val channels: Int,
)

class AudioEngine(private val context: Context) {
    fun decode(
        uri: Uri,
        name: String,
        cancel: AtomicBoolean,
        progress: (String, Float) -> Unit,
    ): Track {
        val dir = File(context.cacheDir, "track-${System.nanoTime()}")
        dir.mkdirs()
        val pcm = File(dir, "audio.pcm")
        val mono = File(dir, "mono.pcm")
        val analyzed = File(dir, "analysis.bin")
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var which = -1
            for (i in 0 until extractor.trackCount) {
                if (
                    extractor
                        .getTrackFormat(i)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                ) {
                    which = i
                    break
                }
            }
            require(which >= 0) { "This file has no readable audio track." }
            extractor.selectTrack(which)
            val format = extractor.getTrackFormat(which)
            val duration =
                if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION) / 1e6
                else 0.0
            require(duration <= 1800) { "Please choose a track of 30 minutes or less." }
            require(
                context.cacheDir.usableSpace >
                    max(256L * 1024 * 1024, (duration * 48000 * 4 * 1.2).toLong())
            ) {
                "Free some storage before analyzing this track."
            }
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            require(channels <= 2) { "Please use a mono or stereo mix." }
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var totalFrames = 0L
            var accumulator = 0.0
            var lastProgress = 0L
            BufferedOutputStream(FileOutputStream(pcm), 262144).use { raw ->
                BufferedOutputStream(FileOutputStream(mono), 65536).use { down ->
                    while (!outDone) {
                        if (cancel.get()) throw InterruptedException("Canceled")
                        if (!inDone) {
                            val index = decoder.dequeueInputBuffer(10000)
                            if (index >= 0) {
                                val buffer = decoder.getInputBuffer(index)!!
                                buffer.clear()
                                val n = extractor.readSampleData(buffer, 0)
                                if (n < 0) {
                                    decoder.queueInputBuffer(
                                        index,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inDone = true
                                } else {
                                    decoder.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val index = decoder.dequeueOutputBuffer(info, 10000)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val out = decoder.outputFormat
                                rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                encoding =
                                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                        out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    else AudioFormat.ENCODING_PCM_16BIT
                                require(channels in 1..2) { "Please use a mono or stereo mix." }
                                require(
                                    encoding == AudioFormat.ENCODING_PCM_16BIT ||
                                        encoding == AudioFormat.ENCODING_PCM_FLOAT
                                ) {
                                    "This audio decoder uses an unsupported sample format."
                                }
                            }
                            else ->
                                if (index >= 0) {
                                    val b = decoder.getOutputBuffer(index)!!
                                    b.position(info.offset)
                                    b.limit(info.offset + info.size)
                                    b.order(ByteOrder.LITTLE_ENDIAN)
                                    val frameBytes =
                                        channels *
                                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                    val output = ByteArray(info.size.coerceAtLeast(1))
                                    var at = 0
                                    while (b.remaining() >= frameBytes) {
                                        var sum = 0.0
                                        repeat(channels) {
                                            val v =
                                                if (encoding == AudioFormat.ENCODING_PCM_FLOAT)
                                                    (b.float.coerceIn(-1f, 1f) * 32767).toInt()
                                                else b.short.toInt()
                                            output[at++] = v.toByte()
                                            output[at++] = (v shr 8).toByte()
                                            sum += v
                                        }
                                        accumulator += 11025.0 / rate
                                        if (accumulator >= 1) {
                                            val v = (sum / channels).toInt()
                                            down.write(v and 255)
                                            down.write((v shr 8) and 255)
                                            accumulator -= 1.0
                                        }
                                        totalFrames++
                                    }
                                    raw.write(output, 0, at)
                                    outDone =
                                        info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    decoder.releaseOutputBuffer(index, false)
                                    if (System.currentTimeMillis() - lastProgress > 150) {
                                        progress(
                                            "Decoding music",
                                            if (duration > 0)
                                                (totalFrames.toDouble() / rate / duration * .6)
                                                    .toFloat()
                                                    .coerceIn(0f, .6f)
                                            else .2f,
                                        )
                                        lastProgress = System.currentTimeMillis()
                                    }
                                }
                        }
                        require(totalFrames.toDouble() / rate < 1800.5) {
                            "Please choose a track of 30 minutes or less."
                        }
                    }
                }
            }
            progress("Finding beats, energy and bass notes", .65f)
            if (cancel.get()) throw InterruptedException("Canceled")
            val error = NativeEngine.analyze(mono.absolutePath, analyzed.absolutePath)
            require(error.isEmpty()) { error }
            if (cancel.get()) throw InterruptedException("Canceled")
            val analysis = Analysis.load(analyzed)
            mono.delete()
            progress("Ready · ${analysis.bpm.toInt()} BPM", 1f)
            return Track(uri, name, analysis, pcm, rate, channels)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {}
            decoder?.release()
            extractor.release()
        }
    }

    fun demo(): Track {
        val folder = File(context.filesDir, "demo")
        folder.mkdirs()
        val song = File(folder, "generated-demo-v1.m4a")
        val analysis = File(folder, "generated-demo-v1.analysis")
        if (!song.exists())
            context.assets.open("generated-demo.m4a").use { input ->
                song.outputStream().use { input.copyTo(it) }
            }
        if (!analysis.exists())
            context.assets.open("generated-demo.analysis").use { input ->
                analysis.outputStream().use { input.copyTo(it) }
            }
        return Track(
            Uri.fromFile(song),
            "SoundWaive · demo beat",
            Analysis.load(analysis),
            null,
            44100,
            2,
        )
    }
}
