@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.admoss.downloader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Locale

/**
 * The Series tab: series and movies that are already on the phone (or were received through Share).
 * Nothing is copied or moved. Removing something only removes it from the app, never the file.
 */

// ---------- state that survives switching tabs ----------

object SrUi {
    /** 0 all, 1 series, 2 movies */
    var filter by mutableStateOf(0)
    var sortAz by mutableStateOf(false)
    var query by mutableStateOf("")

    /** The series that is open, or null for the home screen. */
    var open by mutableStateOf<String?>(null)
}

// ---------- look (follows the app's current theme) ----------

private class SrLook(
    val accent: Color,
    val onAccent: Color,
    val card: Color,
    val text: Color,
    val sub: Color,
    val border: Color
)

@Composable
private fun srLook(): SrLook {
    val scheme = MaterialTheme.colorScheme
    val text = LocalContentColor.current
    return SrLook(scheme.primary, scheme.onPrimary, scheme.surface, text, text.copy(alpha = 0.62f), text.copy(alpha = 0.14f))
}

private val SrRed = Color(0xFFD64545)
private val SrGreen = Color(0xFF3E9B6B)

@Composable
private fun SrIcon(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp) {
    Icon(painter = painterResource(id), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

// ---------- watching progress (written by the player) ----------

private class SrProgress(val position: Int, val duration: Int, val watched: Boolean, val time: Long) {
    val fraction: Float
        get() = if (watched) 1f else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val started: Boolean
        get() = !watched && position > 0
}

private fun srProgress(prefs: SharedPreferences, uri: String): SrProgress = SrProgress(
    prefs.getInt(uri, 0),
    prefs.getInt("$uri#d", 0),
    prefs.getBoolean("$uri#w", false),
    prefs.getLong("$uri#t", 0L)
)

@Composable
private fun srResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick += 1 }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

private fun srTime(ms: Int): String {
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, (s % 3600) / 60, s % 60)
    } else {
        String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

/** "S1 E3": the season and the number of the episode inside it. */
private fun srEpisodeLabel(all: List<HistoryItem>, item: HistoryItem): String {
    val season = item.season
    val inSeason = all.filter { it.season == season }
    val number = inSeason.indexOfFirst { it.uri == item.uri } + 1
    return (if (season != null) "$season  " else "") + "E" + number.coerceAtLeast(1)
}

/** Where "Play" should start: the episode you stopped in, else the first one you have not watched. */
private fun srStartIndex(prefs: SharedPreferences, items: List<HistoryItem>): Int {
    var best = -1
    var bestTime = 0L
    for ((i, h) in items.withIndex()) {
        val p = srProgress(prefs, h.uri)
        if (p.started && p.time >= bestTime) {
            best = i
            bestTime = p.time
        }
    }
    if (best >= 0) return best
    val firstNew = items.indexOfFirst { !srProgress(prefs, it.uri).watched }
    return if (firstNew >= 0) firstNew else 0
}

private fun srPlay(context: Context, items: List<HistoryItem>, start: Int, name: String?) {
    if (items.isEmpty()) return
    val i = Intent(context, PlayerActivity::class.java)
        .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(items.map { it.uri }))
        .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(items.map { it.title }))
        .putStringArrayListExtra(PlayerActivity.EXTRA_THUMBS, ArrayList(items.map { it.thumb ?: "" }))
        .putExtra(PlayerActivity.EXTRA_AUDIO, items.map { it.audio }.toBooleanArray())
        .putExtra(PlayerActivity.EXTRA_SIZES, items.map { it.size }.toLongArray())
        .putExtra(PlayerActivity.EXTRA_NAME, name)
        .putExtra(PlayerActivity.EXTRA_START, start.coerceIn(0, items.size - 1))
    context.startActivity(i)
}

private fun srKeepAccess(context: Context, picked: List<android.net.Uri>) {
    picked.forEach {
        try {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Throwable) {
            // some pickers only give a temporary permission
        }
    }
}

// ---------- one entry on the home screen: a series or a movie ----------

private class SrEntry(val key: String, val title: String, val movie: Boolean, val items: List<HistoryItem>)

private class SrResume(
    val title: String,
    val sub: String,
    val thumb: String?,
    val fraction: Float,
    val time: Long,
    val list: List<HistoryItem>,
    val index: Int,
    val name: String?
)

