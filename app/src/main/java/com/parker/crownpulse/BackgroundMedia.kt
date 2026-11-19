package com.parker.crownpulse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A background layer whose picture depends only on music time, so preview and export agree
 * and export is deterministic. Frames are held at a small working size in reused bitmaps;
 * the renderer scales them into the frame.
 */
interface BackgroundSource {
    /** The picture for music time [seconds], or null if none is ready yet. */
    fun frame(seconds: Float): Bitmap?

    fun release() {}
}

/** A still photo, scaled once to the working size. */
class PhotoBackground(source: Bitmap, shortSide: Int) : BackgroundSource {
    private val image: Bitmap =
        BackgroundMath.capShortSide(source.width, source.height, shortSide).let {
            if (it.width == source.width) source
            else Bitmap.createScaledBitmap(source, it.width, it.height, true)
        }

    override fun frame(seconds: Float) = image
}

/**
 * Animated GIF via [Movie], which can render any time position. AnimatedImageDrawable
 * (API 28+) only plays against the wall clock and cannot seek, so it cannot give the same
 * frame for the same music time; animated WebP is therefore shown as a still photo.
 */
@Suppress("DEPRECATION")
class GifBackground(
    private val movie: Movie,
    shortSide: Int,
    private val end: String,
) : BackgroundSource {
    private val size = BackgroundMath.capShortSide(movie.width(), movie.height(), shortSide)
    private val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private var shown = -1

    override fun frame(seconds: Float): Bitmap {
        val duration = movie.duration()
        val ms =
            if (duration <= 0) 0
            else
                (BackgroundMath.mediaTime(seconds.toDouble(), duration / 1000.0, end) * 1000)
                    .toInt()
                    .coerceIn(0, duration - 1)
        if (ms != shown) {
            movie.setTime(ms)
            bitmap.eraseColor(0)
            canvas.save()
            canvas.scale(
                size.width.toFloat() / movie.width(),
                size.height.toFloat() / movie.height(),
            )
            movie.draw(canvas, 0f, 0f)
            canvas.restore()
            shown = ms
        }
        return bitmap
    }

    companion object {
        fun decode(bytes: ByteArray): Movie? =
            Movie.decodeByteArray(bytes, 0, bytes.size)?.takeIf {
                it.width() > 0 && it.height() > 0
            }
    }
}

/**
 * Video frames by music time. MediaExtractor + MediaCodec decode in order into a
 * SurfaceTexture; a GLES pass scales (and rotates, from the track's rotation) each frame into
 * a working-size pbuffer, read back into a reused bitmap. The video's audio track is never
 * selected. All codec and GL work runs on one private thread with its own EGL context, so it
 * cannot disturb the export encoder's context. Later, a GPU export can sample the same
 * external texture directly and skip the read-back.
 *
 * [frame] blocks until the exact frame is ready (export). [latest] returns the newest frame
 * at once and decodes towards the requested time in the background (preview).
 */
