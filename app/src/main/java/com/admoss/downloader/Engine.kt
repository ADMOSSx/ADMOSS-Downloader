package com.admoss.downloader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

/** Starts the yt-dlp + ffmpeg engine once for the whole app (UI and service share it). */
object Engine {
    var ready by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var message by mutableStateOf("Preparing engine…")
    var failure by mutableStateOf<String?>(null)

    @Volatile
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(name = "admoss-engine") {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                ready = true
            } catch (e: Exception) {
                failed = true
                message = "Engine failed to start"
                failure = e.message
                return@thread
            }
            // Keep yt-dlp fresh: YouTube changes often. Silent if offline.
            try {
                YoutubeDL.getInstance().updateYoutubeDL(app)
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
