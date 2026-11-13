package com.admoss.downloader

import android.net.Uri

/** A photo, video or song in a transfer. [uri] is set on the sending phone only. */
class OmsFile(
    val uri: Uri?,
    val name: String,
    val size: Long,
    /** 0 = photo, 1 = video, 2 = music */
    val kind: Int,
    val mime: String
)

/** A file the user can pick on the sending phone (picker, review screen and transfer header). */
class OmsMediaItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val kind: Int,
    val mime: String,
    val durationMs: Long
)

/**
 * The tiny protocol between two phones (over one TCP connection):
 *
 *  sender   -> MAGIC, code, sender name, number of files, then per file: name, size, kind, mime
 *  receiver -> 1 (accepted), 0 (declined) or 2 (wrong code)
 *  sender   -> the raw bytes of every file, one after the other
 *  receiver -> 1 (everything saved)
 */
object OmsProtocol {
    const val MAGIC = "ADMOSS-OMS-1"
    const val KIND_PHOTO = 0
    const val KIND_VIDEO = 1
    const val KIND_MUSIC = 2
    const val MAX_FILES = 3000
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

    /** A safe file name from another phone: no folders, no strange characters. */
    fun cleanName(raw: String): String {
        val n = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').take(120)
        return n.ifBlank { "file" }
    }
}
