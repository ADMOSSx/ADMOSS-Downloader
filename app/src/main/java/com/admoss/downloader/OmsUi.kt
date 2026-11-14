package com.admoss.downloader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.annotation.DrawableRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// ---------- look (follows the app's current theme) ----------

private class OmsLook(
    val accent: Color,
    val onAccent: Color,
    val card: Color,
    val text: Color,
    val sub: Color,
    val border: Color
)

@Composable
private fun omsLook(): OmsLook {
    val scheme = MaterialTheme.colorScheme
    val text = LocalContentColor.current
    return OmsLook(scheme.primary, scheme.onPrimary, scheme.surface, text, text.copy(alpha = 0.62f), text.copy(alpha = 0.14f))
}

private val OmsGreen = Color(0xFF3E9B6B)
private val OmsRed = Color(0xFFD64545)

@Composable
private fun OmsIcon(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp) {
    Icon(painter = painterResource(id), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun OmsButton(text: String, l: OmsLook, modifier: Modifier = Modifier, filled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (filled) l.accent else Color.Transparent)
            .border(1.5.dp, if (filled) l.accent else l.border, shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) l.onAccent else l.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

private fun omsHasAll(ctx: Context, perms: Array<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

private fun omsHasAny(ctx: Context, perms: Array<String>): Boolean =
    perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

/** What the receiving phone needs to open the private Wi-Fi. */
private fun omsWifiPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun omsEta(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
        m > 0 -> String.format(Locale.US, "%dm %02ds", m, sec)
        else -> "${sec}s"
    }
}

private fun omsDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun omsKindName(kind: Int, plural: Boolean): String = when (kind) {
    OmsProtocol.KIND_PHOTO -> if (plural) "photos" else "photo"
    OmsProtocol.KIND_VIDEO -> if (plural) "videos" else "video"
    else -> if (plural) "songs" else "song"
}

private fun omsCountLine(kinds: List<Int>): String {
    val parts = ArrayList<String>()
    for (k in 0..2) {
        val n = kinds.count { it == k }
        if (n > 0) parts.add("$n ${omsKindName(k, n != 1)}")
    }
    return parts.joinToString("  •  ")
}

// ---------- the tab ----------

@Composable
fun OmsTab() {
    val l = omsLook()
    val context = LocalContext.current
    val phase = OmsCenter.phase
    val step = OmsCenter.step
    val inSession = phase != OmsPhase.IDLE && phase != OmsPhase.PREPARING && phase != OmsPhase.WAITING

    // the screen stays on while phones are talking
    val view = LocalView.current
    DisposableEffect(phase) {
        view.keepScreenOn = phase == OmsPhase.WAITING || phase == OmsPhase.JOINING ||
            phase == OmsPhase.CONNECTING || phase == OmsPhase.REQUEST || phase == OmsPhase.TRANSFERRING
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = step != 0 || phase != OmsPhase.IDLE) { OmsCenter.back(context) }

    when {
        inSession -> OmsTransferScreen(l)
        step == 0 -> OmsHome(l)
        step == 1 -> OmsPick(l)
        step == 2 -> OmsReview(l)
        step == 3 -> OmsScan(l)
        else -> OmsReceive(l)
    }
}

// ---------- home ----------

@Composable
private fun OmsHome(l: OmsLook) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Share", color = l.text, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text("Send photos, videos and music to a phone next to you. No internet needed.", color = l.sub, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))

        // hero
        val dark = lerp(l.accent, Color.Black, 0.5f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(l.accent, dark))),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                for (i in 1..4) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.26f - i * 0.045f),
                        radius = size.height * (0.14f + i * 0.13f),
                        center = c,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
            Box(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                OmsIcon(R.drawable.ic_share, l.accent, 40.dp)
            }
            Text(
                "Fast  •  Private  •  Offline",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .weight(1f)
                    .height(150.dp)
                    .clip(shape)
                    .background(l.accent)
                    .clickable { OmsCenter.step = 1 }
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) { OmsIcon(R.drawable.ic_send, l.onAccent, 26.dp) }
                Column {
                    Text("SEND", color = l.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Pick your files", color = l.onAccent.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .height(150.dp)
                    .clip(shape)
                    .background(l.card)
                    .border(1.5.dp, l.accent, shape)
                    .clickable { OmsCenter.step = 4 }
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(l.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) { OmsIcon(R.drawable.ic_download, l.accent, 26.dp) }
                Column {
                    Text("RECEIVE", color = l.text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    Text("Show my QR code", color = l.sub, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OmsChip("Photos", R.drawable.ic_photo, l)
            OmsChip("Videos", R.drawable.ic_video, l)
            OmsChip("Music", R.drawable.ic_music, l)
        }

        Spacer(Modifier.height(18.dp))
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(l.card)
                .border(1.dp, l.border, shape)
                .padding(16.dp)
        ) {
            Text("How it works", color = l.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Text("1.  The other phone taps RECEIVE and shows a QR code.", color = l.sub, fontSize = 13.sp)
            Text("2.  You tap SEND, pick your files and scan that code.", color = l.sub, fontSize = 13.sp)
            Text("3.  The other phone accepts, and the files fly over a private Wi-Fi.", color = l.sub, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "While sending, your phone leaves your normal Wi-Fi for a moment. Nothing goes through the internet.",
                color = l.sub,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun OmsChip(label: String, @DrawableRes icon: Int, l: OmsLook) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(l.accent.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OmsIcon(icon, l.accent, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = l.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- thumbnails ----------

private val omsThumbCache = android.util.LruCache<String, ImageBitmap>(200)

@Composable
private fun OmsThumb(item: OmsMediaItem, modifier: Modifier, l: OmsLook) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(omsThumbCache.get(item.uri.toString()), item.uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    val b = context.contentResolver.loadThumbnail(item.uri, Size(320, 320), null).asImageBitmap()
                    omsThumbCache.put(item.uri.toString(), b)
                    b
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }
    Box(modifier.background(l.border), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            OmsIcon(
                when (item.kind) {
                    OmsProtocol.KIND_PHOTO -> R.drawable.ic_photo
                    OmsProtocol.KIND_VIDEO -> R.drawable.ic_video
                    else -> R.drawable.ic_music
                },
                l.sub,
                26.dp
            )
        }
    }
}

// ---------- pick ----------

@Composable
private fun OmsPick(l: OmsLook) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(0) }
    var allowed by remember { mutableStateOf(omsHasAny(context, OmsMedia.permissions())) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = omsHasAny(context, OmsMedia.permissions())
    }
    val items by produceState(initialValue = emptyList<OmsMediaItem>(), kind, allowed) {
        value = if (allowed && kind != 3) withContext(Dispatchers.IO) { OmsMedia.load(context, kind) } else emptyList()
    }
    val selected = OmsCenter.selected
    val chosen = remember(selected) { selected.map { it.uri }.toHashSet() }
    val totalSize = selected.sumOf { it.size }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 12.dp, end = 20.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { OmsCenter.back(context) }) { OmsIcon(R.drawable.ic_back, l.text) }
                Text("Choose what to send", color = l.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(l.border)
                    .padding(4.dp)
            ) {
                val tabs = listOf(
                    Triple("Photos", R.drawable.ic_photo, OmsProtocol.KIND_PHOTO),
                    Triple("Videos", R.drawable.ic_video, OmsProtocol.KIND_VIDEO),
                    Triple("Music", R.drawable.ic_music, OmsProtocol.KIND_MUSIC),
                    Triple("Series", R.drawable.ic_series, 3)
                )
                tabs.forEach { t ->
                    val on = kind == t.third
                    val count = if (t.third == 3) {
                        selected.count { it.series.isNotEmpty() }
                    } else {
                        selected.count { it.kind == t.third && it.series.isEmpty() }
                    }
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) l.accent else Color.Transparent)
                            .clickable { kind = t.third }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OmsIcon(t.second, if (on) l.onAccent else l.sub, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (count > 0) "${t.first} $count" else t.first,
                            color = if (on) l.onAccent else l.sub,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Box(Modifier.weight(1f)) {
            when {
                kind == 3 -> OmsSeriesPicker(l)
                !allowed -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Allow access to your photos, videos and music", color = l.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("ADMOSS only reads them to show you what you can send.", color = l.sub, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    OmsButton("Allow access", l, Modifier.fillMaxWidth()) { permLauncher.launch(OmsMedia.permissions()) }
                }
                items.isEmpty() -> Text(
                    "Nothing here yet.",
                    color = l.sub,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(24.dp)
                )
                kind == OmsProtocol.KIND_MUSIC -> LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        val on = item.uri in chosen
                        val shape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(if (on) l.accent.copy(alpha = 0.14f) else l.card)
                                .border(if (on) 2.dp else 1.dp, if (on) l.accent else l.border, shape)
                                .clickable { OmsCenter.toggle(item) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OmsThumb(item, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), l)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = l.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    omsSize(item.size) + if (item.durationMs > 0) "  •  " + omsDuration(item.durationMs) else "",
                                    color = l.sub,
                                    fontSize = 11.sp
                                )
                            }
                            OmsCheck(on, l)
                        }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        val on = item.uri in chosen
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { OmsCenter.toggle(item) }
                        ) {
                            OmsThumb(item, Modifier.fillMaxSize(), l)
                            if (on) Box(Modifier.fillMaxSize().background(l.accent.copy(alpha = 0.28f)))
                            if (item.kind == OmsProtocol.KIND_VIDEO && item.durationMs > 0) {
                                Text(
                                    omsDuration(item.durationMs),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(5.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { OmsCheck(on, l) }
                        }
                    }
                }
            }
        }

        if (selected.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(l.card)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${selected.size} selected", color = l.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(omsSize(totalSize), color = l.sub, fontSize = 12.sp)
                }
                OmsButton("Next", l, Modifier.width(120.dp)) { OmsCenter.step = 2 }
            }
        }
    }
}

