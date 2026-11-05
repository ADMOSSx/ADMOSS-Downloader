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
import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Size
import java.io.File
import kotlin.concurrent.thread
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
    var restoring by mutableStateOf(false)
    var restoreMessage by mutableStateOf<String?>(null)

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

            val folder = item.playlist?.let { safeName(it, 60) }.orEmpty()
            val prefix = if (item.playlist != null) (item.index + 1).toString().padStart(2, '0') + " - " else ""
            val uri = saveToMediaStore(ctx, out, item.audio, folder, prefix)

            val thumbPath = saveThumb(ctx, item.thumbUrl, item.id)
            val saved = HistoryItem(
                out.nameWithoutExtension, uri.toString(), item.audio,
                item.playlist, item.index, thumbPath, out.length()
            )
            history = (listOf(saved) + history).take(500)
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

    // ---- restore the library after a reinstall ----

    private val mainHandler = Handler(Looper.getMainLooper())

    private class Found(val date: Long, val item: HistoryItem)

    /** Looks in Movies/Music/Pictures ADMOSS for files this app saved before and adds them back. */
    fun restoreAsync(ctx: Context, silent: Boolean) {
        if (restoring) return
        val app = ctx.applicationContext
        restoring = true
        if (!silent) restoreMessage = null
        thread(name = "admoss-restore") {
            val n = try {
                restoreFromDevice(app)
            } catch (e: Throwable) {
                -1
            }
            mainHandler.post {
                restoring = false
                if (!silent || n > 0) {
                    restoreMessage = when {
                        n < 0 -> "Couldn't read your files. Allow access to photos, videos and music, then try again."
                        n == 0 -> "Everything saved on this phone is already in your library."
                        n == 1 -> "Restored 1 item from this phone."
                        else -> "Restored $n items from this phone."
                    }
                }
            }
        }
    }

    private fun splitNumber(name: String): Pair<Int, String> {
        val m = Regex("^(\\d{1,4}) - (.*)$").find(name) ?: return Pair(0, name)
        val n = ((m.groupValues[1].toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
        return Pair(n, m.groupValues[2])
    }

    private fun makeThumb(ctx: Context, uri: Uri, key: String): String? = try {
        val bmp = ctx.contentResolver.loadThumbnail(uri, Size(480, 270), null)
        val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
        val file = File(dir, "r${System.currentTimeMillis()}_$key.img")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (e: Throwable) {
        null
    }

    fun restoreFromDevice(ctx: Context): Int {
        val resolver = ctx.contentResolver
        val known = HashSet<String>()
        history.forEach { h ->
            known.add(h.uri)
            known.addAll(h.photoUris)
            h.musicUri?.let { known.add(it) }
        }
        val found = ArrayList<Found>()

        fun scan(collection: Uri, root: String, onRow: (Long, String, Long, String, Long) -> Unit) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_ADDED
            )
            resolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$root%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    onRow(c.getLong(iId), c.getString(iName) ?: "", c.getLong(iSize), c.getString(iPath) ?: "", c.getLong(iDate))
                }
            }
        }

        // Videos (a sub folder is a playlist, files start with their number)
        val videoCol = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        scan(videoCol, "Movies/ADMOSS/") { id, name, size, rel, date ->
            val uri = ContentUris.withAppendedId(videoCol, id)
            if (uri.toString() in known) return@scan
            val sub = rel.removePrefix("Movies/ADMOSS/").trim('/').substringBefore('/')
            val (index, title) = splitNumber(name.substringBeforeLast('.'))
            found.add(
                Found(
                    date,
                    HistoryItem(
                        title = title,
                        uri = uri.toString(),
                        audio = false,
                        playlist = sub.ifBlank { null },
                        index = if (sub.isBlank()) 0 else index,
                        size = size
                    )
                )
            )
        }

        // Music
        val audioCol = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        scan(audioCol, "Music/ADMOSS/") { id, name, size, rel, date ->
            val uri = ContentUris.withAppendedId(audioCol, id)
            if (uri.toString() in known) return@scan
            val sub = rel.removePrefix("Music/ADMOSS/").trim('/').substringBefore('/')
            val (index, title) = splitNumber(name.substringBeforeLast('.'))
            found.add(
                Found(
                    date,
                    HistoryItem(
                        title = title,
                        uri = uri.toString(),
                        audio = true,
                        playlist = sub.ifBlank { null },
                        index = if (sub.isBlank()) 0 else index,
                        size = size
                    )
                )
            )
        }

        // TikTok photo posts: one folder = one post
        val imageCol = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folders = LinkedHashMap<String, ArrayList<Triple<String, Uri, Long>>>()
        val folderDate = HashMap<String, Long>()
        scan(imageCol, "Pictures/ADMOSS/") { id, name, size, rel, date ->
            val sub = rel.removePrefix("Pictures/ADMOSS/").trim('/').substringBefore('/')
            if (sub.isBlank()) return@scan
            folders.getOrPut(sub) { ArrayList() }.add(Triple(name, ContentUris.withAppendedId(imageCol, id), size))
            if (!folderDate.containsKey(sub)) folderDate[sub] = date
        }
        for ((folder, files) in folders) {
            val sorted = files.sortedBy { it.first }
            val uris = sorted.map { it.second.toString() }
            if (uris.all { it in known }) continue
            val title = folder.replace(Regex(" \\d{4,5}$"), "").ifBlank { "TikTok photos" }
            found.add(
                Found(
                    folderDate[folder] ?: 0L,
                    HistoryItem(
                        title = title,
                        uri = uris.first(),
                        audio = false,
                        size = sorted.sumOf { it.third },
                        photoUris = uris
                    )
                )
            )
        }

        val items = found.sortedByDescending { it.date }.map { f ->
            val h = f.item
            if (h.audio) h else h.copy(thumb = makeThumb(ctx, Uri.parse(h.uri), h.uri.substringAfterLast('/')))
        }
        history = (items + history).take(500)
        saveHistory(ctx)
        return items.size
    }

    // ---- playlist editing ----

    fun renamePlaylist(ctx: Context, old: String, newName: String) {
        val n = newName.trim()
        if (n.isBlank() || n == old) return
        val affected = history.filter { it.playlist == old }
        history = history.map { if (it.playlist == old) it.copy(playlist = n) else it }
        saveHistory(ctx)
        moveInBackground(ctx, affected, n)
    }

    fun addToPlaylist(ctx: Context, name: String, uris: List<String>) {
        val n = name.trim()
        if (n.isBlank() || uris.isEmpty()) return
        var next = (history.filter { it.playlist == n }.maxOfOrNull { it.index } ?: -1) + 1
        val moved = ArrayList<HistoryItem>()
        for (u in uris) {
            history = history.map {
                if (it.uri == u && it.photoUris.isEmpty()) {
                    val updated = it.copy(playlist = n, index = next)
                    next += 1
                    moved.add(updated)
                    updated
                } else {
                    it
                }
            }
        }
        saveHistory(ctx)
        moveInBackground(ctx, moved, n)
    }

    fun removeFromPlaylist(ctx: Context, item: HistoryItem) {
        history = history.map { if (it.uri == item.uri) it.copy(playlist = null, index = 0) else it }
        saveHistory(ctx)
        moveInBackground(ctx, listOf(item), "")
    }

    /** Best effort: moves the files into the playlist folder so a later restore keeps the playlist. */
    private fun moveInBackground(ctx: Context, items: List<HistoryItem>, playlist: String) {
        val app = ctx.applicationContext
        thread(name = "admoss-move") {
            for (item in items) {
                if (item.photoUris.isNotEmpty()) continue
                try {
                    val root = if (item.audio) "Music/ADMOSS" else "Movies/ADMOSS"
                    val sub = safeName(playlist, 60)
                    val path = if (sub.isBlank()) root else "$root/$sub"
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
                    app.contentResolver.update(Uri.parse(item.uri), values, null, null)
                } catch (e: Throwable) {
                    // not allowed for this file: the app still keeps the playlist
                }
            }
        }
    }

    // ---- TikTok photo posts ----

    /** A file or folder name: letters, digits and a few simple symbols only (no emojis, no #). */
    private fun safeName(text: String, max: Int): String {
        val allowed = "'.-_(),&! "
        val sb = StringBuilder()
        var count = 0
        var i = 0
        while (i < text.length && count < max) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val ok = Character.isLetterOrDigit(cp) || (cp < 128 && allowed.indexOf(cp.toChar()) >= 0)
            if (ok) {
                sb.appendCodePoint(cp)
                count += 1
            } else {
                sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

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
            val title = cutText(post.title.ifBlank { "TikTok photos" + if (post.author.isNotBlank()) " by @${post.author}" else "" }, 90)

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
            history = (listOf(saved) + history).take(500)
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

    private fun saveToMediaStore(ctx: Context, file: File, audio: Boolean, sub: String = "", prefix: String = ""): Uri {
        val resolver = ctx.contentResolver
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (audio) "audio/mpeg" else "video/mp4"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, prefix + file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (audio) "Music/ADMOSS" else "Movies/ADMOSS") + (if (sub.isNotBlank()) "/$sub" else "")
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
