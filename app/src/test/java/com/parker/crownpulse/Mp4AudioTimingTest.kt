package com.parker.crownpulse

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class Mp4AudioTimingTest {
    private data class Atom(val kind: String, val bytes: ByteArray, val offset: Int)

    private fun atom(kind: String, bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { stream ->
                DataOutputStream(stream).use {
                    it.writeInt(bytes.size + 8)
                    it.writeBytes(kind)
                    it.write(bytes)
                }
            }
            .toByteArray()

    private fun atoms(bytes: ByteArray): List<Atom> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val result = mutableListOf<Atom>()
        while (input.hasRemaining()) {
            val start = input.position()
            val size = input.int
            val name = ByteArray(4).also { input.get(it) }.toString(Charsets.US_ASCII)
            result += Atom(name, ByteArray(size - 8).also { input.get(it) }, start)
        }
        return result
    }

    private fun header(duration: Long, scale: Int, track: Boolean, v1: Boolean): ByteArray =
        ByteBuffer.allocate(100)
            .apply {
                put(0, if (v1) 1.toByte() else 0.toByte())
                if (!track) putInt(if (v1) 20 else 12, scale)
                if (v1) putLong(if (track) 28 else 24, duration)
                else putInt(if (track) 20 else 16, duration.toInt())
            }
            .array()

    private fun track(kind: String, duration: Long, v1: Boolean): ByteArray {
        val handler = ByteArray(24)
        kind.toByteArray(Charsets.US_ASCII).copyInto(handler, 8)
        val media = atom("mdhd", header(100000, 48000, false, v1)) + atom("hdlr", handler)
        return atom("trak", atom("tkhd", header(duration, 1000, true, v1)) + atom("mdia", media))
    }

    private fun fixture(v1: Boolean = false, audio: Boolean = true): ByteArray {
        val media = atom("mdat", ByteArray(1024) { (it * 17).toByte() })
        val tracks = track("vide", 2000, v1) + if (audio) track("soun", 2050, v1) else byteArrayOf()
        return atom("ftyp", "isom0000".toByteArray()) +
            media +
            atom("moov", atom("mvhd", header(2050, 1000, false, v1)) + tracks)
    }

    private fun checkCorrection(v1: Boolean) {
        val file = Files.createTempFile("soundwaive-timing", ".mp4").toFile()
        try {
            val source = fixture(v1)
            file.writeBytes(source)
            Mp4AudioTiming.apply(file, 2048, 96000, 48000)
            val top = atoms(file.readBytes())
            val sourceMedia = atoms(source).single { it.kind == "mdat" }
            val media = top.single { it.kind == "mdat" }
            assertEquals(sourceMedia.offset, media.offset)
            assertArrayEquals(sourceMedia.bytes, media.bytes)
            assertTrue(top.any { it.kind == "free" })
            val movie = atoms(top.single { it.kind == "moov" }.bytes)
            val mvhd = ByteBuffer.wrap(movie.single { it.kind == "mvhd" }.bytes)
            assertEquals(2000L, if (v1) mvhd.getLong(24) else mvhd.getInt(16).toLong())
            val tracks = movie.filter { it.kind == "trak" }.map { atoms(it.bytes) }
            assertFalse(tracks[0].any { it.kind == "edts" })
            val tkhd = ByteBuffer.wrap(tracks[1].single { it.kind == "tkhd" }.bytes)
            assertEquals(2000L, if (v1) tkhd.getLong(28) else tkhd.getInt(20).toLong())
            val edits = atoms(tracks[1].single { it.kind == "edts" }.bytes)
            val entry = ByteBuffer.wrap(edits.single { it.kind == "elst" }.bytes)
            assertEquals(0, entry.int) // version and flags
            assertEquals(1, entry.int) // exactly one playable segment
            assertEquals(2000, entry.int) // complete two-second audible duration
            assertEquals(2048, entry.int) // skip encoder priming, retain beginning of music
            assertEquals(1, entry.short.toInt())
            assertEquals(0, entry.short.toInt())
            Mp4AudioTiming.apply(file, 2048, 96000, 48000)
            val rewritten = atoms(atoms(file.readBytes()).single { it.kind == "moov" }.bytes)
            val audio = atoms(rewritten.filter { it.kind == "trak" }[1].bytes)
            assertEquals(1, audio.count { it.kind == "edts" })
        } finally {
            file.delete()
        }
    }

    @Test fun preservesMediaOffsetsAndCorrectsPriming() = checkCorrection(false)

    @Test fun acceptsVersionOneDurationHeaders() = checkCorrection(true)

    @Test
    fun rejectsMissingAudioWithoutChangingFile() {
        val file = Files.createTempFile("soundwaive-noaudio", ".mp4").toFile()
        try {
            val original = fixture(audio = false)
            file.writeBytes(original)
            assertThrows(IllegalArgumentException::class.java) {
                Mp4AudioTiming.apply(file, 2048, 96000, 48000)
            }
            assertArrayEquals(original, file.readBytes())
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsTruncatedMetadataWithoutChangingFile() {
        val file = Files.createTempFile("soundwaive-truncated", ".mp4").toFile()
        try {
            val original = fixture().dropLast(10).toByteArray()
            file.writeBytes(original)
            assertThrows(IllegalArgumentException::class.java) {
                Mp4AudioTiming.apply(file, 2048, 96000, 48000)
            }
            assertArrayEquals(original, file.readBytes())
        } finally {
            file.delete()
        }
    }
}
