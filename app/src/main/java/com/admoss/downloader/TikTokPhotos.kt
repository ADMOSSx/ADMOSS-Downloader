package com.admoss.downloader

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A TikTok "photo mode" post: several pictures plus a music track. */
data class PhotoPost(
    val id: String,
    val title: String,
    val author: String,
    val images: List<String>,
    val musicUrl: String?,
    val musicTitle: String
)

/**
 * yt-dlp only returns the music of a photo post, never the pictures, so photo posts are read here.
 * First through the tikwm.com API, then by reading the TikTok page itself.
 */
object TikTokPhotos {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    fun isTikTok(url: String): Boolean = url.lowercase().contains("tiktok.com")

    private fun clean(s: String?): String = if (s == null || s == "null") "" else s.trim()

    /** Returns the post, or null when the link is not a photo post or could not be read. */
    fun fetch(url: String): PhotoPost? {
        val viaApi = try {
            fromApi(url)
        } catch (e: Throwable) {
            null
        }
        if (viaApi != null) return viaApi
        return try {
            fromPage(url)
        } catch (e: Throwable) {
            null
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun fromApi(url: String): PhotoPost? {
        val api = "https://www.tikwm.com/api/?hd=1&url=" + URLEncoder.encode(url, "UTF-8")
        val root = JSONObject(httpGet(api))
        if (root.optInt("code", -1) != 0) return null
        val d = root.optJSONObject("data") ?: return null

        val arr = d.optJSONArray("images") ?: return null
        val images = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val u = clean(arr.optString(i, ""))
            if (u.startsWith("http")) images.add(u)
        }
        if (images.isEmpty()) return null

        val info = d.optJSONObject("music_info")
        val music = clean(d.optString("music", "")).ifBlank { clean(info?.optString("play", "")) }
        return PhotoPost(
            id = clean(d.optString("id", "")),
            title = clean(d.optString("title", "")),
            author = clean(d.optJSONObject("author")?.optString("unique_id", "")),
            images = images,
            musicUrl = music.ifBlank { null },
            musicTitle = clean(info?.optString("title", ""))
        )
    }

    private fun fromPage(url: String): PhotoPost? {
        val html = httpGet(url)
        val m = Regex(
            "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: return null

        val root = JSONObject(m.groupValues[1])
        val item = root.optJSONObject("__DEFAULT_SCOPE__")
            ?.optJSONObject("webapp.video-detail")
            ?.optJSONObject("itemInfo")
            ?.optJSONObject("itemStruct") ?: return null

        val arr = item.optJSONObject("imagePost")?.optJSONArray("images") ?: return null
        val images = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i)?.optJSONObject("imageURL")?.optJSONArray("urlList")?.optString(0, "") ?: ""
            if (u.startsWith("http")) images.add(u)
        }
        if (images.isEmpty()) return null

        val music = item.optJSONObject("music")
        return PhotoPost(
            id = clean(item.optString("id", "")),
            title = clean(item.optString("desc", "")),
            author = clean(item.optJSONObject("author")?.optString("uniqueId", "")),
            images = images,
            musicUrl = clean(music?.optString("playUrl", "")).ifBlank { null },
            musicTitle = clean(music?.optString("title", ""))
        )
    }
}
