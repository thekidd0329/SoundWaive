package com.parker.crownpulse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.*
import android.opengl.*
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
    )

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
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val total = ceil((end - start) * fps).toInt()
            var lastUpdate = 0L
            for (f in 0 until total) {
                if (cancel.get()) throw InterruptedException("Export canceled")
                renderer.draw(canvas, w, h, start + f / fps.toFloat(), track.analysis, s, true)
                egl.draw(bitmap, f * 1_000_000_000L / fps)
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
            return Result(output, w, h, total, end - start)
        } finally {
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

    private class EncoderSurface(val surface: Surface, val w: Int, val h: Int, rotated: Boolean) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val program: Int
        private val texture: Int
        private val vertices: FloatBuffer
        private val coords: FloatBuffer
        private var initialized = false

        init {
            val versions = IntArray(2)
            check(EGL14.eglInitialize(display, versions, 0, versions, 1))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(
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
                        EGL14.EGL_OPENGL_ES2_BIT,
                        0x3142,
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
            )
            context =
                EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
            eglSurface =
                EGL14.eglCreateWindowSurface(
                    display,
                    configs[0],
                    surface,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
            check(context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
            fun shader(type: Int, text: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, text)
                GLES20.glCompileShader(id)
                val status = IntArray(1)
                GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { GLES20.glGetShaderInfoLog(id) }
                return id
            }
            val vs =
                shader(
                    GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 aPosition; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTex=aTex; }",
                )
            val fs =
                shader(
                    GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; uniform sampler2D uTexture; varying vec2 vTex; void main(){ gl_FragColor=texture2D(uTexture,vTex); }",
                )
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
            fun fb(a: FloatArray) =
                ByteBuffer.allocateDirect(a.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .apply {
                        put(a)
                        position(0)
                    }
            vertices = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            coords =
                fb(
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
            val pos = GLES20.glGetAttribLocation(program, "aPosition")
            val tex = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glEnableVertexAttribArray(tex)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Video rendering failed" }
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "Video encoder surface failed" }
        }

        fun release() {
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
    }
}
