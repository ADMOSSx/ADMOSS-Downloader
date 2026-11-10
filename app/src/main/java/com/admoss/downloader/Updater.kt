package com.admoss.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val size: Long,
    val notes: String,
    /** SHA-256 of the APK as published by GitHub, when GitHub provides it. */
    val sha256: String?,
    /** A ".sha256" file published next to the APK, when there is one. */
    val sha256Url: String?
)

data class CheckResult(val latestTag: String, val update: UpdateInfo?, val missingApk: Boolean)

/**
 * Looks at the newest GitHub release of this repository, downloads the right APK for this phone,
 * checks it, and hands it to Android's own installer, which asks the user to confirm.
 * The update installs over the current app, so settings and the library are kept.
 */
object Updater {
    var available by mutableStateOf<UpdateInfo?>(null)
    var checking by mutableStateOf(false)
    var downloading by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var stage by mutableStateOf("")
    var message by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())

    /** Compose state is only changed on the main thread. */
    private fun ui(block: () -> Unit) {
        main.post { block() }
    }

    /** Called from Application.onCreate so the state objects are created outside Compose. */
    fun init() {}

    // ---------- finding the newest release ----------

    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "ADMOSS-Downloader")
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Blocking. Asks GitHub for the latest release. Throws if GitHub cannot be reached. */
    fun fetchLatest(): CheckResult {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) throw IllegalStateException("No repository configured")

        val json = JSONObject(httpText("https://api.github.com/repos/$repo/releases/latest"))
        val tag = json.optString("tag_name", "")
        val newer = isNewer(tag, BuildConfig.VERSION_NAME)
        if (!newer) return CheckResult(tag, null, false)

        val abi = if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "arm64-v8a" else "armeabi-v7a"
        var chosen: JSONObject? = null
        var fallback: JSONObject? = null
        val assets = json.optJSONArray("assets")
        val byName = HashMap<String, JSONObject>()
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                byName[name] = a
                if (!name.endsWith(".apk")) continue
                if (fallback == null) fallback = a
                if (chosen == null && name.contains(abi)) chosen = a
            }
        }
        val asset = chosen ?: fallback ?: return CheckResult(tag, null, true)

        val digest = asset.optString("digest", "")
        val sha = if (digest.startsWith("sha256:") && digest.length == 71) digest.substring(7).lowercase() else null
        val shaAsset = byName[asset.optString("name", "") + ".sha256"]

        return CheckResult(
            tag,
            UpdateInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                apkUrl = asset.getString("browser_download_url"),
                size = asset.optLong("size", 0L),
                notes = cleanNotes(json.optString("body", "")),
                sha256 = sha,
                sha256Url = shaAsset?.optString("browser_download_url", "")?.ifBlank { null }
            ),
            false
        )
    }

    /** From the app: never blocks. [manual] = the user asked, so say what happened even if nothing is new. */
    fun check(ctx: Context, manual: Boolean) {
        if (BuildConfig.UPDATE_REPO.isBlank()) {
            if (manual) ui { message = "Updates only work in builds made by GitHub Actions" }
            return
        }
        if (checking) return
        ui {
            checking = true
            if (manual) message = null
        }
        val app = ctx.applicationContext

        thread(name = "admoss-update-check") {
            try {
                val result = fetchLatest()
                AppSettings.markChecked(app)
                val update = result.update
                if (update != null) {
                    ui { available = update }
                } else if (manual) {
                    ui {
                        message = if (result.missingApk) {
                            "A new version exists, but its APK is not attached to the release yet."
                        } else {
                            "You have the latest version (v${BuildConfig.VERSION_NAME})."
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

    /** "1.10.0" is newer than "1.9.5". */
    fun isNewer(remote: String, local: String): Boolean {
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

    /** Turns the GitHub release text (markdown, badges, tables) into short plain text. */
    fun cleanNotes(body: String): String {
        val lines = ArrayList<String>()
        val separator = Regex("^\\|?[\\s:|-]+\\|?$")
        val source = body.replace("\r", "").lines()
        for ((idx, raw) in source.withIndex()) {
            var l = raw.trim()
            if (l.isEmpty()) {
                if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines.add("")
                continue
            }
            if (l.startsWith("![") || l.contains("img.shields.io") || l.startsWith("---") || l.startsWith("```")) continue
            if (separator.matches(l)) continue // table separator row
            // the header row of a table (the line right before the separator row) is only labels
            if (l.startsWith("|") && idx + 1 < source.size && separator.matches(source[idx + 1].trim())) continue
            l = l.replace(Regex("<[^>]+>"), "")
            l = l.replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            l = l.replace(Regex("^#{1,6}\\s*"), "").replace("**", "").replace("`", "")
            if (l.startsWith("|")) {
                val cells = l.trim('|').split("|").map { it.trim() }.filter { it.isNotEmpty() }
                l = "• " + cells.joinToString("  ")
            } else if (l.startsWith("- ") || l.startsWith("* ")) {
                l = "• " + l.drop(2)
            }
            l = l.trim()
            if (l.isEmpty() || l == "•") continue
            lines.add(l)
        }
        val text = lines.joinToString("\n").trim()
        return if (text.length > 900) text.take(900).trimEnd() + "…" else text
    }

    // ---------- download, check, install ----------

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
            message = "Turn on \"Allow from this source\", come back, and tap Update now again"
            return
        }

        message = null
        downloading = true
        progress = 0f
        stage = "Downloading update…"

        thread(name = "admoss-update-download") {
            try {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "ADMOSS-Downloader-update.apk")
                val sha = MessageDigest.getInstance("SHA-256")

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
                            sha.update(buf, 0, read)
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
                    progress = 1f
                    stage = "Checking the file…"
                }
                val hash = sha.digest().joinToString("") { "%02x".format(it) }
                val problem = verify(ctx, file, info, hash)
                if (problem != null) {
                    file.delete()
                    ui {
                        downloading = false
                        message = problem
                    }
                    return@thread
                }

                ui {
                    downloading = false
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

    /** Returns a message when the file must not be installed, or null when everything is fine. */
    private fun verify(ctx: Context, file: File, info: UpdateInfo, hash: String): String? {
        // 1. The whole file arrived
        if (info.size > 0 && file.length() != info.size) {
            return "The download is incomplete. Please try again."
        }

        // 2. The checksum matches the one published with the release
        var expected = info.sha256
        if (expected == null && info.sha256Url != null) {
            expected = try {
                httpText(info.sha256Url).trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
            } catch (e: Throwable) {
                return "Couldn't read the checksum of this update, so it was not installed."
            }
        }
        if (expected != null && expected != hash) {
            return "The file did not pass the safety check (checksum mismatch), so it was not installed."
        }

        // 3. It is really an APK of this app, newer than this one, signed with the same key
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "The downloaded file is not a valid APK."
        if (archive.packageName != ctx.packageName) {
            return "This APK belongs to a different app, so it was not installed."
        }
        val installed = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) {
            return "This version is not newer than the one you already have."
        }
        val newKeys = signerHashes(archive)
        val oldKeys = signerHashes(installed)
        if (newKeys.isEmpty() || newKeys.intersect(oldKeys).isEmpty()) {
            return "This update is signed with a different key than your installed app, so Android " +
                "would not install it over your data. Uninstall the app once, then install the new version."
        }
        return null
    }

    private fun signerHashes(info: PackageInfo): Set<String> {
        val signers = info.signingInfo?.apkContentsSigners ?: return emptySet()
        return signers.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Opens Android's own installer. It shows its confirmation screen before anything is installed. */
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

    // ---------- notification when a new version is found in the background ----------

    fun notifyUpdate(ctx: Context, info: UpdateInfo) {
        val prefs = ctx.getSharedPreferences("admoss", Context.MODE_PRIVATE)
        if (prefs.getString("notified_version", "") == info.version) return // already told the user

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("admoss_update", "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = info.notes.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "Tap to see what's new"
        nm.notify(
            2001,
            NotificationCompat.Builder(ctx, "admoss_update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("ADMOSS Downloader ${info.version} is available")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        prefs.edit().putString("notified_version", info.version).apply()
    }
}