@Composable
private fun OmsCheck(on: Boolean, l: OmsLook) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (on) l.accent else Color.Black.copy(alpha = 0.35f))
            .border(1.5.dp, if (on) l.accent else Color.White.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (on) OmsIcon(R.drawable.ic_check, l.onAccent, 18.dp)
    }
}

// ---------- review (previews before sending) ----------

@Composable
private fun OmsReview(l: OmsLook) {
    val context = LocalContext.current
    val selected = OmsCenter.selected
    LaunchedEffect(selected.isEmpty()) { if (selected.isEmpty()) OmsCenter.step = 1 }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 12.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { OmsCenter.back(context) }) { OmsIcon(R.drawable.ic_back, l.text) }
            Column {
                Text("Ready to send", color = l.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    omsCountLine(selected.map { it.kind }) + "  •  " + omsSize(selected.sumOf { it.size }),
                    color = l.sub,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val seriesLines = omsSelectionSeries(selected)
            if (seriesLines.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val cardShape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(l.accent.copy(alpha = 0.10f))
                            .border(1.dp, l.accent.copy(alpha = 0.4f), cardShape)
                            .padding(12.dp)
                    ) {
                        Text("From your Series tab", color = l.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        seriesLines.forEach { Text(it, color = l.text, fontSize = 13.sp) }
                        Text(
                            "The other phone gets them in its own Series tab, with the same seasons and order.",
                            color = l.sub,
                            fontSize = 11.sp
                        )
                    }
                }
            }
            items(selected, key = { it.uri.toString() }) { item ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    OmsThumb(item, Modifier.fillMaxSize(), l)
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(omsSize(item.size), color = Color.White, fontSize = 10.sp)
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable { OmsCenter.toggle(item) },
                        contentAlignment = Alignment.Center
                    ) { OmsIcon(R.drawable.ic_close, Color.White, 14.dp) }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(l.card)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            OmsButton("Scan the other phone's QR code", l, Modifier.fillMaxWidth()) { OmsCenter.step = 3 }
            Spacer(Modifier.height(6.dp))
            Text(
                "On the other phone: open Share and tap RECEIVE.",
                color = l.sub,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ---------- scan ----------

@Composable
private fun OmsScan(l: OmsLook) {
    val context = LocalContext.current
    var camera by remember { mutableStateOf(omsHasAll(context, arrayOf(Manifest.permission.CAMERA))) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
    var error by remember { mutableStateOf<String?>(null) }
    val handled = remember { AtomicBoolean(false) }

    LaunchedEffect(error) {
        if (error != null) {
            kotlinx.coroutines.delay(2500)
            error = null
            handled.set(false)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (camera) {
            OmsCameraView { text ->
                if (handled.compareAndSet(false, true)) {
                    Handler(Looper.getMainLooper()).post {
                        if (OmsQr.parse(text) == null) {
                            error = "That isn't an ADMOSS share code."
                        } else if (!OmsCenter.startSending(context, text)) {
                            error = "Pick some files first."
                        }
                    }
                }
            }
            // a frame in the middle
            Canvas(Modifier.fillMaxSize()) {
                val side = minOf(size.width, size.height) * 0.62f
                val left = (size.width - side) / 2f
                val top = (size.height - side) / 2f - 30.dp.toPx()
                val arm = side * 0.2f
                val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                val c = l.accent
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(c, Offset(x, y), Offset(x + dx * arm, y), stroke.width, StrokeCap.Round)
                    drawLine(c, Offset(x, y), Offset(x, y + dy * arm), stroke.width, StrokeCap.Round)
                }
                corner(left, top, 1f, 1f)
                corner(left + side, top, -1f, 1f)
                corner(left, top + side, 1f, -1f)
                corner(left + side, top + side, -1f, -1f)
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Allow the camera to scan the QR code", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OmsButton("Allow camera", l, Modifier.fillMaxWidth()) { permLauncher.launch(Manifest.permission.CAMERA) }
            }
        }

        Row(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { OmsCenter.back(context) },
                contentAlignment = Alignment.Center
            ) { OmsIcon(R.drawable.ic_back, Color.White) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                error ?: "Scan the QR code on the receiving phone",
                color = if (error != null) Color(0xFFFFB4A9) else Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Text(
                "${OmsCenter.selected.size} files ready  •  ${omsSize(OmsCenter.selected.sumOf { it.size })}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun OmsCameraView(onText: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    val latest by rememberUpdatedState(onText)

    DisposableEffect(Unit) {
        onDispose {
            try {
                providerHolder[0]?.unbindAll()
            } catch (e: Throwable) {
                // ignore
            }
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val provider = future.get()
                    providerHolder[0] = provider
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor, OmsQrAnalyzer { text -> latest(text) })
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Throwable) {
                    // the camera is busy or missing: the screen stays black
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        }
    )
}

// ---------- receive ----------

@Composable
private fun OmsReceive(l: OmsLook) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(omsHasAll(context, omsWifiPermissions())) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = omsHasAll(context, omsWifiPermissions())
    }
    LaunchedEffect(allowed) {
        if (allowed && OmsCenter.phase == OmsPhase.IDLE) OmsCenter.startReceiving(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { OmsCenter.back(context) }) { OmsIcon(R.drawable.ic_back, l.text) }
            Text("Receive", color = l.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!allowed) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "Allow ADMOSS to create a private Wi-Fi",
                    color = l.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (Build.VERSION.SDK_INT >= 33) "Android calls this \"Nearby devices\"." else "Android calls this \"Location\". Your location is not used.",
                    color = l.sub,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OmsButton("Allow", l, Modifier.fillMaxWidth()) { permLauncher.launch(omsWifiPermissions()) }
            } else {
                val qr = OmsCenter.qrText
                val image = remember(qr) { if (qr != null) OmsQr.bitmap(qr, 640) else null }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (image != null) {
                        Image(bitmap = image, contentDescription = "QR code", modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(color = l.accent)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    if (image != null) "Waiting for the sender…" else "Getting ready…",
                    color = l.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "On the other phone tap SEND, pick the files and scan this code.",
                    color = l.sub,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(l.accent.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OmsIcon(R.drawable.ic_share, l.accent, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(OmsCenter.myName.ifBlank { "This phone" }, color = l.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Spacer(Modifier.height(24.dp))
                OmsButton("Cancel", l, Modifier.fillMaxWidth(), filled = false) { OmsCenter.back(context) }
            }
        }
    }
}

// ---------- transfer ----------

@Composable
private fun OmsTransferScreen(l: OmsLook) {
    val context = LocalContext.current
    val phase = OmsCenter.phase
    val sending = OmsCenter.sending
    val peer = OmsCenter.peerName.ifBlank { "the other phone" }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        when (phase) {
            OmsPhase.JOINING, OmsPhase.CONNECTING -> {
                Spacer(Modifier.height(40.dp))
                CircularProgressIndicator(color = l.accent, modifier = Modifier.size(64.dp), strokeWidth = 5.dp)
                Spacer(Modifier.height(24.dp))
                Text(
                    if (phase == OmsPhase.JOINING) "Connecting to $peer" else "Waiting for $peer",
                    color = l.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (phase == OmsPhase.JOINING) {
                        "If Android asks to connect to a Wi-Fi network, tap Connect."
                    } else {
                        "${OmsCenter.fileCount} files  •  ${omsSize(OmsCenter.totalBytes)}.  The other phone has to accept."
                    },
                    color = l.sub,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                OmsButton("Cancel", l, Modifier.fillMaxWidth(), filled = false) { OmsCenter.cancel(context) }
            }

            OmsPhase.REQUEST -> {
                val files = OmsCenter.request ?: emptyList()
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(l.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) { OmsIcon(R.drawable.ic_download, l.accent, 44.dp) }
                Spacer(Modifier.height(18.dp))
                Text(
                    "$peer wants to send you",
                    color = l.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                val seriesInfo = omsRequestSeries(files)
                if (seriesInfo.isNotEmpty()) {
                    seriesInfo.forEach {
                        Text(it, color = l.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    Text(omsCountLine(files.map { it.kind }), color = l.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Text(omsSize(OmsCenter.totalBytes), color = l.sub, fontSize = 13.sp)
                OmsCenter.requestNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = OmsGreen, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(l.card)
                        .border(1.dp, l.border, shape)
                        .padding(14.dp)
                ) {
                    files.take(6).forEach {
                        Text(it.name, color = l.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (files.size > 6) Text("and ${files.size - 6} more…", color = l.sub, fontSize = 12.sp)
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OmsButton("Decline", l, Modifier.weight(1f), filled = false) { OmsCenter.answer(false) }
                    OmsButton("Accept", l, Modifier.weight(1f)) { OmsCenter.answer(true) }
                }
            }

            OmsPhase.TRANSFERRING -> {
                val total = OmsCenter.totalBytes
                val done = OmsCenter.doneBytes
                val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                val speed = OmsCenter.speed
                val ringTrack = l.border
                val ringColor = l.accent
                Text(
                    if (sending) "Sending to $peer" else "Receiving from $peer",
                    color = l.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = 14.dp.toPx()
                        drawArc(
                            color = ringTrack,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(w / 2, w / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                            style = Stroke(width = w, cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * fraction,
                            useCenter = false,
                            topLeft = Offset(w / 2, w / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                            style = Stroke(width = w, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(fraction * 100).toInt()}%", color = l.text, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp)
                        Text("File ${OmsCenter.fileIndex} of ${OmsCenter.fileCount}", color = l.sub, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    OmsCenter.currentName,
                    color = l.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(l.card)
                        .border(1.dp, l.border, shape)
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OmsStat(if (speed > 0) omsSize(speed) + "/s" else "…", "Speed", l)
                    OmsStat(omsSize(done) + " / " + omsSize(total), "Transferred", l)
                    OmsStat(if (speed > 0) omsEta((total - done) / speed) else "…", "Time left", l)
                }
                Spacer(Modifier.height(24.dp))
                OmsButton("Cancel transfer", l, Modifier.fillMaxWidth(), filled = false) { OmsCenter.cancel(context) }
            }

            OmsPhase.DONE -> {
                Spacer(Modifier.height(30.dp))
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(OmsGreen.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) { OmsIcon(R.drawable.ic_check, OmsGreen, 60.dp) }
                Spacer(Modifier.height(20.dp))
                Text(
                    if (sending) "Sent!" else "Received!",
                    color = l.text,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(OmsCenter.summary.orEmpty(), color = l.sub, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(30.dp))
                OmsButton("Done", l, Modifier.fillMaxWidth()) { OmsCenter.finish(context) }
            }

            else -> {
                // FAILED, REJECTED, CANCELLED
                val failed = phase == OmsPhase.FAILED
                Spacer(Modifier.height(30.dp))
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background((if (failed) OmsRed else l.sub).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) { OmsIcon(if (failed) R.drawable.ic_error else R.drawable.ic_close, if (failed) OmsRed else l.sub, 56.dp) }
                Spacer(Modifier.height(20.dp))
                Text(
                    when (phase) {
                        OmsPhase.FAILED -> "Transfer failed"
                        OmsPhase.REJECTED -> "Declined"
                        else -> "Cancelled"
                    },
                    color = l.text,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(OmsCenter.message.orEmpty(), color = l.sub, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(30.dp))
                OmsButton("Try again", l, Modifier.fillMaxWidth()) { OmsCenter.retry(context) }
                Spacer(Modifier.height(10.dp))
                OmsButton("Close", l, Modifier.fillMaxWidth(), filled = false) { OmsCenter.finish(context) }
            }
        }
    }
}

@Composable
private fun OmsStat(value: String, label: String, l: OmsLook) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = l.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        Text(label, color = l.sub, fontSize = 11.sp)
    }
}


// ---------- series ----------

private fun omsSelectionSeries(items: List<OmsMediaItem>): List<String> =
    items.filter { it.series.isNotEmpty() }.groupBy { it.series }.map { (name, list) ->
        val seasons = list.map { it.season }.filter { it.isNotEmpty() }.distinct()
        name + (if (seasons.isNotEmpty()) "  •  " + seasons.joinToString(", ") else "") +
            "  •  " + (if (list.size == 1) "1 episode" else "${list.size} episodes") +
            "  •  " + omsSize(list.sumOf { it.size })
    }

private fun omsRequestSeries(files: List<OmsFile>): List<String> =
    files.filter { it.series.isNotEmpty() }.groupBy { it.series }.map { (name, list) ->
        val seasons = list.map { it.season }.filter { it.isNotEmpty() }.distinct()
        name + (if (seasons.isNotEmpty()) "  •  " + seasons.joinToString(", ") else "") +
            "  •  " + (if (list.size == 1) "1 episode" else "${list.size} episodes")
    }

private fun omsGroupLine(list: List<HistoryItem>, seasons: Int): String {
    val size = list.sumOf { it.size }
    return (if (seasons > 0) (if (seasons == 1) "1 season  •  " else "$seasons seasons  •  ") else "") +
        (if (list.size == 1) "1 episode" else "${list.size} episodes") +
        (if (size > 0) "  •  " + omsSize(size) else "")
}

/** The Series tab inside the picker: tick a whole series, a season, or single episodes. */
@Composable
private fun OmsSeriesPicker(l: OmsLook) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders = DownloadCenter.phoneItems.groupBy { it.playlist ?: "" }
    val selected = OmsCenter.selected
    val chosen = remember(selected) { selected.map { it.uri.toString() }.toHashSet() }
    var open by remember { mutableStateOf<Set<String>>(emptySet()) }

    if (folders.isEmpty()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No series yet", color = l.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Make one in the Series tab first, then you can send it from here.",
                color = l.sub,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    fun stateOf(list: List<HistoryItem>): Int {
        val n = list.count { it.uri in chosen }
        return when {
            n == 0 -> 0
            n == list.size -> 2
            else -> 1
        }
    }

    fun toggle(list: List<HistoryItem>) {
        val allOn = stateOf(list) == 2
        scope.launch {
            if (allOn) {
                OmsCenter.removeWhere(list.map { it.uri }.toHashSet())
            } else {
                val res = withContext(Dispatchers.IO) { OmsSeries.toItems(context, list) }
                OmsCenter.addItems(res.items)
                if (res.missing > 0) {
                    Toast.makeText(context, "${res.missing} missing files were left out.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { (folder, eps) ->
            val ordered = DownloadCenter.playOrder(eps)
            val seasons = ordered.mapNotNull { it.season }.distinct()
            val folderKey = "f:$folder"
            val folderOpen = folderKey in open
            item(key = folderKey) {
                OmsTreeRow(
                    level = 0,
                    title = folder,
                    subtitle = omsGroupLine(ordered, seasons.size),
                    state = stateOf(ordered),
                    open = folderOpen,
                    expandable = true,
                    thumb = ordered.firstOrNull { !it.thumb.isNullOrBlank() }?.thumb,
                    l = l,
                    onCheck = { toggle(ordered) },
                    onOpen = { open = if (folderOpen) open - folderKey else open + folderKey }
                )
            }
            if (folderOpen) {
                ordered.filter { it.season == null }.forEach { h ->
                    item(key = "e:" + h.uri) {
                        OmsTreeRow(
                            level = 1,
                            title = h.title,
                            subtitle = if (h.size > 0) omsSize(h.size) else "",
                            state = stateOf(listOf(h)),
                            open = false,
                            expandable = false,
                            thumb = null,
                            l = l,
                            onCheck = { toggle(listOf(h)) },
                            onOpen = {}
                        )
                    }
                }
                seasons.forEach { season ->
                    val list = ordered.filter { it.season == season }
                    val seasonKey = "s:$folder|$season"
                    val seasonOpen = seasonKey in open
                    item(key = seasonKey) {
                        OmsTreeRow(
                            level = 1,
                            title = season,
                            subtitle = omsGroupLine(list, 0),
                            state = stateOf(list),
                            open = seasonOpen,
                            expandable = true,
                            thumb = null,
                            l = l,
                            onCheck = { toggle(list) },
                            onOpen = { open = if (seasonOpen) open - seasonKey else open + seasonKey }
                        )
                    }
                    if (seasonOpen) {
                        list.forEach { h ->
                            item(key = "e:" + h.uri) {
                                OmsTreeRow(
                                    level = 2,
                                    title = h.title,
                                    subtitle = if (h.size > 0) omsSize(h.size) else "",
                                    state = stateOf(listOf(h)),
                                    open = false,
                                    expandable = false,
                                    thumb = null,
                                    l = l,
                                    onCheck = { toggle(listOf(h)) },
                                    onOpen = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row of the series picker. [state]: 0 nothing ticked, 1 some, 2 all. */
@Composable
private fun OmsTreeRow(
    level: Int,
    title: String,
    subtitle: String,
    state: Int,
    open: Boolean,
    expandable: Boolean,
    thumb: String?,
    l: OmsLook,
    onCheck: () -> Unit,
    onOpen: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val on = state == 2
    val angle by animateFloatAsState(if (open) 0f else -90f, tween(200), label = "oms-arrow")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (level * 16).dp)
            .clip(shape)
            .background(if (on) l.accent.copy(alpha = 0.12f) else l.card)
            .border(if (on) 2.dp else 1.dp, if (on) l.accent else l.border, shape)
            .clickable { if (expandable) onOpen() else onCheck() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        2 -> l.accent
                        1 -> l.accent.copy(alpha = 0.4f)
                        else -> Color.Transparent
                    }
                )
                .border(1.5.dp, if (state > 0) l.accent else l.sub, CircleShape)
                .clickable { onCheck() },
            contentAlignment = Alignment.Center
        ) {
            if (state == 2) {
                OmsIcon(R.drawable.ic_check, l.onAccent, 18.dp)
            } else if (state == 1) {
                Box(
                    Modifier
                        .width(10.dp)
                        .height(2.dp)
                        .background(l.onAccent)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (level == 0) {
            Thumb(thumb, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), R.drawable.ic_series, 22.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = l.text,
                fontWeight = if (level < 2) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (level == 0) 15.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) Text(subtitle, color = l.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expandable) {
            Icon(
                painter = painterResource(R.drawable.ic_expand),
                contentDescription = if (open) "Hide" else "Show",
                tint = if (open) l.accent else l.sub,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(angle)
            )
        }
    }
}
