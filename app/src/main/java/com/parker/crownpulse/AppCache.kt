package com.parker.crownpulse

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Temporary files under the app's [android.content.Context.getCacheDir]: decoded import audio
 * (`track-*`), in-progress export MP4s (`exports/`), AI-upscaled video frames (`ai-video/`)
 * and leftover AAC timing probes (`aac-timing-*`).
 *
 * User data lives in [android.content.Context.getFilesDir] and SharedPreferences (looks,
 * artwork, background, AI photo, demo) and is never touched here.
 */
object AppCache {
    /**
     * Bytes used by [root] and everything under it. Missing paths are 0. Symbolic links are
     * not followed.
     */
    fun size(root: File): Long {
        if (!root.exists()) return 0L
        if (root.isFile) return root.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (file.isDirectory) {
                val children = file.listFiles() ?: continue
                for (child in children) stack.add(child)
            } else if (file.isFile) {
                total += file.length()
            }
        }
        return total
    }

    /** A short size for the Storage section, in decimal units like Android's Storage screen. */
    fun format(bytes: Long): String {
        val value = abs(bytes)
        return when {
            value < 1_000L -> "$bytes B"
            value < 1_000_000L -> "${bytes / 1_000L} KB"
            value < 1_000_000_000L -> {
                val mb = bytes / 1_000_000.0
                if (abs(mb) >= 10) "${mb.toInt()} MB" else String.format(Locale.US, "%.1f MB", mb)
            }
            else -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        }
    }

    /**
     * Deletes everything in [root] except [keep], which is the current track folder when one
     * is loaded (or a file inside it). [keep] is ignored if it is missing or not inside
     * [root], so this never reaches outside the cache.
     *
     * Call when no export is writing these files: at launch, after an export job ends, or
     * from Export › Clear cache.
     */
    fun clean(root: File, keep: File? = null) {
        if (!root.isDirectory) return
        val rootPath = canonical(root) ?: return
        val keepPath = keep?.let { canonical(it) }?.takeIf { inside(it, rootPath) }
        for (child in root.listFiles() ?: return) {
            val childPath = canonical(child) ?: continue
            if (keepPath != null && sameOrAncestor(childPath, keepPath)) continue
            child.deleteRecursively()
        }
    }

    private fun canonical(file: File): File? =
        try {
            file.canonicalFile
        } catch (_: Exception) {
            try {
                file.absoluteFile.normalize()
            } catch (_: Exception) {
                null
            }
        }

    /** True when [path] is [root] or a file under it. */
    private fun inside(path: File, root: File): Boolean {
        val pathText = path.path
        val rootText = root.path
        return pathText == rootText || pathText.startsWith(rootText + File.separator)
    }

    /** True when [child] is [keep] or the folder that contains it. */
    private fun sameOrAncestor(child: File, keep: File): Boolean {
        val childText = child.path
        val keepText = keep.path
        return childText == keepText || keepText.startsWith(childText + File.separator)
    }
}
