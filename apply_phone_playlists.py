#!/usr/bin/env python3
"""
ADMOSS Downloader v1.11.1 -> v1.12.0
Feature: playlists (series) made from videos / songs that are already on the phone.

Run apply_fixes.py FIRST, then run this from the project root:

    python apply_phone_playlists.py

All edits are checked first. If a file does not match what is expected,
NOTHING is changed and the script says which edit failed.
"""
import os
import sys

BASE = "app/src/main/java/com/admoss/downloader/"
MAIN = BASE + "MainActivity.kt"
DC = BASE + "DownloadCenter.kt"
FD = BASE + "FileDeleter.kt"

EDITS = [
    # =====================================================================
    # DownloadCenter.kt
    # =====================================================================
    (DC, "import android.graphics.Bitmap\n",
     "import android.graphics.Bitmap\nimport android.media.MediaMetadataRetriever\n"),

    # HistoryItem: remember that the file belongs to the user, not to the app
    (DC,
     r"""    val musicUri: String? = null
)
""",
     r"""    val musicUri: String? = null,
    /** Added from the phone by the user: the file is not ours, so it is never deleted or moved. */
    val external: Boolean = false
)
"""),

    # saving / loading the new field
    (DC,
     r"""                o.optString("mu", "").ifBlank { null }
            )
""",
     r"""                o.optString("mu", "").ifBlank { null },
                o.optBoolean("ex", false)
            )
"""),
    (DC,
     r""".put("pu", JSONArray(it.photoUris)).put("mu", it.musicUri ?: "")
""",
     r""".put("pu", JSONArray(it.photoUris)).put("mu", it.musicUri ?: "").put("ex", it.external)
"""),

    # never move the user's own files into the ADMOSS folders
    (DC,
     "                if (item.photoUris.isNotEmpty()) continue\n",
     "                if (item.photoUris.isNotEmpty() || item.external) continue\n"),

    # taking a phone video out of a playlist removes it from the app (it was never ours)
    (DC,
     r"""    fun removeFromPlaylist(ctx: Context, item: HistoryItem) {
""",
     r"""    fun removeFromPlaylist(ctx: Context, item: HistoryItem) {
        if (item.external) {
            removeItems(ctx, listOf(item))
            return
        }
"""),

    # the new code
    (DC,
     "    // ---- playlist editing ----\n",
     r"""    // ---- videos and songs that are already on the phone ----

    private class Picked(val uri: Uri, val name: String, val size: Long)

    private fun describe(ctx: Context, uri: Uri): Picked {
        var name = ""
        var size = 0L
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: ""
                    size = if (c.isNull(1)) 0L else c.getLong(1)
                }
            }
        } catch (e: Throwable) {
            // keep the defaults
        }
        if (name.isBlank()) name = uri.lastPathSegment ?: "Video"
        return Picked(uri, name, size)
    }

    /** "Episode 2" comes before "Episode 10". */
    private fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        var i = 0
        while (i < ra.size && i < rb.size) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                x.toBigInteger().compareTo(y.toBigInteger())
            } else {
                x.compareTo(y)
            }
            if (c != 0) return c
            i++
        }
        return ra.size - rb.size
    }

    /** A small cover for a file we did not create. Best effort. */
    private fun makeExternalThumb(ctx: Context, uri: Uri): String? {
        val bmp: Bitmap? = try {
            ctx.contentResolver.loadThumbnail(uri, Size(480, 270), null)
        } catch (e: Throwable) {
            try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: r.getFrameAtTime(0L)
                    frame?.let { f ->
                        Bitmap.createScaledBitmap(f, 480, (480f * f.height / f.width).toInt().coerceAtLeast(1), true)
                    }
                } finally {
                    r.release()
                }
            } catch (e2: Throwable) {
                null
            }
        }
        if (bmp == null) return null
        return try {
            val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
            val file = File(dir, "e${System.nanoTime()}.img")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            file.absolutePath
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Adds videos or songs that are already on the phone to a playlist. Nothing is copied or moved:
     * the app only remembers where each file is. [onDone] gets the number of items really added.
     */
    fun addFromPhoneAsync(ctx: Context, playlist: String, uris: List<Uri>, onDone: (Int) -> Unit) {
        val n = playlist.trim()
        if (n.isBlank() || uris.isEmpty()) {
            onDone(0)
            return
        }
        val app = ctx.applicationContext
        thread(name = "admoss-add-phone") {
            val made = ArrayList<HistoryItem>()
            try {
                val known = history.map { it.uri }.toSet()
                val picked = uris.filter { it.toString() !in known }
                    .map { describe(app, it) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                for (p in picked) {
                    val type = app.contentResolver.getType(p.uri) ?: ""
                    made.add(
                        HistoryItem(
                            title = cutText(p.name.substringBeforeLast('.').ifBlank { p.name }, 90),
                            uri = p.uri.toString(),
                            audio = type.startsWith("audio"),
                            playlist = n,
                            index = 0,
                            thumb = makeExternalThumb(app, p.uri),
                            size = p.size,
                            external = true
                        )
                    )
                }
            } catch (e: Throwable) {
                // add what was read so far
            }
            mainHandler.post {
                addExternalItems(app, n, made)
                onDone(made.size)
            }
        }
    }

    @Synchronized
    private fun addExternalItems(ctx: Context, name: String, items: List<HistoryItem>) {
        if (items.isEmpty()) return
        var next = (history.filter { it.playlist == name }.maxOfOrNull { it.index } ?: -1) + 1
        val placed = items.map {
            val updated = it.copy(index = next)
            next += 1
            updated
        }
        history = placed + history
        saveHistory(ctx)
    }

    // ---- playlist editing ----
"""),

    # =====================================================================
    # FileDeleter.kt: the user's own files are never deleted
    # =====================================================================
    (FD,
     r"""    fun delete(ctx: Context, items: List<HistoryItem>) {
        if (items.isEmpty() || busy) return
        busy = true
""",
     r"""    fun delete(ctx: Context, all: List<HistoryItem>) {
        if (all.isEmpty() || busy) return
        // Videos the user added from the phone are only taken off the list: their files are never deleted
        val external = all.filter { it.external }
        if (external.isNotEmpty()) DownloadCenter.removeItems(ctx, external)
        val items = all.filter { !it.external }
        if (items.isEmpty()) {
            message = "Removed from the list. The files stay on your phone."
            return
        }
        busy = true
"""),

    # =====================================================================
    # MainActivity.kt
    # =====================================================================
    (MAIN, "import kotlinx.coroutines.launch\n",
     "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext\n"),

    # RenameDialog can have another title
    (MAIN,
     "private fun RenameDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {\n",
     r"""private fun RenameDialog(
    current: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Rename playlist"
) {
"""),
    (MAIN,
     '        title = { Text("Rename playlist") },\n',
     "        title = { Text(title) },\n"),

    # the file picker (inside LibraryTab)
    (MAIN,
     "    // After a reinstall the list is empty: bring the files back by themselves if access was already given\n",
     r"""    // Videos that are already on the phone: picked with Android's file picker, never copied or moved
    var phoneTarget by remember { mutableStateOf<String?>(null) }
    var showNewFromPhone by remember { mutableStateOf(false) }
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
            DownloadCenter.addFromPhoneAsync(context, target, picked) { n ->
                Toast.makeText(
                    context,
                    if (n == 0) "These are already in your library." else "Added $n to \"$target\".",
                    Toast.LENGTH_SHORT
                ).show()
            }
            openName = target
            scope.launch { pagerState.scrollToPage(4) }
        }
    }

    // After a reinstall the list is empty: bring the files back by themselves if access was already given
"""),

    # name dialog for "New from phone"
    (MAIN,
     "        confirmDelete?.let { list ->\n",
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

        confirmDelete?.let { list ->
"""),

    (MAIN,
     "                    onNewPlaylist = { showNew = true },\n",
     "                    onNewPlaylist = { showNew = true },\n"
     "                    onNewFromPhone = { showNewFromPhone = true },\n"),

    (MAIN,
     "        PlaylistDetail(name, onBack = { openName = null }, onRenamed = { openName = it })\n",
     r"""        PlaylistDetail(
            name,
            onBack = { openName = null },
            onRenamed = { openName = it },
            onAddFromPhone = { n ->
                phoneTarget = n
                phoneLauncher.launch(arrayOf("video/*", "audio/*"))
            }
        )
"""),

    # LibraryPage: new parameter and the second button
    (MAIN,
     "    onNewPlaylist: () -> Unit,\n    selected: Set<String>,\n",
     "    onNewPlaylist: () -> Unit,\n    onNewFromPhone: () -> Unit,\n    selected: Set<String>,\n"),
    (MAIN,
     '                SmallAction("New playlist", R.drawable.ic_add, Modifier.fillMaxWidth()) { onNewPlaylist() }\n',
     r"""                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallAction("New playlist", R.drawable.ic_add, Modifier.weight(1f)) { onNewPlaylist() }
                    SmallAction("New from phone", R.drawable.ic_add, Modifier.weight(1f)) { onNewFromPhone() }
                }
"""),

    # PlaylistDetail: new parameter and the "Add from phone" button
    (MAIN,
     "private fun PlaylistDetail(name: String, onBack: () -> Unit, onRenamed: (String) -> Unit) {\n",
     r"""private fun PlaylistDetail(
    name: String,
    onBack: () -> Unit,
    onRenamed: (String) -> Unit,
    onAddFromPhone: (String) -> Unit
) {
"""),
    (MAIN,
     r"""                SmallAction("Rename", R.drawable.ic_edit, Modifier.weight(1f)) { showRename = true }
            }
        }
""",
     r"""                SmallAction("Rename", R.drawable.ic_edit, Modifier.weight(1f)) { showRename = true }
            }
        }

        item {
            SmallAction("Add from phone", R.drawable.ic_add, Modifier.fillMaxWidth()) { onAddFromPhone(name) }
        }
