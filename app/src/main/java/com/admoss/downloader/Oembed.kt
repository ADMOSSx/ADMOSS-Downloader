package com.admoss.downloader

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Title, author and cover that can be shown before the slower yt-dlp lookup finishes. */
data class QuickInfo(val title: String, val author: String, val thumbnailUrl: String?)

/** YouTube and TikTok answer these small "oEmbed" requests in a fraction of a second. */
object Oembed {
    fun fetch(url: String): QuickInfo? {
        val u = url.lowercase()
        val enc = URLEncoder.encode(url, "UTF-8")
        val api = when {
            "youtube.com" in u || "youtu.be" in u -> "https://www.youtube.com/oembed?format=json&url=$enc"
            "tiktok.com" in u -> "https://www.tiktok.com/oembed?url=$enc"
            else -> return null
        }
        return try {
            val c = URL(api).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val title = o.optString("title", "").trim()
            if (title.isBlank() || title == "null") return null
            val author = o.optString("author_name", "").trim().let { if (it == "null") "" else it }
            val thumb = o.optString("thumbnail_url", "").trim().takeIf { it.startsWith("http") }
            QuickInfo(title, author, thumb)
        } catch (e: Throwable) {
            null
        }
    }
}
