package com.parker.crownpulse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.media.*
import android.opengl.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.*
import java.nio.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class VideoExporter {
    data class Result(
        val file: File,
        val width: Int,
        val height: Int,
        val frames: Int,
        val duration: Float,
        /** One plain-language line when the export had to use a slower drawing path. */
        val notice: String? = null,
    )

    /** The GPU effects path for one export (see [openEffects]). */
    private class Effects(
        val recorder: SceneRecorder,
        val post: GpuPost,
        val fx: PostFx,
        /** Set when the video background decodes straight into [post]'s texture. */
        val video: VideoBackground?,
    ) {
        fun close() {
            try {
                recorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "GPU scene release failed", e)
            }
            video?.useBitmaps()
            post.release()
        }
    }

    /**
     * Sets up the GPU effects path on the current encoder context: the scene renders with a
     * transparent background into [canvas], and one shader pass adds the background, a
     * zero-copy video background and the full-frame effects. Null (with a log line) when the
     * phone can't, so the export keeps the Canvas path.
     */
    private fun openEffects(
        egl: EncoderSurface,
        canvas: Surface,
        work: BackgroundMath.Size,
        outWidth: Int,
        s: Settings,
        renderer: VisualRenderer,
    ): Effects? {
        if (Build.VERSION.SDK_INT < 29) {
            Log.i(TAG, "GPU effects need Android 10; using the Canvas path")
            return null
        }
        if (!egl.es3 || !GpuPost.supported()) {
            Log.w(TAG, "GPU effects need OpenGL ES 3 with external textures; using the Canvas path")
            return null
        }
        var post: GpuPost? = null
        var title: Bitmap? = null
        var video: VideoBackground? = null
        try {
            title = renderer.titleLayer(work.width, work.height, s)
            val program = GpuPost(renderer.paperImage, title, outWidth)
            post = program
            val fx = PostFx()
            val source = renderer.background as? VideoBackground
            if (source != null && s.s("backgroundType") == BackgroundMath.VIDEO) {
                val texture = program.openVideo(source.size.width, source.size.height)
                if (source.useTexture(texture)) video = source
            }
            fx.ownsBase = s.s("backgroundType") == BackgroundMath.COLOR || video != null
            val recorder = SceneRecorder(canvas, work.width, work.height)
            Log.i(TAG, "GPU effects on (zero-copy video: ${video != null})")
            return Effects(recorder, program, fx, video)
        } catch (e: Exception) {
            Log.w(TAG, "GPU effects unavailable; using the Canvas path", e)
            video?.useBitmaps()
            post?.release()
            return null
        } finally {
            title?.recycle()
        }
    }

    fun dimensions(s: Settings): Pair<Int, Int> {
        val short = if (s.s("resolution") == "720p") 720 else 1080
        return when (s.s("aspect")) {
            "1:1" -> short to short
            "16:9" -> short * 16 / 9 to short
            "4:5" -> short to (short * 5 / 4 / 2 * 2)
            else -> short to short * 16 / 9
        }
    }

    fun export(
        track: Track,
        settings: Settings,
        renderer: VisualRenderer,
        dir: File,
        cancel: AtomicBoolean,
        progress: (String, Float) -> Unit,
    ): Result {
        dir.mkdirs()
        val s = settings.copy()
        val start = s.f("trimStart").coerceIn(0f, (track.analysis.duration - .1f).coerceAtLeast(0f))
        val end =
            (if (s.f("trimEnd") > start) s.f("trimEnd") else track.analysis.duration).coerceAtMost(
                track.analysis.duration
            )
        require(end - start >= .1f) { "Choose an export range longer than 0.1 seconds." }
        require(track.pcm?.exists() == true) {
            "Audio preparation is incomplete. Import the track again."
        }
        val audio = File(dir, "audio-${System.nanoTime()}.m4a")
        val output = File(dir, "CrownPulse-${System.currentTimeMillis()}.mp4")
        var codec: MediaCodec? = null
        var egl: EncoderSurface? = null
        var mux: MediaMuxer? = null
        var surface: Surface? = null
        var bitmap: Bitmap? = null
        val ae = MediaExtractor()
        var muxStarted = false
        var success = false
        var effects: Effects? = null
        var notice: String? = null
        try {
            val encoderDelay =
                encodeAudio(track, start, end, audio, cancel) {
                    progress("Preparing full-quality audio", it * .1f)
                }
            ae.setDataSource(audio.absolutePath)
            ae.selectTrack(0)
            val af = ae.getTrackFormat(0)
            val (w, h) = dimensions(s)
            val fps = s.s("fps").toIntOrNull() ?: 30
            val bitrate = (s.s("bitrate").substringBefore(' ').toIntOrNull() ?: 12) * 1_000_000
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var name = codecs.findEncoderForFormat(format)
            var rotated = false
            if (name == null && w != h) {
                // Some encoders accept the same pixels only in landscape order.
                // Rotate the texture and tag the MP4; preserve the requested display size.
                format.setInteger(MediaFormat.KEY_WIDTH, h)
                format.setInteger(MediaFormat.KEY_HEIGHT, w)
                name = codecs.findEncoderForFormat(format)
                rotated = name != null
            }
            if (name == null)
                throw IllegalStateException(
                    "This phone cannot export this size and frame rate. Try 720p at 30 fps."
                )
            codec = MediaCodec.createByCodecName(name)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            egl = EncoderSurface(surface, if (rotated) h else w, if (rotated) w else h, rotated)
            codec.start()
            mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotated) mux.setOrientationHint(90)
            val audioIndex = mux.addTrack(af)
            var videoIndex = -1
            val info = MediaCodec.BufferInfo()
            fun drain(endOfStream: Boolean) {
                var waitingSince = System.currentTimeMillis()
                while (true) {
                    if (cancel.get()) throw InterruptedException("Export canceled")
                    val ix = codec.dequeueOutputBuffer(info, if (endOfStream) 10000 else 0)
                    when {
                        ix == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (System.currentTimeMillis() - waitingSince > 30000)
                                throw IOException("The phone's video encoder stopped responding.")
                        }
                        ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            require(!muxStarted)
                            videoIndex = mux!!.addTrack(codec.outputFormat)
                            mux!!.start()
                            muxStarted = true
                        }
                        ix >= 0 -> {
                            waitingSince = System.currentTimeMillis()
                            val b = codec.getOutputBuffer(ix)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
                                info.size = 0
                            if (info.size > 0) {
                                check(muxStarted)
                                b.position(info.offset)
                                b.limit(info.offset + info.size)
                                mux!!.writeSampleData(videoIndex, b, info)
                            }
                            val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(ix, false)
                            if (done) return
                        }
                    }
                }
            }
            // Render at the chosen working size; the encoder pass upscales it to w x h with
            // bilinear texture filtering on the GPU. Frames are drawn with a hardware Canvas
            // into a GPU buffer the encoder pass samples directly (no CPU pixels, no upload).
            // Drawing straight onto the encoder surface would lose the per-frame timestamps,
            // so the existing EGL pass still presents each frame at its exact music time.
            // If the GPU path is unavailable or fails, the CPU bitmap path takes over.
            val work = BackgroundMath.renderSize(w, h, s.s("renderQuality"))
            var gpu: Surface? = egl.openCanvas(work.width, work.height)
            // Fastest path: the scene on the GPU with a transparent background, then one
            // shader pass for the background, video and full-frame effects (see GpuPost).
            effects = gpu?.let { openEffects(egl, it, work, w, s, renderer) }
            if (effects == null) notice = SLOWER_PATH
            var canvas: Canvas? = null
            val total = ceil((end - start) * fps).toInt()
            var lastUpdate = 0L
            for (f in 0 until total) {
                if (cancel.get()) throw InterruptedException("Export canceled")
                val time = start + f / fps.toFloat()
                val nanos = f * 1_000_000_000L / fps
                var drawn = false
                val fast = effects
                if (fast != null) {
                    try {
                        fast.video?.let { video ->
                            video.frame(time) // decodes straight into the GPU texture
                            if (video.takeTextureFrame()) fast.post.latchVideo()
                        }
                        fast.recorder.draw { c ->
                            renderer.draw(
                                c,
                                work.width,
                                work.height,
                                time,
                                track.analysis,
                                s,
                                true,
                                fast.fx,
                            )
                        }
                        egl.drawEffects(nanos, fast.post, fast.fx)
                        drawn = true
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        // The scene renderer owned the GPU canvas, so finish on the CPU path.
                        Log.w(TAG, "GPU effects failed at frame $f; continuing on the CPU path", e)
                        effects = null
                        fast.close()
                        egl.closeCanvas()
                        gpu = null
                        notice = STOPPED_PATH
                    }
                }
                val target = gpu
                if (!drawn && target != null) {
                    try {
                        val c = target.lockHardwareCanvas()
                        try {
                            renderer.draw(c, work.width, work.height, time, track.analysis, s, true)
                        } finally {
                            target.unlockCanvasAndPost(c)
                        }
                        egl.drawCanvas(nanos)
                        drawn = true
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "GPU export path failed; continuing on the CPU path", e)
                        egl.closeCanvas()
                        gpu = null
                        notice = STOPPED_PATH
                    }
                }
                if (!drawn) {
                    val image =
                        bitmap
                            ?: Bitmap.createBitmap(work.width, work.height, Bitmap.Config.ARGB_8888)
                                .also {
                                    bitmap = it
                                    canvas = Canvas(it)
                                }
                    renderer.draw(canvas!!, work.width, work.height, time, track.analysis, s, true)
                    egl.draw(image, nanos)
                }
                drain(false)
                if (System.currentTimeMillis() - lastUpdate > 200) {
                    progress("Rendering frame ${f+1} of $total", .1f + .85f * (f + 1) / total)
                    lastUpdate = System.currentTimeMillis()
                }
            }
            codec.signalEndOfInputStream()
            drain(true)
            progress("Finishing video and audio", .96f)
            val audioBuffer = ByteBuffer.allocate(1024 * 1024)
            while (true) {
                if (cancel.get()) throw InterruptedException("Export canceled")
                audioBuffer.clear()
                val n = ae.readSampleData(audioBuffer, 0)
                if (n < 0) break
                // Extractor flags and codec flags use different bit values.
                val sampleFlags = ae.sampleFlags
                check(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                    "The temporary audio track is unexpectedly encrypted"
                }
                var codecFlags = 0
                if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }
                info.set(0, n, ae.sampleTime, codecFlags)
                mux.writeSampleData(audioIndex, audioBuffer, info)
                ae.advance()
            }
            mux.stop()
            muxStarted = false
            mux.release()
            mux = null
            Mp4AudioTiming.apply(
                output,
                encoderDelay,
                ((end - start) * track.rate).toLong(),
                track.rate,
            )
            success = true
            progress("Video ready", 1f)
            return Result(output, w, h, total, end - start, notice)
        } finally {
            // GL objects first, while the encoder's context is still current.
            try {
                effects?.close()
            } catch (e: Exception) {
                Log.w(TAG, "GPU effects release failed", e)
            }
            try {
                codec?.stop()
            } catch (_: Exception) {}
            codec?.release()
            egl?.release()
            surface?.release()
            bitmap?.recycle()
            ae.release()
            if (muxStarted)
                try {
                    mux?.stop()
                } catch (_: Exception) {}
            try {
                mux?.release()
            } catch (_: Exception) {}
            audio.delete()
            if (!success) output.delete()
        }
    }

    internal fun encodeAudio(
        track: Track,
        start: Float,
        end: Float,
        out: File,
        cancel: AtomicBoolean,
        progress: (Float) -> Unit,
    ): Int {
        // The standard Android AAC-LC encoders use 2048 priming samples.
        // Use the same implementation across phones for deterministic audio timing.
        val available = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val name =
            listOf("c2.android.aac.encoder", "OMX.google.aac.encoder").firstOrNull { wanted ->
                available.any { it.name == wanted && it.isEncoder }
            }
                ?: throw IllegalStateException(
                    "This device does not provide the standard Android AAC encoder."
                )
        val encoderDelay = 2048
        val codec = MediaCodec.createByCodecName(name)
        val f =
            MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                track.rate,
                track.channels,
            )
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, if (track.channels == 2) 320000 else 160000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var mux: MediaMuxer? = null
        var started = false
        try {
            mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            val bytesPerFrame = track.channels * 2
            val frames = ((end - start) * track.rate).toLong()
            val sourceBytes = frames * bytesPerFrame
            val total = ((frames + encoderDelay + 1023) / 1024) * 1024 * bytesPerFrame
            var read = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val temp = ByteArray(16384)
            RandomAccessFile(track.pcm!!, "r").use { file ->
                file.seek((start * track.rate).toLong() * bytesPerFrame)
                while (!outputDone) {
                    if (cancel.get()) throw InterruptedException("Export canceled")
                    if (!inputDone) {
                        val ix = codec.dequeueInputBuffer(10000)
                        if (ix >= 0) {
                            val b = codec.getInputBuffer(ix)!!
                            b.clear()
                            val amount =
                                min(min(b.remaining(), temp.size).toLong(), total - read).toInt() /
                                    bytesPerFrame * bytesPerFrame
                            val count =
                                if (amount > 0) {
                                    temp.fill(0, 0, amount)
                                    val fromSource =
                                        min(amount.toLong(), (sourceBytes - read).coerceAtLeast(0))
                                            .toInt()
                                    if (fromSource > 0) file.readFully(temp, 0, fromSource)
                                    amount
                                } else -1
                            val pts = (read / bytesPerFrame) * 1_000_000L / track.rate
                            if (count <= 0) {
                                codec.queueInputBuffer(
                                    ix,
                                    0,
                                    0,
                                    pts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                b.put(temp, 0, count)
                                codec.queueInputBuffer(ix, 0, count, pts, 0)
                                read += count
                                progress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    when (val ix = codec.dequeueOutputBuffer(info, 10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = mux.addTrack(codec.outputFormat)
                            mux.start()
                            started = true
                        }
                        else ->
                            if (ix >= 0) {
                                val b = codec.getOutputBuffer(ix)!!
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
                                    info.size = 0
                                if (info.size > 0) {
                                    check(started)
                                    b.position(info.offset)
                                    b.limit(info.offset + info.size)
                                    mux.writeSampleData(trackIndex, b, info)
                                }
                                outputDone =
                                    info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(ix, false)
                            }
                    }
                }
            }
            mux.stop()
            started = false
            return encoderDelay
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {}
            codec.release()
            if (started)
                try {
                    mux?.stop()
                } catch (_: Exception) {}
            mux?.release()
        }
    }

    private class EncoderSurface(
        val surface: Surface,
        val w: Int,
        val h: Int,
        private val rotated: Boolean,
    ) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val program: Int
        private val texture: Int
        private val vertices: FloatBuffer
        private val coords: FloatBuffer
        private var initialized = false
        private val position: Int
        private val texCoord: Int
        private val sampler: Int

        // GPU canvas path: a SurfaceTexture whose hardware-Canvas frames are sampled here.
        private var callbacks: HandlerThread? = null
        private var canvasTexture: SurfaceTexture? = null
        private var canvasSurface: Surface? = null
        private var oesProgram = 0
        private var oesTexture = 0
        private var oesPosition = -1
        private var oesTexCoord = -1
        private var oesMatrix = -1
        private var oesSampler = -1
        private val oesCoords =
            floats(
                // Standard GL texture coordinates; the SurfaceTexture matrix flips them.
                if (rotated) floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
                else floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            )
        private val transform = FloatArray(16)
        private val frameLock = Object()
        private var frameReady = false

        /** True when this is an OpenGL ES 3 context (needed for the GPU effects pass). */
        val es3: Boolean

        init {
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1))
            fun choose(renderable: Int): android.opengl.EGLConfig? {
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val count = IntArray(1)
                val found =
                    EGL14.eglChooseConfig(
                        display,
                        intArrayOf(
                            EGL14.EGL_RED_SIZE,
                            8,
                            EGL14.EGL_GREEN_SIZE,
                            8,
                            EGL14.EGL_BLUE_SIZE,
                            8,
                            EGL14.EGL_ALPHA_SIZE,
                            8,
                            EGL14.EGL_RENDERABLE_TYPE,
                            renderable,
                            0x3142, // EGL_RECORDABLE_ANDROID
                            1,
                            EGL14.EGL_NONE,
                        ),
                        0,
                        configs,
                        0,
                        1,
                        count,
                        0,
                    ) && count[0] > 0
                return if (found) configs[0] else null
            }
            // OpenGL ES 3 first (the GPU effects pass needs it), else ES 2 as before.
            var config = choose(EGLExt.EGL_OPENGL_ES3_BIT_KHR)
            var made =
                config?.let {
                    EGL14.eglCreateContext(
                        display,
                        it,
                        EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                        0,
                    )
                } ?: EGL14.EGL_NO_CONTEXT
            es3 = made != EGL14.EGL_NO_CONTEXT
            if (!es3) {
                Log.i(TAG, "OpenGL ES 3 unavailable for export; using ES 2")
                config = checkNotNull(choose(EGL14.EGL_OPENGL_ES2_BIT))
                made =
                    EGL14.eglCreateContext(
                        display,
                        config,
                        EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                        0,
                    )
            }
            context = made
            eglSurface =
                EGL14.eglCreateWindowSurface(
                    display,
                    checkNotNull(config),
                    surface,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
            check(context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
            program =
                link(
                    "attribute vec2 aPosition; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTex=aTex; }",
                    "precision mediump float; uniform sampler2D uTexture; varying vec2 vTex; void main(){ gl_FragColor=texture2D(uTexture,vTex); }",
                )
            position = GLES20.glGetAttribLocation(program, "aPosition")
            texCoord = GLES20.glGetAttribLocation(program, "aTex")
            sampler = GLES20.glGetUniformLocation(program, "uTexture")
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            coords =
                floats(
                    if (rotated) floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)
                    else floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
                )
        }

        fun draw(bitmap: Bitmap, nanos: Long) {
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (!initialized) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                initialized = true
            } else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glUniform1i(sampler, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Video rendering failed" }
            present(nanos)
        }

        private fun present(nanos: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "Video encoder surface failed" }
        }

        /**
         * Opens a [width] x [height] Surface for [Surface.lockHardwareCanvas] frames that
         * [drawCanvas] sends to the encoder. Returns null when the GPU path is unavailable.
         * Call on the export thread (this EGL context is current there).
         */
        fun openCanvas(width: Int, height: Int): Surface? =
            try {
                oesProgram = link(OES_VERTEX, OES_FRAGMENT)
                oesPosition = GLES20.glGetAttribLocation(oesProgram, "aPosition")
                oesTexCoord = GLES20.glGetAttribLocation(oesProgram, "aTex")
                oesMatrix = GLES20.glGetUniformLocation(oesProgram, "uST")
                oesSampler = GLES20.glGetUniformLocation(oesProgram, "uTex")
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
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GPU canvas setup failed" }
                val thread = HandlerThread("soundwaive-export-frames").apply { start() }
                callbacks = thread
                val st = SurfaceTexture(oesTexture)
                canvasTexture = st
                st.setDefaultBufferSize(width, height)
                st.setOnFrameAvailableListener(
                    {
                        synchronized(frameLock) {
                            frameReady = true
                            frameLock.notifyAll()
                        }
                    },
                    Handler(thread.looper),
                )
                Surface(st).also { canvasSurface = it }
            } catch (e: Exception) {
                Log.w(TAG, "GPU canvas unavailable; using the CPU path", e)
                closeCanvas()
                null
            }

        /**
         * Waits for the scene just rendered into the canvas Surface and encodes it at [nanos]
         * through the GPU effects pass [post], which adds the layers described by [fx].
         */
        fun drawEffects(nanos: Long, post: GpuPost, fx: PostFx) {
            latchCanvas()
            post.draw(oesTexture, transform, vertices, oesCoords, w, h, fx)
            present(nanos)
        }

        /** Waits for the frame just posted to the canvas Surface and latches it. */
        private fun latchCanvas() {
            val st = checkNotNull(canvasTexture) { "GPU canvas is closed" }
            synchronized(frameLock) {
                val deadline = System.nanoTime() + FRAME_TIMEOUT_NS
                while (!frameReady) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) throw IOException("The GPU frame did not arrive.")
                    frameLock.wait(left)
                }
                frameReady = false
            }
            st.updateTexImage()
            st.getTransformMatrix(transform)
        }

        /** Waits for the frame just posted to the canvas Surface and encodes it at [nanos]. */
        fun drawCanvas(nanos: Long) {
            latchCanvas()
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(oesProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES20.glEnableVertexAttribArray(oesPosition)
            GLES20.glEnableVertexAttribArray(oesTexCoord)
            GLES20.glVertexAttribPointer(oesPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(oesTexCoord, 2, GLES20.GL_FLOAT, false, 0, oesCoords)
            GLES20.glUniformMatrix4fv(oesMatrix, 1, false, transform, 0)
            GLES20.glUniform1i(oesSampler, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GPU video rendering failed" }
            present(nanos)
        }

        /** Releases the GPU canvas path (safe to call more than once). */
        fun closeCanvas() {
            canvasSurface?.release()
            canvasSurface = null
            canvasTexture?.release()
            canvasTexture = null
            callbacks?.quitSafely()
            callbacks = null
            if (oesTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            oesTexture = 0
            if (oesProgram != 0) GLES20.glDeleteProgram(oesProgram)
            oesProgram = 0
        }

        fun release() {
            closeCanvas()
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        companion object {
            private const val FRAME_TIMEOUT_NS = 3_000_000_000L
            private const val OES_VERTEX =
                "uniform mat4 uST; attribute vec4 aPosition; attribute vec4 aTex; varying vec2 vTex;" +
                    " void main(){ gl_Position=aPosition; vTex=(uST*aTex).xy; }"
            private const val OES_FRAGMENT =
                "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float; uniform samplerExternalOES uTex; varying vec2 vTex;" +
                    " void main(){ gl_FragColor=vec4(texture2D(uTex,vTex).rgb,1.0); }"

            fun floats(a: FloatArray): FloatBuffer =
                ByteBuffer.allocateDirect(a.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(a)
                        position(0)
                    }

            private fun shader(type: Int, text: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, text)
                GLES20.glCompileShader(id)
                val status = IntArray(1)
                GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { GLES20.glGetShaderInfoLog(id) }
                return id
            }

            fun link(vertex: String, fragment: String): Int {
                val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
                val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
                val program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vs)
                GLES20.glAttachShader(program, fs)
                GLES20.glLinkProgram(program)
                val linked = IntArray(1)
                GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
                GLES20.glDeleteShader(vs)
                GLES20.glDeleteShader(fs)
                check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
                return program
            }
        }
    }

    private companion object {
        const val TAG = "SoundWaive"
        const val SLOWER_PATH =
            "This phone couldn't use the fast GPU effects, so this video used the standard path."
        const val STOPPED_PATH =
            "The fast GPU path stopped partway, so the rest of this video used the slower path."
    }
}
