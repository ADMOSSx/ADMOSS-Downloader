package com.admoss.downloader

import org.json.JSONObject

enum class Quality(val label: String, val height: Int?) {
    BEST("Best", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360)
}

data class VideoInfoUi(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    /** Estimated download size in bytes for each quality that is really available (0 = unknown). */
    val sizes: Map<Quality, Long>,
    /** Estimated size of the MP3 (192 kbps) in bytes. */
    val audioSize: Long
)

/** Turns the JSON printed by `yt-dlp --dump-single-json` into something the UI can show. */
object InfoParser {

    private class Fmt(
        val height: Int,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val avc: Boolean,
        val aac: Boolean,
        val size: Long,
        val abr: Double
    )

    private fun clean(s: String?): String = if (s == null || s == "null") "" else s

    private fun hasCodec(s: String?): Boolean = !s.isNullOrEmpty() && s != "none" && s != "null"

    fun parse(raw: String): VideoInfoUi {
        val start = raw.indexOf('{')
        if (start < 0) throw Exception("No info returned")
        val o = JSONObject(raw.substring(start))

        val duration = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0L else it.toLong() }

        val fmts = ArrayList<Fmt>()
        val arr = o.optJSONArray("formats")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val vc = f.optString("vcodec", "none")
                val ac = f.optString("acodec", "none")
                val hasV = hasCodec(vc)
                val hasA = hasCodec(ac)
                if (!hasV && !hasA) continue // storyboards etc.

                var size = f.optLong("filesize", 0L)
                if (size <= 0L) size = f.optLong("filesize_approx", 0L)
                if (size <= 0L) {
                    val tbr = f.optDouble("tbr", 0.0)
                    if (!tbr.isNaN() && tbr > 0 && duration > 0) size = (tbr * 125.0 * duration).toLong()
                }
                val abr = f.optDouble("abr", 0.0).let { if (it.isNaN()) 0.0 else it }

                fmts.add(
                    Fmt(
                        height = f.optInt("height", 0),
                        hasVideo = hasV,
                        hasAudio = hasA,
                        avc = hasV && (vc.contains("avc", true) || vc.contains("h264", true)),
                        aac = hasA && (ac.contains("aac", true) || ac.contains("mp4a", true)),
                        size = size,
                        abr = abr
                    )
                )
            }
        }

        val videos = fmts.filter { it.hasVideo && it.height > 0 }
        val audios = fmts.filter { it.hasAudio && !it.hasVideo }
        val bestAudio = audios.filter { it.aac }.maxByOrNull { it.abr } ?: audios.maxByOrNull { it.abr }
        val maxH = videos.maxOfOrNull { it.height } ?: 0

        val sizes = LinkedHashMap<Quality, Long>()
        for (q in Quality.values()) {
            val limit = q.height
            if (limit != null && maxH < limit) continue
            val v = pick(videos, limit) ?: continue
            sizes[q] = total(v, bestAudio)
        }
        if (sizes.isEmpty()) {
            var top = o.optLong("filesize", 0L)
            if (top <= 0L) top = o.optLong("filesize_approx", 0L)
            sizes[Quality.BEST] = top
        }

        return VideoInfoUi(
            title = clean(o.optString("title", "")).ifBlank { "Video" },
            uploader = clean(o.optString("uploader", "")),
            durationSec = duration,
            thumbnailUrl = clean(o.optString("thumbnail", "")).ifBlank { null },
            sizes = sizes,
            audioSize = if (duration > 0) duration * 24000L else 0L // 192 kbps = 24000 bytes/s
        )
    }

    /** Mirrors the yt-dlp sort we use when downloading: prefer H.264, highest resolution up to the limit. */
    private fun pick(videos: List<Fmt>, limit: Int?): Fmt? {
        val pool = if (limit == null) videos else videos.filter { it.height <= limit }
        if (pool.isEmpty()) return null
        val h264 = pool.filter { it.avc }
        val cand = if (h264.isNotEmpty()) h264 else pool
        val top = cand.maxOf { it.height }
        return cand.filter { it.height == top }.maxByOrNull { it.size }
    }

    private fun total(v: Fmt, audio: Fmt?): Long {
        if (v.size <= 0L) return 0L
        val extra = if (!v.hasAudio && audio != null) audio.size else 0L
        return v.size + extra
    }
}

// ---------- Playlists ----------

data class PlaylistEntry(val url: String, val title: String, val durationSec: Long)

data class PlaylistInfo(val title: String, val uploader: String, val entries: List<PlaylistEntry>)

/** Reads the JSON printed by `yt-dlp --flat-playlist --dump-single-json`. */
object PlaylistParser {

    private fun clean(s: String?): String = if (s == null || s == "null") "" else s

    fun parse(raw: String): PlaylistInfo {
        val start = raw.indexOf('{')
        if (start < 0) throw Exception("No info returned")
        val o = JSONObject(raw.substring(start))
        val arr = o.optJSONArray("entries") ?: throw Exception("No videos found in this link")

        val list = ArrayList<PlaylistEntry>()
        for (i in 0 until minOf(arr.length(), 200)) {
            val e = arr.optJSONObject(i) ?: continue
            val id = clean(e.optString("id", ""))
            var url = clean(e.optString("url", ""))
            if (url.isBlank()) url = clean(e.optString("webpage_url", ""))
            if (url.isBlank() && id.isNotBlank()) url = "https://www.youtube.com/watch?v=$id"
            if (url.isBlank()) continue
            if (!url.startsWith("http")) url = "https://www.youtube.com/watch?v=$url"

            val title = clean(e.optString("title", "")).ifBlank { "Video ${i + 1}" }
            if (title == "[Private video]" || title == "[Deleted video]") continue

            val dur = e.optDouble("duration", 0.0).let { if (it.isNaN()) 0L else it.toLong() }
            list.add(PlaylistEntry(url, title, dur))
        }
        if (list.isEmpty()) throw Exception("No videos found in this link")

        return PlaylistInfo(
            title = clean(o.optString("title", "")).ifBlank { "Playlist" },
            uploader = clean(o.optString("uploader", "")).ifBlank { clean(o.optString("channel", "")) },
            entries = list
        )
    }
}
