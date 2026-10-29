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
        // Create the shared state objects here (outside Compose) and start the engine early
        DownloadCenter.init(this)
        Engine.start(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "Thread: ${t.name}\n\n" + Log.getStackTraceString(e)
                File(filesDir, "last_crash.txt").writeText(text.take(6000))
            } catch (ignored: Throwable) {
                // ignore
            }
            previous?.uncaughtException(t, e)
        }
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
