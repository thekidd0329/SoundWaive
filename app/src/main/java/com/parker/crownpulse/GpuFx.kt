package com.parker.crownpulse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import android.annotation.TargetApi
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * What the GPU export pass adds to one frame. [VisualRenderer.draw] fills it instead of
 * drawing these full-frame layers itself. Alphas are already rounded to 1/255 steps, the
 * way a Paint rounds them, so the GPU pass blends exactly like the Canvas path.
 */
class PostFx {
    /**
     * Set by the exporter before drawing: the GPU pass paints the background color, the
     * video background and the paper grain, so the scene starts transparent.
     */
    var ownsBase = false

    /** Background color (opaque ARGB). */
    var bg = 0

    /** Frame height in frame units (the frame is 1080 units wide). */
    var height = 1920f
    var shakeX = 0f
    var shakeY = 0f
    var mirror = false

    // Video background (used when [ownsBase]).
    var bgAlpha = 0f
    var bgZoom = 1f
    var bgFit = false

    /** Paper grain texture opacity (used when [ownsBase]). */
    var paper = 0f

    /** Build-up darkening, drop flash, grain specks, vignette edge and fade opacities. */
    var dark = 0f
    var flash = 0f
    var grainBase = 0
    var grainAlpha = 0f
    var vignette = 0f
    var fade = 0f

    companion object {
        /** [a] rounded like Paint.setAlpha((a * 255).toInt()). */
        fun alpha(a: Float): Float = (a * 255).toInt().coerceIn(0, 255) / 255f
    }
}

/**
 * Records each frame's Canvas drawing and renders it on the GPU into [surface] with a
 * transparent background (HardwareRenderer, API 29+), so the GPU pass can put the
 * background and video under it. Surface.lockHardwareCanvas always renders opaque.
 */
@TargetApi(29)
internal class SceneRecorder(surface: Surface, private val width: Int, private val height: Int) {
    private val node = RenderNode("soundwaive-scene").apply { setPosition(0, 0, width, height) }
    private val renderer =
        HardwareRenderer().apply {
            setContentRoot(node)
            setOpaque(false)
            setSurface(surface)
        }

    fun draw(block: (Canvas) -> Unit) {
        val c = node.beginRecording(width, height)
        try {
            block(c)
        } finally {
            node.endRecording()
        }
        val result = renderer.createRenderRequest().syncAndDraw()
        val failed =
            HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or
                HardwareRenderer.SYNC_FRAME_DROPPED
        if (result and failed != 0) throw IOException("The GPU frame was not drawn ($result).")
    }

    fun release() {
        try {
            renderer.stop()
            renderer.setSurface(null)
        } catch (_: Exception) {}
        renderer.destroy()
        node.discardDisplayList()
    }
}

/**
 * The GPU export pass: one OpenGL ES 3.0 fragment shader that puts the background color,
 * the video background (sampled straight from the decoder's texture), paper grain, the
 * scene, build-up darkening, the drop flash, grain specks, the vignette, the titles and the
 * fades together in one pass, written straight into the video encoder's surface.
 *
 * Written for tile-based mobile GPUs such as the Tensor G5's PowerVR: no loops, no
 * texelFetch on external textures, no glFinish, clamp-to-edge sampling at any size, one
 * pass (the 48 x 48 blur behind "Fit" videos is a tiny separate draw only when a new video
 * frame arrives), coordinates and hashing in highp, colors in mediump, and time reduced to
 * small numbers on the CPU. Both programs are compiled and linked when the export starts;
 * any failure throws, and the exporter then uses the Canvas path.
 *
 * Needs the encoder's OpenGL ES 3 context to be current. [frameWidth] is the output width
 * in pixels in display orientation.
 */
