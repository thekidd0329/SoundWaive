package com.parker.crownpulse

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Temporary files under the app's [android.content.Context.getCacheDir] that Export ›
 * **Clear cache** may remove:
 * - decoded import audio (`track-*`), except the song currently open
 * - AI-upscaled video frames (`ai-video/`)
 * - leftover AAC timing probes (`aac-timing-*`, including under `exports/`)
 * - export scratch in `exports/` that is not a finished MP4 (`audio-*.m4a`, `*.tmp`)
 *
 * Nothing else in the cache is touched. Finished output MP4s (in `exports/`, or anywhere
 * else) are never deleted. User data lives in [android.content.Context.getFilesDir] and
 * SharedPreferences (looks, artwork, background, AI photo, demo) and is never touched
 * here. Cleanup runs only when the user taps Clear cache, never automatically at launch
 * or after an export.
 */
object AppCache {
    /**
     * Bytes [clean] would free under [root], leaving [keep] (the current track folder, or
     * a file inside it) and every finished MP4. Missing paths are 0. Symbolic links are
     * not followed.
     */
    fun size(root: File, keep: File? = null): Long {
        if (!root.isDirectory) return if (root.isFile && reclaimable(root, root, null)) root.length() else 0L
        val rootPath = canonical(root) ?: return 0L
        val keepPath = keep?.let { canonical(it) }?.takeIf { inside(it, rootPath) }
        var total = 0L
        val stack = ArrayDeque<File>()
        for (child in root.listFiles() ?: return 0L) stack.add(child)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            val path = canonical(file) ?: continue
            if (!inside(path, rootPath)) continue
            if (keepPath != null && sameOrAncestor(path, keepPath)) continue
            if (file.isDirectory) {
                val children = file.listFiles() ?: continue
                for (child in children) stack.add(child)
            } else if (file.isFile && reclaimable(file, root, rootPath)) {
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
     * Deletes only the allowlisted temp directories and files under [root]. [keep] is the
     * current track folder when one is loaded (or a file inside it). [keep] is ignored if
     * it is missing or not inside [root]. MP4 files and anything not on the allowlist are
     * left alone. Never call this while an export is running.
     */
    fun clean(root: File, keep: File? = null) {
        if (!root.isDirectory) return
        val rootPath = canonical(root) ?: return
        val keepPath = keep?.let { canonical(it) }?.takeIf { inside(it, rootPath) }
        for (child in root.listFiles() ?: return) {
            val childPath = canonical(child) ?: continue
            if (!inside(childPath, rootPath)) continue
            if (keepPath != null && sameOrAncestor(childPath, keepPath)) continue
            when {
                child.name.startsWith("track-") ||
                    child.name == "ai-video" ||
                    child.name.startsWith("aac-timing-") -> deleteSkippingMp4(child)
                child.name == "exports" && child.isDirectory -> cleanExports(child)
            }
        }
    }

    /** Temp AAC and probe leftovers inside `exports/`. Finished MP4s stay. */
    private fun cleanExports(dir: File) {
        for (child in dir.listFiles() ?: return) {
            when {
                child.isDirectory && child.name.startsWith("aac-timing-") ->
                    deleteSkippingMp4(child)
                child.isFile && isExportsTemp(child) -> child.delete()
            }
        }
    }

    private fun isExportsTemp(file: File): Boolean {
        if (isMp4(file)) return false
        val name = file.name
        return name.endsWith(".tmp") || (name.startsWith("audio-") && name.endsWith(".m4a"))
    }

    /** Whether [file] would be removed by [clean], ignoring [keep]. */
    private fun reclaimable(file: File, root: File, rootPath: File?): Boolean {
        if (isMp4(file)) return false
        val path = canonical(file) ?: return false
        val base = rootPath ?: canonical(root) ?: return false
        if (!inside(path, base)) return false
        val rel = relativeTo(path, base) ?: return false
        val top = rel.substringBefore(File.separator)
        return when {
            top.startsWith("track-") || top == "ai-video" || top.startsWith("aac-timing-") -> true
            top == "exports" -> {
                val rest = rel.removePrefix("exports").trimStart(File.separatorChar)
                rest.startsWith("aac-timing-") || isExportsTemp(file)
            }
            else -> false
        }
    }

    private fun relativeTo(path: File, root: File): String? {
        val pathText = path.path
        val rootText = root.path
        return when {
            pathText == rootText -> ""
            pathText.startsWith(rootText + File.separator) ->
                pathText.substring(rootText.length + 1)
            else -> null
        }
    }

    private fun isMp4(file: File) = file.name.endsWith(".mp4", ignoreCase = true)

    /** Deletes a file or folder, but never a finished MP4. */
    private fun deleteSkippingMp4(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteSkippingMp4(it) }
            file.delete()
        } else if (!isMp4(file)) {
            file.delete()
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
