package com.parker.crownpulse

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** MP4 edit lists remove AAC priming without discarding the first audible samples. */
object Mp4AudioTiming {
    private data class Box(val type: String, val data: ByteArray)

    private fun uint(b: ByteArray, at: Int) =
        ByteBuffer.wrap(b, at, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL

    private fun children(b: ByteArray): List<Box> {
        val result = ArrayList<Box>()
        var at = 0
        while (at < b.size) {
            require(at + 8 <= b.size) { "Incomplete MP4 metadata" }
            val size = uint(b, at).toInt()
            require(size >= 8 && size <= b.size - at) { "Invalid MP4 metadata" }
            result.add(
                Box(String(b, at + 4, 4, Charsets.US_ASCII), b.copyOfRange(at + 8, at + size))
            )
            at += size
        }
        return result
    }

    private fun pack(boxes: List<Box>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            boxes.forEach {
                out.writeInt(it.data.size + 8)
                out.writeBytes(it.type)
                out.write(it.data)
            }
        }
        return bytes.toByteArray()
    }

    private fun duration(header: ByteArray, value: Long, trackHeader: Boolean): ByteArray {
        val b = header.copyOf()
        val version1 = b[0].toInt() == 1
        val offset =
            if (trackHeader) {
                if (version1) 28 else 20
            } else {
                if (version1) 24 else 16
            }
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        if (version1) buf.putLong(offset, value) else buf.putInt(offset, value.toInt())
        return b
    }

    fun apply(file: File, encoderDelay: Int, audibleFrames: Long, sampleRate: Int) {
        RandomAccessFile(file, "rw").use { f ->
            var moovAt = -1L
            var oldSize = 0L
            var payload: ByteArray? = null
            while (f.filePointer + 8 <= f.length()) {
                val at = f.filePointer
                var size = f.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also { f.readFully(it) }
                var header = 8
                if (size == 1L) {
                    size = f.readLong()
                    header = 16
                }
                if (size == 0L) size = f.length() - at
                require(size >= header && at + size <= f.length()) { "Incomplete MP4" }
                if (String(type, Charsets.US_ASCII) == "moov") {
                    require(size - header < 32 * 1024 * 1024) { "MP4 metadata is too large" }
                    moovAt = at
                    oldSize = size
                    payload = ByteArray((size - header).toInt()).also { f.readFully(it) }
                    break
                }
                f.seek(at + size)
            }
            require(moovAt >= 0 && oldSize >= 8) { "MP4 timing metadata missing" }
            val movie = children(payload!!)
            val mvhd = movie.first { it.type == "mvhd" }.data
            val movieScale = uint(mvhd, if (mvhd[0].toInt() == 1) 20 else 12)
            var updated = false
            val replacement = movie.map { box ->
                if (box.type != "trak") box
                else {
                    val track = children(box.data)
                    val mdia = children(track.first { it.type == "mdia" }.data)
                    val handler = mdia.first { it.type == "hdlr" }.data
                    if (String(handler, 8, 4, Charsets.US_ASCII) != "soun") box
                    else {
                        val mdhd = mdia.first { it.type == "mdhd" }.data
                        val audioScale = uint(mdhd, if (mdhd[0].toInt() == 1) 20 else 12)
                        val movieFrames = (audibleFrames * movieScale + sampleRate / 2) / sampleRate
                        val edit =
                            ByteBuffer.allocate(20)
                                .order(ByteOrder.BIG_ENDIAN)
                                .putInt(0)
                                .putInt(1)
                                .putInt(movieFrames.toInt())
                                .putInt((encoderDelay * audioScale / sampleRate).toInt())
                                .putShort(1)
                                .putShort(0)
                                .array()
                        val adjusted =
                            track
                                .filter { it.type != "edts" }
                                .map {
                                    if (it.type == "tkhd")
                                        Box(it.type, duration(it.data, movieFrames, true))
                                    else it
                                }
                                .toMutableList()
                        adjusted.add(1, Box("edts", pack(listOf(Box("elst", edit)))))
                        updated = true
                        Box("trak", pack(adjusted))
                    }
                }
            }
            require(updated) { "MP4 audio track missing" }
            val longestTrack =
                replacement
                    .filter { it.type == "trak" }
                    .maxOf { track ->
                        val header = children(track.data).first { it.type == "tkhd" }.data
                        if (header[0].toInt() == 1)
                            ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getLong(28)
                        else uint(header, 20)
                    }
            val timedMovie = replacement.map {
                if (it.type == "mvhd") Box(it.type, duration(it.data, longestTrack, false)) else it
            }
            val newMoov = pack(listOf(Box("moov", pack(timedMovie))))
            // Append metadata, then mark the old metadata as free space. Media offsets remain
            // valid.
            f.seek(f.length())
            f.write(newMoov)
            f.fd.sync()
            f.seek(moovAt + 4)
            f.writeBytes("free")
            f.fd.sync()
        }
    }
}
