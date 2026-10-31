package com.admoss.downloader

import com.yausername.youtubedl_android.YoutubeDLRequest

/** Second-chance settings used when the first yt-dlp attempt fails. */
object YtOptions {

    /** IPv4 only + the mobile web client often works when the default one is blocked. */
    fun addFallback(req: YoutubeDLRequest) {
        req.addOption("--force-ipv4")
        req.addOption("--extractor-args", "youtube:player_client=mweb")
    }

    /** No point retrying when the phone is offline or the video itself is unavailable. */
    fun shouldRetry(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        val hopeless = listOf(
            "unable to resolve host", "network is unreachable", "no address associated",
            "private video", "video has been removed", "this video is not available in your country"
        )
        return hopeless.none { m.contains(it) }
    }
}
