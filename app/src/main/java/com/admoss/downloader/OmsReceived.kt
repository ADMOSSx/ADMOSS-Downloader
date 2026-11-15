package com.admoss.downloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** One file that arrived from another phone. [batch] is the start time of the transfer it came with. */
class OmsReceivedItem(
    val uri: String,
    val name: String,
    /** 0 photo, 1 video, 2 music */
    val kind: Int,
    val size: Long,
    val from: String,
    val batch: Long,
    val time: Long,
    val series: String,
    val season: String,
    val movie: Boolean
)

/** The list behind "Received files". Saved on the phone, newest first. */
object OmsReceived {
    var items by mutableStateOf<List<OmsReceivedItem>>(emptyList())

    private var loaded = false
    private val main = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("admoss_received", Context.MODE_PRIVATE)

    /** Main thread. Loads the saved list once. */
    fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        items = try {
            val arr = JSONArray(prefs(ctx).getString("items", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                OmsReceivedItem(
                    o.getString("u"), o.getString("n"), o.optInt("k", 1), o.optLong("s", 0L),
                    o.optString("f", ""), o.optLong("b", 0L), o.optLong("t", 0L),
                    o.optString("sr", ""), o.optString("se", ""), o.optBoolean("m", false)
                )
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("u", it.uri).put("n", it.name).put("k", it.kind).put("s", it.size)
                    .put("f", it.from).put("b", it.batch).put("t", it.time)
                    .put("sr", it.series).put("se", it.season).put("m", it.movie)
            )
        }
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    /** Safe to call from any thread. */
    fun add(ctx: Context, list: List<OmsReceivedItem>) {
        if (list.isEmpty()) return
        val app = ctx.applicationContext
        main.post {
            ensure(app)
            items = (list + items).take(5000)
            save(app)
        }
    }

    /** Main thread. Takes files off the list (it does not touch the files). */
    fun remove(ctx: Context, uris: Set<String>) {
        if (uris.isEmpty()) return
        ensure(ctx)
        items = items.filter { it.uri !in uris }
        save(ctx.applicationContext)
    }
}
