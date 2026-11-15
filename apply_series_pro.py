#!/usr/bin/env python3
"""
ADMOSS: movies in the Series tab, a professional Series UI, and "Received files" in Share.

1. Unzip admoss-series-pro.zip into the project root. It adds SeriesUi.kt and OmsReceived.kt and
   REPLACES OmsProtocol.kt, OmsCenter.kt, OmsUi.kt and OmsSeries.kt.
2. Run this from the project root:

       python apply_series_pro.py

It adds: movies to the data, the "Add movies" function, the progress the player saves (for
"Continue watching"), the new Series screen, and raises the version. Every edit is checked first:
if a file does not match, NOTHING is changed and the script says which edit failed.
"""
import os
import re
import sys

BASE = "app/src/main/java/com/admoss/downloader/"
MAIN = BASE + "MainActivity.kt"
DC = BASE + "DownloadCenter.kt"
PLAYER = BASE + "PlayerActivity.kt"
GRADLE = "app/build.gradle.kts"

NEW_FILES = [BASE + n for n in ("SeriesUi.kt", "OmsReceived.kt", "OmsProtocol.kt", "OmsCenter.kt", "OmsUi.kt", "OmsSeries.kt")]

EDITS = [
    # ---------- DownloadCenter: movies ----------
    (DC,
     "    val season: String? = null\n)\n",
     "    val season: String? = null,\n"
     "    /** A movie of the Series tab (no series name, no season). */\n"
     "    val movie: Boolean = false\n)\n"),
    (DC,
     '                o.optString("se", "").ifBlank { null }\n            )\n',
     '                o.optString("se", "").ifBlank { null },\n                o.optBoolean("mv", false)\n            )\n'),
    (DC,
     '.put("ex", it.external).put("se", it.season ?: "")\n',
     '.put("ex", it.external).put("se", it.season ?: "").put("mv", it.movie)\n'),

    # received movies are added next to the received episodes
    (DC,
     r"""                withCovers.groupBy { it.playlist ?: "" }.forEach { (name, list) ->
                    addExternalItems(app, name, playOrder(list))
                }
""",
     r"""                val received = withCovers.partition { it.movie }
                if (received.first.isNotEmpty()) {
                    history = received.first + history
                    saveHistory(app)
                }
                received.second.groupBy { it.playlist ?: "" }.forEach { (name, list) ->
                    addExternalItems(app, name, playOrder(list))
                }
"""),

    (DC,
     "    // ---- playlist editing ----\n",
     r"""    /** The series of the Series tab (episodes of a folder). */
    val seriesItems: List<HistoryItem>
        get() = history.filter { it.external && !it.movie && it.playlist != null }

    /** The movies of the Series tab. */
    val movieItems: List<HistoryItem>
        get() = history.filter { it.external && it.movie }

    /** Adds movies that are already on the phone. Nothing is copied or moved. */
    fun addMoviesAsync(ctx: Context, uris: List<Uri>, onDone: (Int) -> Unit) {
        val app = ctx.applicationContext
        thread(name = "admoss-add-movies") {
            val made = ArrayList<HistoryItem>()
            try {
                val known = history.map { it.uri }.toSet()
                val picked = uris.filter { it.toString() !in known }
                    .map { describe(app, it) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                for (p in picked) {
                    made.add(
                        HistoryItem(
                            title = cutText(p.name.substringBeforeLast('.').ifBlank { p.name }, 90),
                            uri = p.uri.toString(),
                            audio = false,
                            playlist = null,
                            index = 0,
                            thumb = makeExternalThumb(app, p.uri),
                            size = p.size,
                            external = true,
                            movie = true
                        )
                    )
                }
            } catch (e: Throwable) {
                // add what was read so far
            }
            mainHandler.post {
                if (made.isNotEmpty()) {
                    history = made + history
                    saveHistory(app)
                }
                onDone(made.size)
            }
        }
    }

    @Synchronized
    fun renameItem(ctx: Context, uri: String, title: String) {
        val t = title.trim()
        if (t.isBlank()) return
        history = history.map { if (it.uri == uri) it.copy(title = t) else it }
        saveHistory(ctx)
    }

    // ---- playlist editing ----
"""),

    # ---------- PlayerActivity: remember length, time and "watched" ----------
    (PLAYER,
     r"""        if (p > 5000 && p < d - 8000) {
            prefs.edit().putInt(key, p).apply()
        } else if (p >= d - 8000) {
            prefs.edit().remove(key).apply()
        }
""",
     r"""        if (p > 5000 && p < d - 8000) {
            // position, length and time of the last watch: the Series tab builds "Continue watching" from these
            prefs.edit().putInt(key, p).putInt("$key#d", d).putLong("$key#t", System.currentTimeMillis())
                .remove("$key#w").apply()
        } else if (p >= d - 8000 && p > d / 2) {
            prefs.edit().remove(key).putInt("$key#d", d).putBoolean("$key#w", true)
                .putLong("$key#t", System.currentTimeMillis()).apply()
        }
"""),
    (PLAYER,
     "            prefs.edit().remove(uris[index].toString()).apply()\n",
     r"""            val doneKey = uris[index].toString()
            prefs.edit().remove(doneKey).putBoolean("$doneKey#w", true)
                .putLong("$doneKey#t", System.currentTimeMillis()).apply()
"""),

    # ---------- MainActivity: the new Series screen ----------
    (MAIN, "2 -> SeriesTab()", "2 -> SeriesScreen()"),
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
        print("Unzip admoss-series-pro.zip into the project root first. Missing:")
        for p in missing:
            print("  - " + p)
        sys.exit(1)
    if "addReceivedEpisodes" not in read(DC):
        print("DownloadCenter.kt has no addReceivedEpisodes yet. Run apply_series_share.py first.")
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
        print("Did you apply all the earlier scripts, in order? If yes, send me that file.")
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
