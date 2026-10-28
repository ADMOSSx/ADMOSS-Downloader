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

/** Runs a download as a foreground service so it keeps going when you leave the app. */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.admoss.downloader.START"
        const val ACTION_CANCEL = "com.admoss.downloader.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_TITLE = "title"

        private const val CHANNEL_PROGRESS = "admoss_progress"
        private const val CHANNEL_DONE = "admoss_done"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_DONE = 1002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
        if (intent?.action == ACTION_CANCEL) {
            DownloadCenter.cancel()
            return START_NOT_STICKY
        }

        val i = intent ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        val title = i.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Video" }

        // Must be called quickly after startForegroundService()
        startForeground(
            NOTIF_PROGRESS,
            buildProgress(title, 0, "Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val url = i.getStringExtra(EXTRA_URL)
        if (url == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY

        val audio = i.getBooleanExtra(EXTRA_AUDIO, false)
        val height = i.getIntExtra(EXTRA_HEIGHT, -1).takeIf { it > 0 }

        acquireWakeLock()
        job = scope.launch {
            var item: HistoryItem? = null
            try {
                item = DownloadCenter.run(applicationContext, url, audio, height) { pct, stage ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_PROGRESS, buildProgress(title, pct, stage))
                }
            } finally {
                releaseWakeLock()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            showResult(item)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications ----

    private fun buildProgress(title: String, pct: Int, stage: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (pct > 0) "$stage  $pct%" else stage)
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun showResult(item: HistoryItem?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (item != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(item.uri), if (item.audio) "audio/*" else "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                this, 2, view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                NOTIF_DONE,
                NotificationCompat.Builder(this, CHANNEL_DONE)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Download complete")
                    .setContentText(item.title)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        } else {
            val err = DownloadCenter.error ?: return
            nm.notify(
                NOTIF_DONE,
                NotificationCompat.Builder(this, CHANNEL_DONE)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Download failed")
                    .setContentText(err)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(err))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    // ---- wake lock ----

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "admoss:download").apply {
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
