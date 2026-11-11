package com.admoss.downloader

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/**
 * Deletes downloaded files from the phone's storage (not just from the app's list).
 * Files this install created are deleted at once. For files from an earlier install Android asks
 * the user to confirm, and this class shows that system confirmation through [pendingRequest].
 * An item is only removed from the library after its files are checked to be really gone.
 */
object DeleteController {
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    /** A system confirmation the screen must show. The result goes to [onRequestResult]. */
    var pendingRequest by mutableStateOf<IntentSender?>(null)

    private var waiting: List<HistoryItem> = emptyList()
    private var deletedItems = 0
    private val main = Handler(Looper.getMainLooper())

    fun urisOf(item: HistoryItem): List<String> =
        if (item.photoUris.isNotEmpty()) item.photoUris + listOfNotNull(item.musicUri) else listOf(item.uri)

    /** Call from the main thread. */
    fun delete(ctx: Context, all: List<HistoryItem>) {
        if (all.isEmpty() || busy) return
        // Videos the user added from the phone are only taken off the list: their files are never deleted
        val external = all.filter { it.external }
        if (external.isNotEmpty()) DownloadCenter.removeItems(ctx, external)
        val items = all.filter { !it.external }
        if (items.isEmpty()) {
            message = "Removed from the list. The files stay on your phone."
            return
        }
        busy = true
        message = null
        deletedItems = 0
        val app = ctx.applicationContext
        thread(name = "admoss-delete") {
            try {
                process(app, items, 0)
            } catch (e: Throwable) {
                finish(0, items.size, "Something went wrong while deleting.")
            }
        }
    }

    private fun exists(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    } catch (e: Throwable) {
        true // cannot tell: assume it is still there
    }

    private fun process(ctx: Context, items: List<HistoryItem>, round: Int) {
        val resolver = ctx.contentResolver
        val gone = ArrayList<HistoryItem>()
        val stuck = ArrayList<HistoryItem>()
        val needPermission = ArrayList<Uri>()
        var recoverable: IntentSender? = null

        for (item in items) {
            var allGone = true
            for (s in urisOf(item)) {
                val uri = Uri.parse(s)
                if (!exists(ctx, uri)) continue // already gone
                try {
                    resolver.delete(uri, null, null)
                } catch (e: RecoverableSecurityException) {
                    if (recoverable == null) recoverable = e.userAction.actionIntent.intentSender
                    needPermission.add(uri)
                } catch (e: SecurityException) {
                    needPermission.add(uri)
                } catch (e: Throwable) {
                    // could not delete this file
                }
                if (exists(ctx, uri)) allGone = false
            }
            if (allGone) gone.add(item) else stuck.add(item)
        }

        DownloadCenter.removeItems(ctx, gone)
        deletedItems += gone.size

        if (stuck.isNotEmpty() && round < 500) {
            val sender: IntentSender? =
                if (Build.VERSION.SDK_INT >= 30 && needPermission.isNotEmpty()) {
                    MediaStore.createDeleteRequest(resolver, needPermission).intentSender
                } else {
                    recoverable
                }
            if (sender != null) {
                waiting = stuck
                main.post { pendingRequest = sender }
                return
            }
        }
        finish(deletedItems, stuck.size, null)
    }

    /** Called by the screen with the answer to the system confirmation. */
    fun onRequestResult(ctx: Context, ok: Boolean) {
        pendingRequest = null
        val items = waiting
        waiting = emptyList()
        if (!ok) {
            finish(deletedItems, items.size, null)
            return
        }
        val app = ctx.applicationContext
        thread(name = "admoss-delete") {
            try {
                process(app, items, 1)
            } catch (e: Throwable) {
                finish(deletedItems, items.size, "Something went wrong while deleting.")
            }
        }
    }

    private fun finish(deleted: Int, kept: Int, error: String?) {
        main.post {
            busy = false
            message = error ?: when {
                kept == 0 && deleted == 1 -> "Deleted from your phone."
                kept == 0 -> "Deleted $deleted items from your phone."
                deleted == 0 -> "Nothing was deleted. $kept item(s) were kept."
                else -> "Deleted $deleted. $kept item(s) could not be deleted."
            }
        }
    }
}
