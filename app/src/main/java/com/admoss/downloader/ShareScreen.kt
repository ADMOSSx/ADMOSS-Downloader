package com.admoss.downloader

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- helpers ----------

@DrawableRes
private fun kindIcon(k: ShareKind): Int = when (k) {
    ShareKind.PHOTO -> R.drawable.ic_photo
    ShareKind.VIDEO -> R.drawable.ic_video
    ShareKind.MUSIC -> R.drawable.ic_music
}

private fun kindLabel(k: ShareKind): String = when (k) {
    ShareKind.PHOTO -> "Photo"
    ShareKind.VIDEO -> "Video"
    ShareKind.MUSIC -> "Music"
}

private fun shSize(b: Long): String = if (b <= 0L) "0 MB" else formatSize(b)

private fun shSummary(list: List<ShareFile>): String {
    val ph = list.count { it.kind == ShareKind.PHOTO }
    val vi = list.count { it.kind == ShareKind.VIDEO }
    val mu = list.count { it.kind == ShareKind.MUSIC }
    val parts = ArrayList<String>()
    if (ph > 0) parts.add(if (ph == 1) "1 photo" else "$ph photos")
    if (vi > 0) parts.add(if (vi == 1) "1 video" else "$vi videos")
    if (mu > 0) parts.add(if (mu == 1) "1 song" else "$mu songs")
    parts.add(shSize(list.sumOf { it.size }))
    return parts.joinToString("  •  ")
}

private fun receivePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun qrBitmap(text: String, size: Int = 720): ImageBitmap? = try {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
} catch (e: Throwable) {
    null
}

// ---------- small building blocks ----------

@Composable
private fun ShButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    color: Color? = null,
    onClick: () -> Unit
) {
    val p = LocalPalette.current
    val c = color ?: p.accent
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(54.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(c) else Modifier.border(1.5.dp, c, shape))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) p.onAccent else c, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun ShHero(
    title: String,
    sub: String,
    @DrawableRes icon: Int,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(24.dp)
    val fg = if (filled) p.onAccent else p.text
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (filled) Modifier.background(p.accent)
                else Modifier.background(p.card).border(1.dp, p.border, shape)
            )
            .clickable { onClick() }
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (filled) p.onAccent.copy(alpha = 0.16f) else p.accent),
            contentAlignment = Alignment.Center
        ) { Ico(icon, p.onAccent, 30.dp) }
        Spacer(Modifier.height(16.dp))
        Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = 30.sp)
        Text(sub, color = fg.copy(alpha = 0.75f), fontSize = 14.sp)
    }
}

@Composable
private fun ShPickChip(label: String, @DrawableRes icon: Int, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Ico(icon, p.accent, 26.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ShStat(label: String, value: String, modifier: Modifier) {
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
        Text(value, color = p.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = p.sub, fontSize = 11.sp)
    }
}

@Composable
private fun ShareThumb(f: ShareFile, modifier: Modifier) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val bmp by produceState<ImageBitmap?>(null, f.uri) {
        val u = f.uri
        value = if (u == null || f.kind == ShareKind.MUSIC) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.loadThumbnail(u, Size(240, 240), null).asImageBitmap()
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }
    Box(modifier.background(p.border), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Ico(kindIcon(f.kind), p.sub, 24.dp)
        }
    }
}

@Composable
private fun FileRow(f: ShareFile, onRemove: (() -> Unit)?) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.border, shape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShareThumb(f, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${kindLabel(f.kind)}  •  ${shSize(f.size)}", color = p.sub, fontSize = 11.sp)
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Ico(R.drawable.ic_close, p.sub, 20.dp, "Remove") }
        }
    }
}

// ---------- the Share tab ----------

@Composable
fun ShareTab() {
    val ctx = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(0) } // 0 home, 1 send
    val phase = ShareCenter.phase

    // keep the screen on while something is happening
    val view = LocalView.current
    val active = phase != SharePhase.IDLE
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ShareCenter.startReceive(ctx)
    }

    when (phase) {
        SharePhase.IDLE ->
            if (mode == 1) SendFlow(onBack = { mode = 0 })
            else ShareHome(onSend = { mode = 1 }, onReceive = { perm.launch(receivePermissions()) })
        SharePhase.WAITING -> ReceiveFlow()
        else -> TransferScreen(onClose = { ShareCenter.leave(); mode = 0 })
    }
}

