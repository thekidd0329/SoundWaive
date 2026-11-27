package com.parker.crownpulse

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.IOException

/** A running surface encoder and the actual display geometry selected for this export. */
internal class AvcEncoder(
    val codec: MediaCodec, val surface: Surface, val width: Int, val height: Int,
    val fps: Int, val rotated: Boolean, val notice: String?,
) {
    companion object {
        fun open(width: Int, height: Int, fps: Int, bitrate: Int): AvcEncoder {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
                .mapNotNull { info ->
                    val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                    if (caps == null || MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) null
                    else info.name to caps
                }
            val short = minOf(width, height)
            val sizes = listOf(short, 1080, 720, 360).distinct().filter { it <= short }
            val rates = listOf(fps, 30, 24).distinct().filter { it <= fps }
            for (size in sizes) {
                val w = width * size / short / 2 * 2
                val h = height * size / short / 2 * 2
                for (rate in rates) {
                    for (rotate in if (w == h) listOf(false) else listOf(false, true)) {
                        val encodedW = if (rotate) h else w
                        val encodedH = if (rotate) w else h
                        for ((name, caps) in encoders) {
                            // A broken vendor capability record must not block the other encoders.
                            val bits = runCatching {
                                val video = caps.videoCapabilities
                                if (!video.areSizeAndRateSupported(encodedW, encodedH, rate.toDouble())) null
                                else video.bitrateRange.clamp(bitrate)
                            }.getOrNull() ?: continue
                            // Preserve Release Pack's High-profile preference while retaining
                            // compatibility retries for codecs that refuse profile or color keys.
                            val high = runCatching {
                                caps.profileLevels.filter {
                                    it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                                }.maxOfOrNull { it.level }
                            }.getOrNull()
                            fun format(color: Boolean, profile: Boolean) =
                                MediaFormat.createVideoFormat(mime, encodedW, encodedH).apply {
                                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                                    setInteger(MediaFormat.KEY_BIT_RATE, bits)
                                    setInteger(MediaFormat.KEY_FRAME_RATE, rate)
                                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                                    if (color) {
                                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                                    }
                                    if (profile && high != null) {
                                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                                        setInteger(MediaFormat.KEY_LEVEL, high)
                                    }
                                }
                            val device = (if (high != null) start(name, format(true, true)) else null)
                                ?: start(name, format(true, false))
                                ?: (if (high != null) start(name, format(false, true)) else null)
                                ?: start(name, format(false, false))
                                ?: continue
                            val notice = if (size != short || rate != fps || bits != bitrate) {
                                "This device exported at ${w}×${h}, $rate fps and ${bits / 1000} kbps."
                            } else null
                            return AvcEncoder(device.first, device.second, w, h, rate, rotate, notice)
                        }
                    }
                }
            }
            throw IOException("This device could not start an H.264 video encoder for this aspect ratio.")
        }

        private fun start(name: String, format: MediaFormat): Pair<MediaCodec, Surface>? {
            var codec: MediaCodec? = null
            var surface: Surface? = null
            try {
                codec = MediaCodec.createByCodecName(name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                codec.start()
                return codec to surface
            } catch (e: Exception) {
                Log.w("SoundWaive", "$name rejected $format", e)
                runCatching { surface?.release() }
                runCatching { codec?.release() }
                return null
            }
        }
    }
}
