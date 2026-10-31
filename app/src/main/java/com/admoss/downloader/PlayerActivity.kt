package com.admoss.downloader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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

private val Accent = Color(0xFF00E5FF)
private val Violet = Color(0xFF7C4DFF)
private val Panel = Color(0xF2141A2E)
private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Full-screen player with speed control, double-tap seeking and a playlist menu. */
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_THUMBS = "thumbs"
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
        val name = intent.getStringExtra(EXTRA_NAME)
        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, uriList.size - 1)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Violet)) {
                PlayerScreen(
                    uris = uriList,
                    titles = titles,
                    thumbs = thumbs,
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

private fun fmtTime(ms: Int): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

private fun speedLabel(s: Float): String =
    (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"

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
        PIcon(id, iconSize, if (enabled) Color.White else Color.White.copy(alpha = 0.3f))
    }
}

@Composable
private fun PlayerScreen(
    uris: List<Uri>,
    titles: List<String>,
    thumbs: List<String>,
    name: String?,
    start: Int,
    onClose: () -> Unit,
    onRotate: () -> Unit,
    onExternal: (Int) -> Unit
) {
    val context = LocalContext.current
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

    val multi = uris.size > 1

    DisposableEffect(Unit) {
        video.setOnPreparedListener { mp ->
            player[0] = mp
            try {
                mp.playbackParams = mp.playbackParams.setSpeed(speed)
            } catch (e: Throwable) {
                // speed not supported for this file
            }
        }
        video.setOnCompletionListener {
            if (index < uris.size - 1) {
                index += 1
            } else {
                ended = true
                playing = false
                controls = true
            }
        }
        video.setOnErrorListener { _, _, _ ->
            onExternal(index)
            true
        }
        onDispose { video.stopPlayback() }
    }

    // Load and start the current video
    LaunchedEffect(index) {
        ended = false
        position = 0
        duration = 0
        video.setVideoURI(uris[index])
        video.start()
    }

    // Keep the seek bar and play button in sync
    LaunchedEffect(Unit) {
        while (true) {
            playing = video.isPlaying
            if (dragFraction == null) {
                duration = video.duration.coerceAtLeast(0)
                position = video.currentPosition.coerceAtLeast(0)
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
            delay(700)
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

        // Tap to show / hide controls, double-tap left or right to jump 10 seconds
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
        )

        // Short message in the middle (+10s, speed ...)
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
                        RoundBtn(R.drawable.ic_prev, 52.dp, 26.dp, enabled = index > 0) {
                            index -= 1
                            tick += 1
                        }
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
                        RoundBtn(R.drawable.ic_next, 52.dp, 26.dp, enabled = index < uris.size - 1) {
                            index += 1
                            tick += 1
                        }
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
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Text(
                                speedLabel(speed),
                                color = if (speed == 1f) Color.White else Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        RoundBtn(R.drawable.ic_rewind, 40.dp, 20.dp) { seekBy(-10000) }
                        Spacer(Modifier.width(8.dp))
                        RoundBtn(R.drawable.ic_forward, 40.dp, 20.dp) { seekBy(10000) }
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
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PIcon(R.drawable.ic_playlist, 18.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Playlist",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
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
                count = uris.size,
                current = index,
                name = name,
                onPick = {
                    index = it
                    showList = false
                    tick += 1
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
    count: Int,
    current: Int,
    name: String?,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, current - 1))

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
                Text(
                    "$count videos  •  playing ${current + 1}",
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
                            R.drawable.ic_play,
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
                        }
                    }
                }
            }
        }
    }
}