class VideoBackground
private constructor(
    private val context: Context,
    private val uri: Uri,
    private val shortSide: Int,
    private val end: String,
) : BackgroundSource {
    private val thread = HandlerThread("soundwaive-bg-video").apply { start() }
    private val handler = Handler(thread.looper)
    private val callbacks = HandlerThread("soundwaive-bg-frames").apply { start() }
    private val frameLock = Object()
    private var frameReady = false
    private val released = AtomicBoolean(false)

    private lateinit var extractor: MediaExtractor
    private lateinit var decoder: MediaCodec
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTexture = 0
    private val matrix = FloatArray(16)
    private lateinit var vertices: FloatBuffer
    private lateinit var pixels: ByteBuffer
    private lateinit var back: Bitmap
    private lateinit var front: Bitmap
    var durationUs = 0L
        private set

    lateinit var size: BackgroundMath.Size
        private set

    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var heldIndex = -1
    private var heldPts = NONE
    private var shownPts = NONE
    private var seeked = false

    // Preview requests: newest wanted time, and whether a decode task is queued.
    @Volatile private var wantedUs = 0L
    private val queued = AtomicBoolean(false)

    private fun <T> onThread(block: () -> T): T {
        val task = FutureTask(block)
        check(handler.post(task)) { "Background video was closed" }
        return try {
            task.get(30, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun setup(reference: BackgroundMath.Size?, quality: String?) {
        extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track =
            (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") ==
                    true
            } ?: throw IOException("This file has no video picture.")
        extractor.selectTrack(track) // Audio tracks stay unselected: the song is the only sound.
        val format = extractor.getTrackFormat(track)
        val rotation =
            if (format.containsKey(MediaFormat.KEY_ROTATION))
                format.getInteger(MediaFormat.KEY_ROTATION)
            else 0
        var w = format.getInteger(MediaFormat.KEY_WIDTH)
        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (rotation % 180 != 0) w = h.also { h = w }
        durationUs =
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
            else metadataDuration()
        require(durationUs > 0) { "This video has no readable length." }
        size =
            if (reference != null && quality != null)
                BackgroundMath.backgroundSize(w, h, reference.width, reference.height, quality)
            else BackgroundMath.capShortSide(w, h, shortSide)
        setupGl()
        texture = SurfaceTexture(oesTexture)
        texture.setOnFrameAvailableListener(
            {
                synchronized(frameLock) {
                    frameReady = true
                    frameLock.notifyAll()
                }
            },
            Handler(callbacks.looper),
        )
        surface = Surface(texture)
        decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        // Decoding to a Surface applies KEY_ROTATION through the texture transform.
        decoder.configure(format, surface, null, 0)
        decoder.start()
        pixels = ByteBuffer.allocateDirect(size.width * size.height * 4).order(ByteOrder.LITTLE_ENDIAN)
        back = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        front = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    }

    private fun metadataDuration(): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L) * 1000
        } finally {
            r.release()
        }
    }

    private fun setupGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL unavailable" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            ) && count[0] > 0
        ) { "No EGL configuration for video backgrounds" }
        eglContext =
            EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
        eglSurface =
            EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, size.width, EGL14.EGL_HEIGHT, size.height, EGL14.EGL_NONE),
                0,
            )
        check(eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
        fun shader(type: Int, text: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, text)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        for ((k, v) in
            listOf(
                GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR,
                GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
                GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE,
                GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE,
            )) GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, k, v)
        // x, y, s, t. Texture rows are flipped so glReadPixels (bottom-up) yields an
        // upright, top-down bitmap.
        val quad = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        vertices =
            ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(quad)
                    position(0)
                }
    }

    private fun seek(us: Long) {
        if (heldIndex >= 0) heldIndex = -1 // flush() reclaims held buffers
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        inputDone = false
        outputDone = false
        heldPts = NONE
        seeked = true
    }

    private fun feed() {
        if (inputDone) return
        val ix = decoder.dequeueInputBuffer(2000)
        if (ix < 0) return
        val n = extractor.readSampleData(decoder.getInputBuffer(ix)!!, 0)
        if (n < 0) {
            decoder.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            decoder.queueInputBuffer(ix, 0, n, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** Decodes forward so the frame on screen at [targetUs] is in [front]. Video thread only. */
    private fun decodeTo(targetUs: Long) {
        if (shownPts != NONE && targetUs >= shownPts) {
            if (heldPts != NONE && targetUs < heldPts) return
            if (outputDone && heldIndex < 0) return
        }
        val far = shownPts == NONE && targetUs > SEEK_AHEAD_US
        if ((shownPts != NONE && (targetUs < shownPts || targetUs - shownPts > SEEK_AHEAD_US)) || far)
            seek(targetUs)
        var candidate = -1
        var candidatePts = NONE
        var lastProgress = System.nanoTime()
        while (true) {
            if (released.get()) return
            if (heldIndex < 0 && !outputDone) {
                feed()
                val ix = decoder.dequeueOutputBuffer(info, 5000)
                if (ix >= 0) {
                    lastProgress = System.nanoTime()
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && info.size == 0) {
                        decoder.releaseOutputBuffer(ix, false)
                        outputDone = true
                    } else {
                        heldIndex = ix
                        heldPts = info.presentationTimeUs
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            outputDone = true
                    }
                } else if (System.nanoTime() - lastProgress > STALL_NS) {
                    throw IOException("The background video stopped decoding.")
                }
            }
            if (heldIndex >= 0 && heldPts <= targetUs) {
                if (candidate >= 0) decoder.releaseOutputBuffer(candidate, false)
                candidate = heldIndex
                candidatePts = heldPts
                heldIndex = -1
                heldPts = NONE
                continue
            }
            if (heldIndex >= 0 || outputDone) break
        }
        if (candidate < 0 && (shownPts == NONE || seeked) && heldIndex >= 0) {
            // Nothing starts at or before the target (first frame starts later): show it.
            candidate = heldIndex
            candidatePts = heldPts
            heldIndex = -1
            heldPts = NONE
        }
        if (candidate >= 0) present(candidate, candidatePts)
        seeked = false
    }

    private fun present(index: Int, pts: Long) {
        synchronized(frameLock) { frameReady = false }
        decoder.releaseOutputBuffer(index, true)
        synchronized(frameLock) {
            val deadline = System.nanoTime() + STALL_NS
            while (!frameReady) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw IOException("The background video frame did not arrive.")
                frameLock.wait(left)
            }
        }
        texture.updateTexImage()
        texture.getTransformMatrix(matrix)
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        vertices.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, matrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        pixels.position(0)
        GLES20.glReadPixels(0, 0, size.width, size.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Background video drawing failed" }
        pixels.position(0)
        back.copyPixelsFromBuffer(pixels)
        synchronized(this) { front = back.also { back = front } }
        shownPts = pts
    }

    private fun targetUs(seconds: Float): Long =
        (BackgroundMath.mediaTime(seconds.toDouble(), durationUs / 1e6, end) * 1e6).toLong()

    /** Exact frame for [seconds] of music; blocks. Use from the export thread. */
    override fun frame(seconds: Float): Bitmap {
        val us = targetUs(seconds)
        onThread { decodeTo(us) }
        return synchronized(this) { front }
    }

    /**
     * Newest decoded frame without waiting; decoding catches up with [seconds] in the
     * background and calls [ready] when a new frame is available. Use from the UI thread.
     */
    fun latest(seconds: Float, ready: () -> Unit): Bitmap? {
        wantedUs = targetUs(seconds)
        if (!released.get() && queued.compareAndSet(false, true)) {
            handler.post {
                try {
                    while (true) {
                        val target = wantedUs
                        val before = shownPts
                        decodeTo(target)
                        if (shownPts != before) ready()
                        if (wantedUs == target || released.get()) break
                    }
                } catch (_: Exception) {
                    // Preview keeps the last good frame; export reports errors itself.
                } finally {
                    queued.set(false)
                }
            }
        }
        return if (shownPts == NONE) null else synchronized(this) { front }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            onThread {
                try {
                    decoder.stop()
                } catch (_: Exception) {}
                decoder.release()
                extractor.release()
                surface.release()
                texture.release()
                GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
                GLES20.glDeleteProgram(program)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglReleaseThread()
                // Android reference-counts EGLDisplay: pair our eglInitialize.
                EGL14.eglTerminate(display)
            }
        } catch (_: Exception) {
        } finally {
            thread.quitSafely()
            callbacks.quitSafely()
        }
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        private const val SEEK_AHEAD_US = 3_000_000L
        private const val STALL_NS = 5_000_000_000L
        private const val VERTEX =
            "uniform mat4 uST; attribute vec4 aPosition; attribute vec4 aTex; varying vec2 vTex;" +
                " void main(){ gl_Position=aPosition; vTex=(uST*aTex).xy; }"
        private const val FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; uniform samplerExternalOES uTex; varying vec2 vTex;" +
                " void main(){ gl_FragColor=vec4(texture2D(uTex,vTex).rgb,1.0); }"

        /**
         * Opens [uri]. With [reference] and [quality] the working size follows the export
         * (see [BackgroundMath.backgroundSize]); otherwise the short side is [shortSide].
         */
        fun open(
            context: Context,
            uri: Uri,
            end: String,
            shortSide: Int,
            reference: BackgroundMath.Size? = null,
            quality: String? = null,
        ): VideoBackground {
            val v = VideoBackground(context.applicationContext, uri, shortSide, end)
            try {
                v.onThread { v.setup(reference, quality) }
            } catch (e: Exception) {
                v.released.set(true)
                v.thread.quitSafely()
                v.callbacks.quitSafely()
                throw e
            }
            return v
        }
    }
}

