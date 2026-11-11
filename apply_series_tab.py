#!/usr/bin/env python3
"""
ADMOSS Downloader v1.14.1 -> v1.15.0
New "Series" tab in the bottom bar for videos that are already on the phone.
They no longer show in the Library (All / Videos / Music / Playlists).

Run it after apply_fixes2.py, from the project root:

    python apply_series_tab.py

All edits are checked first. If a file does not match what is expected,
NOTHING is changed and the script says which edit failed.
"""
import os
import sys

BASE = "app/src/main/java/com/admoss/downloader/"
MAIN = BASE + "MainActivity.kt"
DC = BASE + "DownloadCenter.kt"
README = "README.md"

NEW_FILES = {
    # Material "tv" icon
    "app/src/main/res/drawable/ic_series.xml": """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z" />
</vector>
""",
}

EDITS = [
    # =====================================================================
    # DownloadCenter.kt
    # =====================================================================

    # Library playlists only touch downloaded items (phone folders are separate)
    (DC,
     "        history.filter { it.playlist == name }.forEach { deleteThumb(it) }\n"
     "        history = history.filter { it.playlist != name }\n",
     "        history.filter { it.playlist == name && !it.external }.forEach { deleteThumb(it) }\n"
     "        history = history.filter { !(it.playlist == name && !it.external) }\n"),
    (DC,
     "        val affected = history.filter { it.playlist == old }\n"
     "        history = history.map { if (it.playlist == old) it.copy(playlist = n) else it }\n",
     "        val affected = history.filter { it.playlist == old && !it.external }\n"
     "        history = history.map { if (it.playlist == old && !it.external) it.copy(playlist = n) else it }\n"),
    (DC,
     "        var next = (history.filter { it.playlist == n }.maxOfOrNull { it.index } ?: -1) + 1\n",
     "        var next = (history.filter { it.playlist == n && !it.external }.maxOfOrNull { it.index } ?: -1) + 1\n"),
    (DC,
     "        var next = (history.filter { it.playlist == name }.maxOfOrNull { it.index } ?: -1) + 1\n",
     "        var next = (history.filter { it.playlist == name && it.external }.maxOfOrNull { it.index } ?: -1) + 1\n"),

    # the 500 limit must never drop phone videos (they cannot be restored)
    (DC,
     "        history = (listOf(saved) + history).take(500)\n",
     "        history = trimHistory(listOf(saved) + history)\n"),
    (DC,
     "        history = (items.filter { it.uri !in known } + history).take(500)\n",
     "        history = trimHistory(items.filter { it.uri !in known } + history)\n"),

    (DC,
     "    // ---- playlist editing ----\n",
     r"""    /** Videos the user added from the phone. They live in the Series tab and never in the Library. */
    val phoneItems: List<HistoryItem>
        get() = history.filter { it.external }

    /** Everything the app downloaded itself (the Library). */
    val downloads: List<HistoryItem>
        get() = history.filter { !it.external }

    /** Keeps the newest 500 downloads. Phone videos are never dropped: "restore" cannot find them again. */
    private fun trimHistory(list: List<HistoryItem>): List<HistoryItem> {
        val phone = list.filter { it.external }
        val own = list.filter { !it.external }.take(500)
        return phone + own
    }

    @Synchronized
    fun renameFolder(ctx: Context, old: String, newName: String) {
        val n = newName.trim()
        if (n.isBlank() || n == old) return
        history = history.map { if (it.external && it.playlist == old) it.copy(playlist = n) else it }
        saveHistory(ctx)
    }

    /** Removes a whole series from the app. Only the app's list changes: the files stay on the phone. */
    @Synchronized
    fun removeFolder(ctx: Context, name: String) {
        removeItems(ctx, history.filter { it.external && it.playlist == name })
    }

    // ---- playlist editing ----
"""),

    # =====================================================================
    # MainActivity.kt: the tab
    # =====================================================================
    (MAIN, "import androidx.compose.ui.text.style.TextOverflow\n",
     "import androidx.compose.ui.text.style.TextAlign\nimport androidx.compose.ui.text.style.TextOverflow\n"),

    (MAIN,
     "                1 -> LibraryTab()\n                else -> SettingsTab()\n",
     "                1 -> LibraryTab()\n                2 -> SeriesTab()\n                else -> SettingsTab()\n"),

    (MAIN,
     r"""        NavItem("Settings", R.drawable.ic_settings, selected == 2, 0, Modifier.weight(1f)) {
            onSelect(2)
        }
""",
     r"""        NavItem("Series", R.drawable.ic_series, selected == 2, 0, Modifier.weight(1f)) {
            onSelect(2)
        }
        NavItem("Settings", R.drawable.ic_settings, selected == 3, 0, Modifier.weight(1f)) {
            onSelect(3)
        }
"""),

    # =====================================================================
    # MainActivity.kt: the Library only shows downloads
    # =====================================================================
    (MAIN,
     "        val all = DownloadCenter.history\n        val videos = all.filter { !it.audio && it.photoUris.isEmpty() }\n",
     "        val all = DownloadCenter.downloads\n        val videos = all.filter { !it.audio && it.photoUris.isEmpty() }\n"),
    (MAIN,
     "    val all = DownloadCenter.history\n    // Worked out again only when the list or the search text changes, so scrolling stays smooth\n",
     "    val all = DownloadCenter.downloads\n    // Worked out again only when the list or the search text changes, so scrolling stays smooth\n"),
    (MAIN,
     "    val all = DownloadCenter.history\n    val videos = DownloadCenter.playOrder(all.filter { it.playlist == name })\n",
     "    val all = DownloadCenter.downloads\n    val videos = DownloadCenter.playOrder(all.filter { it.playlist == name })\n"),
    (MAIN,
     "        if (DownloadCenter.history.isEmpty() && hasMediaPermission(context)) {\n",
     "        if (DownloadCenter.downloads.isEmpty() && hasMediaPermission(context)) {\n"),

    # the phone buttons leave the Library (they live in the Series tab now)
    (MAIN,
     r"""                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallAction("New playlist", R.drawable.ic_add, Modifier.weight(1f)) { onNewPlaylist() }
                    SmallAction("New from phone", R.drawable.ic_add, Modifier.weight(1f)) { onNewFromPhone() }
                }
""",
     '                SmallAction("New playlist", R.drawable.ic_add, Modifier.fillMaxWidth()) { onNewPlaylist() }\n'),
    (MAIN,
     r"""        item {
            SmallAction("Add from phone", R.drawable.ic_add, Modifier.fillMaxWidth()) { onAddFromPhone(name) }
        }
""",
     "        // (videos from the phone now live in the Series tab)\n"),

    # =====================================================================
    # MainActivity.kt: the Series screens
    # =====================================================================
    (MAIN,
     "/** Asks for the folder name (when it is new) and the season, then the file picker opens. */\n",
     r"""// ---------- Series tab: videos that are already on the phone ----------

@Composable
private fun SeriesTab() {
    val p = LocalPalette.current
    val context = LocalContext.current
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var phoneTarget by remember { mutableStateOf<String?>(null) }
    var phoneSeason by remember { mutableStateOf<String?>(null) }

    // Android's file picker: nothing is copied or moved, the app only remembers where each file is
    val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        val target = phoneTarget
        phoneTarget = null
        if (target != null && picked.isNotEmpty()) {
            picked.forEach {
                try {
                    context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Throwable) {
                    // some pickers only give a temporary permission
                }
            }
            DownloadCenter.addFromPhoneAsync(context, target, picked, phoneSeason) { n ->
                Toast.makeText(
                    context,
                    if (n == 0) "These are already in your series." else "Added $n to \"$target\".",
                    Toast.LENGTH_SHORT
                ).show()
            }
            openName = target
        }
    }

    fun pick(folder: String, season: String?) {
        phoneTarget = folder
        phoneSeason = season
        phoneLauncher.launch(arrayOf("video/*", "audio/*"))
    }

    BackHandler(enabled = openName != null) { openName = null }

    val current = openName
    if (current != null) {
        SeriesDetail(
            name = current,
            onBack = { openName = null },
            onRenamed = { openName = it },
            onPick = { season -> pick(current, season) }
        )
        return
    }

    val items = DownloadCenter.phoneItems
    val groups = items.groupBy { it.playlist ?: "" }
    val sizeText = totalSizeText(items)

    if (showNew) {
        PhoneAddDialog(
            askFolder = true,
            folder = "",
            season = "S1",
            onDone = { f, s ->
                showNew = false
                pick(f, s)
            },
            onDismiss = { showNew = false }
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Series", color = p.text, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Text(
                        if (groups.isEmpty()) {
                            "Your own videos, organized"
                        } else {
                            "${groups.size} series  •  ${items.size} episodes" +
                                (if (sizeText != "-") "  •  $sizeText" else "")
                        },
                        color = p.sub,
                        fontSize = 13.sp
                    )
                }
            }
            if (groups.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SeriesEmpty { showNew = true } }
            }
            items(groups.keys.toList(), key = { it }) { folder ->
                val eps = DownloadCenter.playOrder(groups[folder] ?: emptyList())
                SeriesCard(
                    name = folder,
                    eps = eps,
                    onOpen = { openName = folder },
                    onPlay = { playItems(context, eps, 0, folder) }
                )
            }
        }

        if (groups.isNotEmpty()) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(p.accent)
                    .clickable { showNew = true }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Ico(R.drawable.ic_add, p.onAccent, 22.dp)
                Spacer(Modifier.width(6.dp))
                Text("New series", color = p.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SeriesEmpty(onCreate: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(p.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Ico(R.drawable.ic_series, p.accent, 46.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text("Your own series", color = p.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Make a folder like Wednesday, add seasons, then add the episodes from your phone. " +
                "Your files are never copied, moved or deleted.",
            color = p.sub,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(p.accent)
                .clickable { onCreate() }
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Text("Create your first series", color = p.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

/** A poster: the cover of the first episode, the name, and a play button. */
@Composable
private fun SeriesCard(name: String, eps: List<HistoryItem>, onOpen: () -> Unit, onPlay: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(18.dp)
    val seasons = eps.mapNotNull { it.season }.distinct().size
    val line = (if (seasons > 0) (if (seasons == 1) "1 season" else "$seasons seasons") + "  •  " else "") +
        (if (eps.size == 1) "1 episode" else "${eps.size} episodes")
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(shape)
            .border(1.dp, p.border, shape)
            .clickable { onOpen() }
    ) {
        Thumb(
            eps.firstOrNull { !it.thumb.isNullOrBlank() }?.thumb,
            Modifier.fillMaxSize(),
            R.drawable.ic_series,
            44.dp
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
        ) {
            Text(
                name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(line, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(p.accent)
                .clickable { onPlay() },
            contentAlignment = Alignment.Center
        ) {
            Ico(R.drawable.ic_play, p.onAccent, 22.dp, "Play")
        }
    }
}

/** One series: cover, numbers, play, and a card for each season that opens or hides its episodes. */
@Composable
private fun SeriesDetail(
    name: String,
    onBack: () -> Unit,
    onRenamed: (String) -> Unit,
    onPick: (String?) -> Unit
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val videos = DownloadCenter.playOrder(DownloadCenter.history.filter { it.external && it.playlist == name })
    var showRename by remember { mutableStateOf(false) }
    var showAddSeason by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var seasonToRename by remember { mutableStateOf<String?>(null) }
    var seasonToRemove by remember { mutableStateOf<String?>(null) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }

    if (showRename) {
        RenameDialog(
            current = name,
            title = "Rename series",
            onDone = { n ->
                DownloadCenter.renameFolder(context, name, n)
                showRename = false
                onRenamed(n.trim())
            },
            onDismiss = { showRename = false }
        )
    }
    if (showAddSeason) {
        PhoneAddDialog(
            askFolder = false,
            folder = name,
            season = DownloadCenter.nextSeasonName(name),
            onDone = { _, s ->
                showAddSeason = false
                onPick(s)
            },
            onDismiss = { showAddSeason = false }
        )
    }
    seasonToRename?.let { old ->
        RenameDialog(
            current = old,
            title = "Rename season",
            onDone = { n ->
                DownloadCenter.renameSeason(context, name, old, n)
                seasonToRename = null
            },
            onDismiss = { seasonToRename = null }
        )
    }
    seasonToRemove?.let { s ->
        AlertDialog(
            onDismissRequest = { seasonToRemove = null },
            title = { Text("Remove \"$s\"?") },
            text = {
                Text(
                    "The episodes are removed from the app only. Their files stay on your phone.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    DownloadCenter.removeSeason(context, name, s)
                    seasonToRemove = null
                }) { Text("Remove", color = Pink, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { seasonToRemove = null }) { Text("Cancel") } }
        )
    }
    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("Remove \"${cutText(name, 40)}\"?") },
            text = {
                Text(
                    "This series is removed from the app only. Your video files stay on your phone.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemove = false
                    DownloadCenter.removeFolder(context, name)
                    onBack()
                }) { Text("Remove", color = Pink, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { showRemove = false }) { Text("Cancel") } }
        )
    }

    val seasonList = videos.mapNotNull { it.season }.distinct()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Ico(R.drawable.ic_back, p.text, 24.dp, "Back") }
                Text("Series", color = p.sub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp))
            ) {
                Thumb(
                    videos.firstOrNull { !it.thumb.isNullOrBlank() }?.thumb,
                    Modifier.fillMaxSize(),
                    R.drawable.ic_series,
                    56.dp
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                )
                Text(
                    name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
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
                StatCard("${seasonList.size}", if (seasonList.size == 1) "Season" else "Seasons", Modifier.weight(1f))
                StatCard("${videos.size}", if (videos.size == 1) "Episode" else "Episodes", Modifier.weight(1f))
                StatCard(totalSizeText(videos), "Total size", Modifier.weight(1f))
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
                            Text("Play all", color = p.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
                            Text("Shuffle", color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                }
            }
        } else {
            item {
                Text("This series is empty. Tap Add season to put episodes in it.", color = p.sub, fontSize = 14.sp)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallAction("Add season", R.drawable.ic_add, Modifier.weight(1f)) { showAddSeason = true }
                SmallAction("Rename", R.drawable.ic_edit, Modifier.weight(1f)) { showRename = true }
            }
        }

        // Episodes without a season first, then one card for each season
        val plain = videos.filter { it.season == null }
        itemsIndexed(plain, key = { _, v -> v.uri }) { i, v ->
            PlaylistVideoRow(
                number = i + 1,
                item = v,
                onPlay = { playItems(context, videos, videos.indexOf(v), name) },
                onRemove = { DownloadCenter.removeFromPlaylist(context, v) }
            )
        }
        if (seasonList.size > 1) {
            item(key = "season-toggle-all") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { collapsed = emptySet() }) { Text("Show all", color = p.accent) }
                    TextButton(onClick = { collapsed = seasonList.toSet() }) { Text("Hide all", color = p.sub) }
                }
            }
        }
        seasonList.forEach { s ->
            val eps = videos.filter { it.season == s }
            val isOpen = s !in collapsed
            val sizeText = totalSizeText(eps)
            val subtitle = (if (eps.size == 1) "1 episode" else "${eps.size} episodes") +
                (if (sizeText == "-") "" else "  •  $sizeText")
            item(key = "season-$s") {
                Box(Modifier.animateItemPlacement()) {
                    SeasonCard(
                        name = s,
                        subtitle = subtitle,
                        open = isOpen,
                        onToggle = { collapsed = if (isOpen) collapsed + s else collapsed - s },
                        onPlay = { playItems(context, eps, 0, name + " • " + s) },
                        onAdd = { onPick(s) },
                        onRename = { seasonToRename = s },
                        onRemove = { seasonToRemove = s }
                    )
                }
            }
            if (isOpen) {
                itemsIndexed(eps, key = { _, v -> v.uri }) { i, v ->
                    Box(Modifier.animateItemPlacement().padding(start = 14.dp)) {
                        PlaylistVideoRow(
                            number = i + 1,
                            item = v,
                            onPlay = { playItems(context, videos, videos.indexOf(v), name) },
                            onRemove = { DownloadCenter.removeFromPlaylist(context, v) }
                        )
                    }
                }
            }
        }

        item {
            TextButton(onClick = { showRemove = true }) {
                Text("Remove this series from the app", color = Pink)
            }
        }
    }
}

/** Asks for the folder name (when it is new) and the season, then the file picker opens. */
"""),

    # README
    (README,
     "**Folders with seasons** made from videos",
     "**Series tab**: folders with seasons made from videos"),

    # version
    ("app/build.gradle.kts", "versionCode = 18\n", "versionCode = 19\n"),
    ("app/build.gradle.kts", 'versionName = "1.14.1"\n', 'versionName = "1.15.0"\n'),
]


