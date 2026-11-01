package com.admoss.downloader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private val Accent = Color(0xFF00E5FF)
private val Violet = Color(0xFF7C4DFF)
private val Panel = Color(0xF2141A2E)
private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Full-screen player: speed menu, double-tap seeking, brightness and volume swipes,
 * resume where you stopped, repeat modes, zoom to fill and a playlist menu.
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_THUMBS = "thumbs"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_SIZES = "sizes"
        const val EXTRA_NAME = "name"
        const val EXTRA_START = "start"
    }

    private var uriList: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        uriList = (intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()).map { Uri.parse(it) }
        if (uriList.isEmpty()) {
            finish()
            return
        }
        val titles: List<String> = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        val thumbs: List<String> = intent.getStringArrayListExtra(EXTRA_THUMBS) ?: arrayListOf()
        val audio: BooleanArray = intent.getBooleanArrayExtra(EXTRA_AUDIO) ?: BooleanArray(uriList.size)
        val sizes: LongArray = intent.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(uriList.size)
        val name = intent.getStringExtra(EXTRA_NAME)
        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, uriList.size - 1)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Violet)) {
                PlayerScreen(
                    uris = uriList,
                    titles = titles,
                    thumbs = thumbs,
                    audio = audio,
                    sizes = sizes,
                    name = name,
                    start = start,
                    onClose = { finish() },
                    onRotate = { toggleRotation() },
                    onExternal = { openExternal(it) }
                )
            }
        }

        // Hide the system bars (swipe from the edge to show them)
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.hide(WindowInsetsCompat.Type.systemBars())
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun toggleRotation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /** If the built-in player can't play a file, let another app try. */
    private fun openExternal(index: Int) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriList[index], "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            // ignore
        }
        finish()
    }
}

// ---------- helpers ----------

private fun fmtTime(ms: Int): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes > 0L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> ""
}

private fun speedLabel(s: Float): String =
    (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"

private fun currentBrightness(act: Activity?): Float {
    if (act == null) return 0.5f
    val b = act.window.attributes.screenBrightness
    if (b >= 0f) return b
    return try {
        Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Throwable) {
        0.5f
    }
}

private fun setBrightness(act: Activity?, value: Float) {
    if (act == null) return
    val lp = act.window.attributes
    lp.screenBrightness = value
    act.window.attributes = lp
}

@Composable
private fun PIcon(@DrawableRes id: Int, size: Dp = 24.dp, tint: Color = Color.White) {
    Icon(
        painter = painterResource(id),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size)
    )
}

@Composable
private fun RoundBtn(
    @DrawableRes id: Int,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        PIcon(
            id,
            iconSize,
            when {
                !enabled -> Color.White.copy(alpha = 0.3f)
                active -> Accent
                else -> Color.White
            }
        )
    }
}

// ---------- screen ----------

