@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.admoss.downloader

import android.Manifest
import android.app.Activity
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        ThemeSettings.load(this)
        if (savedInstanceState == null) {
            handleIntent(intent)
            if (AppSettings.autoUpdateEnabled(this)) Updater.check(this, false)
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
            when (tab) {
                0 -> DownloadTab(vm)
                1 -> LibraryTab()
                else -> SettingsTab()
            }
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
        NavItem("Settings", R.drawable.ic_settings, selected == 2, 0, Modifier.weight(1f)) {
            onSelect(2)
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

            vm.photo?.let { post ->
                item { PhotoCard(vm, post) }
            }

            val pl = vm.playlist
            if (pl != null) {
                item { PlaylistHeader(vm, pl) }
                itemsIndexed(pl.entries) { _, entry ->
                    PlaylistRow(entry, vm.selectedUrls.contains(entry.url), vm.sizeOf(entry.url)) { vm.toggleSelected(entry.url) }
                }
            }

            if (vm.photo == null && (vm.info != null || vm.quick != null || vm.playlist != null || vm.infoError)) {
                item {
                    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = appear,
                        enter = fadeIn(tween(300)) + expandVertically(tween(300))
                    ) { OptionsCard(vm) }
                }
            }

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
    var expanded by remember { mutableStateOf(false) }

    if (info != null) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(p.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Ico(R.drawable.ic_download, p.onAccent, 22.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Update available", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    val sizeText = if (info.size > 0) "  •  ${formatSize(info.size)}" else ""
                    Text("Version ${info.version}$sizeText", color = p.sub, fontSize = 13.sp)
                }
            }

            if (info.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("What's new", color = p.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    info.notes,
                    color = p.text,
                    fontSize = 13.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis
                )
                if (info.notes.lines().size > 6 || info.notes.length > 320) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Show less" else "Show more", color = p.accent)
                    }
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
                    if (Updater.progress >= 1f) Updater.stage else "${Updater.stage}  ${(Updater.progress * 100).toInt()}%",
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
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                    TextButton(onClick = { Updater.available = null }) {
                        Text("Later", color = p.sub)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Android will ask you to confirm before it installs. Your downloads and settings are kept.",
                    color = p.sub,
                    fontSize = 11.sp
                )
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
private fun SettingsHeading(text: String) {
    val p = LocalPalette.current
    Text(
        text,
        color = p.sub,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SettingsTab() {
    val p = LocalPalette.current
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Settings", color = p.text, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }

        item { SettingsHeading("Updates") }
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check for updates automatically", color = p.text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Looks for a new release on GitHub when the app opens and every 12 hours.",
                            color = p.sub,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = AppSettings.autoUpdate,
                        onCheckedChange = { AppSettings.setAutoUpdate(context, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = p.onAccent,
                            checkedTrackColor = p.accent
                        )
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("Installed version: ${BuildConfig.VERSION_NAME}", color = p.text, fontSize = 14.sp)
                val last = AppSettings.lastUpdateCheck
                Text(
                    if (last > 0L) {
                        "Last checked: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last))
                    } else {
                        "Not checked yet"
                    },
                    color = p.sub,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (Updater.checking) p.border else p.accent)
                        .clickable(enabled = !Updater.checking) { Updater.check(context, true) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (Updater.checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = p.text
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            if (Updater.checking) "Checking…" else "Check for updates now",
                            color = if (Updater.checking) p.text else p.onAccent,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
        if (Updater.available != null || Updater.message != null) {
            item { UpdateArea() }
        }

        item { SettingsHeading("Downloads") }
        item {
            GlassCard {
                Text("Downloads at the same time", color = p.text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "More at once is faster, but uses more data and battery at the same moment.",
                    color = p.sub,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3).forEach { n ->
                        val on = AppSettings.parallel == n
                        val shape = RoundedCornerShape(10.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(shape)
                                .background(if (on) p.accent else Color.Transparent)
                                .border(1.dp, if (on) p.accent else p.border, shape)
                                .clickable { AppSettings.setParallel(context, n) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$n",
                                color = if (on) p.onAccent else p.text,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }

        item { SettingsHeading("Storage") }
        item {
            GlassCard {
                Text("Where your files are saved", color = p.text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Videos: Movies/ADMOSS\nMusic: Music/ADMOSS\nPhotos: Pictures/ADMOSS",
                    color = p.sub,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Deleting a download in the Library also deletes the file from your phone, after you confirm.",
                    color = p.sub,
                    fontSize = 12.sp
                )
            }
        }

        item { SettingsHeading("About") }
        item {
            GlassCard {
                Text("ADMOSS Downloader ${BuildConfig.VERSION_NAME}", color = p.text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                if (BuildConfig.UPDATE_REPO.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text("github.com/${BuildConfig.UPDATE_REPO}", color = p.sub, fontSize = 12.sp)
                }
            }
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
        val quick = vm.quick
        if (info == null && quick == null) {
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
            val title = info?.title ?: quick?.title.orEmpty()
            val uploader = info?.uploader ?: quick?.author.orEmpty()

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
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
                if (info != null && info.durationSec > 0) {
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
                title,
                color = p.text,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (uploader.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    uploader,
                    color = p.sub,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))
            if (info != null) {
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
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = p.accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Getting sizes…", color = p.sub, fontSize = 12.sp)
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

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = p.accent,
                uncheckedColor = p.sub,
                checkmarkColor = p.onAccent
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PhotoCard(vm: DownloadViewModel, post: PhotoPost) {
    val p = LocalPalette.current
    GlassCard {
        Pill("TikTok photo post", p.accent)
        Spacer(Modifier.height(10.dp))
        Text(
            post.title.ifBlank { "Photo post" },
            color = p.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (post.author.isNotBlank()) {
            Text("@${post.author}", color = p.sub, fontSize = 13.sp)
        }

        if (vm.photoPreviews.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                vm.photoPreviews.forEach { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(72.dp)
                            .height(112.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                val more = post.images.size - vm.photoPreviews.size
                if (more > 0) {
                    Box(
                        Modifier
                            .width(72.dp)
                            .height(112.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.border),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+$more", color = p.sub, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        CheckRow(
            if (post.images.size == 1) "The photo" else "All ${post.images.size} photos",
            vm.photoWantImages
        ) { vm.photoWantImages = it }
        if (post.musicUrl != null) {
            val name = if (post.musicTitle.isNotBlank()) "Music: ${post.musicTitle}" else "Music (MP3)"
            CheckRow(name, vm.photoWantMusic) { vm.photoWantMusic = it }
        } else {
            Text("No music found in this post", color = p.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Photos are saved in Pictures/ADMOSS and music in Music/ADMOSS.",
            color = p.sub,
            fontSize = 12.sp
        )
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
    val photoPost = vm.photo
    val enabled = Engine.ready && vm.link.isNotBlank() && (pl == null || selectedCount > 0) &&
        (photoPost == null || vm.photoWantImages || vm.photoWantMusic)
    val label = when {
        photoPost != null -> {
            val n = photoPost.images.size
            val music = vm.photoWantMusic && photoPost.musicUrl != null
            when {
                vm.photoWantImages && music -> "Download $n photos + music"
                vm.photoWantImages -> "Download $n photos"
                else -> "Download music"
            }
        }
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

private fun openPhotos(context: Context, item: HistoryItem) {
    val i = Intent(context, PhotoViewerActivity::class.java)
        .putStringArrayListExtra(PhotoViewerActivity.EXTRA_URIS, ArrayList(item.photoUris))
        .putExtra(PhotoViewerActivity.EXTRA_MUSIC, item.musicUri ?: "")
        .putExtra(PhotoViewerActivity.EXTRA_TITLE, item.title)
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

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasMediaPermission(context: Context): Boolean =
    mediaPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun itemMeta(item: HistoryItem): String {
    val kind = when {
        item.photoUris.isNotEmpty() -> {
            val n = item.photoUris.size
            (if (n == 1) "1 photo" else "$n photos") + if (item.musicUri != null) " + music" else ""
        }
        item.audio -> "Audio"
        else -> "Video"
    }
    return if (item.size > 0) "$kind  •  ${formatSize(item.size)}" else kind
}

private fun itemIcon(item: HistoryItem): Int = when {
    item.photoUris.isNotEmpty() -> R.drawable.ic_photo
    item.audio -> R.drawable.ic_music
    else -> R.drawable.ic_play
}

/** The selected tab opens up and shows its name; the others stay small. */
@Composable
private fun CategoryTab(label: String, @DrawableRes icon: Int, count: Int, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val bg by animateColorAsState(if (selected) p.accent else Color.Transparent, tween(250), label = "tabBg")
    val fg by animateColorAsState(if (selected) p.onAccent else p.sub, tween(250), label = "tabFg")
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ico(icon, fg, 20.dp)
        if (selected) {
            Spacer(Modifier.width(7.dp))
            Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text("$count", color = fg.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SmallAction(label: String, @DrawableRes icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .border(1.dp, p.border, shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(icon, p.text, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PickVideosDialog(
    title: String,
    askName: Boolean,
    initialName: String,
    candidates: List<HistoryItem>,
    onConfirm: (String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalPalette.current
    var name by remember { mutableStateOf(initialName) }
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (askName) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Playlist name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (candidates.isEmpty()) {
                    Text("There are no other downloaded videos or songs to add.", color = p.sub, fontSize = 14.sp)
                } else {
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(candidates, key = { it.uri }) { c ->
                            val on = c.uri in picked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { picked = if (on) picked - c.uri else picked + c.uri }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = on,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = p.accent,
                                        uncheckedColor = p.sub,
                                        checkmarkColor = p.onAccent
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    c.title,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, picked) },
                enabled = picked.isNotEmpty() && name.isNotBlank()
            ) { Text(if (picked.isEmpty()) "Add" else "Add ${picked.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LibraryTab() {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 5 }) // all, videos, music, photos, playlists
    var gridView by rememberSaveable { mutableStateOf(false) }
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<HistoryItem>?>(null) }
    val selecting = selected.isNotEmpty()

    // Android may ask the user to confirm before it deletes files from an earlier install
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        DeleteController.onRequestResult(context, r.resultCode == Activity.RESULT_OK)
    }
    val pendingRequest = DeleteController.pendingRequest
    LaunchedEffect(pendingRequest) {
        if (pendingRequest != null) deleteLauncher.launch(IntentSenderRequest.Builder(pendingRequest).build())
    }
    val deleteMessage = DeleteController.message
    LaunchedEffect(deleteMessage) {
        if (deleteMessage != null) {
            Toast.makeText(context, deleteMessage, Toast.LENGTH_LONG).show()
            DeleteController.message = null
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            DownloadCenter.restoreAsync(context, false)
        } else {
            DownloadCenter.restoreMessage = "Access was not allowed, so your files could not be found."
        }
    }

    // After a reinstall the list is empty: bring the files back by themselves if access was already given
    LaunchedEffect(Unit) {
        if (DownloadCenter.history.isEmpty() && hasMediaPermission(context)) {
            DownloadCenter.restoreAsync(context, true)
        }
    }

    BackHandler(enabled = openName != null) { openName = null }
    BackHandler(enabled = selecting) { selected = emptySet() }

    val name = openName
    if (name != null) {
        PlaylistDetail(name, onBack = { openName = null }, onRenamed = { openName = it })
    } else {
        val all = DownloadCenter.history
        val videos = all.filter { !it.audio && it.photoUris.isEmpty() }
        val music = all.filter { it.audio && it.photoUris.isEmpty() }
        val photos = all.filter { it.photoUris.isNotEmpty() }
        val groups = all.filter { it.playlist != null }.groupBy { it.playlist ?: "" }

        if (showNew) {
            PickVideosDialog(
                title = "New playlist",
                askName = true,
                initialName = "",
                candidates = all.filter { it.photoUris.isEmpty() },
                onConfirm = { n, uris ->
                    DownloadCenter.addToPlaylist(context, n, uris)
                    showNew = false
                    openName = n.trim()
                    scope.launch { pagerState.scrollToPage(4) }
                },
                onDismiss = { showNew = false }
            )
        }

        confirmDelete?.let { list ->
            DeleteDialog(
                items = list,
                onDeleteFiles = {
                    DeleteController.delete(context, list)
                    confirmDelete = null
                    selected = emptySet()
                },
                onRemoveFromList = {
                    list.forEach { DownloadCenter.removeHistory(context, it) }
                    confirmDelete = null
                    selected = emptySet()
                },
                onDismiss = { confirmDelete = null }
            )
        }

        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                if (selecting) {
                    val picked = all.filter { it.uri in selected }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Ico(R.drawable.ic_close, p.text, 22.dp, "Cancel selection")
                        }
                        Text(
                            "${selected.size} selected",
                            color = p.text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { selected = all.map { it.uri }.toSet() }) {
                            Text("Select all", color = p.accent)
                        }
                        IconButton(onClick = { if (picked.isNotEmpty()) confirmDelete = picked }) {
                            Ico(R.drawable.ic_delete, Pink, 24.dp, "Delete selected")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Library",
                            color = p.text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            if (hasMediaPermission(context)) DownloadCenter.restoreAsync(context, false)
                            else permLauncher.launch(mediaPermissions())
                        }) {
                            Ico(R.drawable.ic_restore, p.text, 22.dp, "Find my saved files")
                        }
                        IconButton(onClick = { gridView = !gridView }) {
                            Ico(if (gridView) R.drawable.ic_list else R.drawable.ic_grid, p.text, 22.dp, "Change view")
                        }
                    }
                }
                if (DeleteController.busy) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = p.accent,
                        trackColor = p.border
                    )
                }

                val sizeText = totalSizeText(all)
                Text(
                    "${all.size} items  •  ${groups.size} playlists" + if (sizeText != "-") "  •  $sizeText" else "",
                    color = p.sub,
                    fontSize = 13.sp
                )

                if (DownloadCenter.restoring || DownloadCenter.restoreMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(p.card)
                            .border(1.dp, p.border, shape)
                            .clickable(enabled = !DownloadCenter.restoring) { DownloadCenter.restoreMessage = null }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (DownloadCenter.restoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = p.accent
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Looking for your saved files…", color = p.text, fontSize = 14.sp)
                        } else {
                            Ico(R.drawable.ic_restore, p.accent, 20.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(DownloadCenter.restoreMessage.orEmpty(), color = p.text, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tabs = listOf(
                        Triple("All", R.drawable.ic_library, all.size),
                        Triple("Videos", R.drawable.ic_video, videos.size),
                        Triple("Music", R.drawable.ic_music, music.size),
                        Triple("Photos", R.drawable.ic_photo, photos.size),
                        Triple("Playlists", R.drawable.ic_playlist, groups.size)
                    )
                    tabs.forEachIndexed { i, t ->
                        CategoryTab(t.first, t.second, t.third, pagerState.currentPage == i) {
                            scope.launch { pagerState.animateScrollToPage(i) }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(if (pagerState.currentPage == 4) "Search playlists" else "Search your downloads") },
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
                Spacer(Modifier.height(10.dp))
            }

            // Swipe left or right to move between the categories
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                LibraryPage(
                    category = page,
                    gridView = gridView,
                    query = query,
                    onOpenPlaylist = { openName = it },
                    onNewPlaylist = { showNew = true },
                    selected = selected,
                    onToggle = { uri -> selected = if (uri in selected) selected - uri else selected + uri },
                    onLongPress = { uri -> selected = selected + uri },
                    onAskDelete = { confirmDelete = listOf(it) }
                )
            }
        }
    }
}

@Composable
private fun LibraryPage(
    category: Int,
    gridView: Boolean,
    query: String,
    onOpenPlaylist: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onAskDelete: (HistoryItem) -> Unit
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val all = DownloadCenter.history
    // Worked out again only when the list or the search text changes, so scrolling stays smooth
    val groups = remember(all) { all.filter { it.playlist != null }.groupBy { it.playlist ?: "" } }
    val groupNames = remember(groups, query) {
        groups.keys.toList().filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    }
    val base = remember(all, category) {
        when (category) {
            1 -> all.filter { !it.audio && it.photoUris.isEmpty() }
            2 -> all.filter { it.audio && it.photoUris.isEmpty() }
            3 -> all.filter { it.photoUris.isNotEmpty() }
            4 -> emptyList()
            else -> all
        }
    }
    val shown = remember(base, query) {
        if (query.isBlank()) base else base.filter { it.title.contains(query, ignoreCase = true) }
    }
    val selecting = selected.isNotEmpty()

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (gridView) 2 else 1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (category == 4) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SmallAction("New playlist", R.drawable.ic_add, Modifier.fillMaxWidth()) { onNewPlaylist() }
            }
            if (groups.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No playlists yet. Download a playlist, or tap New playlist to put your own videos together.",
                        color = p.sub,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else if (groupNames.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("No playlists match \"$query\".", color = p.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(groupNames, key = { it }) { groupName ->
                val list = (groups[groupName] ?: emptyList()).sortedBy { it.index }
                if (gridView) {
                    PlaylistGridCard(
                        name = groupName,
                        videos = list,
                        onOpen = { onOpenPlaylist(groupName) },
                        onPlay = { playItems(context, list, 0, groupName) }
                    )
                } else {
                    PlaylistCard(
                        name = groupName,
                        videos = list,
                        onOpen = { onOpenPlaylist(groupName) },
                        onPlay = { playItems(context, list, 0, groupName) }
                    )
                }
            }
        } else {
            if (base.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        when (category) {
                            1 -> "No videos yet."
                            2 -> "No music yet."
                            3 -> "No photos yet."
                            else -> "Nothing here yet. Finished downloads show up in this list. If you reinstalled the app, tap the arrow icon at the top to find the files it saved before."
                        },
                        color = p.sub,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Nothing matches \"$query\".", color = p.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(shown, key = { it.uri }) { entry ->
                val open = {
                    if (entry.photoUris.isNotEmpty()) openPhotos(context, entry)
                    else playItems(context, listOf(entry), 0)
                }
                val isSelected = entry.uri in selected
                val click = { if (selecting) onToggle(entry.uri) else open() }
                if (gridView) {
                    MediaGridCard(
                        entry,
                        onOpen = click,
                        onRemove = { onAskDelete(entry) },
                        selected = isSelected,
                        selecting = selecting,
                        onLongClick = { onLongPress(entry.uri) }
                    )
                } else {
                    HistoryRow(
                        entry,
                        onOpen = click,
                        onRemove = { onAskDelete(entry) },
                        selected = isSelected,
                        selecting = selecting,
                        onLongClick = { onLongPress(entry.uri) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGridCard(
    item: HistoryItem,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    selected: Boolean = false,
    selecting: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) p.accent.copy(alpha = 0.14f) else p.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.border, shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            Thumb(item.thumb, Modifier.fillMaxSize(), itemIcon(item), 28.dp)
            if (selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (selected) p.accent else Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Ico(R.drawable.ic_check, p.onAccent, 18.dp)
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Ico(R.drawable.ic_delete, Color.White, 17.dp, "Delete")
                }
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                item.title,
                color = p.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(itemMeta(item), color = p.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaylistGridCard(name: String, videos: List<HistoryItem>, onOpen: () -> Unit, onPlay: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .clickable { onOpen() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
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
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(p.accent)
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Ico(R.drawable.ic_play, p.onAccent, 18.dp, "Play all")
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                name,
                color = p.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val sizeText = totalSizeText(videos)
            val countText = if (videos.size == 1) "1 video" else "${videos.size} videos"
            Text(
                if (sizeText != "-") "$countText  •  $sizeText" else countText,
                color = p.sub,
                fontSize = 11.sp
            )
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
private fun PlaylistDetail(name: String, onBack: () -> Unit, onRenamed: (String) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val all = DownloadCenter.history
    val videos = all.filter { it.playlist == name }.sortedBy { it.index }
    var showRename by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    if (showRename) {
        RenameDialog(
            current = name,
            onDone = { newName ->
                DownloadCenter.renamePlaylist(context, name, newName)
                showRename = false
                onRenamed(newName.trim())
            },
            onDismiss = { showRename = false }
        )
    }
    if (showAdd) {
        PickVideosDialog(
            title = "Add to this playlist",
            askName = false,
            initialName = name,
            candidates = all.filter { it.photoUris.isEmpty() && it.playlist != name },
            onConfirm = { _, uris ->
                DownloadCenter.addToPlaylist(context, name, uris)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    if (showDelete) {
        DeleteDialog(
            items = videos,
            onDeleteFiles = {
                showDelete = false
                DeleteController.delete(context, videos)
                onBack()
            },
            onRemoveFromList = {
                showDelete = false
                DownloadCenter.removePlaylist(context, name)
                onBack()
            },
            onDismiss = { showDelete = false }
        )
    }

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
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
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
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
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

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallAction("Add videos", R.drawable.ic_add, Modifier.weight(1f)) { showAdd = true }
                SmallAction("Rename", R.drawable.ic_edit, Modifier.weight(1f)) { showRename = true }
            }
        }

        itemsIndexed(videos, key = { _, v -> v.uri }) { i, v ->
            PlaylistVideoRow(
                number = i + 1,
                item = v,
                onPlay = { playItems(context, videos, i, name) },
                onRemove = { DownloadCenter.removeFromPlaylist(context, v) }
            )
        }

        item {
            Column {
                TextButton(onClick = {
                    DownloadCenter.removePlaylist(context, name)
                    onBack()
                }) {
                    Text("Remove this playlist from the list", color = p.sub)
                }
                if (videos.isNotEmpty()) {
                    TextButton(onClick = { showDelete = true }) {
                        Text("Delete all files in this playlist", color = Pink)
                    }
                }
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
            Ico(R.drawable.ic_close, p.sub, 20.dp, "Remove from this playlist")
        }
    }
}

@Composable
private fun HistoryRow(
    item: HistoryItem,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    selected: Boolean = false,
    selecting: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) p.accent.copy(alpha = 0.14f) else p.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.border, shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(
            item.thumb,
            Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)),
            itemIcon(item),
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
            Text(itemMeta(item), color = p.sub, fontSize = 11.sp)
        }
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = p.accent,
                    uncheckedColor = p.sub,
                    checkmarkColor = p.onAccent
                )
            )
            Spacer(Modifier.width(8.dp))
        } else {
            IconButton(onClick = onRemove) {
                Ico(R.drawable.ic_delete, p.sub, 20.dp, "Delete")
            }
        }
    }
}

/** Asks before anything is deleted. "Delete" removes the files from the phone itself. */
@Composable
private fun DeleteDialog(
    items: List<HistoryItem>,
    onDeleteFiles: () -> Unit,
    onRemoveFromList: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalPalette.current
    val many = items.size > 1
    val files = items.sumOf { if (it.photoUris.isNotEmpty()) it.photoUris.size + (if (it.musicUri != null) 1 else 0) else 1 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (many) "Delete ${items.size} items?" else "Delete this item?") },
        text = {
            Column {
                Text(
                    if (many) "These $files files will be deleted from your phone."
                    else "\"${cutText(items[0].title, 60)}\" will be deleted from your phone.",
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(6.dp))
                Text("You can't undo this.", color = p.sub, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onRemoveFromList) {
                    Text("Only remove from the list", color = p.accent)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDeleteFiles) {
                Text("Delete", color = Pink, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
