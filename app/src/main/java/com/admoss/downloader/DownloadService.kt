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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Works through the download queue as a foreground service, so it keeps going when you leave the app. */
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
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }

        // Must be called quickly after startForegroundService()
        val first = DownloadCenter.nextWaiting()
        startForeground(
            NOTIF_PROGRESS,
            buildProgress(first?.title ?: "ADMOSS Downloader", "", 0, "Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        launchWorker()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- worker ----

    private fun launchWorker() {
        if (job?.isActive == true) return
        acquireWakeLock()

        job = scope.launch {
            var processed = 0
            var done = 0
            var failed = 0
            var lastDone: HistoryItem? = null
            var lastError: String? = null
            val nm = getSystemService(NotificationManager::class.java)

            try {
                while (true) {
                    // Work through everything that is waiting
                    while (true) {
                        val item = DownloadCenter.nextWaiting() ?: break
                        val waitingAfter = DownloadCenter.queue.count { it.status == Status.WAITING } - 1
                        val total = processed + 1 + waitingAfter
                        val label = if (total > 1) "${processed + 1} of $total" else ""

                        nm.notify(NOTIF_PROGRESS, buildProgress(item.title, label, 0, "Starting…"))
                        val saved = DownloadCenter.runItem(applicationContext, item) { pct, stage ->
                            nm.notify(NOTIF_PROGRESS, buildProgress(item.title, label, pct, stage))
                        }
                        processed++
                        when (item.status) {
                            Status.DONE -> {
                                done++
                                lastDone = saved
                            }
                            Status.FAILED -> {
                                failed++
                                lastError = item.error
                            }
                            else -> {}
                        }
                    }

                    // Nothing left. Stop, unless a new request arrived in the meantime.
                    val sid = lastStartId
                    if (DownloadCenter.hasWaiting()) continue
                    showSummary(done, failed, lastDone, lastError)
                    done = 0
                    failed = 0
                    lastDone = null
                    lastError = null
                    if (stopSelfResult(sid)) break
                }
            } finally {
                releaseWakeLock()
            }
        }
    }

    // ---- notifications ----

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
                .setDataAndType(Uri.parse(last.uri), if (last.audio) "audio/*" else "video/*")
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