"""),

    # a phone video whose file was deleted or moved
    (MAIN,
     "private fun itemMeta(item: HistoryItem): String {\n",
     r"""/** True when the file can still be opened (a phone video may have been deleted or moved). */
private fun uriReadable(context: Context, uriText: String): Boolean = try {
    context.contentResolver.openAssetFileDescriptor(Uri.parse(uriText), "r")?.use { true } ?: false
} catch (e: Throwable) {
    false
}

private fun itemMeta(item: HistoryItem): String {
"""),
    (MAIN,
     r"""            val kind = if (item.audio) "Audio" else "Video"
            Text(
                if (item.size > 0) "$kind  •  ${formatSize(item.size)}" else kind,
                color = p.sub,
                fontSize = 11.sp
            )
""",
     r"""            val rowContext = LocalContext.current
            val missing by produceState(initialValue = false, item.uri) {
                value = item.external && withContext(Dispatchers.IO) { !uriReadable(rowContext, item.uri) }
            }
            val kind = if (item.audio) "Audio" else "Video"
            if (missing) {
                Text("File not found on the phone", color = Red, fontSize = 11.sp)
            } else {
                Text(
                    if (item.size > 0) "$kind  •  ${formatSize(item.size)}" else kind,
                    color = p.sub,
                    fontSize = 11.sp
                )
            }
"""),

    # delete dialog: say what happens to phone videos
    (MAIN,
     '                Text("You can\'t undo this.", color = p.sub, fontSize = 13.sp)\n',
     '                Text("You can\'t undo this.", color = p.sub, fontSize = 13.sp)\n'
     r"""                if (items.any { it.external }) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Videos added from your phone are only removed from the app. Their files stay on your phone.",
                        color = p.sub,
                        fontSize = 13.sp
                    )
                }
"""),

    # =====================================================================
    # version
    # =====================================================================
    ("app/build.gradle.kts", "versionCode = 14\n", "versionCode = 15\n"),
    ("app/build.gradle.kts", 'versionName = "1.11.1"\n', 'versionName = "1.12.0"\n'),
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
        print("Did you run apply_fixes.py first? If yes, send me that file and I will adjust the edit.")
        sys.exit(1)

    for path, t in texts.items():
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(t)

    print("Done: %d edits applied, %d already applied." % (len(EDITS) - skipped, skipped))
    print("Version is now 1.12.0 (versionCode 15).")


if __name__ == "__main__":
    main()
