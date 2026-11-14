package com.admoss.downloader

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlin.concurrent.thread

/** Episodes turned into things that can be sent, and how many of them could not be read. */
class OmsConverted(val items: List<OmsMediaItem>, val missing: Int)

/**
 * Sending from the Series tab: whole series, one season, or single episodes.
 * The other phone gets them in its own Series tab, with the same name, seasons and order.
 */
object OmsSeries {

    private fun canRead(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (t: Throwable) {
        false
    }

    /** Blocking: call from a background thread. Files that no longer exist are counted in [OmsConverted.missing]. */
    fun toItems(ctx: Context, list: List<HistoryItem>): OmsConverted {
        val out = ArrayList<OmsMediaItem>()
        var missing = 0
        for (h in list) {
            val series = h.playlist.orEmpty()
            if (!h.external || series.isBlank() || h.photoUris.isNotEmpty()) continue
            val uri = Uri.parse(h.uri)
            if (!canRead(ctx, uri)) {
                missing += 1
                continue
            }
            var name = ""
            var size = 0L
            try {
                ctx.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: ""
                        size = if (c.isNull(1)) 0L else c.getLong(1)
                    }
                }
            } catch (t: Throwable) {
                // use what the library knows
            }
            if (size <= 0L) size = h.size
            if (size <= 0L) {
                try {
                    size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                } catch (t: Throwable) {
                    size = 0L
                }
            }
            if (size <= 0L) {
                missing += 1
                continue
            }
            val kind = if (h.audio) OmsProtocol.KIND_MUSIC else OmsProtocol.KIND_VIDEO
            val type = ctx.contentResolver.getType(uri) ?: ""
            val mime = if (OmsProtocol.kindOfMime(type) == kind) type else OmsProtocol.fallbackMime(kind)
            if (name.isBlank()) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (h.audio) "mp3" else "mp4"
                name = h.title + "." + ext
            }
            out.add(
                OmsMediaItem(
                    uri = uri,
                    name = name,
                    size = size,
                    kind = kind,
                    mime = mime,
                    durationMs = 0L,
                    series = series,
                    season = h.season.orEmpty(),
                    order = h.index
                )
            )
        }
        return OmsConverted(out, missing)
    }

    /** The "Send" buttons of the Series tab: picks the episodes, opens the review screen in the Share tab. */
    fun sendFromSeries(ctx: Context, folder: String, season: String?) {
        val app = ctx.applicationContext
        val busy = OmsCenter.phase
        if (busy == OmsPhase.TRANSFERRING || busy == OmsPhase.JOINING ||
            busy == OmsPhase.CONNECTING || busy == OmsPhase.REQUEST
        ) {
            Toast.makeText(app, "A transfer is running. Finish it first.", Toast.LENGTH_SHORT).show()
            return
        }
        thread(name = "oms-series") {
            val list = DownloadCenter.phoneItems.filter {
                it.playlist == folder && (season == null || it.season == season)
            }
            val converted = toItems(app, DownloadCenter.playOrder(list))
            Handler(Looper.getMainLooper()).post {
                if (converted.items.isEmpty()) {
                    Toast.makeText(app, "Nothing to send: the files are missing from this phone.", Toast.LENGTH_LONG).show()
                } else {
                    // leave a finished or waiting session first, then start a fresh selection
                    if (OmsCenter.phase != OmsPhase.IDLE) OmsCenter.finish(app)
                    OmsCenter.selected = converted.items
                    OmsCenter.step = 2
                    OmsCenter.goShare = true
                    if (converted.missing > 0) {
                        Toast.makeText(app, "${converted.missing} missing files were left out.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