/** Preview wrapper: never blocks the UI thread; [ready] runs when a newer frame exists. */
class LiveVideoBackground(private val video: VideoBackground, private val ready: () -> Unit) :
    BackgroundSource {
    override fun frame(seconds: Float) = video.latest(seconds, ready)

    override fun release() = video.release()
}

/** Opens the background chosen in [s]. Missing or unreadable media yields null (plain color). */
object BackgroundMedia {
    const val PREVIEW_SHORT_SIDE = 360
    private const val MAX_GIF_BYTES = 40L * 1024 * 1024

    fun open(
        context: Context,
        s: Settings,
        photo: Bitmap?,
        shortSide: Int,
        reference: BackgroundMath.Size? = null,
    ): BackgroundSource? {
        val quality = if (reference != null) s.s("renderQuality") else null
        fun side(w: Int, h: Int): Int =
            if (reference != null && quality != null)
                BackgroundMath.backgroundSize(w, h, reference.width, reference.height, quality).let {
                    kotlin.math.min(it.width, it.height)
                }
            else shortSide
        return when (s.s("backgroundType")) {
            BackgroundMath.PHOTO -> photo?.let { PhotoBackground(it, side(it.width, it.height)) }
            BackgroundMath.GIF -> {
                val uri = Uri.parse(s.s("backgroundUri").ifEmpty { return null })
                val bytes =
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_GIF_BYTES) { "This GIF is larger than 40 MB." }
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: return null
                val movie = GifBackground.decode(bytes) ?: throw IOException("This GIF can't be read.")
                GifBackground(movie, side(movie.width(), movie.height()), s.s("backgroundEnd"))
            }
            BackgroundMath.VIDEO -> {
                val uri = Uri.parse(s.s("backgroundUri").ifEmpty { return null })
                VideoBackground.open(context, uri, s.s("backgroundEnd"), shortSide, reference, quality)
            }
            else -> null
        }
    }
}
