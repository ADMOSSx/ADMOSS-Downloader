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
import java.net.HttpURLConnection
import java.net.URL

data class HistoryItem(
    val title: String,
    val uri: String,
    val audio: Boolean,
    val playlist: String? = null,
    val index: Int = 0,
    val thumb: String? = null,
    val size: Long = 0L,
    /** Saved pictures of a TikTok photo post (empty for videos). */
    val photoUris: List<String> = emptyList(),
    val musicUri: String? = null
)

enum class Status { WAITING, DOWNLOADING, DONE, FAILED, CANCELLED }

/** One download in the queue. */
class QueueItem(
    val id: Long,
    val url: String,
    val title: String,
    val audio: Boolean,
    val height: Int?,
    val playlist: String? = null,
    val index: Int = 0,
    val thumbUrl: String? = null,
    val photo: PhotoPost? = null,
    val photoImages: Boolean = true,
    val photoMusic: Boolean = true
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

    fun removePlaylist(ctx: Context, name: String) {
        history.filter { it.playlist == name }.forEach { deleteThumb(it) }
        history = history.filter { it.playlist != name }
        saveHistory(ctx)
    }

    fun removeHistory(ctx: Context, item: HistoryItem) {
        deleteThumb(item)
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

        val photoPost = item.photo
        if (photoPost != null) return runPhotoItem(ctx, item, photoPost, onNotify)

        val tmp = File(ctx.cacheDir, "dl/$id").apply { mkdirs() }
        var result: HistoryItem? = null

        try {
            var waited = 0
            while (!Engine.upFlag && !Engine.downFlag && waited < 120) {
                Thread.sleep(500)
                waited++
            }
            if (!Engine.upFlag) throw Exception("Download engine is not ready")
            if (item.cancelled) {
                item.status = Status.CANCELLED
                item.stage = "Cancelled"
                return null
            }

            var attempt = 0
            var lastPct = -1
            while (true) {
                val req = YoutubeDLRequest(item.url)
                req.addOption("--no-playlist")
                req.addOption("--no-mtime")
                req.addOption("-o", "${tmp.absolutePath}/%(title).80s.%(ext)s")
                if (attempt > 0) YtOptions.addFallback(req)

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

                try {
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
                    break
                } catch (e: Throwable) {
                    if (item.cancelled || attempt >= 1 || !YtOptions.shouldRetry(e)) throw e
                    attempt++
                    item.stage = "Retrying…"
                    item.progress = 0f
                    onNotify(0, item.stage)
                    tmp.listFiles()?.forEach { it.delete() }
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

            val thumbPath = saveThumb(ctx, item.thumbUrl, item.id)
            val saved = HistoryItem(
                out.nameWithoutExtension, uri.toString(), item.audio,
                item.playlist, item.index, thumbPath, out.length()
            )
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

    // ---- TikTok photo posts ----

    private fun safeName(text: String, max: Int): String =
        text.replace(Regex("[\\\\/:*?\"<>|\\r\\n#]"), " ").replace(Regex("\\s+"), " ").trim().take(max).trim()

    /** Downloads one file. The extension comes from the server (jpg, webp, mp3 ...). */
    private fun downloadFile(url: String, base: File, defaultExt: String): File {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
        c.setRequestProperty("Referer", "https://www.tiktok.com/")
        if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode} while downloading a file")

        val type = (c.contentType ?: "").lowercase()
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        val ext = when {
            "webp" in type -> "webp"
            "png" in type -> "png"
            "heic" in type -> "heic"
            "jpeg" in type || "jpg" in type -> "jpg"
            "audio/mp4" in type || "m4a" in type -> "m4a"
            "mpeg" in type || "mp3" in type -> "mp3"
            "aac" in type -> "aac"
            fromUrl in listOf("jpg", "jpeg", "png", "webp", "mp3", "m4a", "aac") -> fromUrl.replace("jpeg", "jpg")
            else -> defaultExt
        }
        val out = File(base.parentFile, base.name + "." + ext)
        c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun saveImage(ctx: Context, file: File, folder: String): Uri {
        val resolver = ctx.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ADMOSS/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw Exception("Could not create the picture file")
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: throw Exception("Could not write the picture")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /** Saves the pictures to Pictures/ADMOSS/<post> and the music to Music/ADMOSS. */
    private fun runPhotoItem(ctx: Context, item: QueueItem, post: PhotoPost, onNotify: (Int, String) -> Unit): HistoryItem? {
        val tmp = File(ctx.cacheDir, "dl/photo-${item.id}").apply { mkdirs() }
        var result: HistoryItem? = null
        try {
            val images = if (item.photoImages) post.images else emptyList()
            val music = if (item.photoMusic) post.musicUrl else null
            val total = images.size + (if (music != null) 1 else 0)
            if (total == 0) throw Exception("Nothing to download")

            val label = safeName(post.title, 40).ifBlank { "TikTok photos" }
            val folder = (label + " " + post.id.takeLast(5)).trim()
            val imageUris = ArrayList<String>()
            var bytes = 0L
            var done = 0

            for ((i, url) in images.withIndex()) {
                if (item.cancelled) {
                    item.status = Status.CANCELLED
                    item.stage = "Cancelled"
                    return null
                }
                item.stage = "Photo ${i + 1} of ${images.size}"
                onNotify(done * 100 / total, item.stage)
                val file = downloadFile(url, File(tmp, "photo_" + (i + 1).toString().padStart(2, '0')), "jpg")
                imageUris.add(saveImage(ctx, file, folder).toString())
                bytes += file.length()
                done += 1
                item.progress = done / total.toFloat()
            }

            var musicUri: String? = null
            if (music != null) {
                if (item.cancelled) {
                    item.status = Status.CANCELLED
                    item.stage = "Cancelled"
                    return null
                }
                item.stage = "Music"
                onNotify(done * 100 / total, item.stage)
                val musicName = safeName(post.musicTitle.ifBlank { label }, 50).ifBlank { "TikTok music" }
                val file = downloadFile(music, File(tmp, "$musicName - TikTok"), "mp3")
                musicUri = saveToMediaStore(ctx, file, true).toString()
                bytes += file.length()
                done += 1
                item.progress = done / total.toFloat()
            }

            val coverUrl = images.firstOrNull() ?: post.images.firstOrNull()
            val thumbPath = saveThumb(ctx, coverUrl, item.id)
            val title = post.title.ifBlank { "TikTok photos" + if (post.author.isNotBlank()) " by @${post.author}" else "" }.take(90)

            val saved = HistoryItem(
                title = title,
                uri = imageUris.firstOrNull() ?: musicUri ?: "",
                audio = imageUris.isEmpty(),
                playlist = null,
                index = 0,
                thumb = thumbPath,
                size = bytes,
                photoUris = imageUris,
                musicUri = musicUri
            )
            history = (listOf(saved) + history).take(100)
            saveHistory(ctx)

            item.progress = 1f
            item.stage = when {
                imageUris.isNotEmpty() && musicUri != null -> "Saved photos and music"
                imageUris.isNotEmpty() -> "Saved to Pictures/ADMOSS"
                else -> "Saved to Music/ADMOSS"
            }
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
        }
        return result
    }

    // ---- helpers ----

    private fun deleteThumb(item: HistoryItem) {
        try {
            item.thumb?.let { File(it).delete() }
        } catch (e: Throwable) {
            // ignore
        }
    }

    /** Saves a small cover image next to the app data. Best effort, never fails the download. */
    private fun saveThumb(ctx: Context, url: String?, id: Long): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
            val file = File(dir, "t${System.currentTimeMillis()}_$id.img")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.setRequestProperty("Referer", "https://www.tiktok.com/")
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            if (file.length() > 0) file.absolutePath else null
        } catch (e: Throwable) {
            null
        }
    }

    fun cleanError(e: Throwable): String {
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
            msg.contains("429") || msg.contains("Too Many Requests", true) ->
                "Too many requests (HTTP 429). Wait a few minutes and try again."
            msg.contains("Unable to extract", true) || msg.contains("nsig", true) ->
                "The site changed something. Tap Update engine, then try again."
            msg.contains("Requested format is not available", true) ->
                "This quality is not available for this video. Pick another quality."
            msg.contains("Video unavailable", true) -> "This video is unavailable."
            msg.contains("not made this video available in your country", true) ->
                "This video is blocked in your country."
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
            HistoryItem(
                o.getString("t"), o.getString("u"), o.getBoolean("a"),
                o.optString("p", "").ifBlank { null }, o.optInt("i", 0),
                o.optString("th", "").ifBlank { null },
                o.optLong("sz", 0L),
                (o.optJSONArray("pu")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: emptyList()),
                o.optString("mu", "").ifBlank { null }
            )
        }
    } catch (e: Throwable) {
        emptyList()
    }

    private fun saveHistory(ctx: Context) {
        val arr = JSONArray()
        history.forEach {
            arr.put(
                JSONObject().put("t", it.title).put("u", it.uri).put("a", it.audio)
                    .put("p", it.playlist ?: "").put("i", it.index).put("th", it.thumb ?: "").put("sz", it.size)
                    .put("pu", JSONArray(it.photoUris)).put("mu", it.musicUri ?: "")
            )
        }
        ctx.getSharedPreferences("admoss", Context.MODE_PRIVATE)
            .edit().putString("history", arr.toString()).apply()
    }
}