def read(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def main():
    if not os.path.isdir("app/src/main/java/com/admoss/downloader"):
        print("Run this from the project root (the folder that contains app/).")
        sys.exit(1)

    texts = {}
    crlf = {}
    for path, _, _ in EDITS:
        if path not in texts:
            if not os.path.exists(path):
                print("Missing file: " + path)
                sys.exit(1)
            t = read(path)
            crlf[path] = "\r\n" in t
            texts[path] = t

    problems = []
    skipped = 0
    for n, (path, old, new) in enumerate(EDITS, 1):
        if crlf[path]:
            old = old.replace("\n", "\r\n")
            new = new.replace("\n", "\r\n")
        t = texts[path]
        count = t.count(old)
        if new in t:
            skipped += 1  # already applied
        elif count == 1:
            texts[path] = t.replace(old, new)
        else:
            problems.append("edit %d in %s: found %d matches (expected 1)" % (n, path, count))

    if problems:
        print("Nothing was changed, because:")
        for p in problems:
            print("  - " + p)
        print("Did you run all the earlier scripts first, in order (the last one is apply_fixes2.py)?")
        print("If yes, send me that file and I will adjust the edit.")
        sys.exit(1)

    for path, t in texts.items():
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(t)

    created = 0
    for path, content in NEW_FILES.items():
        if not os.path.exists(path):
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(content)
            created += 1

    print("Done: %d edits applied, %d already applied, %d icon created." % (len(EDITS) - skipped, skipped, created))
    print("Version is now 1.15.0 (versionCode 19).")


if __name__ == "__main__":
    main()
