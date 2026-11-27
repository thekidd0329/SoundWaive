package com.parker.crownpulse

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.media.MediaScannerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.min

/** One saved Release Pack video, shown in the results list. */
data class PackResult(
    val label: String,
    val fileName: String,
    val uri: Uri,
    val width: Int,
    val height: Int,
    val duration: Float,
    val start: Float,
    val silent: Boolean,
)

/**
 * Turns a [ReleasePlan] into per-clip looks and render options, and saves finished clips to
 * `Movies/SoundWaive/<song>/`. The window choice itself is in [ReleasePlan].
 */
object ReleasePack {
    private const val TAG = "SoundWaive"

    /** Short audio fades so a clip never starts or stops with a click or a hard cut. */
    const val AUDIO_FADE_IN = .08f
    const val AUDIO_FADE_OUT = 1f
    /** Picture fade at the end of each short clip (the start stays on the downbeat hit). */
    const val CLIP_FADE_OUT = .6f
    /** Spotify Canvas: 9:16, 3–8 s, no audio; 30 fps keeps it small and in spec. */
    const val CANVAS_MAX_FPS = 30

    /** The analysis curves the plan reads, made once from the import's analysis. */
    fun song(a: Analysis, s: Settings): ReleasePlan.Song {
        val level = FloatArray(a.frames) { .6f * a.data[it * a.stride + 3] + .4f * a.data[it * a.stride] }
        // A manual tempo makes the renderer use a fixed 4/4 grid from 0 s; cut on that grid.
        val override = s.f("tempoOverride")
        val manual = override >= 30f
        return ReleasePlan.Song(
            duration = a.duration,
            level = level,
            fps = a.fps,
            downbeats = if (manual) FloatArray(0) else a.downbeatTimes,
            bpm = if (manual) override else a.bpm,
            dropTimes = a.dropTimes,
            dropStrengths = a.dropStrengths,
        )
    }

    fun squareSeconds(s: Settings): Float =
        s.s("packSquare").substringBefore(' ').toFloatOrNull()?.coerceIn(5f, 120f) ?: 30f

    fun wants4k(s: Settings): Boolean = s.s("packYouTube") == Settings.PACK_4K

    /** YouTube's recommended SDR upload bitrates: 4K 45 Mbps at 24/30 fps, 68 at 60. */
    fun youTubeBitrate(height: Int, fps: Int, look: Settings): Int {
        val chosen = (look.s("bitrate").substringBefore(' ').toIntOrNull() ?: 12) * 1_000_000
        return if (height >= 2160) (if (fps > 30) 68_000_000 else 45_000_000) else chosen
    }

    fun fps(look: Settings): Int = look.s("fps").toIntOrNull() ?: 30

    /** The look for one clip: its window, frame shape, reframing, fades and variation. */
    fun settingsFor(base: Settings, item: ReleasePlan.Item): Settings {
        val s = base.copy()
        s.set("trimStart", item.window.start.toDouble())
        s.set("trimEnd", item.window.end.toDouble())
        s.set(
            "aspect",
            when {
                item.width == item.height -> "1:1"
                item.width > item.height -> "16:9"
                else -> "9:16"
            },
        )
        // Cover-crop the background for every shape (no letterbox or blurred bars), and keep
        // the symbol inside wide frames.
        s.set("backgroundFit", BackgroundMath.FILL)
        s.set("fitSymbolToFrame", true)
        when (item.kind) {
            ReleasePlan.Kind.YOUTUBE -> {} // the look's own fades over the full song
            ReleasePlan.Kind.CANVAS -> {
                s.set("fadeIn", 0.0)
                s.set("fadeOut", 0.0)
            }
            else -> {
                s.set("fadeIn", 0.0)
                s.set("fadeOut", CLIP_FADE_OUT.toDouble())
            }
        }
        if (item.seedOffset != 0) s.set("seed", (s.i("seed") + item.seedOffset) % 100_000_000)
        return s
    }

    fun optionsFor(base: Settings, item: ReleasePlan.Item): VideoExporter.Options {
        val fps = fps(base)
        return when (item.kind) {
            ReleasePlan.Kind.YOUTUBE ->
                VideoExporter.Options(
                    width = item.width,
                    height = item.height,
                    bitrate = youTubeBitrate(item.height, fps, base),
                    fps = fps,
                )
            ReleasePlan.Kind.CANVAS ->
                VideoExporter.Options(
                    width = item.width,
                    height = item.height,
                    fps = min(fps, CANVAS_MAX_FPS),
                    audio = false,
                    loopCrossfade = ReleasePlan.LOOP_CROSSFADE,
                )
            else ->
                VideoExporter.Options(
                    width = item.width,
                    height = item.height,
                    fps = fps,
                    audioFadeIn = AUDIO_FADE_IN,
                    audioFadeOut = AUDIO_FADE_OUT,
                )
        }
    }

    /** Relative cost of a clip, for one overall progress bar: seconds x pixels (x2 for loops). */
    fun cost(item: ReleasePlan.Item): Double =
        item.window.length.toDouble() * item.width * item.height * (if (item.loop) 2 else 1)

    /** A file-system-safe song name for the folder and file names. */
    fun songName(trackName: String): String {
        val base = trackName.substringBeforeLast('.', trackName)
        val clean =
            base
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimStart('.')
                .take(60)
                .trim()
        return clean.ifEmpty { "Song" }
    }

    fun fileName(song: String, item: ReleasePlan.Item) = "${song}_${item.kind.suffix}.mp4"

    /**
     * Copies [file] to `Movies/SoundWaive/<song>/<name>` through MediaStore (no storage
     * permission needed for the app's own videos). An older copy this app made under the
     * same name is replaced, so re-rendering a pack does not pile up "(1)" files.
     */
    fun save(context: Context, file: File, song: String, name: String): Uri {
        if (Build.VERSION.SDK_INT < 29) return saveLegacy(context, file, song, name)
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folder = "${Environment.DIRECTORY_MOVIES}/SoundWaive/$song/"
        try {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(folder, name),
            )
        } catch (e: Exception) {
            // Not ours any more (for example after a reinstall); MediaStore picks a new name.
            Log.i(TAG, "Kept an existing $name", e)
        }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn’t save $name to Movies.")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {}
            throw e
        }
        return uri
    }
    /** Android 8/9: copy to public Movies, then index it to obtain a shareable content URI. */
    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, song: String, name: String): Uri {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw IOException("Storage permission is required to save a Release Pack to Movies.")
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val folder = File(movies, "SoundWaive/$song")
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Couldn’t create the Release Pack folder.")
        val target = File(folder, name)
        val temp = File.createTempFile(".soundwaive-", ".tmp", folder)
        try {
            file.inputStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
            // Rename only after the copy succeeds; never expose an incomplete MP4.
            if (!temp.renameTo(target)) throw IOException("Couldn’t save $name to Movies.")
        } finally {
            temp.delete()
        }
        val scanned = AtomicReference<Uri?>()
        val ready = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4")) { _, uri ->
            scanned.set(uri)
            ready.countDown()
        }
        if (!ready.await(30, TimeUnit.SECONDS))
            throw IOException("$name was saved, but Android did not index it in time.")
        return scanned.get() ?: throw IOException("$name was saved, but Android could not index it.")
    }

}