@Composable
private fun PlayerScreen(
    uris: List<Uri>,
    titles: List<String>,
    thumbs: List<String>,
    audio: BooleanArray,
    sizes: LongArray,
    name: String?,
    start: Int,
    onClose: () -> Unit,
    onRotate: () -> Unit,
    onExternal: (Int) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val prefs = remember { context.getSharedPreferences("admoss_resume", Context.MODE_PRIVATE) }
    val video = remember { VideoView(context) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }

    var index by remember { mutableStateOf(start) }
    var speed by remember { mutableStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var controls by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var showList by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var repeatMode by remember { mutableStateOf(0) } // 0 off, 1 all, 2 one
    var fill by remember { mutableStateOf(false) }

    val multi = uris.size > 1
    val isAudio = audio.getOrElse(index) { false }

    /** Zoom the picture so it fills the screen (crops the edges), or fit it inside. */
    fun updateScale() {
        val parent = video.parent as? View ?: return
        val vw = video.width
        val vh = video.height
        if (vw <= 0 || vh <= 0 || parent.width <= 0 || parent.height <= 0) return
        val audioNow = audio.getOrElse(index) { false }
        val s = if (fill && !audioNow) {
            maxOf(parent.width / vw.toFloat(), parent.height / vh.toFloat())
        } else {
            1f
        }
        video.scaleX = s
        video.scaleY = s
    }

    /** Remember where the current video stopped, so it can continue next time. */
    fun saveResume() {
        val d = video.duration
        val p = video.currentPosition
        if (d <= 0) return
        val key = uris[index].toString()
        if (p > 5000 && p < d - 8000) {
            prefs.edit().putInt(key, p).apply()
        } else if (p >= d - 8000) {
            prefs.edit().remove(key).apply()
        }
    }

    DisposableEffect(Unit) {
        video.setOnPreparedListener { mp ->
            player[0] = mp
            try {
                mp.playbackParams = mp.playbackParams.setSpeed(speed)
            } catch (e: Throwable) {
                // speed not supported for this file
            }
            val saved = prefs.getInt(uris[index].toString(), 0)
            val dur = mp.duration
            if (saved > 5000 && dur > 0 && saved < dur - 8000) {
                video.seekTo(saved)
                hint = "Resumed at ${fmtTime(saved)}"
            }
            video.post { updateScale() }
        }
        video.setOnCompletionListener {
            prefs.edit().remove(uris[index].toString()).apply()
            when {
                repeatMode == 2 -> {
                    video.seekTo(0)
                    video.start()
                }
                index < uris.size - 1 -> {
                    index += 1
                }
                repeatMode == 1 -> {
                    if (uris.size == 1) {
                        video.seekTo(0)
                        video.start()
                    } else {
                        index = 0
                    }
                }
                else -> {
                    ended = true
                    playing = false
                    controls = true
                }
            }
        }
        video.setOnErrorListener { _, _, _ ->
            onExternal(index)
            true
        }
        onDispose {
            saveResume()
            video.stopPlayback()
        }
    }

    // Load and start the current video
    LaunchedEffect(index) {
        ended = false
        position = 0
        duration = 0
        video.setVideoURI(uris[index])
        video.start()
    }

    LaunchedEffect(fill) { updateScale() }

    // Keep the seek bar and play button in sync, and save the position now and then
    LaunchedEffect(Unit) {
        var saveTick = 0
        while (true) {
            playing = video.isPlaying
            if (dragFraction == null) {
                duration = video.duration.coerceAtLeast(0)
                position = video.currentPosition.coerceAtLeast(0)
            }
            if (fill) updateScale()
            if (playing) {
                saveTick += 1
                if (saveTick >= 12) {
                    saveTick = 0
                    saveResume()
                }
            }
            delay(250)
        }
    }

    // Hide the controls after a few seconds while playing
    LaunchedEffect(tick, controls, playing, showList, showSpeed) {
        if (controls && playing && !showList && !showSpeed) {
            delay(3500)
            controls = false
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(900)
            hint = null
        }
    }

    BackHandler(enabled = showList || showSpeed) {
        showList = false
        showSpeed = false
    }

    fun seekBy(ms: Int) {
        val d = video.duration
        val upper = if (d > 0) d else Int.MAX_VALUE
        val target = (video.currentPosition + ms).coerceIn(0, upper)
        video.seekTo(target)
        position = target
        hint = if (ms > 0) "+${ms / 1000}s" else "-${-ms / 1000}s"
        tick += 1
    }

    fun applySpeed(s: Float) {
        speed = s
        val mp = player[0] ?: return
        try {
            val wasPlaying = video.isPlaying
            mp.playbackParams = mp.playbackParams.setSpeed(s)
            // Setting a speed makes MediaPlayer play, so pause again if it was paused
            if (!wasPlaying) video.pause()
        } catch (e: Throwable) {
            // ignore
        }
        hint = speedLabel(s)
    }

    fun togglePlay() {
        if (ended) {
            video.seekTo(0)
            video.start()
            ended = false
        } else if (video.isPlaying) {
            video.pause()
        } else {
            video.start()
        }
        tick += 1
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        hint = when (repeatMode) {
            1 -> "Repeat all"
            2 -> "Repeat one"
            else -> "Repeat off"
        }
        tick += 1
    }

    fun goTo(i: Int) {
        if (i < 0 || i >= uris.size) return
        saveResume()
        index = i
        tick += 1
    }

    val title = titles.getOrElse(index) { "" }
    val subtitle = buildString {
        if (!name.isNullOrBlank()) append(name)
        if (multi) {
            if (isNotEmpty()) append("  •  ")
            append("${index + 1} of ${uris.size}")
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Video
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    (video.parent as? ViewGroup)?.removeView(video)
                    addView(
                        video,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Audio files show their cover instead of a black screen
        if (isAudio) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Thumb(
                    thumbs.getOrNull(index)?.takeIf { it.isNotBlank() },
                    Modifier
                        .fillMaxHeight(0.5f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(22.dp)),
                    R.drawable.ic_music,
                    72.dp
                )
            }
        }

        // Tap: show / hide controls. Double-tap: jump 10 seconds.
        // Swipe up or down on the left side: brightness. On the right side: volume.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controls = !controls
                            tick += 1
                        },
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2) seekBy(-10000) else seekBy(10000)
                        }
                    )
                }
                .pointerInput(Unit) {
                    var side = 0
                    var startValue = 0f
                    var travelled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            side = if (offset.x < size.width / 2) 1 else 2
                            travelled = 0f
                            startValue = if (side == 1) {
                                currentBrightness(activity)
                            } else {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                            }
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            travelled -= dy
                            val v = (startValue + travelled / (size.height * 0.75f)).coerceIn(0f, 1f)
                            if (side == 1) {
                                setBrightness(activity, v.coerceAtLeast(0.02f))
                                hint = "Brightness ${(v * 100).roundToInt()}%"
                            } else {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
                                hint = "Volume ${(v * 100).roundToInt()}%"
                            }
                        }
                    )
                }
        )

        // Short message at the top (+10s, speed, brightness ...)
        hint?.let { h ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(h, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        // Controls
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(190.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                )

                // Top bar
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .displayCutoutPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundBtn(R.drawable.ic_back) { onClose() }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    RoundBtn(R.drawable.ic_rotate) { onRotate() }
                    if (multi) {
                        Spacer(Modifier.width(8.dp))
                        RoundBtn(R.drawable.ic_playlist) {
                            showList = true
                            showSpeed = false
                        }
                    }
                }

                // Center buttons
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(26.dp)
                ) {
                    if (multi) {
                        RoundBtn(R.drawable.ic_prev, 52.dp, 26.dp, enabled = index > 0) { goTo(index - 1) }
                    }
                    Box(
                        Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(Color(0xFF00B8D4), Violet)))
                            .clickable { togglePlay() },
                        contentAlignment = Alignment.Center
                    ) {
                        PIcon(
                            when {
                                ended -> R.drawable.ic_refresh
                                playing -> R.drawable.ic_pause
                                else -> R.drawable.ic_play
                            },
                            40.dp
                        )
                    }
                    if (multi) {
                        RoundBtn(R.drawable.ic_next, 52.dp, 26.dp, enabled = index < uris.size - 1) { goTo(index + 1) }
                    }
                }

                // Bottom: seek bar and actions
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .displayCutoutPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    val shownPos = dragFraction?.let { (it * duration).toInt() } ?: position
                    val fraction = dragFraction ?: if (duration > 0) position.toFloat() / duration else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtTime(shownPos), color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = fraction.coerceIn(0f, 1f),
                            onValueChange = {
                                dragFraction = it
                                tick += 1
                            },
                            onValueChangeFinished = {
                                val f = dragFraction
                                if (f != null && duration > 0) {
                                    val target = (f * duration).toInt()
                                    video.seekTo(target)
                                    position = target
                                }
                                dragFraction = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Text(fmtTime(duration), color = Color.White, fontSize = 12.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.16f))
                                .clickable {
                                    showSpeed = !showSpeed
                                    showList = false
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                speedLabel(speed),
                                color = if (speed == 1f) Color.White else Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundBtn(R.drawable.ic_rewind, 36.dp, 18.dp) { seekBy(-10000) }
                        Spacer(Modifier.width(6.dp))
                        RoundBtn(R.drawable.ic_forward, 36.dp, 18.dp) { seekBy(10000) }
                        Spacer(Modifier.width(6.dp))
                        RoundBtn(R.drawable.ic_repeat, 36.dp, 18.dp, active = repeatMode != 0) { cycleRepeat() }
                        if (!isAudio) {
                            Spacer(Modifier.width(6.dp))
                            RoundBtn(R.drawable.ic_fit, 36.dp, 18.dp, active = fill) {
                                fill = !fill
                                hint = if (fill) "Zoom to fill" else "Fit to screen"
                                tick += 1
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (multi) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .clickable {
                                        showList = true
                                        showSpeed = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PIcon(R.drawable.ic_playlist, 18.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${index + 1}/${uris.size}",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // Speed menu
                AnimatedVisibility(
                    visible = showSpeed,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 96.dp)
                ) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Panel)
                            .padding(vertical = 6.dp)
                    ) {
                        Speeds.forEach { s ->
                            val selected = s == speed
                            Text(
                                speedLabel(s),
                                color = if (selected) Accent else Color.White,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .width(130.dp)
                                    .clickable {
                                        applySpeed(s)
                                        showSpeed = false
                                        tick += 1
                                    }
                                    .padding(horizontal = 20.dp, vertical = 11.dp)
                            )
                        }
                    }
                }
            }
        }

        // Playlist menu: dim background + sliding panel
        AnimatedVisibility(visible = showList, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) { detectTapGestures { showList = false } }
            )
        }
        AnimatedVisibility(
            visible = showList,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlaylistPanel(
                titles = titles,
                thumbs = thumbs,
                audio = audio,
                sizes = sizes,
                count = uris.size,
                current = index,
                name = name,
                onPick = {
                    goTo(it)
                    showList = false
                },
                onClose = { showList = false }
            )
        }
    }
}

