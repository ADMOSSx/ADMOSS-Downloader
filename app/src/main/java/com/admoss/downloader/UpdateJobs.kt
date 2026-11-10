package com.admoss.downloader

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/** Schedules the background check for new releases (every 12 hours, only when there is internet). */
object UpdateScheduler {
    private const val JOB_ID = 4711

    fun apply(ctx: Context) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (AppSettings.autoUpdateEnabled(ctx) && BuildConfig.UPDATE_REPO.isNotBlank()) {
            if (scheduler.getPendingJob(JOB_ID) == null) {
                val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateCheckJobService::class.java))
                    .setPeriodic(12L * 60L * 60L * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
                scheduler.schedule(job)
            }
        } else {
            scheduler.cancel(JOB_ID)
        }
    }
}

class UpdateCheckJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        thread(name = "admoss-update-job") {
            try {
                if (AppSettings.autoUpdateEnabled(app)) {
                    val result = Updater.fetchLatest()
                    AppSettings.markChecked(app)
                    result.update?.let { Updater.notifyUpdate(app, it) }
                }
            } catch (e: Throwable) {
                // no internet or GitHub unreachable: the next run tries again
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
