package com.admoss.downloader

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Simple full-screen player. Plays a list one after another (used for playlists). */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_START = "start"
    }

    private lateinit var video: VideoView
    private lateinit var titleView: TextView
    private var uris: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        uris = (intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()).map { Uri.parse(it) }
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
        index = intent.getIntExtra(EXTRA_START, 0)
        if (uris.isEmpty()) {
            finish()
            return
        }

        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        video = VideoView(this)
        root.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        titleView = TextView(this)
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 14f
        titleView.setPadding(pad, pad * 2, pad, pad)
        titleView.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        root.addView(
            titleView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        setContentView(root)

        // Full screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.hide(WindowInsetsCompat.Type.systemBars())
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val controller = MediaController(this)
        controller.setPrevNextListeners(
            View.OnClickListener { playIndex(index + 1) },
            View.OnClickListener { playIndex(index - 1) }
        )
        video.setMediaController(controller)
        video.setOnCompletionListener { playIndex(index + 1) }
        video.setOnErrorListener { _, _, _ ->
            openExternal()
            true
        }

        playIndex(index)
    }

    private fun playIndex(i: Int) {
        if (i >= uris.size) {
            finish()
            return
        }
        if (i < 0) return
        index = i
        val suffix = if (uris.size > 1) "   (${i + 1}/${uris.size})" else ""
        titleView.text = titles.getOrElse(i) { "" } + suffix
        video.setVideoURI(uris[i])
        video.start()
    }

    /** If the built-in player can't play a file, let another app try. */
    private fun openExternal() {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uris[index], "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            // ignore
        }
        finish()
    }

    override fun onDestroy() {
        if (this::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }
}
