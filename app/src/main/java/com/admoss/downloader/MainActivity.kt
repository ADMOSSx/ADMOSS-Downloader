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
import androidx.activity.compose.BackHandler
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

private val Pink = Color(0xFFE0524B)   // cancel / delete
private val Green = Color(0xFF3E9B6B)  // done
private val Red = Color(0xFFD64545)    // errors
private val Orange = Color(0xFFD08A2E) // warnings

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
    val onAccent: Color,
    val nav: Color
)

private val DarkPalette = Palette(
    dark = true,
    bgTop = Color(0xFF151311),
    bgMid = Color(0xFF151311),
    bgBottom = Color(0xFF151311),
    text = Color(0xFFEFEAE2),
    sub = Color(0xFFA59E92),
    card = Color(0xFF1E1B18),
    border = Color(0xFF2F2B26),
    accent = Color(0xFFF08A4B),
    onAccent = Color(0xFF1C1007),
    nav = Color(0xFF1A1816)
)

private val LightPalette = Palette(
    dark = false,
    bgTop = Color(0xFFF5F2EC),
    bgMid = Color(0xFFF5F2EC),
    bgBottom = Color(0xFFF5F2EC),
    text = Color(0xFF1F1C18),
    sub = Color(0xFF6C655B),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFE3DDD2),
    accent = Color(0xFFC2410C),
    onAccent = Color(0xFFFFFFFF),
    nav = Color(0xFFFBF9F5)
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
        if (savedInstanceState == null) {
            handleIntent(intent)
            Updater.check(this, false)
        }
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
        darkColorScheme(primary = palette.accent, onPrimary = palette.onAccent, background = palette.bgTop, surface = palette.card)
    } else {
        lightColorScheme(primary = palette.accent, onPrimary = palette.onAccent, background = palette.bgTop, surface = palette.card)
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
    val shape = RoundedCornerShape(16.dp)
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
            .clip(RoundedCornerShape(8.dp))
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
            .background(p.bgTop)
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
            .clip(RoundedCornerShape(10.dp))
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
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header() }

            if (Updater.available != null || Updater.message != null) {
                item { UpdateArea() }
            }

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
                    PlaylistRow(entry, vm.selectedUrls.contains(entry.url), vm.sizeOf(entry.url)) { vm.toggleSelected(entry.url) }
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
        Column(Modifier.weight(1f)) {
            Text(
                "ADMOSS Downloader",
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.text
            )
            Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = p.sub)
        }
        IconButton(onClick = { Updater.check(context, true) }) {
            Ico(R.drawable.ic_refresh, p.text, 22.dp, "Check for updates")
        }
        IconButton(onClick = { ThemeSettings.set(context, !p.dark) }) {
            Ico(if (p.dark) R.drawable.ic_sun else R.drawable.ic_moon, p.text, 24.dp, "Switch theme")
        }
    }
}

