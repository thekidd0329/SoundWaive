package com.parker.crownpulse

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(parent: File, name: String, bytes: Int): File =
        File(parent, name).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun sizeCountsOnlyAllowlistedTemps() {
        val root = tmp.newFolder("cache")
        file(File(root, "ai-video/clip"), "1.jpg", 100)
        file(File(root, "ai-video/clip"), "2.jpg", 50)
        file(File(root, "track-1"), "audio.pcm", 20)
        file(File(root, "exports"), "audio-1.m4a", 8)
        file(File(root, "exports"), "CrownPulse-1.mp4", 80)
        file(root, "other.mp4", 40)
        file(File(root, "unknown"), "note.txt", 7)
        assertEquals(178L, AppCache.size(root))
        assertEquals(158L, AppCache.size(root, File(root, "track-1")))
        assertEquals(0L, AppCache.size(File(root, "missing")))
    }

    @Test
    fun formatUsesDecimalUnits() {
        assertEquals("0 B", AppCache.format(0))
        assertEquals("512 B", AppCache.format(512))
        assertEquals("2 KB", AppCache.format(2_000))
        assertEquals("1.5 MB", AppCache.format(1_500_000))
        assertEquals("640 MB", AppCache.format(640_000_000))
        assertEquals("1.2 GB", AppCache.format(1_200_000_000))
    }

    @Test
    fun cleanRemovesAllowlistedTempsButKeepsTheCurrentTrack() {
        val cache = tmp.newFolder("cache")
        val files = tmp.newFolder("files")
        file(files, "artwork.png", 40)
        file(File(files, "demo"), "generated-demo-v1.m4a", 30)
        file(File(cache, "ai-video/clip"), "frame.jpg", 200)
        file(File(cache, "aac-timing-old"), "probe.pcm", 10)
        file(File(cache, "exports"), "audio-1.m4a", 8)
        file(File(cache, "exports/aac-timing-probe"), "probe.m4a", 4)
        file(File(cache, "track-old"), "audio.pcm", 15)
        val current = File(cache, "track-current").also { file(it, "audio.pcm", 25) }

        AppCache.clean(cache, current)

        assertTrue(File(current, "audio.pcm").isFile)
        assertEquals(25L, File(current, "audio.pcm").length())
        assertFalse(File(cache, "ai-video").exists())
        assertFalse(File(cache, "aac-timing-old").exists())
        assertFalse(File(cache, "exports/audio-1.m4a").exists())
        assertFalse(File(cache, "exports/aac-timing-probe").exists())
        assertFalse(File(cache, "track-old").exists())
        assertTrue(File(files, "artwork.png").isFile)
        assertTrue(File(files, "demo/generated-demo-v1.m4a").isFile)
        assertEquals(0L, AppCache.size(cache, current))
    }

    @Test
    fun cleanNeverDeletesFinishedMp4sOrUnlistedFiles() {
        val cache = tmp.newFolder("cache")
        val current = File(cache, "track-current").also { file(it, "audio.pcm", 25) }
        val exportMp4 = file(File(cache, "exports"), "CrownPulse-1.mp4", 80)
        val rootMp4 = file(cache, "saved.mp4", 40)
        val stray = file(cache, "stray.tmp", 5)
        val unknown = file(File(cache, "unknown"), "clip.mp4", 30)
        file(File(cache, "exports"), "audio-1.m4a", 8)
        file(File(cache, "ai-video/clip"), "frame.jpg", 200)

        AppCache.clean(cache, current)

        assertTrue(exportMp4.isFile)
        assertEquals(80L, exportMp4.length())
        assertTrue(rootMp4.isFile)
        assertTrue(stray.isFile)
        assertTrue(unknown.isFile)
        assertTrue(File(current, "audio.pcm").isFile)
        assertFalse(File(cache, "exports/audio-1.m4a").exists())
        assertFalse(File(cache, "ai-video").exists())
        assertTrue(File(cache, "exports").isDirectory)
    }

    @Test
    fun cleanKeepsTheTrackWhenGivenAFileInsideIt() {
        val cache = tmp.newFolder("cache")
        val current = File(cache, "track-current").also { file(it, "audio.pcm", 25) }
        file(File(cache, "ai-video/clip"), "frame.jpg", 200)
        AppCache.clean(cache, File(current, "audio.pcm"))
        assertTrue(File(current, "audio.pcm").isFile)
        assertFalse(File(cache, "ai-video").exists())
    }

    @Test
    fun cleanIgnoresAKeepPathOutsideTheCache() {
        val cache = tmp.newFolder("cache")
        val files = tmp.newFolder("files")
        file(File(cache, "ai-video/clip"), "frame.jpg", 200)
        file(files, "artwork.png", 40)
        AppCache.clean(cache, files)
        assertFalse(File(cache, "ai-video").exists())
        assertTrue(File(files, "artwork.png").isFile)
    }

    @Test
    fun cleanWithNoKeepRemovesStaleTracksButNotMp4s() {
        val cache = tmp.newFolder("cache")
        file(File(cache, "track-1"), "audio.pcm", 25)
        file(File(cache, "ai-video/clip"), "frame.jpg", 200)
        file(File(cache, "exports"), "CrownPulse-1.mp4", 80)
        AppCache.clean(cache)
        assertFalse(File(cache, "track-1").exists())
        assertFalse(File(cache, "ai-video").exists())
        assertTrue(File(cache, "exports/CrownPulse-1.mp4").isFile)
    }
}
