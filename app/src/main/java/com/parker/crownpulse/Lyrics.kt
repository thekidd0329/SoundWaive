package com.parker.crownpulse

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** One lyric word with its time in the song (seconds). [text] is shown; [word] is what was aligned. */
class LyricWord(
    val text: String,
    val word: String,
    val line: Int,
    var onset: Float,
    var offset: Float,
    val confidence: Float,
    /** Low confidence, odd length, or a guessed pronunciation: offered in the tap-to-fix pass. */
    var flagged: Boolean,
    /** Set by tap-to-fix, so a fixed word is not offered again. */
    var fixed: Boolean = false,
)

/** Word timings for one song, as stored in app storage (see [LyricsStore]). */
class LyricTimings(val text: String, val words: List<LyricWord>, val vad: Boolean = false) {
    /** First word index of each line, plus words.size at the end. */
    val lineStarts: IntArray = run {
        val starts = ArrayList<Int>()
        var last = -1
        words.forEachIndexed { i, w ->
            if (w.line != last) {
                starts.add(i)
                last = w.line
            }
        }
        starts.add(words.size)
        starts.toIntArray()
    }

    val lineCount: Int
        get() = lineStarts.size - 1

    val flaggedCount: Int
        get() = words.count { it.flagged && !it.fixed }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("format", 1)
            put("text", text)
            put("vad", vad)
            put(
                "words",
                JSONArray().apply {
                    for (w in words)
                        put(
                            JSONObject().apply {
                                put("text", w.text)
                                put("word", w.word)
                                put("line", w.line)
                                put("onset", w.onset.toDouble())
                                put("offset", w.offset.toDouble())
                                put("confidence", w.confidence.toDouble())
                                put("flagged", w.flagged)
                                put("fixed", w.fixed)
                            }
                        )
                },
            )
        }

    companion object {
        /** Reads the aligner's JSON (from Rust) or a stored file. Throws with the aligner's error. */
        fun fromJson(json: JSONObject, text: String = json.optString("text")): LyricTimings {
            if (json.has("error")) throw IllegalStateException(json.getString("error"))
            val list = json.getJSONArray("words")
            val words =
                List(list.length()) { i ->
                    val o = list.getJSONObject(i)
                    LyricWord(
                        text = o.optString("text", o.optString("word")),
                        word = o.optString("word"),
                        line = o.optInt("line"),
                        onset = o.optDouble("onset").toFloat(),
                        offset = o.optDouble("offset").toFloat(),
                        confidence = o.optDouble("confidence", 0.0).toFloat(),
                        flagged = o.optBoolean("flagged"),
                        fixed = o.optBoolean("fixed"),
                    )
                }
            require(words.all { it.onset.isFinite() && it.offset.isFinite() }) { "Bad lyric timings." }
            return LyricTimings(text, words, json.optBoolean("vad"))
        }
    }
}

/**
 * Karaoke timing: which line is on screen and how far into it the singer is. Lines appear
 * [LEAD] seconds before their first word and stay up to [HOLD] seconds after their last one
 * (or until the next line appears). Pure Kotlin so it is unit tested.
 */
class KaraokeClock(private val timings: LyricTimings) {
    /** Line on screen at [t], or -1. */
    fun lineAt(t: Float): Int {
        val starts = timings.lineStarts
        val words = timings.words
        var line = -1
        for (l in 0 until timings.lineCount) {
            if (words[starts[l]].onset - LEAD <= t) line = l else break
        }
        if (line < 0) return -1
        val last = words[starts[line + 1] - 1]
        return if (t <= max(last.offset, last.onset) + HOLD) line else -1
    }

    /** Words of [line] that have started by [t]. */
    fun sung(line: Int, t: Float): Int {
        val from = timings.lineStarts[line]
        val to = timings.lineStarts[line + 1]
        var k = from
        while (k < to && timings.words[k].onset <= t) k++
        return k - from
    }

    /** Whether the last started word of [line] is still being sung at [t] (it is highlighted). */
    fun active(line: Int, t: Float): Boolean {
        val sung = sung(line, t)
        if (sung == 0) return false
        val k = timings.lineStarts[line] + sung
        val w = timings.words[k - 1]
        val end =
            if (k < timings.words.size) min(w.offset + ACTIVE_TAIL, timings.words[k].onset)
            else w.offset + ACTIVE_TAIL
        return t < max(end, w.onset + MIN_ACTIVE)
    }

    /**
     * Changes exactly when the drawn picture changes (line, words sung, active word); -1 when
     * nothing is shown. The GPU pass re-uploads its lyrics texture only when this changes.
     */
    fun key(t: Float): Long {
        val line = lineAt(t)
        if (line < 0) return -1L
        return (line.toLong() shl 32) or (sung(line, t).toLong() shl 1) or (if (active(line, t)) 1L else 0L)
    }

