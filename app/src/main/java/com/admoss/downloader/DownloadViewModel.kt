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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
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

    // ---- playlist ----
    var playlist by mutableStateOf<PlaylistInfo?>(null)
    var selectedUrls by mutableStateOf<Set<String>>(emptySet())
    var playlistHint by mutableStateOf(false) // single video link that also belongs to a playlist

    private var infoSeq = 0
    private var infoPid: String? = null

    init {
        DownloadCenter.init(ctx)
        Engine.start(ctx)
    }

    fun setSharedText(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        onLinkChange(url)
    }

    fun onLinkChange(text: String) {
        link = text
        message = null
        refreshInfo()
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

    private fun refreshInfo() {
        val seq = ++infoSeq
        stopInfoProcess()

        info = null
        thumb = null
        infoError = false
        playlist = null
        playlistHint = false
        selectedUrls = emptySet()

        val url = link.trim()
        if (!url.startsWith("http")) {
            infoLoading = false
            return
        }
        infoLoading = true
        val asPlaylist = isPlaylistUrl(url)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(600) // wait while the user is still typing / pasting
                if (seq != infoSeq) return@launch
                while (!Engine.upFlag && !Engine.downFlag) delay(200)
                if (seq != infoSeq) return@launch
                if (Engine.downFlag) throw Exception("Engine failed")

                if (asPlaylist) fetchPlaylist(url, seq) else fetchSingle(url, seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (seq == infoSeq) infoError = true
            } finally {
                if (seq == infoSeq) infoLoading = false
            }
        }
    }

    private fun fetchSingle(url: String, seq: Int) {
        val pid = "info-$seq"
        infoPid = pid
        val req = YoutubeDLRequest(url)
        req.addOption("--dump-single-json")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        val resp = YoutubeDL.getInstance().execute(req, pid) { _, _, _ -> }
        if (seq != infoSeq) return

        val parsed = InfoParser.parse(resp.out)
        val bmp = loadThumb(parsed.thumbnailUrl)
        if (seq != infoSeq) return

        info = parsed
        thumb = bmp
        playlistHint = url.lowercase().contains("list=")
        if (!parsed.sizes.containsKey(quality)) quality = Quality.BEST
    }

    private fun fetchPlaylist(url: String, seq: Int) {
        val pid = "info-$seq"
        infoPid = pid
        val req = YoutubeDLRequest(url)
        req.addOption("--flat-playlist")
        req.addOption("--dump-single-json")
        req.addOption("--no-warnings")
        val resp = YoutubeDL.getInstance().execute(req, pid) { _, _, _ -> }
        if (seq != infoSeq) return

        val parsed = PlaylistParser.parse(resp.out)
        if (seq != infoSeq) return

        info = null
        thumb = null
        playlistHint = false
        playlist = parsed
        selectedUrls = parsed.entries.map { it.url }.toSet()
    }

    /** The link is a video inside a playlist: load the whole playlist instead. */
    fun loadPlaylistFromHint() {
        val id = Regex("[?&]list=([A-Za-z0-9_-]+)").find(link)?.groupValues?.get(1) ?: return
        val seq = ++infoSeq
        stopInfoProcess()
        infoLoading = true
        infoError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                fetchPlaylist("https://www.youtube.com/playlist?list=$id", seq)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (seq == infoSeq) infoError = true
            } finally {
                if (seq == infoSeq) infoLoading = false
            }
        }
    }

    fun toggleSelected(url: String) {
        selectedUrls = if (url in selectedUrls) selectedUrls - url else selectedUrls + url
    }

    fun selectAll(on: Boolean) {
        selectedUrls = if (on) playlist?.entries?.map { it.url }?.toSet() ?: emptySet() else emptySet()
    }

    private fun loadThumb(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val bytes = c.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- downloading ----------

    private fun newItem(url: String, title: String): QueueItem = QueueItem(
        id = DownloadCenter.newId(),
        url = url,
        title = title,
        audio = audioOnly,
        height = if (audioOnly) null else quality.height
    )

    /** Adds the current link (or the selected playlist videos) to the queue and starts the service. */
    fun download() {
        val url = link.trim()
        if (!url.startsWith("http")) {
            message = "Please paste a valid link (starting with http)"
            return
        }
        if (!Engine.ready) return

        val pl = playlist
        val items: List<QueueItem> = if (pl != null) {
            val chosen = pl.entries.filter { it.url in selectedUrls }
            if (chosen.isEmpty()) {
                message = "Select at least one video"
                return
            }
            chosen.map { newItem(it.url, it.title) }
        } else {
            val title = info?.title ?: url.removePrefix("https://").removePrefix("http://").take(40)
            listOf(newItem(url, title))
        }

        DownloadCenter.enqueue(items)

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

        // Clear the box so the next link can be pasted right away
        onLinkChange("")
    }
}
