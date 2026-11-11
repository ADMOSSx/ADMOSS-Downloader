#!/usr/bin/env python3
"""
ADMOSS Downloader v1.12.0 -> v1.13.0
Feature: folders with seasons for phone videos (Wednesday > S1, S2 ...).

Run apply_fixes.py, then apply_phone_playlists.py, then this one, from the project root:

    python apply_seasons.py

All edits are checked first. If a file does not match what is expected,
NOTHING is changed and the script says which edit failed.
"""
import os
import sys

BASE = "app/src/main/java/com/admoss/downloader/"
MAIN = BASE + "MainActivity.kt"
DC = BASE + "DownloadCenter.kt"

EDITS = [
    # =====================================================================
    # DownloadCenter.kt
    # =====================================================================

    # new field on every item
    (DC,
     r"""    val external: Boolean = false
)
""",
     r"""    val external: Boolean = false,
    /** Season inside a folder (only for phone videos), for example "S1". */
    val season: String? = null
)
"""),

    # saving / loading it
    (DC,
     r"""                o.optBoolean("ex", false)
            )
""",
     r"""                o.optBoolean("ex", false),
                o.optString("se", "").ifBlank { null }
            )
"""),
    (DC,
     r""".put("ex", it.external)
""",
     r""".put("ex", it.external).put("se", it.season ?: "")
"""),

    # a video moved into another folder does not keep an old season
    (DC,
     "                    val updated = it.copy(playlist = n, index = next)\n",
     "                    val updated = it.copy(playlist = n, index = next, season = null)\n"),

    # adding from the phone now takes a season
    (DC,
     "    fun addFromPhoneAsync(ctx: Context, playlist: String, uris: List<Uri>, onDone: (Int) -> Unit) {\n",
     "    fun addFromPhoneAsync(ctx: Context, playlist: String, uris: List<Uri>, season: String?, onDone: (Int) -> Unit) {\n"),
    (DC,
     '        val app = ctx.applicationContext\n        thread(name = "admoss-add-phone") {\n',
     '        val app = ctx.applicationContext\n'
     '        val seasonName = season?.trim()?.ifBlank { null }\n'
     '        thread(name = "admoss-add-phone") {\n'),
    (DC,
     "                            external = true\n",
     "                            external = true,\n                            season = seasonName\n"),

    # season helpers
    (DC,
     "    // ---- playlist editing ----\n",
     r"""    /** Suggests "S1" for a new folder and "S2", "S3" ... for the next seasons of an existing one. */
    fun nextSeasonName(folder: String): String =
        "S" + (history.filter { it.playlist == folder }.mapNotNull { it.season }.distinct().size + 1)

    /** Episodes without a season first, then the seasons in natural order (S2 before S10), episodes in order. */
    fun playOrder(list: List<HistoryItem>): List<HistoryItem> =
        list.sortedWith { a, b ->
            val sa = a.season
            val sb = b.season
            when {
                sa == null && sb != null -> -1
                sa != null && sb == null -> 1
                sa != null && sb != null && sa != sb -> naturalCompare(sa, sb)
                else -> a.index - b.index
            }
        }

    @Synchronized
    fun renameSeason(ctx: Context, folder: String, old: String, newName: String) {
        val n = newName.trim()
        if (n.isBlank() || n == old) return
        history = history.map { if (it.playlist == folder && it.season == old) it.copy(season = n) else it }
        saveHistory(ctx)
    }

    /** Only phone videos have seasons, so this only removes them from the app. No file is touched. */
    @Synchronized
    fun removeSeason(ctx: Context, folder: String, season: String) {
        val gone = history.filter { it.playlist == folder && it.season == season && it.external }
        removeItems(ctx, gone)
    }

    // ---- playlist editing ----
"""),

    # =====================================================================
    # MainActivity.kt
    # =====================================================================

    # library cards play in season order
    (MAIN,
     "                val list = (groups[groupName] ?: emptyList()).sortedBy { it.index }\n",
     "                val list = DownloadCenter.playOrder(groups[groupName] ?: emptyList())\n"),

    # state for the dialogs (inside LibraryTab)
    (MAIN,
     "    var phoneTarget by remember { mutableStateOf<String?>(null) }\n",
     "    var phoneTarget by remember { mutableStateOf<String?>(null) }\n"
     "    var phoneSeason by remember { mutableStateOf<String?>(null) }\n"
     "    var addPhoneFolder by remember { mutableStateOf<String?>(null) }\n"),
    (MAIN,
     "            DownloadCenter.addFromPhoneAsync(context, target, picked) { n ->\n",
     "            DownloadCenter.addFromPhoneAsync(context, target, picked, phoneSeason) { n ->\n"),

    # "New from phone" asks for the folder and the first season
    (MAIN,
     r"""        if (showNewFromPhone) {
            RenameDialog(
                current = "",
                title = "New playlist from phone files",
                onDone = { n ->
                    showNewFromPhone = false
                    phoneTarget = n.trim()
                    phoneLauncher.launch(arrayOf("video/*", "audio/*"))
                },
                onDismiss = { showNewFromPhone = false }
            )
        }
""",
     r"""        if (showNewFromPhone) {
            PhoneAddDialog(
                askFolder = true,
                folder = "",
                season = "S1",
                onDone = { f, s ->
                    showNewFromPhone = false
                    phoneTarget = f
                    phoneSeason = s
                    phoneLauncher.launch(arrayOf("video/*", "audio/*"))
                },
                onDismiss = { showNewFromPhone = false }
            )
        }
"""),

    # "Add from phone" inside a folder asks for the season
    (MAIN,
     r"""        PlaylistDetail(
            name,
            onBack = { openName = null },
            onRenamed = { openName = it },
            onAddFromPhone = { n ->
                phoneTarget = n
                phoneLauncher.launch(arrayOf("video/*", "audio/*"))
            }
        )
""",
     r"""        PlaylistDetail(
            name,
            onBack = { openName = null },
            onRenamed = { openName = it },
            onAddFromPhone = { n -> addPhoneFolder = n }
        )
        addPhoneFolder?.let { f ->
            PhoneAddDialog(
                askFolder = false,
                folder = f,
                season = DownloadCenter.nextSeasonName(f),
                onDone = { _, s ->
                    addPhoneFolder = null
                    phoneTarget = f
                    phoneSeason = s
                    phoneLauncher.launch(arrayOf("video/*", "audio/*"))
                },
                onDismiss = { addPhoneFolder = null }
            )
        }
"""),

    # PlaylistDetail: order, season dialogs, season sections
    (MAIN,
     "    val videos = all.filter { it.playlist == name }.sortedBy { it.index }\n",
     "    val videos = DownloadCenter.playOrder(all.filter { it.playlist == name })\n"),

    (MAIN,
     "    var showDelete by remember { mutableStateOf(false) }\n",
     r"""    var showDelete by remember { mutableStateOf(false) }
    var seasonToRename by remember { mutableStateOf<String?>(null) }
    var seasonToRemove by remember { mutableStateOf<String?>(null) }

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
"""),

    (MAIN,
     r"""        itemsIndexed(videos, key = { _, v -> v.uri }) { i, v ->
            PlaylistVideoRow(
                number = i + 1,
                item = v,
                onPlay = { playItems(context, videos, i, name) },
                onRemove = { DownloadCenter.removeFromPlaylist(context, v) }
            )
        }
""",
     r"""        // Episodes without a season first, then one section for each season
        val plain = videos.filter { it.season == null }
        itemsIndexed(plain, key = { _, v -> v.uri }) { i, v ->
            PlaylistVideoRow(
                number = i + 1,
                item = v,
                onPlay = { playItems(context, videos, videos.indexOf(v), name) },
                onRemove = { DownloadCenter.removeFromPlaylist(context, v) }
            )
        }
        videos.mapNotNull { it.season }.distinct().forEach { s ->
            val eps = videos.filter { it.season == s }
            item(key = "season-$s") {
                SeasonHeader(
                    name = s,
                    count = eps.size,
                    onPlay = { playItems(context, eps, 0, name + " • " + s) },
                    onRename = { seasonToRename = s },
                    onRemove = { seasonToRemove = s }
                )
            }
            itemsIndexed(eps, key = { _, v -> v.uri }) { i, v ->
                PlaylistVideoRow(
                    number = i + 1,
                    item = v,
                    onPlay = { playItems(context, videos, videos.indexOf(v), name) },
                    onRemove = { DownloadCenter.removeFromPlaylist(context, v) }
                )
            }
        }
"""),

    # new composables
    (MAIN,
     "@Composable\nprivate fun PlaylistVideoRow(number: Int,",
     r"""/** Asks for the folder name (when it is new) and the season, then the file picker opens. */
@Composable
private fun PhoneAddDialog(
    askFolder: Boolean,
    folder: String,
    season: String,
    onDone: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var f by remember { mutableStateOf(folder) }
    var s by remember { mutableStateOf(season) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (askFolder) "New folder from phone" else "Add from phone") },
        text = {
            Column {
                if (askFolder) {
                    OutlinedTextField(
                        value = f,
                        onValueChange = { f = it },
                        singleLine = true,
                        label = { Text("Folder name (for example Wednesday)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = s,
                    onValueChange = { s = it },
                    singleLine = true,
                    label = { Text("Season (optional, for example S1)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(f.trim(), s.trim()) }, enabled = f.isNotBlank()) {
                Text("Choose videos")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The title of one season inside a folder, with play and a small menu. */
@Composable
private fun SeasonHeader(
    name: String,
    count: Int,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit
) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = p.text,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(if (count == 1) "1 episode" else "$count episodes", color = p.sub, fontSize = 12.sp)
        }
        IconButton(onClick = onPlay) { Ico(R.drawable.ic_play, p.accent, 26.dp, "Play this season") }
        Box {
            IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_edit, p.sub, 20.dp, "Season options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename season") },
                    onClick = {
                        menu = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Remove season from the app", color = Pink) },
                    onClick = {
                        menu = false
                        onRemove()
                    }
                )
            }
        }
    }
}

@Composable
private fun PlaylistVideoRow(number: Int,"""),

    # =====================================================================
    # version
    # =====================================================================
    ("app/build.gradle.kts", "versionCode = 15\n", "versionCode = 16\n"),
    ("app/build.gradle.kts", 'versionName = "1.12.0"\n', 'versionName = "1.13.0"\n'),
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
        print("Did you run apply_fixes.py and apply_phone_playlists.py first (in that order)?")
        print("If yes, send me that file and I will adjust the edit.")
        sys.exit(1)

    for path, t in texts.items():
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(t)

    print("Done: %d edits applied, %d already applied." % (len(EDITS) - skipped, skipped))
    print("Version is now 1.13.0 (versionCode 16).")


if __name__ == "__main__":
    main()
