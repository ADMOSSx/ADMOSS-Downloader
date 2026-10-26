package com.admoss.downloader

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Quality(val label: String, val height: Int?) {
    BEST("Best", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360)
}

data class HistoryItem(val title: String, val uri: String, val audio: Boolean)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()
    private val prefs get() = ctx.getSharedPreferences("admoss", Context.MODE_PRIVATE)

    // ---- UI state ----
    var link by mutableStateOf("")
    var audioOnly by mutableStateOf(false)
    var quality by mutableStateOf(Quality.BEST)

    var engineReady by mutableStateOf(false)
    var engineFailed by mutableStateOf(false)
    var engineMessage by mutableStateOf("Preparing engine…")

    var downloading by mutableStateOf(false)
    var progress by mutableStateOf(0f) // 0..1
    var eta by mutableStateOf(-1L)
    var stage by mutableStateOf("")

    var error by mutableStateOf<String?>(null)
    var success by mutableStateOf<String?>(null)

    var history by mutableStateOf(loadHistory())

    private var currentProcessId: String? = null
    @Volatile private var cancelled = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(ctx)
                FFmpeg.getInstance().init(ctx)
                engineReady = true
            } catch (e: Exception) {
                engineFailed = true
                engineMessage = "Engine failed to start"
                error = e.message ?: "Could not start the download engine"
                return@launch
            }
            // Keep yt-dlp fresh: YouTube changes often. Silent if offline.
            try {
                YoutubeDL.getInstance().updateYoutubeDL(ctx)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun setSharedText(text: String) {
        link = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        error = null
        success = null
    }

    fun startDownload() {
        val url = link.trim()
        if (!url.startsWith("http")) {
            error = "Please paste a valid link (starting with http)"
            return
        }
        if (downloading || !engineReady) return

        error = null
        success = null
        cancelled = false
        downloading = true
        progress = 0f
        eta = -1L
        stage = "Starting…"

        val audio = audioOnly
        val q = quality
        val pid = "admoss-${System.currentTimeMillis()}"
        currentProcessId = pid

        viewModelScope.launch(Dispatchers.IO) {
            val tmp = File(ctx.cacheDir, "dl/$pid").apply { mkdirs() }
            try {
                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                req.addOption("--no-mtime")
                req.addOption("-o", "${tmp.absolutePath}/%(title).80s.%(ext)s")

                if (audio) {
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                    req.addOption("--audio-quality", "0")
                } else {
                    // Prefer H.264 + AAC so the file plays everywhere. "Best" = best available H.264 (up to 1080p on YouTube).
                    val sort = if (q.height != null) {
                        "res:${q.height},vcodec:h264,acodec:aac"
                    } else {
                        "vcodec:h264,acodec:aac"
                    }
                    req.addOption("-S", sort)
                    req.addOption("--merge-output-format", "mp4")
                }

                YoutubeDL.getInstance().execute(req, pid) { p, etaSec, line ->
                    if (p >= 0f) progress = (p / 100f).coerceIn(0f, 1f)
                    eta = etaSec
                    when {
                        line.contains("[Merger]") -> stage = "Merging video & audio…"
                        line.contains("[ExtractAudio]") -> stage = "Converting to MP3…"
                        line.contains("[download]") -> stage = "Downloading…"
                        else -> {}
                    }
                }

                stage = "Saving to your phone…"
                val out = tmp.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                    ?: throw Exception("Download finished but no file was found")

                val uri = saveToMediaStore(out, audio)
                progress = 1f

                history = (listOf(HistoryItem(out.nameWithoutExtension, uri.toString(), audio)) + history).take(50)
                saveHistory()
                success = if (audio) "Saved to Music/ADMOSS" else "Saved to Movies/ADMOSS"
            } catch (e: Exception) {
                if (!cancelled) error = cleanError(e)
            } finally {
                tmp.deleteRecursively()
                downloading = false
                currentProcessId = null
            }
        }
    }

    fun cancel() {
        cancelled = true
        stage = "Cancelling…"
        currentProcessId?.let {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun clearHistory() {
        history = emptyList()
        saveHistory()
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

    private fun saveToMediaStore(file: File, audio: Boolean): Uri {
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

    private fun loadHistory(): List<HistoryItem> = try {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryItem(o.getString("t"), o.getString("u"), o.getBoolean("a"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveHistory() {
        val arr = JSONArray()
        history.forEach {
            arr.put(JSONObject().put("t", it.title).put("u", it.uri).put("a", it.audio))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}
