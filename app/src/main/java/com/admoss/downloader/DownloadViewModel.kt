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

    // ---- video info shown before downloading ----
    var info by mutableStateOf<VideoInfoUi?>(null)
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var infoLoading by mutableStateOf(false)
    var infoError by mutableStateOf(false)

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
        DownloadCenter.error = null
        if (!DownloadCenter.downloading) DownloadCenter.success = null
        refreshInfo()
    }

    private fun refreshInfo() {
        val seq = ++infoSeq

        infoPid?.let {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        infoPid = null
        info = null
        thumb = null
        infoError = false

        val url = link.trim()
        if (!url.startsWith("http")) {
            infoLoading = false
            return
        }
        infoLoading = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(600) // wait while the user is still typing / pasting
                if (seq != infoSeq) return@launch
                while (!Engine.ready && !Engine.failed) delay(200)
                if (seq != infoSeq) return@launch
                if (Engine.failed) throw Exception("Engine failed")

                val pid = "info-$seq"
                infoPid = pid
                val req = YoutubeDLRequest(url)
                req.addOption("--dump-single-json")
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                val resp = YoutubeDL.getInstance().execute(req, pid) { _, _, _ -> }
                if (seq != infoSeq) return@launch

                val parsed = InfoParser.parse(resp.out)
                val bmp = loadThumb(parsed.thumbnailUrl)
                if (seq != infoSeq) return@launch

                info = parsed
                thumb = bmp
                if (!parsed.sizes.containsKey(quality)) quality = Quality.BEST
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == infoSeq) infoError = true
            } finally {
                if (seq == infoSeq) infoLoading = false
            }
        }
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
        } catch (e: Exception) {
            null
        }
    }

    fun startDownload() {
        val url = link.trim()
        if (!url.startsWith("http")) {
            DownloadCenter.error = "Please paste a valid link (starting with http)"
            return
        }
        if (DownloadCenter.downloading || !Engine.ready) return

        val title = info?.title ?: "Video"
        DownloadCenter.prepare(title)

        val intent = Intent(ctx, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_AUDIO, audioOnly)
            putExtra(DownloadService.EXTRA_HEIGHT, quality.height ?: -1)
            putExtra(DownloadService.EXTRA_TITLE, title)
        }
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Exception) {
            DownloadCenter.downloading = false
            DownloadCenter.error = "Could not start the download service"
        }
    }
}
