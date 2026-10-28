package com.admoss.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(val title: String, val uri: String, val audio: Boolean)

/**
 * Process-wide download state. The foreground service writes to it,
 * the Compose UI reads from it, so progress survives rotation and leaving the app.
 */
object DownloadCenter {

    var downloading by mutableStateOf(false)
    var progress by mutableStateOf(0f) // 0..1
    var eta by mutableStateOf(-1L)
    var stage by mutableStateOf("")
    var title by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var success by mutableStateOf<String?>(null)
    var history by mutableStateOf<List<HistoryItem>>(emptyList())

    @Volatile
    private var cancelled = false

    @Volatile
    private var currentPid: String? = null

    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        history = loadHistory(ctx)
    }

    fun prepare(newTitle: String) {
        title = newTitle
        error = null
        success = null
        cancelled = false
        downloading = true
        progress = 0f
        eta = -1L
        stage = "Starting…"
    }

    fun cancel() {
        cancelled = true
        stage = "Cancelling…"
        currentPid?.let {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun clearHistory(ctx: Context) {
        history = emptyList()
        saveHistory(ctx)
    }

    /**
     * Blocking: call from a background thread (the service does).
     * Returns the saved file, or null if it failed or was cancelled.
     */
    fun run(
        ctx: Context,
        url: String,
        audio: Boolean,
        height: Int?,
        onNotify: (Int, String) -> Unit
    ): HistoryItem? {
        val id = "admoss-${System.currentTimeMillis()}"
        currentPid = id
        val tmp = File(ctx.cacheDir, "dl/$id").apply { mkdirs() }
        var result: HistoryItem? = null

        try {
            var waited = 0
            while (!Engine.ready && !Engine.failed && waited < 120) {
                Thread.sleep(500)
                waited++
            }
            if (!Engine.ready) throw Exception("Download engine is not ready")
            if (cancelled) return null

            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--no-mtime")
            req.addOption("-o", "${tmp.absolutePath}/%(title).80s.%(ext)s")

            if (audio) {
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "192K")
            } else {
                // Prefer H.264 + AAC so the file plays everywhere
                val sort = if (height != null) {
                    "res:$height,vcodec:h264,acodec:aac"
                } else {
                    "vcodec:h264,acodec:aac"
                }
                req.addOption("-S", sort)
                req.addOption("--merge-output-format", "mp4")
            }

            var lastPct = -1
            YoutubeDL.getInstance().execute(req, id) { p, etaSec, line ->
                if (p >= 0f) progress = (p / 100f).coerceIn(0f, 1f)
                eta = etaSec
                var changed = false
                when {
                    line.contains("[Merger]") -> {
                        stage = "Merging video & audio…"
                        changed = true
                    }
                    line.contains("[ExtractAudio]") -> {
                        stage = "Converting to MP3…"
                        changed = true
                    }
                    line.contains("[download]") && stage != "Downloading…" -> {
                        stage = "Downloading…"
                        changed = true
                    }
                    else -> {}
                }
                val pct = (progress * 100).toInt()
                if (pct != lastPct || changed) {
                    lastPct = pct
                    onNotify(pct, stage)
                }
            }

            if (cancelled) return null

            stage = "Saving to your phone…"
            onNotify(100, stage)
            val out = tmp.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw Exception("Download finished but no file was found")

            val uri = saveToMediaStore(ctx, out, audio)
            progress = 1f

            val item = HistoryItem(out.nameWithoutExtension, uri.toString(), audio)
            history = (listOf(item) + history).take(50)
            saveHistory(ctx)
            success = if (audio) "Saved to Music/ADMOSS" else "Saved to Movies/ADMOSS"
            result = item
        } catch (e: Exception) {
            if (!cancelled) error = cleanError(e)
        } finally {
            tmp.deleteRecursively()
            downloading = false
            currentPid = null
        }
        return result
    }

    // ---- helpers ----

    private fun cleanError(e: Exception): String {
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
    } catch (e: Exception) {
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
