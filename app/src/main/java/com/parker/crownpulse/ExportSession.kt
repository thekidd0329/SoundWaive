package com.parker.crownpulse

import android.graphics.Bitmap
import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the export service needs so it can keep going if the screen is left. A single
 * export writes to [output]; a Release Pack ([pack]) has no output and saves to Movies.
 */
class ExportJob(
    val output: Uri?,
    var track: Track,
    val settings: Settings,
    val artwork: Bitmap?,
    val backdrop: Bitmap?,
    val photo: Bitmap?,
    val aiPhoto: Bitmap?,
    val aiPhotoChip: String?,
    val pack: Boolean = false,
    /** Word-timed lyrics for this song, drawn when the look's "lyrics" switch is on. */
    val lyrics: LyricTimings? = null,
)

sealed class ExportStatus {
    data object Idle : ExportStatus()

    data class Running(val label: String, val progress: Float) : ExportStatus()

    data class Finished(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val duration: Float,
        val notice: String?,
        val aiLine: String?,
    ) : ExportStatus()

    data class Failed(val message: String) : ExportStatus()

    /** A Release Pack ended: what was saved, what failed, and whether it was stopped early. */
    data class PackFinished(
        val folder: String,
        val results: List<PackResult>,
        val problems: List<String>,
        val notes: List<String>,
        val canceled: Boolean,
    ) : ExportStatus()

    data object Canceled : ExportStatus()
}

/**
 * Process-wide export state. The activity attaches to this when it is visible; the
 * foreground service keeps updating it while the export runs in the background.
 */
object ExportSession {
    val cancel = AtomicBoolean(false)

    @Volatile
    var job: ExportJob? = null
        private set

    @Volatile
    var status: ExportStatus = ExportStatus.Idle
        private set

    /** Set when Android ends the mediaProcessing service at the 6-hour limit. */
    @Volatile var timedOut = false

    private val listeners = CopyOnWriteArrayList<(ExportStatus) -> Unit>()

    fun isRunning(): Boolean = status is ExportStatus.Running

    fun listen(listener: (ExportStatus) -> Unit) {
        listeners += listener
        listener(status)
    }

    fun stopListening(listener: (ExportStatus) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun begin(job: ExportJob): Boolean {
        if (status is ExportStatus.Running) return false
        this.job = job
        cancel.set(false)
        timedOut = false
        publish(ExportStatus.Running("Preparing export", 0f))
        return true
    }

    fun progress(label: String, value: Float) {
        publish(ExportStatus.Running(label, value.coerceIn(0f, 1f)))
    }

    fun finish(
        uri: Uri,
        width: Int,
        height: Int,
        duration: Float,
        notice: String?,
        aiLine: String?,
    ) {
        publish(ExportStatus.Finished(uri, width, height, duration, notice, aiLine))
    }

    fun finishPack(
        folder: String,
        results: List<PackResult>,
        problems: List<String>,
        notes: List<String>,
        canceled: Boolean,
    ) {
        publish(ExportStatus.PackFinished(folder, results, problems, notes, canceled))
    }

    fun fail(message: String) {
        publish(ExportStatus.Failed(message))
    }

    fun canceled() {
        publish(if (timedOut) ExportStatus.Failed(TIMEOUT_MESSAGE) else ExportStatus.Canceled)
    }

    /**
     * Lets the UI take a finished, failed or canceled result once. Returns null if there
     * is nothing to show, or if another caller already consumed it.
     */
    @Synchronized
    fun consume(): ExportStatus? {
        val current = status
        if (
            current is ExportStatus.Finished ||
                current is ExportStatus.PackFinished ||
                current is ExportStatus.Failed ||
                current is ExportStatus.Canceled
        ) {
            status = ExportStatus.Idle
            job = null
            timedOut = false
            return current
        }
        return null
    }

    private fun publish(next: ExportStatus) {
        status = next
        for (listener in listeners) listener(next)
    }

    const val TIMEOUT_MESSAGE =
        "Export stopped because Android limits background video processing to 6 hours in a 24-hour period. Open SoundWaive and try again."
}