/** The things you stopped in the middle of, newest first. One entry per series. */
private fun srContinue(
    prefs: SharedPreferences,
    groups: Map<String, List<HistoryItem>>,
    movies: List<HistoryItem>
): List<SrResume> {
    val out = ArrayList<SrResume>()
    for ((name, eps) in groups) {
        val ordered = DownloadCenter.playOrder(eps)
        var best = -1
        var bestTime = 0L
        for ((i, h) in ordered.withIndex()) {
            val p = srProgress(prefs, h.uri)
            if (p.started && p.time >= bestTime) {
                best = i
                bestTime = p.time
            }
        }
        if (best >= 0) {
            val h = ordered[best]
            out.add(
                SrResume(
                    name, srEpisodeLabel(ordered, h) + "  •  " + h.title,
                    h.thumb, srProgress(prefs, h.uri).fraction, bestTime, ordered, best, name
                )
            )
        }
    }
    for (m in movies) {
        val p = srProgress(prefs, m.uri)
        if (p.started) out.add(SrResume(m.title, "Movie", m.thumb, p.fraction, p.time, listOf(m), 0, null))
    }
    return out.sortedByDescending { it.time }.take(8)
}

// ---------- the tab ----------

@Composable
fun SeriesScreen() {
    val context = LocalContext.current
    val tick = srResumeTick()
    val open = SrUi.open
    BackHandler(enabled = open != null) { SrUi.open = null }

    // The file picker is shared by the home screen and the series screen
    var pickFolder by remember { mutableStateOf<String?>(null) }
    var pickSeason by remember { mutableStateOf<String?>(null) }
    val episodeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        val folder = pickFolder
        pickFolder = null
        if (folder != null && picked.isNotEmpty()) {
            srKeepAccess(context, picked)
            DownloadCenter.addFromPhoneAsync(context, folder, picked, pickSeason) { n ->
                Toast.makeText(
                    context,
                    if (n == 0) "These are already in your series." else "Added $n to \"$folder\".",
                    Toast.LENGTH_SHORT
                ).show()
            }
            SrUi.open = folder
        }
    }
    val movieLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            srKeepAccess(context, picked)
            DownloadCenter.addMoviesAsync(context, picked) { n ->
                Toast.makeText(
                    context,
                    if (n == 0) "These movies are already here." else if (n == 1) "Added 1 movie." else "Added $n movies.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            SrUi.filter = if (SrUi.filter == 1) 0 else SrUi.filter
        }
    }
    fun pickEpisodes(folder: String, season: String?) {
        pickFolder = folder
        pickSeason = season
        episodeLauncher.launch(arrayOf("video/*", "audio/*"))
    }

    if (open != null) {
        SrDetail(open, tick) { season -> pickEpisodes(open, season) }
    } else {
        SrHome(tick, { folder, season -> pickEpisodes(folder, season) }, { movieLauncher.launch(arrayOf("video/*")) })
    }
}

// ---------- home ----------

