#!/usr/bin/env python3
"""
ADMOSS Share: send series from the Series tab (v1.17.x -> next minor).

1. Unzip admoss-share-series.zip into the project root. It REPLACES OmsProtocol.kt, OmsCenter.kt and
   OmsUi.kt (they are the files from the first Share zip) and adds OmsSeries.kt.
2. Run this from the project root:

       python apply_series_share.py

It adds: the function that puts received episodes into the Series tab, a "Send" button on each series and
season, the jump to the Share tab, and raises the version. All edits are checked first: if a file does not
match, NOTHING is changed and the script says which edit failed.
"""
import os
import re
import sys

BASE = "app/src/main/java/com/admoss/downloader/"
MAIN = BASE + "MainActivity.kt"
DC = BASE + "DownloadCenter.kt"
GRADLE = "app/build.gradle.kts"

NEW_FILES = [BASE + n for n in ("OmsProtocol.kt", "OmsCenter.kt", "OmsUi.kt", "OmsSeries.kt")]

EDITS = [
    # ---------- DownloadCenter: received episodes go into the Series tab ----------
    (DC,
     "    // ---- playlist editing ----\n",
     r"""    /**
     * Episodes received from another phone (the files are already saved by the app).
     * They are added to the Series tab with their name, season and order, and get a cover.
     */
    fun addReceivedEpisodes(ctx: Context, items: List<HistoryItem>) {
        if (items.isEmpty()) return
        val app = ctx.applicationContext
        thread(name = "admoss-received") {
            val withCovers = items.map { it.copy(thumb = it.thumb ?: makeExternalThumb(app, Uri.parse(it.uri))) }
            mainHandler.post {
                withCovers.groupBy { it.playlist ?: "" }.forEach { (name, list) ->
                    addExternalItems(app, name, playOrder(list))
                }
            }
        }
    }

    // ---- playlist editing ----
"""),

    # ---------- MainActivity: the Series tab can open the Share tab ----------
    (MAIN,
     "    var tab by rememberSaveable { mutableStateOf(0) }\n",
     r"""    var tab by rememberSaveable { mutableStateOf(0) }

    // The Series tab's "Send" buttons ask for the Share tab (tab 4)
    LaunchedEffect(OmsCenter.goShare) {
        if (OmsCenter.goShare) {
            tab = 4
            OmsCenter.goShare = false
        }
    }
"""),

    # "Send" in the season menu
    (MAIN,
     r"""    onRemove: () -> Unit
) {
    val p = LocalPalette.current
    val angle by animateFloatAsState(if (open) 0f else -90f, tween(200), label = "season-arrow")
""",
     r"""    onRemove: () -> Unit,
    onSend: (() -> Unit)? = null
) {
    val p = LocalPalette.current
    val angle by animateFloatAsState(if (open) 0f else -90f, tween(200), label = "season-arrow")
"""),
    (MAIN,
     r"""                DropdownMenuItem(
                    text = { Text("Add videos") },
                    onClick = {
                        menu = false
                        onAdd()
                    }
                )
""",
     r"""                DropdownMenuItem(
                    text = { Text("Add videos") },
                    onClick = {
                        menu = false
                        onAdd()
                    }
                )
                if (onSend != null) {
                    DropdownMenuItem(
                        text = { Text("Send to another phone") },
                        onClick = {
                            menu = false
                            onSend()
                        }
                    )
                }
"""),
    (MAIN,
     "                        onAdd = { onPick(s) },\n",
     "                        onAdd = { onPick(s) },\n"
     "                        onSend = { OmsSeries.sendFromSeries(context, name, s) },\n"),

    # "Send" for the whole series
    (MAIN,
     r"""                SmallAction("Add season", R.drawable.ic_add, Modifier.weight(1f)) { showAddSeason = true }
                SmallAction("Rename", R.drawable.ic_edit, Modifier.weight(1f)) { showRename = true }
            }
        }
""",
     r"""                SmallAction("Add season", R.drawable.ic_add, Modifier.weight(1f)) { showAddSeason = true }
                SmallAction("Rename", R.drawable.ic_edit, Modifier.weight(1f)) { showRename = true }
            }
        }

        item {
            SmallAction("Send to another phone", R.drawable.ic_send, Modifier.fillMaxWidth()) {
                OmsSeries.sendFromSeries(context, name, null)
            }
        }
"""),
]


def read(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def main():
    if not os.path.isdir("app/src/main/java/com/admoss/downloader"):
        print("Run this from the project root (the folder that contains app/).")
        sys.exit(1)
    missing = [p for p in NEW_FILES if not os.path.exists(p)]
    if missing:
        print("Unzip admoss-share-series.zip into the project root first. Missing:")
        for p in missing:
            print("  - " + p)
        sys.exit(1)
    if "OmsTab()" not in read(MAIN):
        print("The Share tab is not in MainActivity yet. Run apply_oms_hooks.py (from the first Share zip) first.")
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
        print("Did you apply the Series tab scripts and the first Share zip? If yes, send me that file.")
        sys.exit(1)

    for path, t in texts.items():
        write(path, t)

    applied = len(EDITS) - skipped
    if applied > 0:
        g = read(GRADLE)
        code = re.search(r"versionCode = (\d+)", g)
        name = re.search(r'versionName = "(\d+)\.(\d+)\.(\d+)"', g)
        if code and name:
            new_code = int(code.group(1)) + 1
            new_name = "%s.%d.0" % (name.group(1), int(name.group(2)) + 1)
            g = g.replace(code.group(0), "versionCode = %d" % new_code, 1)
            g = g.replace(name.group(0), 'versionName = "%s"' % new_name, 1)
            write(GRADLE, g)
            print("Version is now %s (versionCode %d)." % (new_name, new_code))
        else:
            print("Raise versionCode and versionName in app/build.gradle.kts by hand.")
    print("Done: %d edits applied, %d already applied." % (applied, skipped))


if __name__ == "__main__":
    main()
