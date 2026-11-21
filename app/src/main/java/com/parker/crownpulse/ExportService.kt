package com.parker.crownpulse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
        startForeground(
            PROGRESS_ID,
            progressNotification(ExportSession.status),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        )
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
        var exportBackground: BackgroundSource? = null
        try {
            var ready = job.track
            if (ready.pcm == null) {
                val decoded =
                    AudioEngine(this).decode(ready.uri, ready.name, ExportSession.cancel) {
                        label,
                        value ->
                        ExportSession.progress(label, value * .08f)
                    }
                ready = decoded.copy(analysis = ready.analysis)
                job.track = ready
            }
            val engine = VisualRenderer(this)
            engine.artwork = job.artwork
            engine.backdrop = job.backdrop
            val snapshot = job.settings
            val (outW, outH) = VideoExporter().dimensions(snapshot)
            val photo = job.photo
            var aiChip: String? =
                if (
                    snapshot.s("backgroundType") == BackgroundMath.PHOTO &&
                        photo != null &&
                        photo === job.aiPhoto
                )
                    job.aiPhotoChip
                else null
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
                    ExportSession.progress("Getting the AI sharpening ready", .09f)
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
                VideoExporter().export(
                    ready,
                    snapshot,
                    engine,
                    File(cacheDir, "exports"),
                    ExportSession.cancel,
                ) { label, value ->
                    ExportSession.progress(label, value)
                }
            if (ExportSession.cancel.get()) throw InterruptedException("Canceled")
            contentResolver.openOutputStream(job.output, "w")!!.use { out ->
                result.file.inputStream().use { it.copyTo(out) }
            }
            result.file.delete()
            val usedAi = aiChip != null && (aiVideo == null || aiVideo.improved > 0)
            val aiLine = if (usedAi) "Background sharpened by AI on the $aiChip." else null
            ExportSession.finish(
                job.output,
                result.width,
                result.height,
                result.duration,
                result.notice,
                aiLine,
            )
            notifyDone("Your video is ready", "Tap to open SoundWaive")
        } catch (e: InterruptedException) {
            try {
                android.provider.DocumentsContract.deleteDocument(contentResolver, job.output)
            } catch (_: Exception) {}
            ExportSession.canceled()
            if (ExportSession.status is ExportStatus.Failed) {
                notifyDone("Export stopped", ExportSession.TIMEOUT_MESSAGE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            try {
                android.provider.DocumentsContract.deleteDocument(contentResolver, job.output)
            } catch (_: Exception) {}
            val message = e.message ?: "Please try again."
            ExportSession.fail(message)
            notifyDone("Couldn’t finish the export", message)
        } finally {
            try {
                exportBackground?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Export background release failed", e)
            }
            ExportSession.stopListening(onStatus)
            stopAndRelease()
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
        val text = if (running == null || percent <= 0) "Preparing export" else "$percent%"
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_export)
            .setContentTitle("Exporting video")
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