internal class GpuPost(paper: Bitmap, title: Bitmap?, frameWidth: Int) {
    private var program = 0
    private var blurProgram = 0
    private val pixel = 1080f / frameWidth
    private val textures = IntArray(4) // paper, titles, blur, video (external)
    private var blurFramebuffer = 0
    private val hasTitle = title != null
    private var video: SurfaceTexture? = null
    private var videoWidth = 1
    private var videoHeight = 1
    private var videoLatched = false
    private val videoMatrix = FloatArray(16)
    private val blurQuad =
        floats(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))

    private var aPosition = -1
    private var aTex = -1
    private var uST = -1
    private var uVideoST = -1
    private var uFrame = -1
    private var uShake = -1
    private var uMirror = -1
    private var uPixel = -1
    private var uBg = -1
    private var uVideoAlpha = -1
    private var uVideoRect = -1
    private var uBlurAlpha = -1
    private var uBlurRect = -1
    private var uPaperAlpha = -1
    private var uDark = -1
    private var uWhite = -1
    private var uFlash = -1
    private var uGrainBase = -1
    private var uGrainAlpha = -1
    private var uVignette = -1
    private var uTitleOn = -1
    private var uFade = -1

    init {
        try {
            program = link(VERTEX, FRAGMENT)
            blurProgram = link(BLUR_VERTEX, BLUR_FRAGMENT)
            GLES20.glGenTextures(4, textures, 0)
            for (i in 0..2) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
                clampLinear(GLES20.GL_TEXTURE_2D)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, paper, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
            if (title != null) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, title, 0)
            else blank()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, BLUR, BLUR, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[3])
            clampLinear(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            val fb = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            blurFramebuffer = fb[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFramebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                textures[2], 0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Blur target incomplete: $status" }

            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            check(aPosition >= 0 && aTex >= 0) { "GPU effects shader has no inputs" }
            fun u(name: String) = GLES20.glGetUniformLocation(program, name)
            uST = u("uST")
            uVideoST = u("uVideoST")
            uFrame = u("uFrame")
            uShake = u("uShake")
            uMirror = u("uMirror")
            uPixel = u("uPixel")
            uBg = u("uBg")
            uVideoAlpha = u("uVideoAlpha")
            uVideoRect = u("uVideoRect")
            uBlurAlpha = u("uBlurAlpha")
            uBlurRect = u("uBlurRect")
            uPaperAlpha = u("uPaperAlpha")
            uDark = u("uDark")
            uWhite = u("uWhite")
            uFlash = u("uFlash")
            uGrainBase = u("uGrainBase")
            uGrainAlpha = u("uGrainAlpha")
            uVignette = u("uVignette")
            uTitleOn = u("uTitleOn")
            uFade = u("uFade")
            // Fixed texture units, one per sampler (samplers of different types must never
            // share a unit).
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(u("uScene"), 0)
            GLES20.glUniform1i(u("uVideo"), 1)
            GLES20.glUniform1i(u("uBlur"), 2)
            GLES20.glUniform1i(u("uPaper"), 3)
            GLES20.glUniform1i(u("uTitle"), 4)
            GLES20.glUseProgram(blurProgram)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uTex"), 1)
            GLES20.glUseProgram(0)
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "GPU effects setup failed (GL error $error)" }
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    /**
     * External texture for a zero-copy video background of [width] x [height] (display
     * orientation): hand it to VideoBackground.useTexture, then call [latchVideo] whenever
     * a new frame has arrived in it.
     */
    fun openVideo(width: Int, height: Int): SurfaceTexture {
        videoWidth = width.coerceAtLeast(1)
        videoHeight = height.coerceAtLeast(1)
        return video ?: SurfaceTexture(textures[3]).also { video = it }
    }

    /** Takes the newest video frame into the texture and refreshes the blurred copy. */
    fun latchVideo() {
        val st = video ?: return
        st.updateTexImage()
        st.getTransformMatrix(videoMatrix)
        videoLatched = true
        // The blurred edges behind "Fit" videos: the frame shrunk to 48 x 48 with bilinear
        // filtering, like the Canvas path, then stretched in the main pass.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFramebuffer)
        GLES20.glViewport(0, 0, BLUR, BLUR)
        GLES20.glUseProgram(blurProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[3])
        val pos = GLES20.glGetAttribLocation(blurProgram, "aPosition")
        val tex = GLES20.glGetAttribLocation(blurProgram, "aTex")
        blurQuad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, blurQuad)
        blurQuad.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, blurQuad)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(blurProgram, "uST"), 1, false, videoMatrix, 0,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) throw IOException("GPU video frame failed ($error)")
    }

    /**
     * Draws the frame into the current surface ([width] x [height] pixels, the encoder's
     * size): [scene] is the external texture holding the scene with [sceneMatrix];
     * [vertices] and [coords] are the full-screen quad (coords carry any encoder rotation).
     */
    fun draw(
        scene: Int,
        sceneMatrix: FloatArray,
        vertices: FloatBuffer,
        coords: FloatBuffer,
        width: Int,
        height: Int,
        fx: PostFx,
    ) {
        val frameH = fx.height
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, scene)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[3])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
        GLES20.glUniformMatrix4fv(uST, 1, false, sceneMatrix, 0)
        GLES20.glUniformMatrix4fv(uVideoST, 1, false, videoMatrix, 0)
        GLES20.glUniform2f(uFrame, 1080f, frameH)
        GLES20.glUniform2f(uShake, fx.shakeX, fx.shakeY)
        GLES20.glUniform1f(uMirror, if (fx.mirror) 1f else 0f)
        GLES20.glUniform1f(uPixel, pixel)
        GLES20.glUniform3f(uBg, red(fx.bg), green(fx.bg), blue(fx.bg))
        val showVideo = fx.ownsBase && videoLatched && fx.bgAlpha > 0f
        if (showVideo) {
            val fit = if (fx.bgFit) BackgroundMath.FIT else BackgroundMath.FILL
            val r = BackgroundMath.place(videoWidth, videoHeight, 1080f, frameH, fit, fx.bgZoom)
            GLES20.glUniform4f(uVideoRect, r[0] / 1080f, r[1] / frameH, r[2] / 1080f, r[3] / frameH)
            GLES20.glUniform1f(uVideoAlpha, PostFx.alpha(fx.bgAlpha))
            if (fx.bgFit) {
                val b = BackgroundMath.place(1, 1, 1080f, frameH, BackgroundMath.FILL, fx.bgZoom)
                GLES20.glUniform4f(uBlurRect, b[0] / 1080f, b[1] / frameH, b[2] / 1080f, b[3] / frameH)
                GLES20.glUniform1f(uBlurAlpha, PostFx.alpha(fx.bgAlpha * .7f))
            } else GLES20.glUniform1f(uBlurAlpha, 0f)
        } else {
            GLES20.glUniform1f(uVideoAlpha, 0f)
            GLES20.glUniform1f(uBlurAlpha, 0f)
        }
        GLES20.glUniform1f(uPaperAlpha, if (fx.ownsBase) fx.paper else 0f)
        GLES20.glUniform1f(uDark, fx.dark)
        GLES20.glUniform3f(uWhite, 229 / 255f, 228 / 255f, 222 / 255f)
        GLES20.glUniform1f(uFlash, fx.flash)
        GLES30.glUniform1ui(uGrainBase, fx.grainBase)
        GLES20.glUniform1f(uGrainAlpha, fx.grainAlpha)
        GLES20.glUniform4f(
            uVignette, 540f, frameH * .5f, maxOf(700f, frameH * .68f), fx.vignette,
        )
        GLES20.glUniform1f(uTitleOn, if (hasTitle) 1f else 0f)
        GLES20.glUniform1f(uFade, fx.fade)
        vertices.position(0)
        coords.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTex)
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) throw IOException("GPU effects pass failed ($error)")
    }

    /** Frees the textures and programs (the caller releases any video SurfaceTexture user). */
    fun release() {
        video?.release()
        video = null
        if (blurFramebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(blurFramebuffer), 0)
        blurFramebuffer = 0
        if (textures[0] != 0) GLES20.glDeleteTextures(4, textures, 0)
        textures.fill(0)
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
        if (blurProgram != 0) GLES20.glDeleteProgram(blurProgram)
        blurProgram = 0
    }

    private fun blank() {
        val clear = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, clear,
        )
    }

    companion object {
        private const val TAG = "SoundWaive"
        private const val BLUR = 48

        /** True when this context can run [GpuPost] (OpenGL ES 3 with external textures in ES 3 shaders). */
        fun supported(): Boolean {
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: return false
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: return false
            val es3 = Regex("OpenGL ES (\\d+)").find(version)?.groupValues?.get(1)?.toIntOrNull()
            if (es3 == null || es3 < 3) return false
            return "GL_OES_EGL_image_external_essl3" in extensions
        }

        private fun red(c: Int) = ((c shr 16) and 0xff) / 255f

        private fun green(c: Int) = ((c shr 8) and 0xff) / 255f

        private fun blue(c: Int) = (c and 0xff) / 255f

        private fun clampLinear(target: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(a)
                position(0)
            }

        private fun compile(type: Int, text: String): Int {
            val id = GLES20.glCreateShader(type)
            check(id != 0) { "Could not create a shader" }
            GLES20.glShaderSource(id, text)
            GLES20.glCompileShader(id)
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(id)
                GLES20.glDeleteShader(id)
                Log.w(TAG, "GPU effects shader did not compile: $log")
                throw IllegalStateException("Shader compile failed: $log")
            }
            return id
        }

        private fun link(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs =
                try {
                    compile(GLES20.GL_FRAGMENT_SHADER, fragment)
                } catch (e: Exception) {
                    GLES20.glDeleteShader(vs)
                    throw e
                }
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                Log.w(TAG, "GPU effects shader did not link: $log")
                throw IllegalStateException("Shader link failed: $log")
            }
            return program
        }

        private const val VERTEX =
            """#version 300 es
uniform highp mat4 uST;
in highp vec4 aPosition;
in highp vec4 aTex;
out highp vec2 vScene;
out highp vec2 vScreen;
void main() {
    gl_Position = aPosition;
    vScene = (uST * aTex).xy;
    // Frame position, 0..1 from the top-left; aTex already carries any encoder rotation.
    vScreen = vec2(aTex.x, 1.0 - aTex.y);
}
"""

        // Each step repeats one Canvas layer of VisualRenderer.draw, in the same order.
        private const val FRAGMENT =
            """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
precision highp int;
uniform mediump samplerExternalOES uScene;
uniform mediump samplerExternalOES uVideo;
uniform lowp sampler2D uBlur;
uniform lowp sampler2D uPaper;
uniform lowp sampler2D uTitle;
uniform highp mat4 uVideoST;
uniform highp vec2 uFrame;
uniform highp vec2 uShake;
uniform highp float uMirror;
uniform highp float uPixel;
uniform vec3 uBg;
uniform float uVideoAlpha;
uniform highp vec4 uVideoRect;
uniform float uBlurAlpha;
uniform highp vec4 uBlurRect;
uniform float uPaperAlpha;
uniform float uDark;
uniform vec3 uWhite;
uniform float uFlash;
uniform highp uint uGrainBase;
uniform float uGrainAlpha;
uniform highp vec4 uVignette;
uniform float uTitleOn;
uniform float uFade;
in highp vec2 vScene;
in highp vec2 vScreen;
out vec4 fragColor;

// BeatFx.hash01: the "lowbias32" integer hash, bit for bit.
highp float hash01(highp uint n) {
    highp uint x = n;
    x ^= x >> 16u;
    x *= 0x7feb352du;
    x ^= x >> 15u;
    x *= 0x846ca68bu;
    x ^= x >> 16u;
    return float(x >> 8u) * (1.0 / 16777216.0);
}

highp float inside(highp vec2 p, highp vec4 r) {
    return step(r.x, p.x) * step(p.x, r.z) * step(r.y, p.y) * step(p.y, r.w);
}

highp vec2 rectUv(highp vec2 p, highp vec4 r) {
    return clamp((p - r.xy) / (r.zw - r.xy), 0.0, 1.0);
}

void main() {
    highp vec2 screen = vScreen;
    // Background color, blurred edges, video and paper grain (when this pass owns them).
    vec3 base = uBg;
    if (uBlurAlpha > 0.0) {
        vec3 blur = texture(uBlur, rectUv(screen, uBlurRect)).rgb;
        base = mix(base, blur, uBlurAlpha * inside(screen, uBlurRect));
    }
    if (uVideoAlpha > 0.0) {
        highp vec2 v = rectUv(screen, uVideoRect);
        highp vec2 st = (uVideoST * vec4(v.x, 1.0 - v.y, 0.0, 1.0)).xy;
        vec3 picture = texture(uVideo, st).rgb;
        base = mix(base, picture, uVideoAlpha * inside(screen, uVideoRect));
    }
    if (uPaperAlpha > 0.0) {
        vec4 paper = texture(uPaper, screen);
        base = paper.rgb * uPaperAlpha + base * (1.0 - paper.a * uPaperAlpha);
    }
    // The scene, premultiplied, over the base.
    vec4 scene = texture(uScene, vScene);
    vec3 color = scene.rgb + base * (1.0 - scene.a);
    // Build-up darkening and the drop flash cover the whole frame.
    color *= 1.0 - uDark;
    color = mix(color, uWhite, uFlash);
    // Layer position in frame units: screen shake moves it, mirror flips it.
    highp vec2 layer = screen * uFrame - uShake;
    highp vec2 local = vec2(mix(layer.x, uFrame.x - layer.x, uMirror), layer.y);
    highp vec4 frame = vec4(0.0, 0.0, uFrame.x, uFrame.y);
    if (uGrainAlpha > 0.0) {
        // BeatFx.grainSpeck for this pixel's cell, with exact pixel coverage.
        highp vec2 cell = floor(local / 135.0);
        if (cell.x >= 0.0 && cell.x < 8.0 && cell.y >= 0.0) {
            highp uint index = uint(cell.y) * 8u + uint(cell.x);
            highp uint n = uGrainBase + index * 3u;
            highp float span = (135.0 - 3.0) / 135.0;
            highp vec2 corner =
                (cell + vec2(hash01(n), hash01(n + 1u)) * span) * 135.0;
            highp float size = 1.0 + hash01(n + 2u) * 2.0;
            highp vec2 lo = max(local - 0.5 * uPixel, corner);
            highp vec2 hi = min(local + 0.5 * uPixel, corner + size);
            highp vec2 cover = clamp(hi - lo, 0.0, uPixel) / uPixel;
            color = mix(color, uWhite, uGrainAlpha * cover.x * cover.y);
        }
    }
    if (uVignette.w > 0.0) {
        highp float t = clamp(length(layer - uVignette.xy) / uVignette.z, 0.0, 1.0);
        color *= 1.0 - uVignette.w * t * inside(layer, frame);
    }
    if (uTitleOn > 0.0) {
        // The titles layer was drawn mirrored already; only shake moves it.
        highp vec2 tuv = layer / uFrame;
        vec4 title = texture(uTitle, clamp(tuv, 0.0, 1.0)) * inside(tuv, vec4(0.0, 0.0, 1.0, 1.0));
        color = title.rgb + color * (1.0 - title.a);
    }
    color = mix(color, uBg, uFade * inside(layer, frame));
    fragColor = vec4(color, 1.0);
}
"""

        private const val BLUR_VERTEX =
            "uniform mat4 uST; attribute vec4 aPosition; attribute vec4 aTex; varying vec2 vTex;" +
                " void main(){ gl_Position=aPosition; vTex=(uST*aTex).xy; }"
        private const val BLUR_FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; uniform samplerExternalOES uTex; varying vec2 vTex;" +
                " void main(){ gl_FragColor=vec4(texture2D(uTex,vTex).rgb,1.0); }"
    }
}
