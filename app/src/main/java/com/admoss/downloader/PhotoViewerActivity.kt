package com.admoss.downloader

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Accent = Color(0xFFF08A4B)
private val OnAccent = Color(0xFF1C1007)

/** Shows the pictures of a TikTok photo post as a slideshow, with its music playing. */
class PhotoViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_MUSIC = "music"
        const val EXTRA_TITLE = "title"
    }

    private var player: MediaPlayer? = null
    private var wantMusic = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uris = (intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()).map { Uri.parse(it) }
        if (uris.isEmpty()) {
            finish()
            return
        }
        val music = intent.getStringExtra(EXTRA_MUSIC)?.takeIf { it.isNotBlank() }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        if (music != null) {
            try {
                val m = MediaPlayer()
                m.setDataSource(this, Uri.parse(music))
                m.isLooping = true
                m.prepare()
                m.start()
                player = m
            } catch (e: Throwable) {
                player = null
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = OnAccent)) {
                PhotoScreen(
                    uris = uris,
                    title = title,
                    hasMusic = player != null,
                    onMusic = { setMusic(it) },
                    onClose = { finish() }
                )
            }
        }

        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.hide(WindowInsetsCompat.Type.systemBars())
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setMusic(on: Boolean) {
        wantMusic = on
        try {
            val p = player ?: return
            if (on) p.start() else if (p.isPlaying) p.pause()
        } catch (e: Throwable) {
            // ignore
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            player?.pause()
        } catch (e: Throwable) {
            // ignore
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (wantMusic) player?.start()
        } catch (e: Throwable) {
            // ignore
        }
    }

    override fun onDestroy() {
        try {
            player?.release()
        } catch (e: Throwable) {
            // ignore
        }
        player = null
        super.onDestroy()
    }
}

private fun decodePicture(ctx: Context, uri: Uri): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 2200) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
} catch (e: Throwable) {
    null
}

@Composable
private fun PIcon(@DrawableRes id: Int, size: Dp = 22.dp, tint: Color = Color.White) {
    Icon(painter = painterResource(id), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun RoundBtn(@DrawableRes id: Int, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        PIcon(id, 22.dp, if (active) Accent else Color.White)
    }
}

@Composable
private fun PhotoPage(uri: Uri, onTap: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { decodePicture(context, uri) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(color = Accent)
        }
    }
}

@Composable
private fun PhotoScreen(
    uris: List<Uri>,
    title: String,
    hasMusic: Boolean,
    onMusic: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val pager = rememberPagerState(pageCount = { uris.size })
    var controls by remember { mutableStateOf(true) }
    var musicOn by remember { mutableStateOf(hasMusic) }
    var slideshow by remember { mutableStateOf(uris.size > 1) }

    // Move to the next picture every few seconds, like TikTok does
    LaunchedEffect(slideshow, pager.currentPage) {
        if (slideshow && uris.size > 1) {
            delay(3500)
            pager.animateScrollToPage((pager.currentPage + 1) % uris.size)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            PhotoPage(uris[page]) { controls = !controls }
        }

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
                        .height(120.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                )

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
                            if (title.isBlank()) "TikTok photos" else title,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${pager.currentPage + 1} / ${uris.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .displayCutoutPadding()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (hasMusic) {
                        RoundBtn(R.drawable.ic_music, active = musicOn) {
                            musicOn = !musicOn
                            onMusic(musicOn)
                        }
                        Spacer(Modifier.width(14.dp))
                    }
                    if (uris.size > 1) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.Black.copy(alpha = 0.4f))
                                .clickable { slideshow = !slideshow }
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PIcon(
                                if (slideshow) R.drawable.ic_pause else R.drawable.ic_play,
                                20.dp,
                                if (slideshow) Accent else Color.White
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (slideshow) "Slideshow on" else "Slideshow off",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