@Composable
private fun PlaylistPanel(
    titles: List<String>,
    thumbs: List<String>,
    audio: BooleanArray,
    sizes: LongArray,
    count: Int,
    current: Int,
    name: String?,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, current - 1))
    val total = sizes.sum()

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.64f)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Panel)
            .displayCutoutPadding()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (name.isNullOrBlank()) "Playlist" else name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sizeText = fmtSize(total)
                Text(
                    "$count videos" + (if (sizeText.isNotEmpty()) "  •  $sizeText" else "") + "  •  playing ${current + 1}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
            RoundBtn(R.drawable.ic_close, 40.dp, 20.dp) { onClose() }
        }

        LazyColumn(
            state = state,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(titles) { i, t ->
                val active = i == current
                val isAudioItem = audio.getOrElse(i) { false }
                val size = fmtSize(sizes.getOrElse(i) { 0L })
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (active) Accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                        .clickable { onPick(i) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .width(108.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        Thumb(
                            thumbs.getOrNull(i)?.takeIf { it.isNotBlank() },
                            Modifier.fillMaxSize(),
                            if (isAudioItem) R.drawable.ic_music else R.drawable.ic_play,
                            22.dp
                        )
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text("${i + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t,
                            color = if (active) Accent else Color.White,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (active) {
                            Text("Now playing", color = Accent, fontSize = 11.sp)
                        } else if (size.isNotEmpty()) {
                            Text(size, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
