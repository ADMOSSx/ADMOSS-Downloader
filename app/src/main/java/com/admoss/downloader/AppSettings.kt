package com.admoss.downloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The few settings the app has. Saved in SharedPreferences, readable from the UI and from background jobs. */
object AppSettings {
    var autoUpdate by mutableStateOf(true)
    var parallel by mutableStateOf(2)          // downloads running at the same time (1 to 3)
    var lastUpdateCheck by mutableStateOf(0L)

    private var loaded = false
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("admoss", Context.MODE_PRIVATE)

    /** Called from Application.onCreate, outside Compose. */
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        autoUpdate = p.getBoolean("auto_update", true)
        parallel = p.getInt("parallel", 2).coerceIn(1, 3)
        lastUpdateCheck = p.getLong("last_update_check", 0L)
    }

    fun setAutoUpdate(ctx: Context, value: Boolean) {
        autoUpdate = value
        prefs(ctx).edit().putBoolean("auto_update", value).apply()
        UpdateScheduler.apply(ctx)
    }

    fun setParallel(ctx: Context, value: Int) {
        parallel = value.coerceIn(1, 3)
        prefs(ctx).edit().putInt("parallel", parallel).apply()
    }

    /** For background code that has no UI state. */
    fun autoUpdateEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_update", true)

    fun parallelNow(ctx: Context): Int = prefs(ctx).getInt("parallel", 2).coerceIn(1, 3)

    fun markChecked(ctx: Context) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putLong("last_update_check", now).apply()
        main.post { lastUpdateCheck = now }
    }
}
