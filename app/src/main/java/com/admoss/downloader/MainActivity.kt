package com.admoss.downloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

private val Cyan = Color(0xFF00E5FF)
private val Violet = Color(0xFF7C4DFF)
private val Pink = Color(0xFFFF4081)
private val Green = Color(0xFF00E676)
private val Red = Color(0xFFFF5252)
private val Orange = Color(0xFFFFAB40)

class MainActivity : ComponentActivity() {

    private val vm: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AdmossTheme { AdmossScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.setSharedText(it) }
        }
    }
}

@Composable
fun AdmossTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan,
            secondary = Violet,
            background = Color(0xFF0B0F1A),
            surface = Color(0xFF131A2B),
            onPrimary = Color.Black
        ),
        content = content
    )
}

// ---------- helpers ----------

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "Size unknown"
    bytes >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}

private fun formatDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatEta(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun platformOf(url: String): String? {
    val u = url.lowercase()
    return when {
        "youtu" in u -> "YouTube"
        "tiktok" in u -> "TikTok"
        u.startsWith("http") -> "Other site"
        else -> null
    }
}

// ---------- screen ----------

@Composable
fun AdmossScreen(vm: DownloadViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Ask for notification permission once (needed to see download progress in the notification bar)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val bg = Brush.verticalGradient(
        listOf(Color(0xFF0B0F1A), Color(0xFF16102E), Color(0xFF0B0F1A))
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Header() }

        item {
            LinkCard(vm, onPaste = {
                val text = clipboard.getText()?.text.orEmpty()
                vm.setSharedText(text)
            })
        }

        item {
            AnimatedVisibility(visible = vm.infoLoading || vm.info != null) { InfoCard(vm) }
        }

        item { OptionsCard(vm) }

        item { DownloadButton(vm) }

        item {
            AnimatedVisibility(visible = DownloadCenter.downloading) { ProgressCard() }
        }

        DownloadCenter.error?.let { msg ->
            item { MessageCard(msg, isError = true) }
        }
        DownloadCenter.success?.let { msg ->
            item { MessageCard(msg, isError = false) }
        }

        if (DownloadCenter.history.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Recent downloads",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    TextButton(onClick = { DownloadCenter.clearHistory(context) }) {
                        Text("Clear", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
            items(DownloadCenter.history, key = { it.uri }) { item ->
                HistoryRow(item) { openItem(context, item) }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------- pieces ----------

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Cyan, Violet))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "ADMOSS",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                color = Color.White
            )
            Text(
                "Downloader  •  YouTube & TikTok",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(18.dp),
        content = content
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LinkCard(vm: DownloadViewModel, onPaste: () -> Unit) {
    GlassCard {
        Text(
            "Paste your link",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = vm.link,
            onValueChange = { vm.onLinkChange(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !DownloadCenter.downloading,
            placeholder = { Text("youtube.com/…  or  tiktok.com/…") },
            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
            trailingIcon = {
                if (vm.link.isEmpty()) {
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                    }
                } else {
                    IconButton(onClick = { vm.onLinkChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                    }
                }
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
            )
        )

        val platform = platformOf(vm.link)
        if (platform != null) {
            Spacer(Modifier.height(10.dp))
            val color = when (platform) {
                "YouTube" -> Red
                "TikTok" -> Cyan
                else -> Violet
            }
            Pill("Detected: $platform", color)
        }

        if (vm.infoError) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Couldn't load the video info. You can still try to download.",
                color = Orange,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun InfoCard(vm: DownloadViewModel) {
    GlassCard {
        val info = vm.info
        if (info == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Cyan
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Getting video info…",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                vm.thumb?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (info.durationSec > 0) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            formatDuration(info.durationSec),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                info.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (info.uploader.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    info.uploader,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))
            val bytes = if (vm.audioOnly) info.audioSize else (info.sizes[vm.quality] ?: 0L)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (bytes > 0) "≈ ${formatSize(bytes)}" else "Size unknown", Cyan)
                Pill(if (vm.audioOnly) "MP3 192 kbps" else vm.quality.label, Violet)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(vm: DownloadViewModel) {
    GlassCard {
        Text(
            "Format",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(4.dp)
        ) {
            FormatTab("Video", Icons.Rounded.Videocam, !vm.audioOnly, Modifier.weight(1f)) {
                vm.audioOnly = false
            }
            FormatTab("Audio MP3", Icons.Rounded.MusicNote, vm.audioOnly, Modifier.weight(1f)) {
                vm.audioOnly = true
            }
        }

        AnimatedVisibility(visible = !vm.audioOnly) {
            Column {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Quality",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                val available = vm.info?.sizes?.keys?.toList()?.takeIf { it.isNotEmpty() }
                    ?: Quality.values().toList()
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    available.forEach { q ->
                        val size = vm.info?.sizes?.get(q) ?: 0L
                        FilterChip(
                            selected = vm.quality == q,
                            onClick = { vm.quality = q },
                            label = {
                                Text(if (size > 0) "${q.label} · ${formatSize(size)}" else q.label)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.25f),
                                selectedLabelColor = Cyan
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.9f), Violet))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun DownloadButton(vm: DownloadViewModel) {
    val focus = LocalFocusManager.current
    val enabled = Engine.ready && !DownloadCenter.downloading && vm.link.isNotBlank()
    val gray = Color(0xFF2A3045)

    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (enabled) Brush.horizontalGradient(listOf(Cyan, Violet))
                else Brush.horizontalGradient(listOf(gray, gray))
            )
            .clickable(enabled = enabled) {
                focus.clearFocus()
                vm.startDownload()
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (Engine.failed) {
                Text(Engine.message, color = Color.White, fontWeight = FontWeight.SemiBold)
            } else if (!Engine.ready) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                Spacer(Modifier.width(10.dp))
                Text(Engine.message, color = Color.White, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    "DOWNLOAD",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun ProgressCard() {
    val animated by animateFloatAsState(
        targetValue = DownloadCenter.progress,
        animationSpec = tween(300),
        label = "progress"
    )

    GlassCard {
        if (DownloadCenter.title.isNotBlank()) {
            Text(
                DownloadCenter.title,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                if (DownloadCenter.progress <= 0f) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = Cyan,
                        strokeWidth = 8.dp
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.fillMaxSize(),
                        color = Cyan,
                        trackColor = Color.White.copy(alpha = 0.1f),
                        strokeWidth = 8.dp
                    )
                }
                Text(
                    if (DownloadCenter.progress <= 0f) "…" else "${(animated * 100).toInt()}%",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp
                )
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    DownloadCenter.stage,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    if (DownloadCenter.eta > 0) "About ${formatEta(DownloadCenter.eta)} left"
                    else "Runs in the background",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
                TextButton(onClick = { DownloadCenter.cancel() }) {
                    Text("Cancel", color = Pink, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50)),
            color = Violet,
            trackColor = Color.White.copy(alpha = 0.1f)
        )
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    val color = if (isError) Red else Green
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = color
        )
        Spacer(Modifier.width(12.dp))
        Text(message, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Cyan.copy(alpha = 0.7f), Violet.copy(alpha = 0.7f)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (item.audio) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.audio) "Audio  •  Music/ADMOSS" else "Video  •  Movies/ADMOSS",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        }
    }
}

private fun openItem(context: Context, item: HistoryItem) {
    try {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(item.uri), if (item.audio) "audio/*" else "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(context, "Can't open this file", Toast.LENGTH_SHORT).show()
    }
}
