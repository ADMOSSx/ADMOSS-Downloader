package com.admoss.downloader

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/** Share the app, or share downloaded videos / series / photos with any other app (WhatsApp, Bluetooth...). */
object Share {
    private val main = Handler(Looper.getMainLooper())

    private fun open(ctx: Context, intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    /** Shares the download link of the app (instant). */
    fun appLink(ctx: Context) {
        val repo = BuildConfig.UPDATE_REPO.ifBlank { "ADMOSSx/ADMOSS-Downloader" }
        val text = "ADMOSS Downloader: download YouTube and TikTok videos\nhttps://github.com/$repo/releases/latest"
        open(ctx, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share app")
    }

    /** Shares the APK file itself (works with no internet: Bluetooth, Nearby Share, WhatsApp...). */
    fun appFile(ctx: Context) {
        val app = ctx.applicationContext
        thread(name = "admoss-share-apk") {
            try {
                val dir = File(app.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "ADMOSS-Downloader.apk")
                File(app.applicationInfo.sourceDir).copyTo(out, overwrite = true)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", out)
                val i = Intent(Intent.ACTION_SEND)
                    .setType("application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                main.post { open(app, i, "Share app file") }
            } catch (e: Throwable) {
                main.post { appLink(app) } // fall back to the link
            }
        }
    }

    /** Shares one or many downloads (a video, a whole playlist/series, photo posts). No copying. */
    fun items(ctx: Context, items: List<HistoryItem>) {
        val uris: List<Uri> = items
            .flatMap { if (it.photoUris.isNotEmpty()) it.photoUris else listOf(it.uri) }
            .filter { it.isNotBlank() }
            .map { Uri.parse(it) }
        if (uris.isEmpty()) return

        val mime = when {
            items.all { it.photoUris.isNotEmpty() } -> "image/*"
            items.all { it.audio && it.photoUris.isEmpty() } -> "audio/*"
            items.all { !it.audio && it.photoUris.isEmpty() } -> "video/*"
            else -> "*/*"
        }
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newRawUri("", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        open(ctx, i, if (uris.size == 1) "Share" else "Share ${uris.size} files")
    }
}