@Composable
private fun SrHome(tick: Int, onNewSeries: (String, String?) -> Unit, onAddMovies: () -> Unit) {
    val l = srLook()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("admoss_resume", Context.MODE_PRIVATE) }
    val groups = DownloadCenter.seriesItems.groupBy { it.playlist ?: "" }
    val movies = DownloadCenter.movieItems
    val q = SrUi.query.trim()
    var searching by remember { mutableStateOf(q.isNotEmpty()) }
    var showNew by remember { mutableStateOf(false) }
    var plus by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SrEntry?>(null) }
    var removing by remember { mutableStateOf<SrEntry?>(null) }

    val seriesEntries = groups.map { (name, eps) -> SrEntry("s:$name", name, false, DownloadCenter.playOrder(eps)) }
    val movieEntries = movies.map { SrEntry("m:" + it.uri, it.title, true, listOf(it)) }
    fun prepare(list: List<SrEntry>): List<SrEntry> {
        val found = list.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
        return if (SrUi.sortAz) found.sortedBy { it.title.lowercase() } else found
    }
    val shownSeries = if (SrUi.filter == 2) emptyList() else prepare(seriesEntries)
    val shownMovies = if (SrUi.filter == 1) emptyList() else prepare(movieEntries)
    val nothingAtAll = seriesEntries.isEmpty() && movieEntries.isEmpty()
    val resume = remember(tick, groups, movies) { srContinue(prefs, groups, movies) }

    if (showNew) {
        SrNewSeriesDialog(
            onDone = { folder, season ->
                showNew = false
                onNewSeries(folder, season.ifBlank { null })
            },
            onDismiss = { showNew = false }
        )
    }
    renaming?.let { e ->
        SrTextDialog(
            title = if (e.movie) "Rename movie" else "Rename series",
            label = "Name",
            initial = e.title,
            action = "Save",
            onDone = { n ->
                if (e.movie) DownloadCenter.renameItem(context, e.items[0].uri, n) else DownloadCenter.renameFolder(context, e.title, n)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
    removing?.let { e ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${cutText(e.title, 40)}\"?") },
            text = {
                Text(
                    (if (e.movie) "This movie is" else "This series is") +
                        " removed from the app only. Your video files stay on your phone.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (e.movie) DownloadCenter.removeItems(context, e.items) else DownloadCenter.removeFolder(context, e.title)
                    removing = null
                }) { Text("Remove", color = SrRed, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Series & Movies", color = l.text, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            Text(
                                if (nothingAtAll) {
                                    "Your own videos, organized"
                                } else {
                                    "${seriesEntries.size} series  •  ${movieEntries.size} movies"
                                },
                                color = l.sub,
                                fontSize = 13.sp
                            )
                        }
                        if (!nothingAtAll) {
                            IconButton(onClick = {
                                searching = !searching
                                if (!searching) SrUi.query = ""
                            }) { SrIcon(if (searching) R.drawable.ic_close else R.drawable.ic_search, l.text, 22.dp) }
                        }
                    }
                    if (searching) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = SrUi.query,
                            onValueChange = { SrUi.query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Search series and movies") },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = l.accent,
                                unfocusedBorderColor = l.border,
                                focusedTextColor = l.text,
                                unfocusedTextColor = l.text,
                                cursorColor = l.accent,
                                focusedPlaceholderColor = l.sub,
                                unfocusedPlaceholderColor = l.sub
                            )
                        )
                    }
                    if (!nothingAtAll) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SrChip("All", SrUi.filter == 0, l) { SrUi.filter = 0 }
                            SrChip("Series", SrUi.filter == 1, l) { SrUi.filter = 1 }
                            SrChip("Movies", SrUi.filter == 2, l) { SrUi.filter = 2 }
                            Spacer(Modifier.weight(1f))
                            SrChip(if (SrUi.sortAz) "A–Z" else "Recent", false, l) { SrUi.sortAz = !SrUi.sortAz }
                        }
                    }
                }
            }

            if (resume.isNotEmpty() && q.isEmpty() && SrUi.filter == 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("Continue watching", color = l.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(resume.size) { i ->
                                val r = resume[i]
                                SrResumeCard(r, l) { srPlay(context, r.list, r.index, r.name) }
                            }
                        }
                    }
                }
            }

            if (nothingAtAll) {
                item(span = { GridItemSpan(maxLineSpan) }) { SrEmpty(l, { showNew = true }, onAddMovies) }
            } else if (shownSeries.isEmpty() && shownMovies.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (q.isNotEmpty()) "Nothing matches \"$q\"." else "Nothing here yet.",
                        color = l.sub,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            if (shownSeries.isNotEmpty()) {
                if (SrUi.filter == 0 && shownMovies.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SrSection("Series", shownSeries.size, l) }
                }
                items(shownSeries, key = { it.key }) { e ->
                    SrPoster(
                        e, prefs, tick, l,
                        onOpen = { SrUi.open = e.title },
                        onPlay = { srPlay(context, e.items, srStartIndex(prefs, e.items), e.title) },
                        onSend = { OmsSeries.sendFromSeries(context, e.title, null) },
                        onRename = { renaming = e },
                        onRemove = { removing = e }
                    )
                }
            }
            if (shownMovies.isNotEmpty()) {
                if (SrUi.filter == 0 && shownSeries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SrSection("Movies", shownMovies.size, l) }
                }
                items(shownMovies, key = { it.key }) { e ->
                    SrPoster(
                        e, prefs, tick, l,
                        onOpen = { srPlay(context, e.items, 0, null) },
                        onPlay = { srPlay(context, e.items, 0, null) },
                        onSend = { OmsSeries.sendItems(context, e.items) },
                        onRename = { renaming = e },
                        onRemove = { removing = e }
                    )
                }
            }
        }

        if (!nothingAtAll) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(l.accent)
                        .clickable { plus = true }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SrIcon(R.drawable.ic_add, l.onAccent, 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Add", color = l.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                DropdownMenu(expanded = plus, onDismissRequest = { plus = false }) {
                    DropdownMenuItem(
                        text = { Text("New series") },
                        onClick = {
                            plus = false
                            showNew = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add movies") },
                        onClick = {
                            plus = false
                            onAddMovies()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SrChip(label: String, selected: Boolean, l: SrLook, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) l.accent else Color.Transparent)
            .border(1.dp, if (selected) l.accent else l.border, shape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) l.onAccent else l.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SrSection(title: String, count: Int, l: SrLook) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = l.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.width(8.dp))
        Text("$count", color = l.sub, fontSize = 13.sp)
    }
}

