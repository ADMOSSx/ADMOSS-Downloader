package com.admoss.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Works through the download queue as a foreground service, so it keeps going when you leave the app.
 * Up to 3 downloads run at the same time (chosen in Settings).
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.admoss.downloader.START"
        const val ACTION_CANCEL_ALL = "com.admoss.downloader.CANCEL_ALL"

        private const val CHANNEL_PROGRESS = "admoss_progress"
        private const val CHANNEL_DONE = "admoss_done"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_DONE = 1002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    private val running = AtomicInteger(0)   // workers alive right now
    private val doneCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)

    @Volatile
    private var lastDone: HistoryItem? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId

        if (intent?.action == ACTION_CANCEL_ALL) {
            DownloadCenter.cancelAll()
            if (running.get() == 0) stopSelf()
            return START_NOT_STICKY
        }

        // Must be called quickly after startForegroundService()
        val first = DownloadCenter.nextWaiting()
        startForeground(
            NOTIF_PROGRESS,
            buildProgress(first?.title ?: "ADMOSS Downloader", "", 0, "Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireWakeLock()
        spawnWorkers()
        // Nothing was waiting (for example it was cancelled already): don't stay in the foreground
        if (running.get() == 0) {
            releaseWakeLock()
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- workers ----

    /** Starts as many workers as allowed (and needed). Safe to call again when new items arrive. */
    private fun spawnWorkers() {
        val limit = AppSettings.parallelNow(applicationContext)
        while (DownloadCenter.hasWaiting() && running.get() < limit) {
            running.incrementAndGet()
            scope.launch {
                try {
                    workerLoop()
                } finally {
                    if (running.decrementAndGet() == 0) finishRound()
                }
            }
        }
    }

    private fun workerLoop() {
        while (true) {
            val item = DownloadCenter.claimNext() ?: break
            val saved = DownloadCenter.runItem(applicationContext, item) { _, _ -> updateNotification() }
            when (item.status) {
                Status.DONE -> {
                    doneCount.incrementAndGet()
                    lastDone = saved
                }
                Status.FAILED -> {
                    failedCount.incrementAndGet()
                    lastError = item.error
                }
                else -> {}
            }
            updateNotification()
        }
    }

    /** The last worker has no more work. Stop, unless something new arrived in the meantime. */
    private fun finishRound() {
        val sid = lastStartId
        if (DownloadCenter.hasWaiting()) {
            spawnWorkers()
            return
        }
        showSummary(doneCount.getAndSet(0), failedCount.getAndSet(0), lastDone, lastError)
        lastDone = null
        lastError = null
        if (stopSelfResult(sid)) {
            releaseWakeLock()
        } else {
            spawnWorkers() // a new request arrived while finishing
        }
    }

    // ---- notifications ----

    private fun updateNotification() {
        val queue = DownloadCenter.queue.toList()
        val active = queue.filter { it.status == Status.DOWNLOADING }
        val waiting = queue.count { it.status == Status.WAITING }
        val title = when {
            active.isEmpty() -> "ADMOSS Downloader"
            active.size == 1 -> active[0].title
            else -> "${active.size} downloads running"
        }
        val pct = if (active.isEmpty()) 0 else (active.map { it.progress }.average() * 100).toInt()
        val stage = if (active.size == 1) active[0].stage else "Downloading…"
        val label = if (waiting > 0) "$waiting waiting" else ""
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_PROGRESS, buildProgress(title, label, pct, stage))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildProgress(title: String, label: String, pct: Int, stage: String): Notification {
        val cancelAll = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (pct > 0) "$stage  $pct%" else stage)
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel all", cancelAll)
        if (label.isNotBlank()) builder.setSubText(label)
        return builder.build()
    }

    private fun showSummary(done: Int, failed: Int, last: HistoryItem?, lastError: String?) {
        if (done == 0 && failed == 0) return
        val nm = getSystemService(NotificationManager::class.java)

        val builder = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setAutoCancel(true)

        if (done == 1 && failed == 0 && last != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    Uri.parse(last.uri),
                    if (last.photoUris.isNotEmpty()) "image/*" else if (last.audio) "audio/*" else "video/*"
                )
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                this, 2, view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete")
                .setContentText(last.title)
                .setContentIntent(pi)
        } else if (done == 0 && failed == 1) {
            val err = lastError ?: "Something went wrong"
            builder.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText(err)
                .setStyle(NotificationCompat.BigTextStyle().bigText(err))
                .setContentIntent(openAppIntent())
        } else {
            val text = if (failed > 0) "$done finished, $failed failed" else "$done downloads finished"
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Downloads finished")
                .setContentText(text)
                .setContentIntent(openAppIntent())
        }
        nm.notify(NOTIF_DONE, builder.build())
    }

    // ---- wake lock ----

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "admoss:download").apply {
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
