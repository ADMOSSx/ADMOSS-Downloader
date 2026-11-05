package com.admoss.downloader

import com.yausername.youtubedl_android.YoutubeDLRequest

/** Extra yt-dlp settings: a faster lookup, and a second-chance setup when the first try fails. */
object YtOptions {

    /** Skips extra YouTube requests that are not needed to read the title, sizes and formats. */
    private const val YT_SKIP = "skip=hls,dash,translated_subs"

    /** For the info lookup only: fewer network requests, so the answer comes sooner. */
    fun addInfoSpeedups(req: YoutubeDLRequest) {
        req.addOption("--extractor-args", "youtube:$YT_SKIP")
    }

    /** IPv4 only + the mobile web client often works when the default one is blocked. */
    fun addFallback(req: YoutubeDLRequest, forInfo: Boolean = false) {
        req.addOption("--force-ipv4")
        // one single --extractor-args for YouTube, so the settings are not overwritten
        req.addOption("--extractor-args", "youtube:player_client=mweb" + if (forInfo) ";$YT_SKIP" else "")
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
