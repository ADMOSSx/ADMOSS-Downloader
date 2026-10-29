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
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

// ---------- colors & theme ----------

private val Teal = Color(0xFF00B8D4)
private val Violet = Color(0xFF7C4DFF)
private val Pink = Color(0xFFFF4081)
private val Green = Color(0xFF00C853)
private val Red = Color(0xFFFF5252)
private val Orange = Color(0xFFFFAB40)
private val Brand = Brush.horizontalGradient(listOf(Teal, Violet))

class Palette(
    val dark: Boolean,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val text: Color,
    val sub: Color,
    val card: Color,
    val border: Color,
    val accent: Color,
    val nav: Color
)

private val DarkPalette = Palette(
    dark = true,
    bgTop = Color(0xFF0B0F1A),
    bgMid = Color(0xFF16102E),
    bgBottom = Color(0xFF0B0F1A),
    text = Color.White,
    sub = Color.White.copy(alpha = 0.6f),
    card = Color.White.copy(alpha = 0.06f),
    border = Color.White.copy(alpha = 0.10f),
    accent = Color(0xFF00E5FF),
    nav = Color(0xFF10152A)
)

private val LightPalette = Palette(
    dark = false,
    bgTop = Color(0xFFF6F8FF),
    bgMid = Color(0xFFEEE9FF),
    bgBottom = Color(0xFFF6F8FF),
    text = Color(0xFF12152B),
    sub = Color(0xFF5B6280),
    card = Color.White,
    border = Color(0xFFE1E5F2),
    accent = Color(0xFF0097A7),
    nav = Color.White
)

private val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Remembers the dark / light choice (null = follow the phone). */
object ThemeSettings {
    var dark by mutableStateOf<Boolean?>(null)
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("admoss", Context.MODE_PRIVATE)
        if (p.contains("dark")) dark = p.getBoolean("dark", true)
    }

    fun set(ctx: Context, value: Boolean) {
        dark = value
        ctx.getSharedPreferences("admoss", Context.MODE_PRIVATE)
            .edit().putBoolean("dark", value).apply()
    }
}

// ---------- activity ----------

class MainActivity : ComponentActivity() {

    private val vm: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        ThemeSettings.load(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AdmossRoot { vm } }
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
fun AdmossRoot(getVm: () -> DownloadViewModel) {
    val context = LocalContext.current
    val dark = ThemeSettings.dark ?: isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette

    // Status bar / navigation bar icons must match the theme
    LaunchedEffect(dark) {
        val act = context as? ComponentActivity ?: return@LaunchedEffect
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        act.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    val scheme = if (dark) {
        darkColorScheme(primary = palette.accent, secondary = Violet, background = palette.bgTop, surface = Color(0xFF131A2B), onPrimary = Color.Black)
    } else {
        lightColorScheme(primary = palette.accent, secondary = Violet, background = palette.bgTop, surface = Color.White, onPrimary = Color.White)
    }

    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                contentColor = palette.text
            ) {
                val crash = remember { CrashReport.read(context) }
                var showCrash by remember { mutableStateOf(crash != null) }
                if (crash != null && showCrash) {
                    CrashScreen(crash) {
                        CrashReport.clear(context)
                        showCrash = false
                    }
                } else {
                    AdmossApp(getVm())
                }
            }
        }
    }
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

@Composable
private fun Ico(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp, desc: String? = null) {
    Icon(
        painter = painterResource(id),
        contentDescription = desc,
        tint = tint,
        modifier = Modifier.size(size)
    )
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .padding(18.dp)
            .animateContentSize(),
        content = content
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- app shell ----------

@Composable
private fun AdmossApp(vm: DownloadViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current

    // Ask for notification permission once (to see download progress in the notification bar)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by rememberSaveable { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bgTop, p.bgMid, p.bgBottom)))
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            if (tab == 0) DownloadTab(vm) else LibraryTab()
        }
        BottomBar(tab) { tab = it }
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.nav)
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavItem("Download", R.drawable.ic_download, selected == 0, DownloadCenter.activeCount, Modifier.weight(1f)) {
            onSelect(0)
        }
        NavItem("Library", R.drawable.ic_library, selected == 1, 0, Modifier.weight(1f)) {
            onSelect(1)
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    badge: Int,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val p = LocalPalette.current
    val color = if (selected) p.accent else p.sub
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Ico(icon, color, 26.dp)
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-4).dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Pink),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$badge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- download tab ----------

