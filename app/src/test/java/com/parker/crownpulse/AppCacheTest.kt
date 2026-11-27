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
    fun sizeAddsNestedFilesAndIgnoresMissing() {
        val root = tmp.newFolder("cache")
        file(File(root, "ai-video/clip"), "1.jpg", 100)
        file(File(root, "ai-video/clip"), "2.jpg", 50)
        file(File(root, "track-1"), "audio.pcm", 20)
        assertEquals(170L, AppCache.size(root))
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
    fun cleanRemovesLeftoversButKeepsTheCurrentTrack() {
        val cache = tmp.newFolder("cache")
        val files = tmp.newFolder("files")
        file(files, "artwork.png", 40)
        file(File(files, "demo"), "generated-demo-v1.m4a", 30)
        file(File(cache, "ai-video/clip"), "frame.jpg", 200)
        file(File(cache, "aac-timing-old"), "probe.pcm", 10)
        file(File(cache, "exports"), "CrownPulse-1.mp4", 80)
        file(File(cache, "track-old"), "audio.pcm", 15)
        val current = File(cache, "track-current").also { file(it, "audio.pcm", 25) }
        File(cache, "stray.tmp").writeBytes(ByteArray(5))

        AppCache.clean(cache, current)

        assertTrue(File(current, "audio.pcm").isFile)
        assertEquals(25L, File(current, "audio.pcm").length())
        assertFalse(File(cache, "ai-video").exists())
        assertFalse(File(cache, "aac-timing-old").exists())
        assertFalse(File(cache, "exports").exists())
        assertFalse(File(cache, "track-old").exists())
        assertFalse(File(cache, "stray.tmp").exists())
        // filesDir is a sibling, not a child of cache, and must be left alone.
        assertTrue(File(files, "artwork.png").isFile)
        assertTrue(File(files, "demo/generated-demo-v1.m4a").isFile)
        assertEquals(25L, AppCache.size(cache))
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
    fun cleanWithNoKeepEmptiesTheCache() {
        val cache = tmp.newFolder("cache")
        file(File(cache, "track-1"), "audio.pcm", 25)
        file(File(cache, "ai-video/clip"), "frame.jpg", 200)
        AppCache.clean(cache)
        assertEquals(0, cache.listFiles()?.size ?: 0)
    }
}
