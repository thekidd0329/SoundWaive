package com.parker.crownpulse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min

/**
 * "Sharpen & clean up background (AI)": Real-ESRGAN-General-x4v3 (a 4x super-resolution
 * model trained on noisy, compressed pictures, so it also cleans them up) through LiteRT's
 * CompiledModel API. It tries the Tensor TPU first (only with the AOT-compiled
 * [MODEL_G5] asset; the Tensor SDK has no on-device compilation), then the GPU, then the
 * CPU, and remembers which one it got in [chip].
 *
 * The model takes 128 x 128 RGB tiles in 0..1 and returns 512 x 512 ones. Pictures are cut
 * into overlapping tiles (edge pixels repeated at the borders) and only each tile's middle
 * is kept, so no seams show. Everything happens on the caller's thread; [enhance] is
 * synchronized because the model has one set of buffers.
 *
 * Anything that goes wrong is logged and the caller keeps the original picture: the effect
 * never stops an export.
 */
class AiUpscaler
private constructor(
    private val environment: Environment,
    private val model: CompiledModel,
    /** "TPU", "GPU" or "CPU". */
    val chip: String,
) {
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()
    private val tile = FloatArray(TILE * TILE * 3)

    /** Milliseconds for one tile after a first warm-up run (gray input). */
    private fun timeOneTile(): Long {
        tile.fill(.5f)
        inputs[0].writeFloat(tile)
        model.run(inputs, outputs)
        val start = System.nanoTime()
        model.run(inputs, outputs)
        outputs[0].readFloat()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun closeAll() {
        inputs.forEach { runCatching { it.close() } }
        outputs.forEach { runCatching { it.close() } }
        runCatching { model.close() }
        runCatching { environment.close() }
    }

    /**
     * Upscales and cleans [source] for display with a short side of [targetShort] pixels.
     * Returns null when there is nothing to gain (the picture is already at least that
     * sharp, or it has transparency the model can't keep). Pass [opaque] = true for
     * pictures known to be opaque whose bitmap still reports an alpha channel (video frames).
     */
    @Synchronized
    fun enhance(source: Bitmap, targetShort: Int, opaque: Boolean = !source.hasAlpha()): Bitmap? {
        val sourceShort = min(source.width, source.height)
        if (sourceShort <= 0 || sourceShort >= targetShort || !opaque) return null
        // Feed at most half the target size: 4x then lands at 2x the target or more, and is
        // scaled down from there, which keeps the work (number of tiles) bounded.
        val inputShort = min(sourceShort, (targetShort + 1) / 2)
        val input =
            if (inputShort < sourceShort)
                Bitmap.createScaledBitmap(
                    source,
                    (source.width.toLong() * inputShort / sourceShort).toInt().coerceAtLeast(1),
                    (source.height.toLong() * inputShort / sourceShort).toInt().coerceAtLeast(1),
                    true,
                )
            else source
        val w = input.width
        val h = input.height
        val pixels = IntArray(w * h)
        input.getPixels(pixels, 0, w, 0, 0, w, h)
        if (input !== source) input.recycle()
        val outW = w * SCALE
        val outH = h * SCALE
        val out = IntArray(outW * outH)
        var cy = 0
        while (cy < h) {
            var cx = 0
            while (cx < w) {
                val ox = cx - PAD
                val oy = cy - PAD
                var k = 0
                for (ty in 0 until TILE) {
                    val row = (oy + ty).coerceIn(0, h - 1) * w
                    for (tx in 0 until TILE) {
                        val p = pixels[row + (ox + tx).coerceIn(0, w - 1)]
                        tile[k] = ((p shr 16) and 0xff) / 255f
                        tile[k + 1] = ((p shr 8) and 0xff) / 255f
                        tile[k + 2] = (p and 0xff) / 255f
                        k += 3
                    }
                }
                inputs[0].writeFloat(tile)
                model.run(inputs, outputs)
                val result = outputs[0].readFloat()
                check(result.size >= OUT * OUT * 3) { "Unexpected model output size ${result.size}" }
                // Keep only this tile's middle (its CORE x CORE input pixels, times 4).
                val x0 = cx * SCALE
                val x1 = min(cx + CORE, w) * SCALE
                val y0 = cy * SCALE
                val y1 = min(cy + CORE, h) * SCALE
                for (y in y0 until y1) {
                    val tileRow = (y - oy * SCALE) * OUT
                    val outRow = y * outW
                    for (x in x0 until x1) {
                        val j = (tileRow + x - ox * SCALE) * 3
                        out[outRow + x] =
                            (0xff shl 24) or
                                (channel(result[j]) shl 16) or
                                (channel(result[j + 1]) shl 8) or
                                channel(result[j + 2])
                    }
                }
                cx += CORE
            }
            cy += CORE
        }
        val big = Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
        val bigShort = min(outW, outH)
        if (bigShort <= targetShort) return big
        val scaled =
            Bitmap.createScaledBitmap(
                big,
                (outW.toLong() * targetShort / bigShort).toInt().coerceAtLeast(1),
                (outH.toLong() * targetShort / bigShort).toInt().coerceAtLeast(1),
                true,
            )
        if (scaled !== big) big.recycle()
        return scaled
    }

    companion object {
        private const val TAG = "SoundWaive"
        const val MODEL = "models/real_esrgan_general_x4v3.tflite"

        /** AOT-compiled for the Tensor G5 TPU by scripts/aot_compile_g5.py (optional). */
        const val MODEL_G5 = "models/real_esrgan_general_x4v3_Google_Tensor_G5.tflite"

        /** Changes whenever cached results would look different (new model or tiling). */
        const val VERSION = "esrgan-x4v3-1"
        const val TILE = 128
        const val SCALE = 4
        const val OUT = TILE * SCALE
        const val PAD = 8
        const val CORE = TILE - 2 * PAD

        /** Slower than this per tile on the "TPU" means it silently ran on the CPU. */
        private const val NPU_MAX_TILE_MS = 150L

        private var shared: AiUpscaler? = null
        private var tried = false

        /** The loaded upscaler, loading it on first use; null if no chip could run it. */
        @Synchronized
        fun get(context: Context): AiUpscaler? {
            if (!tried) {
                tried = true
                shared = load(context.applicationContext)
            }
            return shared
        }

        private fun channel(v: Float): Int = (v * 255f + .5f).toInt().coerceIn(0, 255)

        private fun hasAsset(context: Context, name: String): Boolean =
            try {
                context.assets.open(name).close()
                true
            } catch (_: IOException) {
                false
            }

        private fun attempt(
            context: Context,
            asset: String,
            accelerator: Accelerator,
            chip: String,
            npu: Boolean,
        ): AiUpscaler? {
            var env: Environment? = null
            var model: CompiledModel? = null
            return try {
                env =
                    if (npu) Environment.create(context, BuiltinNpuAcceleratorProvider(context))
                    else Environment.create(context)
                if (accelerator !in env.getAvailableAccelerators()) {
                    Log.i(TAG, "AI upscale: $chip not available")
                    env.close()
                    return null
                }
                model = CompiledModel.create(context.assets, asset, CompiledModel.Options(accelerator), env)
                val upscaler = AiUpscaler(env, model, chip)
                val ms = upscaler.timeOneTile()
                if (npu && ms > NPU_MAX_TILE_MS) {
                    // A runtime/dispatch version mismatch can "load" on the NPU but run on
                    // the CPU (hundreds of ms); then the GPU is the faster choice.
                    Log.w(TAG, "AI upscale: TPU tile took $ms ms, looks like CPU fallback; trying the GPU")
                    upscaler.closeAll()
                    return null
                }
                Log.i(TAG, "AI upscale runs on the $chip ($asset), $ms ms per tile")
                upscaler
            } catch (e: Throwable) {
                // Includes a missing or mismatched native library (UnsatisfiedLinkError) and
                // the Tensor service refusing the app (edgetpu_app_service error 16).
                Log.w(TAG, "AI upscale: $chip failed to load $asset", e)
                runCatching { model?.close() }
                runCatching { env?.close() }
                null
            }
        }

        private fun load(context: Context): AiUpscaler? {
            if (hasAsset(context, MODEL_G5))
                attempt(context, MODEL_G5, Accelerator.NPU, "TPU", npu = true)?.let { return it }
            if (!hasAsset(context, MODEL)) {
                Log.w(TAG, "AI upscale: $MODEL is missing; run scripts/fetch_models.sh")
                return null
            }
            return attempt(context, MODEL, Accelerator.GPU, "GPU", npu = false)
                ?: attempt(context, MODEL, Accelerator.CPU, "CPU", npu = false)
                ?: null.also { Log.w(TAG, "AI upscale unavailable; backgrounds stay as they are") }
        }

        /** Short cache-file-safe key for [text]. */
        fun key(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
                "%02x".format(it)
            }.take(24)
    }
}

/**
 * Export wrapper for a video background with the AI effect: each decoded frame (one copy,
 * the existing GPU read-back) is upscaled and cleaned, and the result is cached as a JPEG
 * per frame time under [cacheDir], so exporting the same clip again reads the cache instead
 * of running the model. Frames already as sharp as [targetShort] pass through unchanged.
 * After any model error the rest of the export uses the plain frames.
 */
class AiVideoBackground(
    private val video: VideoBackground,
    private val ai: AiUpscaler,
    private val targetShort: Int,
    private val cacheDir: File,
) : BackgroundSource {
    private var lastUs = Long.MIN_VALUE
    private var last: Bitmap? = null
    private var failed = false

    /** Frames that came out of the model or its cache (0 = the effect did nothing). */
    var improved = 0
        private set

    override fun frame(seconds: Float): Bitmap? {
        val raw = video.frame(seconds) ?: return null
        if (failed) return raw
        val us = video.shownUs
        if (us == Long.MIN_VALUE) return raw // nothing decoded yet
        if (us == lastUs) return last ?: raw
        lastUs = us
        val file = File(cacheDir, "$us.jpg")
        val cached: Bitmap? = if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        val image: Bitmap? = cached ?: fresh(raw, file, us)
        // No recycling: the GPU may still be drawing the previous frame's bitmap.
        last = image
        if (image != null) improved++
        return image ?: raw
    }

    private fun fresh(raw: Bitmap, file: File, us: Long): Bitmap? {
        val made =
            try {
                ai.enhance(raw, targetShort, opaque = true)
            } catch (e: Throwable) {
                Log.w("SoundWaive", "AI upscale failed on a video frame; using plain frames", e)
                failed = true
                null
            } ?: return null
        try {
            cacheDir.mkdirs()
            val temp = File(cacheDir, "$us.tmp")
            temp.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (!temp.renameTo(file)) temp.delete()
        } catch (e: Throwable) {
            Log.w("SoundWaive", "Could not cache an upscaled frame", e)
        }
        return made
    }

    override fun release() = video.release()

    companion object {
        /**
         * Cache folder for this clip at this size. Only the newest clip's folder is kept, so
         * the cache can't grow without bound.
         */
        fun cacheFor(root: File, uri: String, targetShort: Int, size: BackgroundMath.Size): File {
            val base = File(root, "ai-video")
            val name = AiUpscaler.key("$uri|$targetShort|${size.width}x${size.height}|${AiUpscaler.VERSION}")
            base.listFiles()?.forEach { if (it.name != name) it.deleteRecursively() }
            return File(base, name)
        }
    }
}
