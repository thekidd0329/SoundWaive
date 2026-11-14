package com.parker.crownpulse

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object AudioTimingCheck {
    fun run(ctx: Context) {
        val dir = File(ctx.filesDir, "qa")
        val cache =
            ctx.cacheDir.listFiles()!!.first {
                File(it, "analysis.bin").exists() && File(it, "audio.pcm").length() > 40000000
            }
        val analysis = Analysis.load(File(cache, "analysis.bin"))
        val input = File(ctx.filesDir, "qa-input.flac")
        val track = Track(Uri.fromFile(input), "test", analysis, File(cache, "audio.pcm"), 44100, 2)
        val cancel = AtomicBoolean(false)
        for ((name, start, end) in
            listOf(Triple("portrait", 30f, 32f), Triple("landscape", 90f, 91f))) {
            val audio = File(dir, "fixed-audio.m4a")
            val delay = VideoExporter().encodeAudio(track, start, end, audio, cancel) { _ -> }
            val old = File(dir, "independent-$name.mp4")
            val next = File(dir, "corrected-$name.mp4")
            val ve = MediaExtractor()
            val ae = MediaExtractor()
            ve.setDataSource(old.absolutePath)
            ae.setDataSource(audio.absolutePath)
            val vi =
                (0 until ve.trackCount).first {
                    ve.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
                }
            ve.selectTrack(vi)
            ae.selectTrack(0)
            val mux = MediaMuxer(next.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (name == "portrait") mux.setOrientationHint(90)
            val outVideo = mux.addTrack(ve.getTrackFormat(vi))
            val outAudio = mux.addTrack(ae.getTrackFormat(0))
            mux.start()
            val b = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            fun copy(e: MediaExtractor, index: Int) {
                while (true) {
                    b.clear()
                    val n = e.readSampleData(b, 0)
                    if (n < 0) break
                    info.set(0, n, e.sampleTime, e.sampleFlags)
                    mux.writeSampleData(index, b, info)
                    e.advance()
                }
            }
            copy(ve, outVideo)
            copy(ae, outAudio)
            mux.stop()
            mux.release()
            ve.release()
            ae.release()
            audio.delete()
            Mp4AudioTiming.apply(next, delay, ((end - start) * track.rate).toLong(), track.rate)
        }
        val mono = track.copy(pcm = File(ctx.filesDir, "qa48.pcm"), rate = 48000, channels = 1)
        val m4a = File(dir, "48k-mono.m4a")
        val delay = VideoExporter().encodeAudio(mono, 0f, 1f, m4a, cancel) { _ -> }
        Mp4AudioTiming.apply(m4a, delay, 48000, 48000)
        val roundtrip =
            AudioEngine(ctx).decode(
                Uri.fromFile(File(dir, "corrected-portrait.mp4")),
                "roundtrip",
                cancel,
            ) { _, _ ->
            }
        roundtrip.pcm!!.copyTo(File(dir, "native-reimport.pcm"), true)
        File(dir, "native-reimport.txt")
            .writeText(
                "${roundtrip.rate} Hz, ${roundtrip.channels} channels, ${roundtrip.pcm.length()/roundtrip.channels/2} frames"
            )
        File(dir, "audio-timing-result.txt")
            .writeText("PASS: corrected portrait and landscape MP4s; 48 kHz mono AAC fixture")
    }
}
