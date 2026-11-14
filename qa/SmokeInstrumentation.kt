package com.parker.crownpulse

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class SmokeInstrumentation : Instrumentation() {
    private var stillOnly = false
    private var audioOnly = false
    private var artOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        stillOnly = arguments?.getString("mode") == "stills"
        audioOnly = arguments?.getString("mode") == "audio"
        artOnly = arguments?.getString("mode") == "art"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val ctx = targetContext
            val out = File(ctx.filesDir, "qa")
            out.mkdirs()
            val cancel = AtomicBoolean(false)
            val renderer = VisualRenderer(ctx)
            if (artOnly) {
                val file = File(ctx.filesDir, "qa-art.png")
                ctx.assets.open("crown.png").use { a -> file.outputStream().use { a.copyTo(it) } }
                val monitor = addMonitor(MainActivity::class.java.name, null, false)
                ctx.startActivity(
                    android.content
                        .Intent(ctx, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                val activity =
                    monitor.waitForActivityWithTimeout(45000) ?: error("Activity did not launch")
                runOnMainSync {
                    val method =
                        MainActivity::class
                            .java
                            .getDeclaredMethod(
                                "readImage",
                                Uri::class.java,
                                Boolean::class.javaPrimitiveType,
                            )
                    method.isAccessible = true
                    method.invoke(activity, Uri.fromFile(file), false)
                }
                check(File(ctx.filesDir, "artwork.png").length() > 100000)
                val state =
                    ctx.getSharedPreferences("crownpulse", Context.MODE_PRIVATE)
                        .getString("settings", "")!!
                check(org.json.JSONObject(state).getString("symbol") == "Custom artwork")
                File(out, "art-result.txt")
                    .writeText("PASS: imported PNG, saved artwork and selected custom symbol")
                finish(Activity.RESULT_OK, result)
                return
            }
            if (audioOnly) {
                AudioTimingCheck.run(ctx)
                finish(Activity.RESULT_OK, result)
                return
            }
            fun status(t: String) {
                File(out, "progress.txt").appendText(t + "\n")
                android.util.Log.i("CrownPulseQA", t)
                val b = Bundle()
                b.putString("stream", t + "\n")
                sendStatus(1, b)
            }
            fun still(
                name: String,
                a: Analysis,
                s: Settings,
                t: Float,
                w: Int = 540,
                h: Int = 960,
            ) {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                renderer.draw(Canvas(b), w, h, t, a, s, true)
                File(out, name).outputStream().use {
                    b.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                b.recycle()
            }
            status("Load generated demo")
            val demo = AudioEngine(ctx).demo()
            if (stillOnly) {
                still("original-final.png", demo.analysis, Settings(), 24f)
                val cyan = Settings()
                cyan.set("symbolColor", "#48E5F0")
                still("cyan-final.png", demo.analysis, cyan, 24f)
                File(out, "stills-result.txt").writeText("PASS")
                finish(Activity.RESULT_OK, result)
                return
            }
            check(abs(demo.analysis.duration - 32f) < .1f)
            if (!File(out, "original.png").exists())
                still("original.png", demo.analysis, Settings(), 24f)
            for (preset in
                Settings.PRESETS.filter { !File(out, it.replace(' ', '_') + ".png").exists() }) {
                val s = Settings()
                s.set("lockSymbol", false)
                s.preset(preset)
                still(preset.replace(' ', '_') + ".png", demo.analysis, s, 22f)
            }
            val testFile = File(ctx.filesDir, "qa-input.flac")
            check(testFile.exists())
            status("Decode independent FLAC and run Rust analysis")
            var last = 0f
            val cached =
                ctx.cacheDir.listFiles()?.firstOrNull {
                    File(it, "analysis.bin").exists() && File(it, "audio.pcm").length() > 40000000
                }
            val imported =
                if (cached != null)
                    Track(
                        Uri.fromFile(testFile),
                        "losingmymindvlc.flac",
                        Analysis.load(File(cached, "analysis.bin")),
                        File(cached, "audio.pcm"),
                        44100,
                        2,
                    )
                else
                    AudioEngine(ctx).decode(
                        Uri.fromFile(testFile),
                        "losingmymindvlc.flac",
                        cancel,
                    ) { label, v ->
                        if (v - last > .15f) {
                            status("$label ${(v*100).toInt()}%")
                            last = v
                        }
                    }
            check(abs(imported.analysis.duration - 261.47f) < .2f)
            check(imported.analysis.notes.size > 10)
            check(imported.analysis.onsets.size > 10)
            check(imported.analysis.data.all { it.isFinite() })
            status(
                "Rust: ${imported.analysis.bpm} BPM, ${imported.analysis.notes.size} bass notes, ${imported.analysis.onsets.size} onsets"
            )
            still("independent-track.png", imported.analysis, Settings(), 45f)
            val json =
                Settings().apply {
                    set("symbolColor", "#48E5F0")
                    set("notes", false)
                    set("seed", 117)
                }
            val restored = Settings(org.json.JSONObject(json.json.toString()))
            check(restored.s("symbolColor") == "#48E5F0" && !restored.b("notes"))
            val locked = restored.copy()
            locked.vary()
            check(locked.s("symbolColor") == "#48E5F0" && locked.s("symbol") == "Drippy crown")
            status("Export portrait MP4 with imported audio")
            val s = Settings()
            s.set("trimStart", 30)
            s.set("trimEnd", 32)
            s.set("resolution", "1080p")
            s.set("fps", "30")
            var pr = 0f
            val exp =
                VideoExporter().export(imported, s, renderer, out, cancel) { label, v ->
                    if (v - pr > .08f) {
                        status("$label ${(v*100).toInt()}%")
                        pr = v
                    }
                }
            exp.file.renameTo(File(out, "independent-portrait.mp4"))
            check(exp.frames == 60)
            status("Export landscape variation")
            val variation = Settings()
            variation.set("lockSymbol", false)
            variation.preset("Neon orbit")
            variation.set("aspect", "16:9")
            variation.set("resolution", "720p")
            variation.set("fps", "24")
            variation.set("trimStart", 90)
            variation.set("trimEnd", 91)
            val second =
                VideoExporter().export(imported, variation, renderer, out, cancel) { _, _ -> }
            second.file.renameTo(File(out, "independent-landscape.mp4"))
            check(second.width == 1280 && second.height == 720 && second.frames == 24)
            File(out, "analysis-report.txt")
                .writeText(
                    "Input: losingmymindvlc.flac\nDuration: ${imported.analysis.duration}\nBPM estimate: ${imported.analysis.bpm}\nNotes: ${imported.analysis.notes.size}\nOnsets: ${imported.analysis.onsets.size}\nPCM: ${imported.rate} Hz, ${imported.channels} channels\nAll checks passed\n"
                )
            File(out, "result.txt").writeText("PASS")
            result.putString("stream", "CROWNPULSE_SMOKE_PASS\n")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            File(targetContext.filesDir, "qa/result.txt").writeText(e.stackTraceToString())
            android.util.Log.e("CrownPulseQA", "Failed", e)
            result.putString("stream", "CROWNPULSE_SMOKE_FAIL ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
