#!/usr/bin/env python3
"""
ADMOSS Downloader v1.13.0 -> v1.14.0
Design: season cards that expand / hide their episodes, a "+" button to add videos
straight into one season, a season counter, and smoother movement.

Run it after apply_seasons.py, from the project root:

    python apply_design.py

All edits are checked first. If a file does not match what is expected,
NOTHING is changed and the script says which edit failed.
"""
import os
import sys

BASE = "app/src/main/java/com/admoss/downloader/"
MAIN = BASE + "MainActivity.kt"

# New icons (created only if they do not exist yet)
NEW_FILES = {
    "app/src/main/res/drawable/ic_expand.xml": """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z" />
</vector>
""",
    "app/src/main/res/drawable/ic_more.xml": """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" />
</vector>
""",
}

EDITS = [
    # rotate() for the arrow
    (MAIN, "import androidx.compose.ui.draw.clip\n",
     "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.rotate\n"),

    # LibraryTab: "+" on a season opens the picker for that season at once
    (MAIN,
     "            onAddFromPhone = { n -> addPhoneFolder = n }\n",
     r"""            onAddFromPhone = { n -> addPhoneFolder = n },
            onAddToSeason = { f, s ->
                phoneTarget = f
                phoneSeason = s
                phoneLauncher.launch(arrayOf("video/*", "audio/*"))
            }
"""),

    # PlaylistDetail: new parameter
    (MAIN,
     "    onAddFromPhone: (String) -> Unit\n) {\n",
     "    onAddFromPhone: (String) -> Unit,\n    onAddToSeason: (String, String) -> Unit\n) {\n"),

    # which seasons are hidden
    (MAIN,
     "    var seasonToRemove by remember { mutableStateOf<String?>(null) }\n",
     "    var seasonToRemove by remember { mutableStateOf<String?>(null) }\n"
     "    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }\n"),

    # third stat card shows the number of seasons
    (MAIN,
     '                StatCard("${videos.count { it.audio }}", "Audio files", Modifier.weight(1f))\n',
     r"""                val seasonCount = videos.mapNotNull { it.season }.distinct().size
                if (seasonCount > 0) {
                    StatCard("$seasonCount", if (seasonCount == 1) "Season" else "Seasons", Modifier.weight(1f))
                } else {
                    StatCard("${videos.count { it.audio }}", "Audio files", Modifier.weight(1f))
                }
"""),

    # the season sections
    (MAIN,
     r"""        videos.mapNotNull { it.season }.distinct().forEach { s ->
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
""",
     r"""        val seasonList = videos.mapNotNull { it.season }.distinct()
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
                        onAdd = { onAddToSeason(name, s) },
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
"""),

    # the new season card
    (MAIN,
     "/** Asks for the folder name (when it is new) and the season, then the file picker opens. */\n",
     r"""/** One season of a folder: tap to show or hide its episodes. "+" adds videos, the big button plays it. */
@Composable
private fun SeasonCard(
    name: String,
    subtitle: String,
    open: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit
) {
    val p = LocalPalette.current
    val angle by animateFloatAsState(if (open) 0f else -90f, tween(200), label = "season-arrow")
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (open) p.accent.copy(alpha = 0.10f) else p.card)
            .border(1.dp, if (open) p.accent.copy(alpha = 0.45f) else p.border, shape)
            .clickable { onToggle() }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_expand),
            contentDescription = if (open) "Hide episodes" else "Show episodes",
            tint = if (open) p.accent else p.sub,
            modifier = Modifier
                .size(26.dp)
                .rotate(angle)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = p.text,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(subtitle, color = p.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onAdd) { Ico(R.drawable.ic_add, p.accent, 24.dp, "Add videos to this season") }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(p.accent)
                .clickable { onPlay() },
            contentAlignment = Alignment.Center
        ) {
            Ico(R.drawable.ic_play, p.onAccent, 22.dp, "Play this season")
        }
        Box {
            IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_more, p.sub, 22.dp, "Season options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Add videos") },
                    onClick = {
                        menu = false
                        onAdd()
                    }
                )
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

/** Asks for the folder name (when it is new) and the season, then the file picker opens. */
"""),

    # version
    ("app/build.gradle.kts", "versionCode = 16\n", "versionCode = 17\n"),
    ("app/build.gradle.kts", 'versionName = "1.13.0"\n', 'versionName = "1.14.0"\n'),
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
        print("Did you run apply_seasons.py first? If yes, send me that file and I will adjust the edit.")
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

    print("Done: %d edits applied, %d already applied, %d icons created." % (len(EDITS) - skipped, skipped, created))
    print("Version is now 1.14.0 (versionCode 17).")


if __name__ == "__main__":
    main()