    companion object {
        const val LEAD = .6f
        const val HOLD = 1.8f
        /** A word stays highlighted this long after its end, unless the next one starts. */
        const val ACTIVE_TAIL = .25f
        const val MIN_ACTIVE = .12f
    }
}

/** Per-song lyrics and timings as JSON in app storage (`files/lyrics/<song key>.json`). */
object LyricsStore {
    /** A stable key for a song: its document URI, hashed (no path or name in the file name). */
    fun key(uri: Uri): String =
        MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }

    private fun file(context: Context, uri: Uri) = File(File(context.filesDir, "lyrics"), key(uri) + ".json")

    fun load(context: Context, uri: Uri): LyricTimings? =
        try {
            val f = file(context, uri)
            if (f.exists()) LyricTimings.fromJson(JSONObject(f.readText())) else null
        } catch (e: Exception) {
            android.util.Log.w("SoundWaive", "Stored lyrics could not be read", e)
            null
        }

    /** Lyrics typed for a song that was not aligned yet (kept so nothing typed is lost). */
    fun loadDraft(context: Context, uri: Uri): String? =
        File(File(context.filesDir, "lyrics"), key(uri) + ".txt").takeIf { it.exists() }?.readText()

    fun saveDraft(context: Context, uri: Uri, text: String) {
        val f = File(File(context.filesDir, "lyrics"), key(uri) + ".txt")
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun save(context: Context, uri: Uri, timings: LyricTimings, name: String, duration: Float) {
        val f = file(context, uri)
        f.parentFile?.mkdirs()
        val json = timings.toJson().put("song", name).put("duration", duration.toDouble())
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "Couldn’t save the lyrics." }
        }
    }

    fun delete(context: Context, uri: Uri) {
        file(context, uri).delete()
    }
}

/**
 * Runs the offline aligner in the Rust engine. The two ONNX models and the CMU dictionary are
 * app assets (not compiled into the native library); they are copied once to no-backup app
 * storage because the engine reads them by path.
 */
object LyricsAligner {
    // Asset name in the APK → filename written into the model folder.
    // AAPT/AGP strips a trailing ".gz" from asset names, so the dictionary is
    // stored as cmudict-nostress.dict and copied to cmudict-nostress.txt.gz,
    // which is the name the engine reads.
    private val ASSETS =
        listOf(
            "scores.onnx" to "scores.onnx",
            "voice.onnx" to "voice.onnx",
            "cmudict-nostress.dict" to "cmudict-nostress.txt.gz",
        )
    /** Upstream's VAD threshold used when "Skip instrumental parts" is on. */
    const val VAD_THRESHOLD = 5f

    fun available(): Boolean =
        try {
            NativeEngine.lyricsAvailable()
        } catch (_: Throwable) {
            false
        }

    /** The model folder, copying the assets the first time (and after an app update). */
    @Synchronized
    fun modelDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, "lyrics-models")
        dir.mkdirs()
        val stamp = File(dir, "version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version && ASSETS.all { File(dir, it.second).length() > 0 })
            return dir
        for ((asset, name) in ASSETS) {
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            context.assets.open("lyrics/$asset").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "Couldn’t prepare the lyrics models." }
            }
        }
        stamp.writeText(version)
        return dir
    }

    /** Aligns [text] to the song's decoded PCM. Slow (seconds): call off the main thread. */
    fun align(context: Context, track: Track, text: String, skipInstrumental: Boolean): LyricTimings {
        val pcm = requireNotNull(track.pcm?.takeIf { it.exists() }) { "Import the song again to add lyrics." }
        require(text.any { it.isLetterOrDigit() }) { "Paste or dictate the lyrics first." }
        val dir = modelDir(context)
        val json =
            NativeEngine.alignLyrics(
                pcm.absolutePath,
                track.rate,
                track.channels,
                text,
                dir.absolutePath,
                if (skipInstrumental) VAD_THRESHOLD else 0f,
                true,
            )
        return LyricTimings.fromJson(JSONObject(json), text)
    }
}

/**
 * Tap-to-fix: moves a word to where the user tapped. The tap is corrected by the calibrated
 * tap latency, then snapped to the strongest spectral-flux onset within [WINDOW] seconds,
 * and finally kept between its neighbours so the words stay in order.
 */
object TapFix {
    /** Search half-width around the corrected tap, seconds. */
    const val WINDOW = .12f
    /** Smallest gap kept between neighbouring word onsets, seconds. */
    const val MIN_GAP = .03f
    private const val FFT = 512
    private const val HOP = 128