@Composable
private fun DownloadTab(vm: DownloadViewModel) {
    val p = LocalPalette.current
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header() }

            item {
                LinkCard(vm) { vm.setSharedText(clipboard.getText()?.text.orEmpty()) }
            }

            item {
                AnimatedVisibility(visible = vm.infoLoading || vm.info != null) { InfoCard(vm) }
            }

            val pl = vm.playlist
            if (pl != null) {
                item { PlaylistHeader(vm, pl) }
                itemsIndexed(pl.entries) { _, entry ->
                    PlaylistRow(entry, vm.selectedUrls.contains(entry.url)) { vm.toggleSelected(entry.url) }
                }
            }

            item { OptionsCard(vm) }

            vm.message?.let { msg ->
                item { MessageCard(msg) }
            }

            if (Engine.failed) {
                item { MessageCard("Engine error: ${Engine.failure ?: "unknown"}") }
            }

            if (DownloadCenter.queue.isNotEmpty()) {
                item { QueueHeader() }
                val queueList = DownloadCenter.queue.toList()
                items(queueList, key = { it.id }) { QueueRow(it) }
            }
        }

        // Download button always visible at the bottom
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, p.bgBottom)))
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)
        ) {
            DownloadButton(vm)
        }
    }
}

@Composable
private fun Header() {
    val p = LocalPalette.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brand),
            contentAlignment = Alignment.Center
        ) {
            Ico(R.drawable.ic_download, Color.White, 28.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "ADMOSS",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                color = p.text
            )
            Text("YouTube  •  TikTok  •  and more", fontSize = 12.sp, color = p.sub)
        }
        IconButton(onClick = { ThemeSettings.set(context, !p.dark) }) {
            Ico(if (p.dark) R.drawable.ic_sun else R.drawable.ic_moon, p.text, 24.dp, "Switch theme")
        }
    }
}

