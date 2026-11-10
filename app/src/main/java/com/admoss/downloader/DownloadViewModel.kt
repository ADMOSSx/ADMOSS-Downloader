package com.admoss.downloader

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.net.URL

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()

    // ---- what the user picked ----
    var link by mutableStateOf("")
        private set
    var audioOnly by mutableStateOf(false)
    var quality by mutableStateOf(Quality.BEST)
    var message by mutableStateOf<String?>(null)

    // ---- single video info ----
    var info by mutableStateOf<VideoInfoUi?>(null)
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var infoLoading by mutableStateOf(false)
    var infoError by mutableStateOf(false)
    var infoErrorText by mutableStateOf<String?>(null)

    // ---- playlist ----
    var playlist by mutableStateOf<PlaylistInfo?>(null)
    var selectedUrls by mutableStateOf<Set<String>>(emptySet())
    var playlistHint by mutableStateOf(false) // single video link that also belongs to a playlist

    // ---- playlist size calculation ----
    var playlistInfos by mutableStateOf<Map<String, VideoInfoUi>>(emptyMap())
    var sizeRunning by mutableStateOf(false)
    var sizeChecked by mutableStateOf(0)
    var sizeTotal by mutableStateOf(0)
    var sizeNote by mutableStateOf<String?>(null)

    @Volatile
    private var sizeCancelled = false
    private val sizePids = ConcurrentHashMap.newKeySet<String>()
    private val sizeLock = Any()

    // The raw yt-dlp info of videos we already looked up. A download reuses it (--load-info-json),
    // so yt-dlp does not have to ask YouTube / TikTok a second time.
    private val rawCache = ConcurrentHashMap<String, String>()
    private val rawTime = ConcurrentHashMap<String, Long>()

    private fun rememberRaw(url: String, raw: String) {
        if (rawCache.size > 40) {
            rawCache.clear()
            rawTime.clear()
        }
        rawCache[url] = raw
        rawTime[url] = System.currentTimeMillis()
    }

    /** Saves the remembered info of [url] to a file for the download, if it is still fresh (links expire). */
    private fun saveInfoJson(url: String, id: Long): String? {
        val raw = rawCache[url] ?: return null
        val age = System.currentTimeMillis() - (rawTime[url] ?: 0L)
        if (age > 2L * 60L * 60L * 1000L) return null
        return try {
            val dir = File(ctx.cacheDir, "info").apply { mkdirs() }
            val f = File(dir, "$id.json")
            f.writeText(raw.substring(raw.indexOf('{').coerceAtLeast(0)))
            f.absolutePath
        } catch (e: Throwable) {
            null
        }
    }

    // ---- TikTok photo post (pictures + music) ----
    var photo by mutableStateOf<PhotoPost?>(null)
    var photoPreviews by mutableStateOf<List<ImageBitmap>>(emptyList())
    var photoWantImages by mutableStateOf(true)
    var photoWantMusic by mutableStateOf(true)

    // title and cover shown at once, while the sizes are still loading
    var quick by mutableStateOf<QuickInfo?>(null)

    private val infoCache = ConcurrentHashMap<String, VideoInfoUi>()
    private val thumbCache = ConcurrentHashMap<String, ImageBitmap>()

    private var infoSeq = 0
    private var infoPid: String? = null

    init {
        DownloadCenter.init(ctx)
        Engine.start(ctx)
    }

    fun setSharedText(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        onLinkChange(url, true)
    }

    fun onLinkChange(text: String, immediate: Boolean = false) {
        link = text
        message = null
        refreshInfo(immediate)
    }

    // ---------- info / playlist loading ----------

    private fun isPlaylistUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("/playlist")) return true
        return u.contains("list=") && !u.contains("v=") && !u.contains("youtu.be/")
    }

    private fun stopInfoProcess() {
        infoPid?.let {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (e: Throwable) {
                // ignore
            }
        }
        infoPid = null
    }

    private fun refreshInfo(immediate: Boolean = false) {
        val seq = ++infoSeq
        stopInfoProcess()

        info = null
        quick = null
        thumb = null
        infoError = false
        infoErrorText = null
        playlist = null
        playlistHint = false
        selectedUrls = emptySet()
        cancelSizeCalc()
        playlistInfos = emptyMap()
        photo = null
        photoPreviews = emptyList()
        sizeChecked = 0
        sizeTotal = 0
        sizeNote = null

        val url = link.trim()
        if (!url.startsWith("http")) {
            infoLoading = false
            return
        }
        val asPlaylist = isPlaylistUrl(url)
        val cached = infoCache[url]
        if (cached != null && !asPlaylist) {
            info = cached
            thumb = thumbCache[url]
            playlistHint = url.lowercase().contains("list=")
            if (!cached.sizes.containsKey(quality)) quality = Quality.BEST
            infoLoading = false
            return
        }
        infoLoading = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!immediate) delay(350) // wait while the user is still typing
                if (seq != infoSeq) return@launch

                // Title and cover first: a tiny request that does not need the engine
                if (!asPlaylist) {
                    launch {
                        val q = Oembed.fetch(url)
                        if (q != null && seq == infoSeq) {
                            val bmp = loadThumb(q.thumbnailUrl, if (url.contains("tiktok", true)) 2 else 1)
                            if (seq == infoSeq && info == null && photo == null) {
                                quick = q
                                thumb = bmp
                            }
                        }
                    }
                }

                val tiktok = TikTokPhotos.isTikTok(url)
                if (tiktok && url.contains("/photo/")) {
                    // Photo posts: yt-dlp cannot get the pictures, so read the post directly
                    if (!loadPhoto(url, seq)) throw Exception("Couldn't read this photo post. Try again in a moment.")
                    return@launch
                }

                // TikTok links: check for a photo post at the same time as the normal lookup
                val photoJob = if (tiktok && !asPlaylist) async { TikTokPhotos.fetch(url) } else null

                while (!Engine.upFlag && !Engine.downFlag) delay(200)
                if (seq != infoSeq) return@launch
                if (Engine.downFlag) throw Exception("Engine failed")

                if (asPlaylist) {
                    fetchPlaylist(url, seq)
                } else if (photoJob != null) {
                    val ytJob = async {
                        try {
                            fetchSingle(url, seq)
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            e
                        }
                    }
                    val post = photoJob.await()
                    if (post != null) {
                        stopInfoProcess()
                        ytJob.cancel()
                        applyPhoto(post, seq)
                    } else {
                        val err = ytJob.await()
                        if (err != null) throw err
                    }
                } else {
                    fetchSingle(url, seq)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (seq == infoSeq) {
                    infoError = true
                    infoErrorText = DownloadCenter.cleanError(e)
                }
            } finally {
                if (seq == infoSeq) infoLoading = false
            }
        }
    }

    private fun runJson(url: String, pid: String, flat: Boolean, fallback: Boolean): String {
        val req = YoutubeDLRequest(url)
        if (flat) req.addOption("--flat-playlist") else req.addOption("--no-playlist")
        req.addOption("--dump-single-json")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "10")
        if (fallback) YtOptions.addFallback(req, true) else YtOptions.addInfoSpeedups(req)
        return YoutubeDL.getInstance().execute(req, pid) { _, _, _ -> }.out
    }

    /** Tries normally, then once more with the fallback settings. */
    private fun runJsonWithRetry(url: String, seq: Int, flat: Boolean): String {
        val pid = "info-$seq"
        infoPid = pid
        return try {
            runJson(url, pid, flat, false)
        } catch (e: Throwable) {
            if (seq != infoSeq || !YtOptions.shouldRetry(e)) throw e
            val pid2 = "info-$seq-b"
            infoPid = pid2
            runJson(url, pid2, flat, true)
        }
    }

    private fun fetchSingle(url: String, seq: Int) {
        val raw = runJsonWithRetry(url, seq, false)
        if (seq != infoSeq) return

        val parsed = InfoParser.parse(raw)
        rememberRaw(url, raw)
        val bmp = thumb ?: loadThumb(parsed.thumbnailUrl)
        if (seq != infoSeq) return

        if (infoCache.size > 30) {
            infoCache.clear()
            thumbCache.clear()
        }
        infoCache[url] = parsed
        if (bmp != null) thumbCache[url] = bmp

        quick = null
        info = parsed
        thumb = bmp
        playlistHint = url.lowercase().contains("list=")
        if (!parsed.sizes.containsKey(quality)) quality = Quality.BEST
    }

    private fun fetchPlaylist(url: String, seq: Int) {
        val raw = runJsonWithRetry(url, seq, true)
        if (seq != infoSeq) return

        val parsed = PlaylistParser.parse(raw)
        if (seq != infoSeq) return

        info = null
        thumb = null
        playlistHint = false
        playlist = parsed
        selectedUrls = parsed.entries.map { it.url }.toSet()
    }

    fun retryInfo() {
        refreshInfo(true)
    }

    /** The link is a video inside a playlist: load the whole playlist instead. */
    fun loadPlaylistFromHint() {
        val id = Regex("[?&]list=([A-Za-z0-9_-]+)").find(link)?.groupValues?.get(1) ?: return
        val seq = ++infoSeq
        stopInfoProcess()
        infoLoading = true
        infoError = false
        infoErrorText = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                fetchPlaylist("https://www.youtube.com/playlist?list=$id", seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (seq == infoSeq) {
                    infoError = true
                    infoErrorText = DownloadCenter.cleanError(e)
                }
            } finally {
                if (seq == infoSeq) infoLoading = false
            }
        }
    }

    val checkedCount: Int
        get() = selectedUrls.count { playlistInfos.containsKey(it) }

    val selectedSize: Long
        get() = selectedUrls.sumOf { u -> playlistInfos[u]?.let { estimateSize(it, audioOnly, quality) } ?: 0L }

    fun sizeOf(url: String): Long =
        playlistInfos[url]?.let { estimateSize(it, audioOnly, quality) } ?: 0L

    /** Looks up the selected videos, three at a time, and adds up the estimated sizes. */
    fun calculatePlaylistSize() {
        val pl = playlist ?: return
        if (sizeRunning || !Engine.ready) return
        val urls = pl.entries.map { it.url }.filter { it in selectedUrls && !playlistInfos.containsKey(it) }
        if (urls.isEmpty()) return

        sizeCancelled = false
        sizeRunning = true
        sizeChecked = 0
        sizeTotal = urls.size
        sizeNote = null
        val seq = infoSeq

        viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(3)
            val failures = AtomicInteger(0)
            try {
                coroutineScope {
                    urls.map { url ->
                        async {
                            gate.withPermit {
                                if (sizeCancelled || seq != infoSeq) return@withPermit
                                var pid = "size-${System.nanoTime()}"
                                sizePids.add(pid)
                                try {
                                    val raw = try {
                                        runJson(url, pid, false, false)
                                    } catch (e: Throwable) {
                                        if (sizeCancelled || !YtOptions.shouldRetry(e)) throw e
                                        sizePids.remove(pid)
                                        pid = "$pid-b"
                                        sizePids.add(pid)
                                        runJson(url, pid, false, true)
                                    }
                                    val parsed = InfoParser.parse(raw)
                                    rememberRaw(url, raw)
                                    if (!sizeCancelled && seq == infoSeq) {
                                        synchronized(sizeLock) { playlistInfos = playlistInfos + (url to parsed) }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    if (!sizeCancelled && failures.incrementAndGet() >= 5) {
                                        sizeNote = "Stopped after several errors. Try again later."
                                        sizeCancelled = true
                                    }
                                } finally {
                                    sizePids.remove(pid)
                                }
                                synchronized(sizeLock) { sizeChecked += 1 }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                sizeRunning = false
                sizePids.clear()
            }
        }
    }

    fun cancelSizeCalc() {
        sizeCancelled = true
        sizePids.toList().forEach {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    fun toggleSelected(url: String) {
        selectedUrls = if (url in selectedUrls) selectedUrls - url else selectedUrls + url
    }

    fun selectAll(on: Boolean) {
        selectedUrls = if (on) playlist?.entries?.map { it.url }?.toSet() ?: emptySet() else emptySet()
    }

    private fun loadThumb(url: String?, sample: Int = 2): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (url.contains("tiktok", ignoreCase = true)) c.setRequestProperty("Referer", "https://www.tiktok.com/")
            val bytes = c.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }

    /** Reads a TikTok photo post. Returns false if the link is not one. */
    private fun loadPhoto(url: String, seq: Int): Boolean {
        val post = TikTokPhotos.fetch(url) ?: return false
        applyPhoto(post, seq)
        return true
    }

    private fun applyPhoto(post: PhotoPost, seq: Int) {
        if (seq != infoSeq) return
        val previews = post.images.take(6).parallelMap(6) { loadThumb(it, 4) }.filterNotNull()
        if (seq != infoSeq) return

        info = null
        quick = null
        thumb = null
        playlistHint = false
        photoWantImages = true
        photoWantMusic = post.musicUrl != null
        photoPreviews = previews
        photo = post
    }

    // ---------- downloading ----------

    /** YouTube covers can be built from the video id. */
    private fun youtubeThumb(url: String): String? {
        val id = Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
            ?: return null
        return "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    }

    private fun newItem(
        url: String,
        title: String,
        playlist: String? = null,
        index: Int = 0,
        thumbUrl: String? = null
    ): QueueItem = QueueItem(
        id = DownloadCenter.newId(),
        url = url,
        title = title,
        audio = audioOnly,
        height = if (audioOnly) null else quality.height,
        playlist = playlist,
        index = index,
        thumbUrl = thumbUrl
    )

    /** Adds the current link (or the selected playlist videos) to the queue and starts the service. */
    fun download() {
        val url = link.trim()
        if (!url.startsWith("http")) {
            message = "Please paste a valid link (starting with http)"
            return
        }
        if (!Engine.ready) return

        val post = photo
        if (post != null) {
            if (!photoWantImages && !photoWantMusic) {
                message = "Choose photos, music, or both"
                return
            }
            if (!photoWantImages && post.musicUrl == null) {
                message = "This post has no music"
                return
            }
            val label = cutText(post.title.ifBlank { "TikTok photos" }, 60)
            val item = QueueItem(
                id = DownloadCenter.newId(),
                url = url,
                title = label,
                audio = false,
                height = null,
                thumbUrl = post.images.firstOrNull(),
                photo = post,
                photoImages = photoWantImages,
                photoMusic = photoWantMusic && post.musicUrl != null
            )
            DownloadCenter.enqueue(listOf(item))
            startQueueService(listOf(item))
            onLinkChange("")
            return
        }

        val pl = playlist
        val items: List<QueueItem> = if (pl != null) {
            val chosen = pl.entries.withIndex().filter { it.value.url in selectedUrls }
            if (chosen.isEmpty()) {
                message = "Select at least one video"
                return
            }
            chosen.map {
                newItem(it.value.url, it.value.title, pl.title, it.index, youtubeThumb(it.value.url))
            }
        } else {
            val title = info?.title ?: url.removePrefix("https://").removePrefix("http://").take(40)
            listOf(newItem(url, title, null, 0, youtubeThumb(url) ?: info?.thumbnailUrl))
        }

        // Writing the info files can take a while for big playlists, so do it off the main thread.
        // The items join the queue only after that, so the service never sees them half ready.
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                items.forEach { it.infoJsonPath = saveInfoJson(it.url, it.id) }
            }
            DownloadCenter.enqueue(items)
            startQueueService(items)
        }

        // Clear the box so the next link can be pasted right away
        onLinkChange("")
    }

    private fun startQueueService(items: List<QueueItem>) {
        val intent = Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_START)
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Throwable) {
            items.forEach {
                it.error = "Could not start the download service"
                it.stage = "Failed"
                it.status = Status.FAILED
            }
        }
    }
}
