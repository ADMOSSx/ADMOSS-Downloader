package com.admoss.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class UpdateInfo(val version: String, val apkUrl: String, val size: Long)

/**
 * Checks the latest GitHub release of this repository, downloads the right APK for this phone
 * and hands it to the Android installer. It installs over the old version because every build
 * is signed with the same key.
 */
object Updater {
    var available by mutableStateOf<UpdateInfo?>(null)
    var checking by mutableStateOf(false)
    var downloading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var message by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())

    /** Compose state is only changed on the main thread. */
    private fun ui(block: () -> Unit) {
        main.post { block() }
    }

    /** Called from Application.onCreate so the state objects are created outside Compose. */
    fun init() {}

    fun check(ctx: Context, manual: Boolean) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) {
            if (manual) ui { message = "Updates only work in builds made by GitHub Actions" }
            return
        }
        if (checking) return
        ui {
            checking = true
            if (manual) message = null
        }

        thread(name = "admoss-update-check") {
            try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "ADMOSS-Downloader")
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")

                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name", "")
                val abi = if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "arm64-v8a" else "armeabi-v7a"

                var chosen: JSONObject? = null
                var fallback: JSONObject? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name", "")
                        if (!name.endsWith(".apk")) continue
                        if (fallback == null) fallback = a
                        if (name.contains(abi)) {
                            chosen = a
                            break
                        }
                    }
                }
                val asset = chosen ?: fallback
                val newer = isNewer(tag, BuildConfig.VERSION_NAME)

                if (newer && asset != null) {
                    val info = UpdateInfo(
                        version = tag.removePrefix("v").removePrefix("V"),
                        apkUrl = asset.getString("browser_download_url"),
                        size = asset.optLong("size", 0L)
                    )
                    ui { available = info }
                } else if (manual) {
                    ui {
                        message = if (newer) {
                            "A new version exists, but its APK was not found in the release"
                        } else {
                            "You have the latest version (v${BuildConfig.VERSION_NAME})"
                        }
                    }
                }
            } catch (e: Throwable) {
                if (manual) ui { message = "Couldn't check for updates. Check your internet connection." }
            } finally {
                ui { checking = false }
            }
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V").split(".")
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun downloadAndInstall(ctx: Context) {
        val info = available ?: return
        if (downloading) return

        // Android asks once for permission to install apps from this app
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Throwable) {
                // ignore
            }
            message = "Turn on \"Allow from this source\", come back, and tap UPDATE NOW again"
            return
        }

        message = null
        downloading = true
        progress = 0f

        thread(name = "admoss-update-download") {
            try {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "ADMOSS-Downloader-update.apk")

                val c = URL(info.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "ADMOSS-Downloader")
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size

                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) {
                                    last = pct
                                    ui { progress = pct / 100f }
                                }
                            }
                        }
                    }
                }

                ui {
                    downloading = false
                    progress = 1f
                    install(ctx, file)
                }
            } catch (e: Throwable) {
                ui {
                    downloading = false
                    message = "Update download failed. Try again."
                }
            }
        }
    }

    private fun install(ctx: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Throwable) {
            message = "Couldn't open the installer"
        }
    }
}