@Composable
private fun LinkCard(vm: DownloadViewModel, onPaste: () -> Unit) {
    val p = LocalPalette.current
    GlassCard {
        Text("Paste your link", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = vm.link,
            onValueChange = { vm.onLinkChange(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("youtube.com/…  or  tiktok.com/…") },
            leadingIcon = { Ico(R.drawable.ic_link, p.sub, 22.dp) },
            trailingIcon = {
                if (vm.link.isEmpty()) {
                    IconButton(onClick = onPaste) { Ico(R.drawable.ic_paste, p.sub, 22.dp, "Paste") }
                } else {
                    IconButton(onClick = { vm.onLinkChange("") }) { Ico(R.drawable.ic_close, p.sub, 22.dp, "Clear") }
                }
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = p.accent,
                unfocusedBorderColor = p.border,
                focusedTextColor = p.text,
                unfocusedTextColor = p.text,
                cursorColor = p.accent,
                focusedPlaceholderColor = p.sub,
                unfocusedPlaceholderColor = p.sub
            )
        )

        val platform = platformOf(vm.link)
        if (platform != null) {
            Spacer(Modifier.height(10.dp))
            val color = when (platform) {
                "YouTube" -> Red
                "TikTok" -> p.accent
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
    val p = LocalPalette.current
    GlassCard {
        val info = vm.info
        if (info == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = p.accent
                )
                Spacer(Modifier.width(12.dp))
                Text("Getting video info…", color = p.sub, fontSize = 14.sp)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.border)
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
                color = p.text,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (info.uploader.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    info.uploader,
                    color = p.sub,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))
            val bytes = if (vm.audioOnly) info.audioSize else (info.sizes[vm.quality] ?: 0L)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (bytes > 0) "≈ ${formatSize(bytes)}" else "Size unknown", p.accent)
                Pill(if (vm.audioOnly) "MP3 192 kbps" else vm.quality.label, Violet)
            }

            if (vm.playlistHint) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Violet.copy(alpha = 0.14f))
                        .clickable { vm.loadPlaylistFromHint() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Ico(R.drawable.ic_playlist, Violet, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Part of a playlist. Tap to load all videos",
                        color = Violet,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(vm: DownloadViewModel, pl: PlaylistInfo) {
    val p = LocalPalette.current
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brand),
                contentAlignment = Alignment.Center
            ) {
                Ico(R.drawable.ic_playlist, Color.White, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pl.title,
                    color = p.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val by = if (pl.uploader.isNotBlank()) "  •  ${pl.uploader}" else ""
                Text("${pl.entries.size} videos$by", color = p.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Pill("${vm.selectedUrls.size} selected", p.accent)
            Row {
                TextButton(onClick = { vm.selectAll(true) }) { Text("Select all", color = p.accent) }
                TextButton(onClick = { vm.selectAll(false) }) { Text("None", color = p.sub) }
            }
        }
    }
}

@Composable
private fun PlaylistRow(entry: PlaylistEntry, checked: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = p.accent,
                uncheckedColor = p.sub,
                checkmarkColor = Color.White
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            entry.title,
            color = p.text,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (entry.durationSec > 0) {
            Text(
                formatDuration(entry.durationSec),
                color = p.sub,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(vm: DownloadViewModel) {
    val p = LocalPalette.current
    GlassCard {
        Text("Format", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(p.border.copy(alpha = 0.5f))
                .padding(4.dp)
        ) {
            FormatTab("Video", R.drawable.ic_video, !vm.audioOnly, Modifier.weight(1f)) {
                vm.audioOnly = false
            }
            FormatTab("Audio MP3", R.drawable.ic_music, vm.audioOnly, Modifier.weight(1f)) {
                vm.audioOnly = true
            }
        }

        AnimatedVisibility(visible = !vm.audioOnly) {
            Column {
                Spacer(Modifier.height(14.dp))
                Text("Quality", color = p.sub, fontSize = 13.sp)
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
                                labelColor = p.text,
                                selectedContainerColor = p.accent.copy(alpha = 0.2f),
                                selectedLabelColor = p.accent
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
    @DrawableRes icon: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(11.dp)
    val tint = if (selected) Color.White else p.sub
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) Brand
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(icon, tint, 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun DownloadButton(vm: DownloadViewModel) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val pl = vm.playlist
    val selectedCount = vm.selectedUrls.size
    val enabled = Engine.ready && vm.link.isNotBlank() && (pl == null || selectedCount > 0)
    val label = when {
        pl != null -> if (selectedCount == 1) "DOWNLOAD 1 VIDEO" else "DOWNLOAD $selectedCount VIDEOS"
        vm.audioOnly -> "DOWNLOAD MP3"
        else -> "DOWNLOAD"
    }
    val off = if (p.dark) Color(0xFF2A3045) else Color(0xFFCDD3E6)

    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (enabled) Brand else Brush.horizontalGradient(listOf(off, off))
            )
            .clickable(enabled = enabled || Engine.failed) {
                focus.clearFocus()
                if (Engine.failed) Engine.retry(context) else vm.download()
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
                Ico(R.drawable.ic_download, Color.White, 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    val shape = RoundedCornerShape(18.dp)
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Red.copy(alpha = 0.12f))
            .border(1.dp, Red.copy(alpha = 0.35f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ico(R.drawable.ic_error, Red, 24.dp)
        Spacer(Modifier.width(12.dp))
        Text(message, color = p.text, fontSize = 14.sp)
    }
}

// ---------- queue ----------

@Composable
private fun QueueHeader() {
    val p = LocalPalette.current
    val active = DownloadCenter.activeCount
    val finished = DownloadCenter.finishedCount
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            if (active > 0) "Downloads  ($active active)" else "Downloads",
            color = p.text,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Row {
            if (active > 0) {
                TextButton(onClick = { DownloadCenter.cancelAll() }) {
                    Text("Cancel all", color = Pink)
                }
            }
            if (finished > 0) {
                TextButton(onClick = { DownloadCenter.clearFinished() }) {
                    Text("Clear finished", color = p.sub)
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem) {
    val p = LocalPalette.current
    val animated by animateFloatAsState(
        targetValue = item.progress,
        animationSpec = tween(300),
        label = "queue-progress"
    )
    val shape = RoundedCornerShape(18.dp)
    val status = item.status

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .padding(14.dp)
            .animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                when (status) {
                    Status.DOWNLOADING -> {
                        if (item.progress <= 0f) {
                            CircularProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                color = p.accent,
                                strokeWidth = 3.dp
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = { animated },
                                modifier = Modifier.fillMaxSize(),
                                color = p.accent,
                                trackColor = p.border,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                    Status.DONE -> Ico(R.drawable.ic_check, Green, 30.dp)
                    Status.FAILED -> Ico(R.drawable.ic_error, Red, 30.dp)
                    Status.CANCELLED -> Ico(R.drawable.ic_close, p.sub, 26.dp)
                    Status.WAITING -> Ico(R.drawable.ic_download, p.sub, 24.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = p.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val line = when (status) {
                    Status.WAITING -> "Waiting in queue"
                    Status.DOWNLOADING -> buildString {
                        append(item.stage)
                        if (item.progress > 0f) append("  ·  ${(animated * 100).toInt()}%")
                        if (item.eta > 0) append("  ·  ${formatEta(item.eta)} left")
                    }
                    Status.DONE -> item.stage
                    Status.FAILED -> "Failed"
                    Status.CANCELLED -> "Cancelled"
                }
                Text(line, color = p.sub, fontSize = 12.sp)
            }
            if (status == Status.WAITING || status == Status.DOWNLOADING) {
                IconButton(onClick = { DownloadCenter.cancelItem(item) }) {
                    Ico(R.drawable.ic_close, p.sub, 20.dp, "Cancel")
                }
            }
        }

        if (status == Status.DOWNLOADING) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = Violet,
                trackColor = p.border
            )
        }
        if (status == Status.FAILED) {
            item.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Red, fontSize = 12.sp)
            }
        }
    }
}

// ---------- library tab ----------

@Composable
private fun LibraryTab() {
    val p = LocalPalette.current
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }

    val all = DownloadCenter.history
    val shown = if (query.isBlank()) all else all.filter { it.title.contains(query, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Library", color = p.text, fontWeight = FontWeight.Black, fontSize = 24.sp)
                Text("${all.size} downloads", color = p.sub, fontSize = 13.sp)
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search your downloads") },
                leadingIcon = { Ico(R.drawable.ic_search, p.sub, 22.dp) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Ico(R.drawable.ic_close, p.sub, 20.dp, "Clear") }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.accent,
                    unfocusedBorderColor = p.border,
                    focusedTextColor = p.text,
                    unfocusedTextColor = p.text,
                    cursorColor = p.accent,
                    focusedPlaceholderColor = p.sub,
                    unfocusedPlaceholderColor = p.sub
                )
            )
        }

        if (all.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Your finished downloads will show up in this list.",
                    color = p.sub,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    "No downloads match \"$query\".",
                    color = p.sub,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        }

        items(shown, key = { it.uri }) { item ->
            HistoryRow(
                item,
                onOpen = { openItem(context, item) },
                onRemove = { DownloadCenter.removeHistory(context, item) }
            )
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onOpen: () -> Unit, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .clickable { onOpen() }
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Brand),
            contentAlignment = Alignment.Center
        ) {
            Ico(if (item.audio) R.drawable.ic_music else R.drawable.ic_play, Color.White, 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = p.text,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.audio) "Audio  •  Music/ADMOSS" else "Video  •  Movies/ADMOSS",
                color = p.sub,
                fontSize = 11.sp
            )
        }
        IconButton(onClick = onRemove) {
            Ico(R.drawable.ic_delete, p.sub, 20.dp, "Remove from list")
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

// ---------- crash report ----------

@Composable
private fun CrashScreen(report: String, onContinue: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F1A))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
    ) {
        Text(
            "ADMOSS crashed last time",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap Copy and send this text to Claude so it can be fixed.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(12.dp)
        ) {
            SelectionContainer {
                Text(
                    report,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") }
            TextButton(onClick = onContinue) { Text("Continue", color = Color(0xFF00E5FF)) }
        }
    }
}
