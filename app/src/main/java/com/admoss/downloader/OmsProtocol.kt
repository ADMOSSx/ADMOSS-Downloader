package com.admoss.downloader

import android.net.Uri

/** A photo, video or song in a transfer. [uri] is set on the sending phone only. */
class OmsFile(
    val uri: Uri?,
    val name: String,
    val size: Long,
    /** 0 = photo, 1 = video, 2 = music */
    val kind: Int,
    val mime: String,
    /** Set when the file is an episode from the Series tab. */
    val series: String = "",
    val season: String = "",
    val order: Int = 0,
    /** A movie of the Series tab (it has no series name). */
    val movie: Boolean = false
)

/** A file the user can pick on the sending phone (picker, review screen and transfer header). */
class OmsMediaItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val kind: Int,
    val mime: String,
    val durationMs: Long,
    /** Set when the file is an episode from the Series tab. */
    val series: String = "",
    val season: String = "",
    val order: Int = 0,
    /** A movie of the Series tab (it has no series name). */
    val movie: Boolean = false
)

/**
 * The tiny protocol between two phones (over one TCP connection):
 *
 *  sender   -> MAGIC, code, sender name, number of files, then per file:
 *              name, size, kind, mime, series, season, order, movie
 *  receiver -> 1 (accepted), 0 (declined) or 2 (wrong code)
 *  receiver -> one yes/no per file: "send it" (no = already on this phone, skip it)
 *  sender   -> the raw bytes of every file that was asked for, one after the other
 *  receiver -> 1 (everything saved)
 */
object OmsProtocol {
    const val MAGIC = "ADMOSS-OMS-3"
    const val KIND_PHOTO = 0
    const val KIND_VIDEO = 1
    const val KIND_MUSIC = 2
    const val MAX_FILES = 3000
    const val MAX_FILE_BYTES = 8L * 1024L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 20L * 1024L * 1024L * 1024L
    const val CHUNK = 256 * 1024

    /** Photos, videos and music only. Anything else gives -1 and is never sent or saved. */
    fun kindOfMime(mime: String?): Int = when {
        mime == null -> -1
        mime.startsWith("image/") -> KIND_PHOTO
        mime.startsWith("video/") -> KIND_VIDEO
        mime.startsWith("audio/") -> KIND_MUSIC
        else -> -1
    }

    fun fallbackMime(kind: Int): String = when (kind) {
        KIND_PHOTO -> "image/jpeg"
        KIND_VIDEO -> "video/mp4"
        else -> "audio/mpeg"
    }

    /** Returns the next total, or null when a size is invalid or the aggregate cap is exceeded. */
    fun addToTotal(current: Long, next: Long): Long? {
        if (current < 0L || current > MAX_TOTAL_BYTES || next < 0L || next > MAX_FILE_BYTES) return null
        if (next > MAX_TOTAL_BYTES - current) return null
        return current + next
    }

    /** A safe file name from another phone: no folders, no strange characters. */
    fun cleanName(raw: String): String {
        val n = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').take(120)
        return n.ifBlank { "file" }
    }
}
