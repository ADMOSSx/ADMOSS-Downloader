package com.admoss.downloader

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Saves the error text when the app crashes, so it can be shown (and copied) on the next launch. */
class AdmossApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Install the handler BEFORE initializing anything that could fail during startup.
        // Keep the current startup stage so the next-launch report points to the failing area.
        var startupStage = "Application.onCreate"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildString {
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Startup stage: $startupStage")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(Log.getStackTraceString(error))
                }
                File(filesDir, "last_crash.txt").writeText(report.take(12000))
            } catch (loggingError: Throwable) {
                Log.e("ADMOSS-Crash", "Could not save crash report", loggingError)
            }

            // Preserve Android's normal crash handling.
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Log.e("ADMOSS-Crash", "Uncaught exception", error)
            }
        }

        // Startup helpers are isolated: an optional scheduler or engine initialization
        // failure must not prevent Android from opening the main screen.
        fun initialize(stage: String, action: () -> Unit) {
            startupStage = stage
            try {
                action()
            } catch (error: Throwable) {
                Log.e("ADMOSS-Startup", "Initialization failed at: $stage", error)
            }
        }

        initialize("DownloadCenter.init") { DownloadCenter.init(this) }
        initialize("Engine.start") { Engine.start(this) }
        initialize("Updater.init") { Updater.init() }
        initialize("AppSettings.init") { AppSettings.init(this) }
        initialize("UpdateScheduler.apply") { UpdateScheduler.apply(this) }
        startupStage = "Application startup completed"
    }
}

object CrashReport {
    fun read(ctx: Context): String? {
        val f = File(ctx.filesDir, "last_crash.txt")
        return if (f.exists()) f.readText() else null
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, "last_crash.txt").delete()
    }
}
