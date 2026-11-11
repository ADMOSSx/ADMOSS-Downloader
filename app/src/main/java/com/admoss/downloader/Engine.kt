package com.admoss.downloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

/**
 * Starts the yt-dlp + ffmpeg engine once for the whole app (UI and service share it).
 *
 * The Compose state values (ready / failed ...) are only changed on the main thread.
 * Background code should check the plain upFlag / downFlag flags instead.
 */
object Engine {
    var ready by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var message by mutableStateOf("Preparing engine…")
    var failure by mutableStateOf<String?>(null)
    var updating by mutableStateOf(false)
    var updateMessage by mutableStateOf<String?>(null)

    @Volatile
    var upFlag = false

    @Volatile
    var downFlag = false

    @Volatile
    private var started = false

    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        downFlag = false
        val app = context.applicationContext
        thread(name = "admoss-engine") {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                upFlag = true
                main.post {
                    failed = false
                    ready = true
                }
            } catch (e: Throwable) {
                downFlag = true
                val reason = e.message ?: e.javaClass.simpleName
                main.post {
                    failed = true
                    message = "Engine failed to start (tap to retry)"
                    failure = reason
                }
                return@thread
            }
            // Keep yt-dlp fresh: YouTube changes often. Silent if offline.
            try {
                val prefs = app.getSharedPreferences("admoss", Context.MODE_PRIVATE)
                val last = prefs.getLong("engine_update_at", 0L)
                if (System.currentTimeMillis() - last > 12L * 60L * 60L * 1000L) {
                    Thread.sleep(25000) // let the first video lookups finish first
                    // Never swap the engine files while a download is running: wait for it (up to 10 minutes)
                    var waited = 0
                    while (DownloadCenter.activeCount > 0 && waited < 120) {
                        Thread.sleep(5000)
                        waited++
                    }
                    if (DownloadCenter.activeCount == 0) {
                        YoutubeDL.getInstance().updateYoutubeDL(app)
                        prefs.edit().putLong("engine_update_at", System.currentTimeMillis()).apply()
                    }
                }
            } catch (e: Throwable) {
                // ignore
            }
        }
    }

    @Synchronized
    fun retry(context: Context) {
        started = false
        main.post {
            failed = false
            failure = null
            message = "Preparing engine…"
        }
        start(context)
    }

    /** Downloads the newest yt-dlp. Fixes most "couldn't load video info" problems. */
    fun updateNow(context: Context) {
        if (updating || !upFlag) return
        if (DownloadCenter.activeCount > 0) {
            main.post { updateMessage = "Wait for the running downloads to finish, then tap Update engine again." }
            return
        }
        val app = context.applicationContext
        main.post {
            updating = true
            updateMessage = null
        }
        thread(name = "admoss-engine-update") {
            val text = try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(app)
                when (status.toString()) {
                    "ALREADY_UP_TO_DATE" -> "The engine is already up to date. Try the link again."
                    "DONE" -> "Engine updated. Try the link again."
                    else -> "Engine update finished. Try the link again."
                }
            } catch (e: Throwable) {
                "Update failed: ${e.message ?: e.javaClass.simpleName}"
            }
            main.post {
                updating = false
                updateMessage = text
            }
        }
    }
}