@Composable
private fun ShareHome(onSend: () -> Unit, onReceive: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Share", color = p.text, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text(
            "Send photos, videos and music to a phone next to you. No internet, no account.",
            color = p.sub,
            fontSize = 14.sp
        )
        ShHero("SEND", "Pick photos, videos or music", R.drawable.ic_send, true, Modifier.weight(1f), onSend)
        ShHero("RECEIVE", "Show a code on this phone", R.drawable.ic_download, false, Modifier.weight(1f), onReceive)
    }
}

// ---------- send ----------

@Composable
private fun SendFlow(onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(0) } // 0 choose files, 1 connect
    val picked = ShareCenter.picked

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val add = withContext(Dispatchers.IO) { ShareCenter.describe(ctx, uris) }
                val have = ShareCenter.picked.mapNotNull { it.uri }.toSet()
                ShareCenter.picked = ShareCenter.picked + add.filter { it.uri !in have }
            }
        }
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents
        if (text != null) ShareCenter.startSend(ctx, text)
    }

    DisposableEffect(stage) {
        if (stage == 1) ShareCenter.startDiscovery(ctx)
        onDispose { if (stage == 1 && ShareCenter.phase == SharePhase.IDLE) ShareCenter.stopDiscovery() }
    }
    BackHandler { if (stage == 1) stage = 0 else onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (stage == 1) stage = 0 else onBack() }) { Ico(R.drawable.ic_back, p.text, 24.dp, "Back") }
            Text(if (stage == 0) "Send" else "Connect", color = p.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }

        if (stage == 0) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShPickChip("Photos", R.drawable.ic_photo, Modifier.weight(1f)) { pick.launch(arrayOf("image/*")) }
                ShPickChip("Videos", R.drawable.ic_video, Modifier.weight(1f)) { pick.launch(arrayOf("video/*")) }
                ShPickChip("Music", R.drawable.ic_music, Modifier.weight(1f)) { pick.launch(arrayOf("audio/*")) }
            }
            Spacer(Modifier.height(12.dp))
            if (picked.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Choose what you want to send. You can pick many files, and add more any time.",
                        color = p.sub,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(picked, key = { it.uri.toString() }) { f ->
                        FileRow(f) { ShareCenter.picked = ShareCenter.picked.filter { it !== f } }
                    }
                }
                Column(Modifier.padding(20.dp)) {
                    Text(shSummary(picked), color = p.sub, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    ShButton("Continue", Modifier.fillMaxWidth()) { stage = 1 }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Pill(shSummary(picked), p.accent)
                ShButton("Scan the other phone's QR code", Modifier.fillMaxWidth()) {
                    scan.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the code shown on the other phone")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                }
                Text("On the other phone open Share and tap RECEIVE.", color = p.sub, fontSize = 13.sp)

                Spacer(Modifier.height(4.dp))
                Text("Nearby phones", color = p.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val list = ShareCenter.nearby
                if (list.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.accent)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking for phones on the same Wi-Fi…", color = p.sub, fontSize = 13.sp)
                    }
                } else {
                    list.forEach { d ->
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(p.card)
                                .border(1.dp, p.border, shape)
                                .clickable { ShareCenter.sendTo(ctx, d) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Ico(R.drawable.ic_send, p.accent, 24.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.name, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text("Tap to send", color = p.sub, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- receive (waiting with the QR code) ----------

@Composable
private fun ReceiveFlow() {
    val p = LocalPalette.current
    BackHandler { ShareCenter.leave() }
    val qr = ShareCenter.qr
    val bmp = remember(qr) { qr?.let { qrBitmap(it) } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ShareCenter.leave() }) { Ico(R.drawable.ic_back, p.text, 24.dp, "Back") }
            Text("Receive", color = p.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text("Ready to receive", color = p.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text(ShareCenter.status, color = p.sub, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White)
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = "QR code", modifier = Modifier.fillMaxSize())
            } else if (!ShareCenter.hotspotFailed) {
                CircularProgressIndicator(color = p.accent)
            } else {
                Ico(R.drawable.ic_error, Color.Gray, 56.dp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Pill("This phone: ${ShareCenter.deviceName()}", p.accent)
        Spacer(Modifier.height(10.dp))
        Text(
            "Files are saved to Pictures, Movies and Music, in a folder called ADMOSS Share.",
            color = p.sub,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ---------- transfer screen ----------

@Composable
private fun TransferScreen(onClose: () -> Unit) {
    val p = LocalPalette.current
    val phase = ShareCenter.phase
    val sending = ShareCenter.sending
    val files = ShareCenter.files
    val total = ShareCenter.totalBytes
    val frac = if (total > 0L) (ShareCenter.doneBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(frac, tween(250), label = "share-progress")
    val rate = if (ShareCenter.speed > 0L) "${formatSize(ShareCenter.speed)}/s" else "…"

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (phase) {
            SharePhase.CONNECTING -> {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator(Modifier.size(56.dp), color = p.accent, strokeWidth = 5.dp)
                Spacer(Modifier.height(20.dp))
                Text(ShareCenter.status, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("If Android asks to connect to the other phone's network, tap Connect.", color = p.sub, fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                ShButton("Cancel", Modifier.fillMaxWidth(), filled = false, color = Pink) { ShareCenter.cancelTransfer() }
            }

            SharePhase.REQUEST -> {
                Spacer(Modifier.height(8.dp))
                Pill("Incoming files", p.accent)
                Spacer(Modifier.height(12.dp))
                Text("${ShareCenter.peerName} wants to send you", color = p.text, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
                Text(shSummary(files), color = p.sub, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files) { f -> FileRow(f, null) }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShButton("Decline", Modifier.weight(1f), filled = false, color = Pink) { ShareCenter.decline() }
                    ShButton("Accept", Modifier.weight(1f)) { ShareCenter.accept() }
                }
            }

            SharePhase.TRANSFERRING -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (sending) "Sending to ${ShareCenter.peerName}" else "Receiving from ${ShareCenter.peerName}",
                    color = p.text, fontWeight = FontWeight.Bold, fontSize = 18.sp
                )
                Spacer(Modifier.height(24.dp))
                Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.fillMaxSize(),
                        color = p.accent,
                        trackColor = p.border,
                        strokeWidth = 12.dp
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(animated * 100).toInt()}%", color = p.text, fontWeight = FontWeight.Bold, fontSize = 44.sp)
                        Text(rate, color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                val idx = ShareCenter.fileIndex
                Text("File ${idx + 1} of ${files.size}", color = p.sub, fontSize = 12.sp)
                Text(files.getOrNull(idx)?.name ?: "", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShStat("Speed", rate, Modifier.weight(1f))
                    ShStat("Transferred", "${shSize(ShareCenter.doneBytes)} / ${shSize(total)}", Modifier.weight(1.4f))
                    ShStat("Time left", if (ShareCenter.eta > 0L) formatEta(ShareCenter.eta) else "…", Modifier.weight(1f))
                }
                Spacer(Modifier.weight(1f))
                ShButton("Cancel", Modifier.fillMaxWidth(), filled = false, color = Pink) { ShareCenter.cancelTransfer() }
            }

            SharePhase.DONE -> {
                Spacer(Modifier.weight(1f))
                Ico(R.drawable.ic_check, Green, 96.dp)
                Spacer(Modifier.height(16.dp))
                Text("Transfer complete", color = p.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Text(shSummary(files), color = p.sub, fontSize = 13.sp)
                if (!sending) {
                    Spacer(Modifier.height(8.dp))
                    Text("Saved in ADMOSS Share (Pictures, Movies, Music).", color = p.sub, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f))
                ShButton("Done", Modifier.fillMaxWidth()) { onClose() }
            }

            else -> { // FAILED
                Spacer(Modifier.weight(1f))
                Ico(R.drawable.ic_error, Red, 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(if (ShareCenter.error == "Cancelled") "Cancelled" else "Transfer failed", color = p.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(6.dp))
                Text(ShareCenter.error ?: "", color = p.sub, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                ShButton("Close", Modifier.fillMaxWidth()) { onClose() }
            }
        }
    }
}
