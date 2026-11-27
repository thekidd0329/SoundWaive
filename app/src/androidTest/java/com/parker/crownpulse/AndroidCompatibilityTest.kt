package com.parker.crownpulse

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class AndroidCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun bundledModelSharpensAnOpaquePhoto() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff3366aa.toInt())
            setHasAlpha(false)
        }
        if (!AiUpscaler.supportsModel()) {
            assertNull(AiUpscaler.get(context))
            return
        }
        val ai = checkNotNull(AiUpscaler.get(context))
        if (!Process.is64Bit() && Build.SUPPORTED_32_BIT_ABIS.firstOrNull() == "x86") {
            assertEquals("CPU", ai.chip)
        }
        val result = checkNotNull(ai.enhance(source, 16))
        assertEquals(16, result.width)
        assertEquals(16, result.height)
        assertTrue(android.graphics.Color.blue(result.getPixel(8, 8)) > 80)
    }

    @Test fun measuredAacPrimingMatchesThePlatformEncoder() {
        val track = AudioEngine(context).demo()
        val cancel = AtomicBoolean(false)
        var encoderDelay = -1
        val measured = AacTimingProbe.measure(context, track, context.cacheDir, cancel) { probe, duration, file ->
            encoderDelay = VideoExporter(context).encodeAudio(probe, 0f, duration, file, cancel) {}
        }
        assertTrue("Expected $encoderDelay samples, measured $measured", kotlin.math.abs(measured - encoderDelay) <= 2)
    }

    @Test fun importedAudioExportsAfterLeavingTheActivity() {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val input = File(context.cacheDir, "compat-input.m4a")
        val output = File(context.cacheDir, "compat-output.mp4")
        val terminal = CountDownLatch(1)
        var final: ExportStatus? = null
        val listener: (ExportStatus) -> Unit = { status ->
            if (status is ExportStatus.Finished || status is ExportStatus.Failed || status is ExportStatus.Canceled) {
                final = status
                terminal.countDown()
            }
        }
        var track: Track? = null
        try {
            context.assets.open("generated-demo.m4a").use { stream ->
                input.outputStream().use { stream.copyTo(it) }
            }
            val imported = AudioEngine(context).decode(Uri.fromFile(input), "Demo", AtomicBoolean(false)) { _, _ -> }
            track = imported
            assertTrue(imported.analysis.beats.isNotEmpty())
            val settings = Settings().apply {
                set("resolution", "720p")
                set("fps", "24")
                set("trimEnd", .5f)
                set("renderQuality", BackgroundMath.FAST)
            }
            ExportSession.consume()
            assertTrue(ExportSession.begin(ExportJob(Uri.fromFile(output), imported, settings, null, null, null, null, null)))
            ExportSession.listen(listener)
            instrumentation.runOnMainSync {
                context.startForegroundService(Intent(context, ExportService::class.java).setAction(ExportService.ACTION_START))
                activity.moveTaskToBack(true)
            }
            assertTrue("Timed out waiting for export", terminal.await(180, TimeUnit.SECONDS))
            assertTrue("Export ended with $final", final is ExportStatus.Finished)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                assertEquals(setOf("video/avc", "audio/mp4a-latm"), formats.map { it.getString(MediaFormat.KEY_MIME) }.toSet())
                val audio = formats.single { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
                assertEquals(imported.rate, audio.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(imported.channels, audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally {
                extractor.release()
            }
        } finally {
            ExportSession.stopListening(listener)
            if (ExportSession.isRunning()) ExportSession.cancel.set(true)
            ExportSession.consume()
            instrumentation.runOnMainSync { activity.finish() }
            input.delete()
            output.delete()
            track?.pcm?.parentFile?.deleteRecursively()
        }
    }
}
