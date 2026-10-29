package com.admoss.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(val title: String, val uri: String, val audio: Boolean)

enum class Status { WAITING, DOWNLOADING, DONE, FAILED, CANCELLED }

/** One download in the queue. */
class QueueItem(
    val id: Long,
    val url: String,
    val title: String,
    val audio: Boolean,
    val height: Int?
) {
    var status by mutableStateOf(Status.WAITING)
    var progress by mutableStateOf(0f) // 0..1
    var eta by mutableStateOf(-1L)
    var stage by mutableStateOf("Waiting in queue")
    var error by mutableStateOf<String?>(null)

    @Volatile
    var cancelled = false
}

/**
 * Process-wide download state. The foreground service works through the queue,
 * the Compose UI reads from it, so everything survives rotation and leaving the app.
 */
object DownloadCenter {

    val queue = mutableStateListOf<QueueItem>()
    var history by mutableStateOf<List<HistoryItem>>(emptyList())

    @Volatile
    private var currentPid: String? = null

    @Volatile
    private var currentItem: QueueItem? = null

    private var loaded = false
    private var lastId = 0L

    val activeCount: Int
        get() = queue.count { it.status == Status.WAITING || it.status == Status.DOWNLOADING }

    val finishedCount: Int
        get() = queue.count {
            it.status == Status.DONE || it.status == Status.FAILED || it.status == Status.CANCELLED
        }

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        history = loadHistory(ctx)
    }

    @Synchronized
    fun newId(): Long = ++lastId

    fun enqueue(items: List<QueueItem>) {
        queue.addAll(items)
    }

    fun nextWaiting(): QueueItem? = queue.firstOrNull { it.status == Status.WAITING }

    fun hasWaiting(): Boolean = nextWaiting() != null

    fun cancelItem(item: QueueItem) {
        when (item.status) {
            Status.WAITING -> {
                item.cancelled = true
                item.status = Status.CANCELLED
                item.stage = "Cancelled"
            }
            Status.DOWNLOADING -> {
                item.cancelled = true
                item.stage = "Cancelling…"
                if (currentItem === item) {
                    currentPid?.let {
                        try {
                            YoutubeDL.getInstance().destroyProcessById(it)
                        } catch (e: Throwable) {
                            // ignore
                        }
                    }
                }
            }
            else -> {}
        }
    }

    fun cancelAll() {
        queue.toList().forEach { cancelItem(it) }
    }

    fun clearFinished() {
        queue.removeAll {
            it.status == Status.DONE || it.status == Status.FAILED || it.status == Status.CANCELLED
        }
    }

    fun removeHistory(ctx: Context, item: HistoryItem) {
        history = history.filter { it.uri != item.uri }
        saveHistory(ctx)
    }

    /**
     * Blocking: call from a background thread (the service does).
     * Returns the saved file, or null if it failed or was cancelled.
     */
    fun runItem(ctx: Context, item: QueueItem, onNotify: (Int, String) -> Unit): HistoryItem? {
        val id = "admoss-${item.id}"
        currentPid = id
        currentItem = item
        item.status = Status.DOWNLOADING
        item.stage = "Starting…"
        item.progress = 0f
        item.eta = -1L

        val tmp = File(ctx.cacheDir, "dl/$id").apply { mkdirs() }
        var result: HistoryItem? = null

        try {
            var waited = 0
            while (!Engine.isReady && !Engine.isFailed && waited < 120) {
                Thread.sleep(500)
                waited++
            }
            if (!Engine.isReady) throw Exception("Download engine is not ready")
            if (item.cancelled) {
                item.status = Status.CANCELLED
                item.stage = "Cancelled"
                return null
            }

            val req = YoutubeDLRequest(item.url)
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")
            req.addOption("-o", "${tmp.absolutePath}/%(title).80s.%(ext)s")

            if (item.audio) {
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "192K")
            } else {
                // Prefer H.264 + AAC so the file plays everywhere
                val h = item.height
                val sort = if (h != null) {
                    "res:$h,vcodec:h264,acodec:aac"
                } else {
                    "vcodec:h264,acodec:aac"
                }
                req.addOption("-S", sort)
                req.addOption("--merge-output-format", "mp4")
            }

            var lastPct = -1
            YoutubeDL.getInstance().execute(req, id) { p, etaSec, line ->
                if (p >= 0f) item.progress = (p / 100f).coerceIn(0f, 1f)
                item.eta = etaSec
                var changed = false
                when {
                    line.contains("[Merger]") -> {
                        item.stage = "Merging video & audio…"
                        changed = true
                    }
                    line.contains("[ExtractAudio]") -> {
                        item.stage = "Converting to MP3…"
                        changed = true
                    }
                    line.contains("[download]") && item.stage != "Downloading…" -> {
                        item.stage = "Downloading…"
                        changed = true
                    }
                    else -> {}
                }
                val pct = (item.progress * 100).toInt()
                if (pct != lastPct || changed) {
                    lastPct = pct
                    onNotify(pct, item.stage)
                }
            }

            if (item.cancelled) {
                item.status = Status.CANCELLED
                item.stage = "Cancelled"
                return null
            }

            item.stage = "Saving to your phone…"
            onNotify(100, item.stage)
            val out = tmp.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw Exception("Download finished but no file was found")

            val uri = saveToMediaStore(ctx, out, item.audio)

            val saved = HistoryItem(out.nameWithoutExtension, uri.toString(), item.audio)
            history = (listOf(saved) + history).take(100)
            saveHistory(ctx)

            item.progress = 1f
            item.stage = if (item.audio) "Saved to Music/ADMOSS" else "Saved to Movies/ADMOSS"
            item.status = Status.DONE
            result = saved
        } catch (e: Throwable) {
            if (item.cancelled) {
                item.status = Status.CANCELLED
                item.stage = "Cancelled"
            } else {
                item.error = cleanError(e)
                item.stage = "Failed"
                item.status = Status.FAILED
            }
        } finally {
            tmp.deleteRecursively()
            currentPid = null
            currentItem = null
        }
        return result
    }

    // ---- helpers ----

    private fun cleanError(e: Throwable): String {
        val raw = e.message ?: "Unknown error"
        val line = raw.lines().lastOrNull { it.contains("ERROR") }
            ?: raw.lines().lastOrNull { it.isNotBlank() }
            ?: raw
        val msg = line.substringAfter("ERROR:", line).trim()
        return when {
            msg.contains("Sign in to confirm", true) ->
                "YouTube asked for a verification check. Try again later or try another video."
            msg.contains("Unable to resolve host", true) || msg.contains("Network", true) ->
                "No internet connection."
            msg.contains("Private video", true) -> "This video is private."
            msg.length > 220 -> msg.take(220) + "…"
            else -> msg
        }
    }

    private fun saveToMediaStore(ctx: Context, file: File, audio: Boolean): Uri {
        val resolver = ctx.contentResolver
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (audio) "audio/mpeg" else "video/mp4"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (audio) "Music/ADMOSS" else "Movies/ADMOSS"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val uri = resolver.insert(collection, values) ?: throw Exception("Could not create the file")
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: throw Exception("Could not write the file")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun loadHistory(ctx: Context): List<HistoryItem> = try {
        val prefs = ctx.getSharedPreferences("admoss", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(o.getString("t"), o.getString("u"), o.getBoolean("a"))
        }
    } catch (e: Throwable) {
        emptyList()
    }

    private fun saveHistory(ctx: Context) {
        val arr = JSONArray()
        history.forEach {
            arr.put(JSONObject().put("t", it.title).put("u", it.uri).put("a", it.audio))
        }
        ctx.getSharedPreferences("admoss", Context.MODE_PRIVATE)
            .edit().putString("history", arr.toString()).apply()
    }
}
