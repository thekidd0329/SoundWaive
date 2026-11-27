package com.parker.crownpulse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import kotlin.math.min

/**
 * Runs a video export as a mediaProcessing foreground service so Android does not freeze
 * the process when SoundWaive is no longer on screen.
 */
class ExportService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ExportSession.cancel.set(true)
            return START_NOT_STICKY
        }
        ensureChannel()
        val notification = progressNotification(ExportSession.status)
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(PROGRESS_ID, notification)
        }
        if (wakeLock?.isHeld != true) {
            wakeLock =
                (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundWaive:export")
                    .apply {
                        setReferenceCounted(false)
                        acquire(WAKE_LOCK_MS)
                    }
        }
        if (worker?.isAlive != true) {
            worker = Thread({ runExport() }, "soundwaive-export").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "mediaProcessing foreground service timed out")
        ExportSession.timedOut = true
        ExportSession.cancel.set(true)
        stopAndRelease()
    }

    override fun onDestroy() {
        ExportSession.cancel.set(true)
        stopAndRelease()
        super.onDestroy()
    }

    private fun runExport() {
        val job = ExportSession.job
        if (job == null) {
            ExportSession.fail("The export could not start.")
            stopAndRelease()
            return
        }
        val onStatus: (ExportStatus) -> Unit = { state ->
            if (state is ExportStatus.Running) {
                notifications().notify(PROGRESS_ID, progressNotification(state))
            }
        }
        ExportSession.listen(onStatus)
        try {
            if (job.pack) runPack(job) else runSingle(job)
        } finally {
            ExportSession.stopListening(onStatus)
            stopAndRelease()
        }
    }

    /** One video to the file the user picked. */
    private fun runSingle(job: ExportJob) {
        val output = job.output
        if (output == null) {
            ExportSession.fail("The export could not start.")
            return
        }
        try {
            val ready = decoded(job, .08f)
            val rendered =
                render(job, ready, job.settings, VideoExporter.Options()) { label, value ->
                    ExportSession.progress(label, value)
                }
            val result = rendered.result
            if (ExportSession.cancel.get()) throw InterruptedException("Canceled")
            contentResolver.openOutputStream(output, "w")!!.use { out ->
                result.file.inputStream().use { it.copyTo(out) }
            }
            result.file.delete()
            ExportSession.finish(
                output,
                result.width,
                result.height,
                result.duration,
                result.notice,
                rendered.aiLine,
            )
            notifyDone("Your video is ready", "Tap to open SoundWaive")
        } catch (e: InterruptedException) {
            try {
                android.provider.DocumentsContract.deleteDocument(contentResolver, output)
            } catch (_: Exception) {}
            ExportSession.canceled()
            if (ExportSession.status is ExportStatus.Failed) {
                notifyDone("Export stopped", ExportSession.TIMEOUT_MESSAGE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            try {
                android.provider.DocumentsContract.deleteDocument(contentResolver, output)
            } catch (_: Exception) {}
            val message = e.message ?: "Please try again."
            ExportSession.fail(message)
            notifyDone("Couldn’t finish the export", message)
        }
    }

    /**
     * Release Pack: one analysis, seven renders in a row (see [ReleasePlan] for the windows),
     * each saved to Movies/SoundWaive/<song>/ as soon as it is done. A clip that fails is
     * reported and the rest still render. Cancel keeps the clips already saved.
     */
    private fun runPack(job: ExportJob) {
        val folder = ReleasePack.songName(job.track.name)
        val results = ArrayList<PackResult>()
        val problems = ArrayList<String>()
        val notes = LinkedHashSet<String>()
        try {
            val ready = decoded(job, .03f)
            val look = job.settings
            val fps = ReleasePack.fps(look)
            var youTube = 1920 to 1080
            if (ReleasePack.wants4k(look)) {
                val bitrate = ReleasePack.youTubeBitrate(2160, fps, look)
                if (VideoExporter(this).canEncode(3840, 2160, fps, bitrate)) youTube = 3840 to 2160
                else
                    notes +=
                        "This phone’s video encoder can’t make 4K at $fps fps, so the YouTube video is 1080p."
            }
            val plan =
                ReleasePlan.plan(
                    ReleasePack.song(ready.analysis, look),
                    youTube,
                    ReleasePack.squareSeconds(look),
                )
            for (item in plan)
                Log.i(
                    TAG,
                    "Release Pack ${item.kind}: ${item.window.start}–${item.window.end} s, " +
                        "${item.width}x${item.height} (${item.why})",
                )
            val costs = plan.map { ReleasePack.cost(it) }
            val total = costs.sum().coerceAtLeast(1.0)
            var done = 0.0
            for ((i, item) in plan.withIndex()) {
                if (ExportSession.cancel.get()) throw InterruptedException("Canceled")
                val base = .03f + .97f * (done / total).toFloat()
                val share = .97f * (costs[i] / total).toFloat()
                val prefix = "${i + 1} of ${plan.size} · ${item.kind.label}"
                ExportSession.progress(prefix, base)
                try {
                    val rendered =
                        render(
                            job,
                            ready,
                            ReleasePack.settingsFor(look, item),
                            ReleasePack.optionsFor(look, item),
                        ) { label, value ->
                            ExportSession.progress("$prefix · $label", base + share * value)
                        }
                    val file = rendered.result.file
                    try {
                        if (ExportSession.cancel.get()) throw InterruptedException("Canceled")
                        val name = ReleasePack.fileName(folder, item)
                        val uri = ReleasePack.save(this, file, folder, name)
                        results +=
                            PackResult(
                                label = item.kind.label,
                                fileName = name,
                                uri = uri,
                                width = rendered.result.width,
                                height = rendered.result.height,
                                duration = rendered.result.duration,
                                start = item.window.start,
                                silent = !item.audio,
                            )
                    } finally {
                        file.delete()
                    }
                    rendered.result.notice?.let { notes += it }
                    rendered.aiLine?.let { notes += it }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Release Pack ${item.kind} failed", e)
                    problems += "${item.kind.label}: ${e.message ?: "couldn’t render"}"
                }
                done += costs[i]
            }
            ExportSession.finishPack(folder, results, problems, notes.toList(), false)
            notifyDone(
                if (problems.isEmpty()) "Your Release Pack is ready"
                else "Release Pack finished with problems",
                "${results.size} videos in Movies/SoundWaive/$folder",
            )
        } catch (e: InterruptedException) {
            if (ExportSession.timedOut) problems += ExportSession.TIMEOUT_MESSAGE
            ExportSession.finishPack(folder, results, problems, notes.toList(), true)
            notifyDone(
                "Release Pack stopped",
                "${results.size} finished videos kept in Movies/SoundWaive/$folder",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Release Pack failed", e)
            if (results.isEmpty()) {
                val message = e.message ?: "Please try again."
                ExportSession.fail(message)
                notifyDone("Couldn’t make the Release Pack", message)
            } else {
                problems += e.message ?: "The Release Pack stopped early."
                ExportSession.finishPack(folder, results, problems, notes.toList(), true)
                notifyDone("Release Pack stopped", "${results.size} finished videos kept")
            }
        }
    }

    /** The song decoded to PCM once per job; [share] of the progress bar while it runs. */
    private fun decoded(job: ExportJob, share: Float): Track {
        var ready = job.track
        if (ready.pcm == null) {
            val decoded =
                AudioEngine(this).decode(ready.uri, ready.name, ExportSession.cancel) {
                    label,
                    value ->
                    ExportSession.progress(label, value * share)
                }
            ready = decoded.copy(analysis = ready.analysis)
            job.track = ready
        }
        return ready
    }

    private class Rendered(val result: VideoExporter.Result, val aiLine: String?)

    /**
     * Renders [snapshot] to a temporary MP4: opens the background at this output's size, adds
     * AI sharpening to a video background when it helps, and runs [VideoExporter].
     */
    private fun render(
        job: ExportJob,
        ready: Track,
        snapshot: Settings,
        options: VideoExporter.Options,
        progress: (String, Float) -> Unit,
    ): Rendered {
        val engine = VisualRenderer(this)
        engine.artwork = job.artwork
        engine.backdrop = job.backdrop
        val (outW, outH) = VideoExporter(this).dimensions(snapshot, options)
        val photo = job.photo
        var aiChip: String? =
            if (
                snapshot.s("backgroundType") == BackgroundMath.PHOTO &&
                    photo != null &&
                    photo === job.aiPhoto
            )
                job.aiPhotoChip
            else null
        var exportBackground: BackgroundSource? = null
        try {
            exportBackground =
                try {
                    BackgroundMedia.open(
                        this,
                        snapshot,
                        photo,
                        BackgroundMedia.PREVIEW_SHORT_SIDE,
                        BackgroundMath.Size(outW, outH),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "The background can't be opened; exporting with the color.", e)
                    null
                }
            var aiVideo: AiVideoBackground? = null
            val opened = exportBackground
            if (opened is VideoBackground && snapshot.b("aiEnhance")) {
                val target =
                    min(
                        min(outW, outH),
                        BackgroundMath.qualityShortSide(snapshot.s("renderQuality"))
                            ?: Int.MAX_VALUE,
                    )
                if (min(opened.size.width, opened.size.height) < target) {
                    progress("Getting the AI sharpening ready", .09f)
                    val ai =
                        try {
                            AiUpscaler.get(this)
                        } catch (e: Throwable) {
                            Log.w(TAG, "AI sharpening skipped", e)
                            null
                        }
                    if (ai != null) {
                        val wrapped =
                            AiVideoBackground(
                                opened,
                                ai,
                                target,
                                AiVideoBackground.cacheFor(
                                    cacheDir,
                                    snapshot.s("backgroundUri"),
                                    target,
                                    opened.size,
                                ),
                            )
                        aiVideo = wrapped
                        aiChip = ai.chip
                        exportBackground = wrapped
                    }
                }
            }
            engine.background = exportBackground
            val result =
                VideoExporter(this)
                    .export(
                        ready,
                        snapshot,
                        engine,
                        File(cacheDir, "exports"),
                        ExportSession.cancel,
                        options,
                        progress,
                    )
            val usedAi = aiChip != null && (aiVideo == null || aiVideo.improved > 0)
            val aiLine = if (usedAi) "Background sharpened by AI on the $aiChip." else null
            return Rendered(result, aiLine)
        } finally {
            try {
                exportBackground?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Export background release failed", e)
            }
        }
    }

    private fun stopAndRelease() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = notifications()
        if (manager.getNotificationChannel(CHANNEL_PROGRESS) == null)
            manager.createNotificationChannel(
                NotificationChannel(
                        CHANNEL_PROGRESS,
                        "Exports",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply { description = "Shows export progress and lets you cancel." }
            )
        if (manager.getNotificationChannel(CHANNEL_DONE) == null)
            manager.createNotificationChannel(
                NotificationChannel(
                        CHANNEL_DONE,
                        "Export finished",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply { description = "Lets you know when an export finishes or stops." }
            )
    }

    private fun progressNotification(state: ExportStatus): Notification {
        val running = state as? ExportStatus.Running
        val percent = ((running?.progress ?: 0f) * 100).toInt().coerceIn(0, 100)
        val pack = ExportSession.job?.pack == true
        val text =
            if (running == null || percent <= 0) "Preparing export"
            else if (pack) "$percent% · ${running.label.substringBeforeLast(" · Rendering")}"
            else "$percent%"
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle(if (pack) "Rendering Release Pack" else "Exporting video")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, running == null)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun notifyDone(title: String, text: String) {
        notifications()
            .notify(
                DONE_ID,
                Notification.Builder(this, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_stat_export)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build(),
            )
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notifications() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val ACTION_START = "com.parker.crownpulse.EXPORT_START"
        const val ACTION_CANCEL = "com.parker.crownpulse.EXPORT_CANCEL"
        private const val TAG = "SoundWaive"
        private const val CHANNEL_PROGRESS = "export"
        private const val CHANNEL_DONE = "export-done"
        private const val PROGRESS_ID = 14
        private const val DONE_ID = 15
        /** Matches the mediaProcessing foreground-service limit. */
        private const val WAKE_LOCK_MS = 6 * 60 * 60 * 1000L
    }
}