    /**
     * Onset time near [time] in mono [samples] at [rate] that starts at [startTime]: the
     * spectral-flux peak closest to [time] within [WINDOW], or [time] when there is no clear
     * peak. Pure Kotlin, so it is unit tested on the JVM.
     */
    fun snap(samples: FloatArray, rate: Int, startTime: Float, time: Float): Float {
        val flux = flux(samples)
        if (flux.size < 3) return time
        val frameTime = { i: Int -> startTime + (i * HOP + FFT / 2f) / rate }
        var mean = 0.0
        for (v in flux) mean += v
        mean /= flux.size
        var varSum = 0.0
        for (v in flux) varSum += (v - mean) * (v - mean)
        val threshold = mean + .5 * sqrt(varSum / flux.size)
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in 1 until flux.size - 1) {
            val v = flux[i]
            if (v < threshold || v < flux[i - 1] || v < flux[i + 1]) continue
            val d = abs(frameTime(i) - time)
            if (d <= WINDOW && d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return if (best >= 0) frameTime(best) else time
    }

    /** Half-wave rectified log-magnitude spectral flux, one value per hop. */
    fun flux(x: FloatArray): DoubleArray {
        if (x.size < FFT) return DoubleArray(0)
        val frames = 1 + (x.size - FFT) / HOP
        val window = DoubleArray(FFT) { .5 - .5 * cos(2 * PI * it / FFT) }
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        var previous = DoubleArray(FFT / 2 + 1)
        val out = DoubleArray(frames)
        for (f in 0 until frames) {
            for (i in 0 until FFT) {
                re[i] = x[f * HOP + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            val mag = DoubleArray(FFT / 2 + 1) { ln(1 + 100 * sqrt(re[it] * re[it] + im[it] * im[it])) }
            if (f > 0) {
                var sum = 0.0
                for (k in mag.indices) sum += max(0.0, mag[k] - previous[k])
                out[f] = sum
            }
            previous = mag
        }
        return out
    }

    /** In-place radix-2 FFT (size must be a power of two). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Sets word [index] to [onset], clamped between its neighbours so the order never changes,
     * and keeps the previous word's end and its own end consistent.
     */
    fun apply(words: List<LyricWord>, index: Int, onset: Float) {
        val w = words[index]
        val lo = if (index > 0) words[index - 1].onset + MIN_GAP else 0f
        val hi = if (index + 1 < words.size) words[index + 1].onset - MIN_GAP else Float.MAX_VALUE
        val t = if (lo <= hi) onset.coerceIn(lo, hi) else w.onset
        val length = (w.offset - w.onset).coerceIn(.08f, 1.5f)
        w.onset = t
        w.offset = if (index + 1 < words.size) min(t + length, words[index + 1].onset) else t + length
        if (index > 0) {
            val p = words[index - 1]
            if (p.offset > t) p.offset = t
        }
        w.fixed = true
        w.flagged = false
    }

    /** Mono samples of the decoded song between [from] and [to] seconds (interleaved 16-bit PCM). */
    fun readMono(pcm: File, rate: Int, channels: Int, from: Float, to: Float): FloatArray {
        val frameBytes = 2 * channels
        val first = (max(0f, from) * rate).toLong()
        val count = ((to - max(0f, from)) * rate).toInt().coerceAtLeast(0)
        val bytes = ByteArray(count * frameBytes)
        var read = 0
        RandomAccessFile(pcm, "r").use { f ->
            f.seek(first * frameBytes)
            while (read < bytes.size) {
                val n = f.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
        }
        val frames = read / frameBytes
        return FloatArray(frames) { i ->
            var sum = 0
            for (c in 0 until channels) {
                val at = i * frameBytes + c * 2
                sum += (bytes[at].toInt() and 0xff) or (bytes[at + 1].toInt() shl 8)
            }
            sum / (channels * 32768f)
        }
    }

    /**
     * Tap latency from a calibration run: the median of (tap - nearest beat) over the taps,
     * in seconds. Taps more than 250 ms from any beat are ignored. Null when fewer than four
     * taps land near a beat.
     */
    fun latency(taps: List<Float>, beats: FloatArray): Float? {
        if (beats.isEmpty()) return null
        val diffs =
            taps.mapNotNull { tap ->
                var best = Float.MAX_VALUE
                for (b in beats) if (abs(tap - b) < abs(best)) best = tap - b
                best.takeIf { abs(it) <= .25f }
            }.sorted()
        if (diffs.size < 4) return null
        return diffs[diffs.size / 2]
    }
}