@Composable
private fun UpdateArea() {
    val p = LocalPalette.current
    val context = LocalContext.current
    val info = Updater.available
    val msg = Updater.message

    if (info != null) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(p.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Ico(R.drawable.ic_download, p.onAccent, 24.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Update available", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    val sizeText = if (info.size > 0) "  •  ${formatSize(info.size)}" else ""
                    Text("Version ${info.version}$sizeText", color = p.sub, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (Updater.downloading) {
                LinearProgressIndicator(
                    progress = { Updater.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = p.accent,
                    trackColor = p.border
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Downloading update…  ${(Updater.progress * 100).toInt()}%",
                    color = p.sub,
                    fontSize = 12.sp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(p.accent)
                            .clickable { Updater.downloadAndInstall(context) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Update now",
                            color = p.onAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    TextButton(onClick = { Updater.available = null }) {
                        Text("Later", color = p.sub)
                    }
                }
            }
            if (msg != null) {
                Spacer(Modifier.height(8.dp))
                Text(msg, color = Orange, fontSize = 12.sp)
            }
        }
    } else if (msg != null) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(p.card)
                .border(1.dp, p.border, shape)
                .clickable { Updater.message = null }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Ico(R.drawable.ic_refresh, p.accent, 22.dp)
            Spacer(Modifier.width(12.dp))
            Text(msg, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LinkCard(vm: DownloadViewModel, onPaste: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
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
            shape = RoundedCornerShape(12.dp),
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
                else -> p.sub
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
            vm.infoErrorText?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = p.sub, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.retryInfo() }) { Text("Try again", color = p.accent) }
                TextButton(onClick = { Engine.updateNow(context) }, enabled = !Engine.updating) {
                    Text(if (Engine.updating) "Updating…" else "Update engine", color = p.accent)
                }
            }
            Engine.updateMessage?.let { Text(it, color = p.sub, fontSize = 12.sp) }
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
                    .clip(RoundedCornerShape(12.dp))
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
                Pill(if (vm.audioOnly) "MP3 192 kbps" else vm.quality.label, p.sub)
            }

            if (vm.playlistHint) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.accent.copy(alpha = 0.12f))
                        .clickable { vm.loadPlaylistFromHint() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Ico(R.drawable.ic_playlist, p.accent, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Part of a playlist. Tap to load all videos",
                        color = p.accent,
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
    val selected = vm.selectedUrls.size
    val checked = vm.checkedCount
    val bytes = vm.selectedSize

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(p.accent),
                contentAlignment = Alignment.Center
            ) {
                Ico(R.drawable.ic_playlist, p.onAccent, 22.dp)
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
            Pill("$selected selected", p.accent)
            Row {
                TextButton(onClick = { vm.selectAll(true) }) { Text("Select all", color = p.accent) }
                TextButton(onClick = { vm.selectAll(false) }) { Text("None", color = p.sub) }
            }
        }

        // Total size of the selected videos
        Spacer(Modifier.height(4.dp))
        if (vm.sizeRunning) {
            LinearProgressIndicator(
                progress = { if (vm.sizeTotal > 0) vm.sizeChecked / vm.sizeTotal.toFloat() else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = p.accent,
                trackColor = p.border
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Calculating size…  ${vm.sizeChecked} / ${vm.sizeTotal}",
                    color = p.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.cancelSizeCalc() }) { Text("Stop", color = Pink) }
            }
        } else {
            if (checked > 0) {
                val complete = checked >= selected
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val label = if (bytes > 0) "≈ ${formatSize(bytes)}${if (complete) "" else "+"}" else "Size unknown"
                    Pill(label, p.accent)
                    Text(
                        if (complete) "total for $selected videos" else "so far, $checked of $selected checked",
                        color = p.sub,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (selected > checked) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.5.dp, p.accent, RoundedCornerShape(10.dp))
                        .clickable { vm.calculatePlaylistSize() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (checked == 0) "Calculate total size" else "Check the remaining ${selected - checked}",
                        color = p.accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
        vm.sizeNote?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Orange, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlaylistRow(entry: PlaylistEntry, checked: Boolean, sizeBytes: Long, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
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
        Column(
            Modifier.padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (entry.durationSec > 0) {
                Text(formatDuration(entry.durationSec), color = p.sub, fontSize = 12.sp)
            }
            if (sizeBytes > 0) {
                Text(formatSize(sizeBytes), color = p.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
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
                .clip(RoundedCornerShape(10.dp))
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
    val shape = RoundedCornerShape(8.dp)
    val tint = if (selected) p.onAccent else p.sub
    Box(
        modifier
            .clip(shape)
            .background(if (selected) p.accent else Color.Transparent)
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
        pl != null -> if (selectedCount == 1) "Download 1 video" else "Download $selectedCount videos"
        vm.audioOnly -> "Download MP3"
        else -> "Download"
    }
    val off = if (p.dark) Color(0xFF2F2B26) else Color(0xFFD9D3C8)
    val fg = if (enabled || Engine.failed) p.onAccent else p.sub
    val sub: String? = if (pl != null) {
        val total = vm.selectedSize
        if (vm.checkedCount > 0 && total > 0) "≈ ${formatSize(total)}${if (vm.checkedCount < selectedCount) "+" else ""}" else null
    } else {
        vm.info?.let { i ->
            val b = estimateSize(i, vm.audioOnly, vm.quality)
            if (b > 0) "≈ ${formatSize(b)}" else null
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled || Engine.failed) p.accent else off
            )
            .clickable(enabled = enabled || Engine.failed) {
                focus.clearFocus()
                if (Engine.failed) Engine.retry(context) else vm.download()
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (Engine.failed) {
                Text(Engine.message, color = fg, fontWeight = FontWeight.SemiBold)
            } else if (!Engine.ready) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = fg
                )
                Spacer(Modifier.width(10.dp))
                Text(Engine.message, color = fg, fontWeight = FontWeight.SemiBold)
            } else {
                Ico(R.drawable.ic_download, fg, 24.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        label,
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    if (sub != null) {
                        Text(sub, color = fg.copy(alpha = 0.85f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    val shape = RoundedCornerShape(14.dp)
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
    val shape = RoundedCornerShape(14.dp)
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
                color = p.accent,
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

private fun playItems(context: Context, items: List<HistoryItem>, start: Int, name: String? = null) {
    if (items.isEmpty()) return
    val i = Intent(context, PlayerActivity::class.java)
        .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(items.map { it.uri }))
        .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(items.map { it.title }))
        .putStringArrayListExtra(PlayerActivity.EXTRA_THUMBS, ArrayList(items.map { it.thumb ?: "" }))
        .putExtra(PlayerActivity.EXTRA_AUDIO, items.map { it.audio }.toBooleanArray())
        .putExtra(PlayerActivity.EXTRA_SIZES, items.map { it.size }.toLongArray())
        .putExtra(PlayerActivity.EXTRA_NAME, name)
        .putExtra(PlayerActivity.EXTRA_START, start)
    context.startActivity(i)
}

/** Total size of some downloads. A "+" means some older files have no size saved. */
private fun totalSizeText(items: List<HistoryItem>): String {
    val total = items.sumOf { it.size }
    if (total <= 0L) return "-"
    val partial = items.any { it.size <= 0L }
    return formatSize(total) + if (partial) "+" else ""
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = p.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
        Text(label, color = p.sub, fontSize = 11.sp)
    }
}

@Composable
private fun LibraryTab() {
    val p = LocalPalette.current
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(0) } // 0 = all downloads, 1 = playlists
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = openName != null) { openName = null }

    val name = openName
    if (name != null) {
        PlaylistDetail(name) { openName = null }
    } else {
        val all = DownloadCenter.history
        val groups = all.filter { it.playlist != null }.groupBy { it.playlist ?: "" }
        val groupNames = groups.keys.toList().filter { query.isBlank() || it.contains(query, ignoreCase = true) }
        val shown = if (query.isBlank()) all else all.filter { it.title.contains(query, ignoreCase = true) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Library", color = p.text, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("${all.size}", "Downloads", Modifier.weight(1f))
                    StatCard("${groups.size}", "Playlists", Modifier.weight(1f))
                    StatCard(totalSizeText(all), "Storage used", Modifier.weight(1f))
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.border.copy(alpha = 0.5f))
                        .padding(4.dp)
                ) {
                    FormatTab("All downloads", R.drawable.ic_library, mode == 0, Modifier.weight(1f)) { mode = 0 }
                    FormatTab("Playlists", R.drawable.ic_playlist, mode == 1, Modifier.weight(1f)) { mode = 1 }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(if (mode == 0) "Search your downloads" else "Search playlists") },
                    leadingIcon = { Ico(R.drawable.ic_search, p.sub, 22.dp) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Ico(R.drawable.ic_close, p.sub, 20.dp, "Clear") }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
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

            if (mode == 0) {
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
                        onOpen = { playItems(context, listOf(item), 0) },
                        onRemove = { DownloadCenter.removeHistory(context, item) }
                    )
                }
            } else {
                if (groups.isEmpty()) {
                    item {
                        Text(
                            "No playlists yet. Download a playlist from the Download tab and it will appear here, with all its videos in order.",
                            color = p.sub,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }
                } else if (groupNames.isEmpty()) {
                    item {
                        Text(
                            "No playlists match \"$query\".",
                            color = p.sub,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }
                }

                items(groupNames, key = { it }) { groupName ->
                    val videos = (groups[groupName] ?: emptyList()).sortedBy { it.index }
                    PlaylistCard(
                        name = groupName,
                        videos = videos,
                        onOpen = { openName = groupName },
                        onPlay = { playItems(context, videos, 0, groupName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(name: String, videos: List<HistoryItem>, onOpen: () -> Unit, onPlay: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .clickable { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            Thumb(videos.firstOrNull()?.thumb, Modifier.fillMaxSize(), R.drawable.ic_playlist, 30.dp)
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Ico(R.drawable.ic_playlist, Color.White, 13.dp)
                Spacer(Modifier.width(4.dp))
                Text("${videos.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = p.text,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val sizeText = totalSizeText(videos)
            val countText = if (videos.size == 1) "1 video" else "${videos.size} videos"
            Text(
                if (sizeText != "-") "$countText  •  $sizeText" else countText,
                color = p.sub,
                fontSize = 12.sp
            )
            videos.firstOrNull()?.let {
                Text(
                    "Starts with: ${it.title}",
                    color = p.sub,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onPlay) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(p.accent),
                contentAlignment = Alignment.Center
            ) {
                Ico(R.drawable.ic_play, p.onAccent, 24.dp, "Play all")
            }
        }
    }
}

@Composable
private fun PlaylistDetail(name: String, onBack: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val videos = DownloadCenter.history.filter { it.playlist == name }.sortedBy { it.index }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Ico(R.drawable.ic_back, p.text, 24.dp, "Back") }
                Text("Playlist", color = p.sub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Cover with title
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Thumb(videos.firstOrNull()?.thumb, Modifier.fillMaxSize(), R.drawable.ic_playlist, 56.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                )
                Text(
                    name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                )
            }
        }

        // Quick facts: number of videos and total size
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    if (videos.size == 1) "1" else "${videos.size}",
                    if (videos.size == 1) "Video" else "Videos",
                    Modifier.weight(1f)
                )
                StatCard(totalSizeText(videos), "Total size", Modifier.weight(1f))
                StatCard("${videos.count { it.audio }}", "Audio files", Modifier.weight(1f))
            }
        }

        if (videos.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.accent)
                            .clickable { playItems(context, videos, 0, name) },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ico(R.drawable.ic_play, p.onAccent, 22.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Play all",
                                color = p.onAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.5.dp, p.accent, RoundedCornerShape(12.dp))
                            .clickable { playItems(context, videos.shuffled(), 0, name) },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ico(R.drawable.ic_shuffle, p.accent, 22.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Shuffle",
                                color = p.accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text("This playlist is empty.", color = p.sub, fontSize = 14.sp)
            }
        }

        itemsIndexed(videos, key = { _, v -> v.uri }) { i, v ->
            PlaylistVideoRow(
                number = i + 1,
                item = v,
                onPlay = { playItems(context, videos, i, name) },
                onRemove = { DownloadCenter.removeHistory(context, v) }
            )
        }

        item {
            TextButton(onClick = {
                DownloadCenter.removePlaylist(context, name)
                onBack()
            }) {
                Text("Remove this playlist from the list", color = Pink)
            }
        }
    }
}

@Composable
private fun PlaylistVideoRow(number: Int, item: HistoryItem, onPlay: () -> Unit, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .clickable { onPlay() }
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            Thumb(
                item.thumb,
                Modifier.fillMaxSize(),
                if (item.audio) R.drawable.ic_music else R.drawable.ic_play,
                24.dp
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text("$number", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val kind = if (item.audio) "Audio" else "Video"
            Text(
                if (item.size > 0) "$kind  •  ${formatSize(item.size)}" else kind,
                color = p.sub,
                fontSize = 11.sp
            )
        }
        IconButton(onClick = onRemove) {
            Ico(R.drawable.ic_delete, p.sub, 20.dp, "Remove from list")
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onOpen: () -> Unit, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .clickable { onOpen() }
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(
            item.thumb,
            Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)),
            if (item.audio) R.drawable.ic_music else R.drawable.ic_play,
            24.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val kind = if (item.audio) "Audio" else "Video"
            Text(
                if (item.size > 0) "$kind  •  ${formatSize(item.size)}" else kind,
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
            .background(Color(0xFF151311))
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
                .clip(RoundedCornerShape(10.dp))
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
            TextButton(onClick = onContinue) { Text("Continue", color = Color(0xFFF08A4B)) }
        }
    }
}
