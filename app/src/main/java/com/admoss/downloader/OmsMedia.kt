package com.admoss.downloader

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** Reads the photos, videos and songs on this phone (newest first). */
object OmsMedia {

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** Blocking: call from a background thread. [kind]: 0 photos, 1 videos, 2 music. */
    fun load(ctx: Context, kind: Int, limit: Int = 2000): List<OmsMediaItem> {
        val collection = when (kind) {
            OmsProtocol.KIND_PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            OmsProtocol.KIND_VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        val hasDuration = kind != OmsProtocol.KIND_PHOTO
        val projection = if (hasDuration) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DURATION
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
            )
        }
        val out = ArrayList<OmsMediaItem>()
        try {
            ctx.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iDur = if (hasDuration) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
                while (c.moveToNext() && out.size < limit) {
                    val size = c.getLong(iSize)
                    if (size <= 0L) continue
                    val mime = c.getString(iMime) ?: ""
                    // a song reported as "video/mp4" (or similar) is sent with a mime that matches its kind
                    val safeMime = if (OmsProtocol.kindOfMime(mime) == kind) mime else OmsProtocol.fallbackMime(kind)
                    out.add(
                        OmsMediaItem(
                            uri = ContentUris.withAppendedId(collection, c.getLong(iId)),
                            name = c.getString(iName) ?: "file",
                            size = size,
                            kind = kind,
                            mime = safeMime,
                            durationMs = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            // no permission or no access: the list stays as it is
        }
        return out
    }
}