@Composable
private fun SrEmpty(l: SrLook, onSeries: () -> Unit, onMovies: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(l.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { SrIcon(R.drawable.ic_series, l.accent, 46.dp) }
        Spacer(Modifier.height(18.dp))
        Text("Your own cinema", color = l.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add movies, or make a series with seasons and episodes from videos on your phone. " +
                "Your files are never copied, moved or deleted.",
            color = l.sub,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SrButton("Create a series", l, true, onSeries)
            SrButton("Add movies", l, false, onMovies)
        }
    }
}

@Composable
private fun SrButton(text: String, l: SrLook, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (filled) l.accent else Color.Transparent)
            .border(1.5.dp, if (filled) l.accent else l.border, shape)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(text, color = if (filled) l.onAccent else l.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun SrBar(fraction: Float, l: SrLook, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.28f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(l.accent)
        )
    }
}

@Composable
private fun SrResumeCard(r: SrResume, l: SrLook, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(230.dp)
            .clip(shape)
            .background(l.card)
            .border(1.dp, l.border, shape)
            .clickable { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            Thumb(r.thumb, Modifier.fillMaxSize(), R.drawable.ic_play, 28.dp)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(l.accent),
                contentAlignment = Alignment.Center
            ) { SrIcon(R.drawable.ic_play, l.onAccent, 26.dp) }
            SrBar(r.fraction, l, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(r.title, color = l.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.sub, color = l.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A poster: cover, name, a badge, progress, play. Long-press for more. */
@Composable
private fun SrPoster(
    e: SrEntry,
    prefs: SharedPreferences,
    tick: Int,
    l: SrLook,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onSend: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    var menu by remember { mutableStateOf(false) }
    val seasons = e.items.mapNotNull { it.season }.distinct().size
    val watched = remember(tick, e.items) { e.items.count { srProgress(prefs, it.uri).watched } }
    val movieProgress = if (e.movie) remember(tick, e.items) { srProgress(prefs, e.items[0].uri) } else null
    val fraction = if (e.movie) (movieProgress?.fraction ?: 0f) else watched.toFloat() / e.items.size.coerceAtLeast(1)
    val done = if (e.movie) movieProgress?.watched == true else watched == e.items.size
    val line = if (e.movie) {
        if (e.items[0].size > 0) omsSize(e.items[0].size) else "Movie"
    } else {
        (if (seasons > 0) (if (seasons == 1) "1 season  •  " else "$seasons seasons  •  ") else "") +
            (if (e.items.size == 1) "1 episode" else "${e.items.size} episodes")
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(shape)
            .border(1.dp, l.border, shape)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
    ) {
        Thumb(
            e.items.firstOrNull { !it.thumb.isNullOrBlank() }?.thumb,
            Modifier.fillMaxSize(),
            if (e.movie) R.drawable.ic_video else R.drawable.ic_series,
            44.dp
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 14.dp)
        ) {
            Text(e.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(line, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1)
            if (fraction > 0f) {
                Spacer(Modifier.height(8.dp))
                SrBar(fraction, l)
            }
        }
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (done) {
                SrIcon(R.drawable.ic_check, SrGreen, 14.dp)
                Spacer(Modifier.width(4.dp))
                Text("WATCHED", color = SrGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(if (e.movie) "MOVIE" else "SERIES", color = l.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(l.accent)
                .clickable { onPlay() },
            contentAlignment = Alignment.Center
        ) { SrIcon(R.drawable.ic_play, l.onAccent, 22.dp) }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (e.movie) "Play" else "Play / resume") }, onClick = { menu = false; onPlay() })
            DropdownMenuItem(text = { Text("Send to another phone") }, onClick = { menu = false; onSend() })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Remove from the app", color = SrRed) }, onClick = { menu = false; onRemove() })
        }
    }
}

// ---------- dialogs ----------

@Composable
private fun SrTextDialog(
    title: String,
    label: String,
    initial: String,
    action: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SrNewSeriesDialog(onDone: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var season by remember { mutableStateOf("S1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New series") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Series name (for example Wednesday)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = season,
                    onValueChange = { season = it },
                    singleLine = true,
                    label = { Text("First season (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(name.trim(), season.trim()) }, enabled = name.isNotBlank()) { Text("Choose videos") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------- one series ----------

@Composable
private fun SrDetail(name: String, tick: Int, onPick: (String?) -> Unit) {
    val l = srLook()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("admoss_resume", Context.MODE_PRIVATE) }
    val videos = DownloadCenter.playOrder(DownloadCenter.seriesItems.filter { it.playlist == name })
    LaunchedEffect(videos.isEmpty()) { if (videos.isEmpty()) SrUi.open = null }

    val seasons = videos.mapNotNull { it.season }.distinct()
    val plain = videos.filter { it.season == null }
    val chips = seasons + (if (plain.isNotEmpty()) listOf("") else emptyList())
    var selected by remember(name) { mutableStateOf<String?>(null) }
    val pickedSeason = selected
    val current: String? = if (pickedSeason != null && pickedSeason in chips) pickedSeason else chips.firstOrNull()
    val shown = when {
        current == null -> videos
        current == "" -> plain
        else -> videos.filter { it.season == current }
    }
    val progress = remember(tick, videos) { videos.associate { it.uri to srProgress(prefs, it.uri) } }
    val watchedCount = progress.values.count { it.watched }
    val startIndex = remember(tick, videos) { srStartIndex(prefs, videos) }
    val resuming = videos.getOrNull(startIndex)?.let { progress[it.uri]?.started == true } == true
    val size = videos.sumOf { it.size }

    var menu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showAddSeason by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var seasonToRename by remember { mutableStateOf<String?>(null) }
    var seasonToRemove by remember { mutableStateOf<String?>(null) }

    if (showRename) {
        SrTextDialog("Rename series", "Name", name, "Save", { n ->
            DownloadCenter.renameFolder(context, name, n)
            showRename = false
            SrUi.open = n.trim()
        }, { showRename = false })
    }
    if (showAddSeason) {
        SrTextDialog("Add a season", "Season name", DownloadCenter.nextSeasonName(name), "Choose videos", { s ->
            showAddSeason = false
            onPick(s)
        }, { showAddSeason = false })
    }
    seasonToRename?.let { old ->
        SrTextDialog("Rename season", "Name", old, "Save", { n ->
            DownloadCenter.renameSeason(context, name, old, n)
            seasonToRename = null
        }, { seasonToRename = null })
    }
    seasonToRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { seasonToRemove = null },
            title = { Text("Remove \"$s\"?") },
            text = { Text("The episodes are removed from the app only. Their files stay on your phone.", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    DownloadCenter.removeSeason(context, name, s)
                    seasonToRemove = null
                }) { Text("Remove", color = SrRed, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { seasonToRemove = null }) { Text("Cancel") } }
        )
    }
    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("Remove \"${cutText(name, 40)}\"?") },
            text = { Text("This series is removed from the app only. Your video files stay on your phone.", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showRemove = false
                    DownloadCenter.removeFolder(context, name)
                    SrUi.open = null
                }) { Text("Remove", color = SrRed, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { showRemove = false }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // hero
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
            ) {
                Thumb(
                    videos.firstOrNull { !it.thumb.isNullOrBlank() }?.thumb,
                    Modifier.fillMaxSize(),
                    R.drawable.ic_series,
                    64.dp
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.92f))
                            )
                        )
                )
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { SrUi.open = null },
                        contentAlignment = Alignment.Center
                    ) { SrIcon(R.drawable.ic_back, Color.White) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable { menu = true },
                            contentAlignment = Alignment.Center
                        ) { SrIcon(R.drawable.ic_more, Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Add episodes") }, onClick = { menu = false; onPick(current?.ifBlank { null }) })
                            DropdownMenuItem(text = { Text("Add a season") }, onClick = { menu = false; showAddSeason = true })
                            DropdownMenuItem(text = { Text("Rename series") }, onClick = { menu = false; showRename = true })
                            DropdownMenuItem(
                                text = { Text("Send whole series") },
                                onClick = { menu = false; OmsSeries.sendFromSeries(context, name, null) }
                            )
                            if (!current.isNullOrEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Send $current") },
                                    onClick = { menu = false; OmsSeries.sendFromSeries(context, name, current) }
                                )
                                DropdownMenuItem(text = { Text("Rename $current") }, onClick = { menu = false; seasonToRename = current })
                                DropdownMenuItem(
                                    text = { Text("Remove $current", color = SrRed) },
                                    onClick = { menu = false; seasonToRemove = current }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Remove series", color = SrRed) },
                                onClick = { menu = false; showRemove = true }
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                ) {
                    Text(name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (seasons.size > 1) "${seasons.size} seasons  •  " else if (seasons.size == 1) "1 season  •  " else "") +
                            (if (videos.size == 1) "1 episode" else "${videos.size} episodes") +
                            (if (size > 0) "  •  " + omsSize(size) else ""),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    if (watchedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        SrBar(watchedCount.toFloat() / videos.size.coerceAtLeast(1), l)
                        Spacer(Modifier.height(4.dp))
                        Text("$watchedCount of ${videos.size} watched", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }

        // play buttons
        item {
            Row(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(l.accent)
                        .clickable { srPlay(context, videos, startIndex, name) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SrIcon(R.drawable.ic_play, l.onAccent, 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (resuming) "Resume  " + srEpisodeLabel(videos, videos[startIndex]) else "Play",
                        color = l.onAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.5.dp, l.accent, RoundedCornerShape(14.dp))
                        .clickable { srPlay(context, videos.shuffled(), 0, name) },
                    contentAlignment = Alignment.Center
                ) { SrIcon(R.drawable.ic_shuffle, l.accent, 24.dp) }
            }
        }

        // seasons
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chips.size) { i ->
                    val c = chips[i]
                    SrChip(if (c.isEmpty()) "Other" else c, c == current, l) { selected = c }
                }
                item {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, l.accent, RoundedCornerShape(50))
                            .clickable { showAddSeason = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SrIcon(R.drawable.ic_add, l.accent, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("Season", color = l.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
        }

        item {
            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (current.isNullOrEmpty()) "Episodes" else current,
                    color = l.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onPick(current?.ifBlank { null }) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SrIcon(R.drawable.ic_add, l.accent, 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("Add episodes", color = l.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        itemsIndexed(shown, key = { _, v -> v.uri }) { i, v ->
            Box(Modifier.padding(horizontal = 20.dp)) {
                SrEpisodeRow(
                    number = i + 1,
                    item = v,
                    progress = progress[v.uri],
                    l = l,
                    onPlay = { srPlay(context, videos, videos.indexOf(v), name) },
                    onSend = { OmsSeries.sendItems(context, listOf(v)) },
                    onRemove = { DownloadCenter.removeFromPlaylist(context, v) }
                )
            }
        }

        item {
            TextButton(onClick = { showRemove = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text("Remove this series from the app", color = SrRed)
            }
        }
    }
}

@Composable
private fun SrEpisodeRow(
    number: Int,
    item: HistoryItem,
    progress: SrProgress?,
    l: SrLook,
    onPlay: () -> Unit,
    onSend: () -> Unit,
    onRemove: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val missing by produceState(initialValue = false, item.uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(item.uri), "r")?.use { false } ?: true
            } catch (e: Throwable) {
                true
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(l.card)
            .border(1.dp, l.border, shape)
            .clickable { onPlay() }
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(124.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            Thumb(item.thumb, Modifier.fillMaxSize(), R.drawable.ic_play, 24.dp)
            Text(
                "E$number",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
            if (progress?.watched == true) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) { SrIcon(R.drawable.ic_check, SrGreen, 16.dp) }
            }
            if (progress != null && progress.started) SrBar(progress.fraction, l, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = l.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (missing) {
                Text("File not found on the phone", color = SrRed, fontSize = 11.sp)
            } else {
                val state = when {
                    progress?.watched == true -> "Watched"
                    progress != null && progress.started -> "Resume " + srTime(progress.position)
                    else -> ""
                }
                Text(
                    (if (item.size > 0) omsSize(item.size) else "") + (if (state.isNotEmpty()) "  •  $state" else ""),
                    color = l.sub,
                    fontSize = 11.sp
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { SrIcon(R.drawable.ic_more, l.sub, 20.dp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Send to another phone") }, onClick = { menu = false; onSend() })
                DropdownMenuItem(text = { Text("Remove from the series", color = SrRed) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}
