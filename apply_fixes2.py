#!/usr/bin/env python3
"""
ADMOSS Downloader v1.14.0 -> v1.14.1
All the remaining fixes from the code review:

  5) memory: the saved video info cache keeps the 15 newest only (not "everything, then clear")
     and the info files of cancelled downloads are deleted (also cleaned when the app starts)
  6) engine: it no longer starts from the background update job, and it never updates yt-dlp
     while a download is running
  7) allowBackup is off (Google backup could bring back a library full of dead links)
  + "429" error check is exact, Settings text says what happens to phone videos,
    README brought up to date, the release workflow checks that the tag matches the app version

Run it after apply_design.py, from the project root:

    python apply_fixes2.py

All edits are checked first. If a file does not match what is expected,
NOTHING is changed and the script says which edit failed.
"""
import os
import sys

BASE = "app/src/main/java/com/admoss/downloader/"
VM = BASE + "DownloadViewModel.kt"
DC = BASE + "DownloadCenter.kt"
ENG = BASE + "Engine.kt"
SVC = BASE + "DownloadService.kt"
CRASH = BASE + "CrashReport.kt"
MAIN = BASE + "MainActivity.kt"
MANIFEST = "app/src/main/AndroidManifest.xml"
README = "README.md"
WORKFLOW = ".github/workflows/build.yml"

EDITS = [
    # ---------- 5) memory and leftover files ----------
    (VM,
     r"""        if (rawCache.size > 40) {
            rawCache.clear()
            rawTime.clear()
        }
""",
     r"""        if (rawCache.size >= 15) {
            // Drop only the oldest one, so a playlist size check does not lose everything it just read
            val oldest = rawTime.entries.minByOrNull { it.value }?.key
            if (oldest != null) {
                rawCache.remove(oldest)
                rawTime.remove(oldest)
            } else {
                rawCache.clear()
            }
        }
"""),

    (DC,
     r"""            Status.WAITING -> {
                item.cancelled = true
                item.status = Status.CANCELLED
                item.stage = "Cancelled"
            }
""",
     r"""            Status.WAITING -> {
                item.cancelled = true
                item.status = Status.CANCELLED
                item.stage = "Cancelled"
                // the saved video info is not needed any more
                item.infoJsonPath?.let {
                    try {
                        File(it).delete()
                    } catch (e: Throwable) {
                        // ignore
                    }
                }
            }
"""),

    (CRASH,
     '        initialize("DownloadCenter.init") { DownloadCenter.init(this) }\n',
     r"""        initialize("Cache cleanup") {
            // Leftovers of cancelled or interrupted downloads. A new process has nothing running yet.
            File(cacheDir, "info").deleteRecursively()
            File(cacheDir, "dl").deleteRecursively()
        }
        initialize("DownloadCenter.init") { DownloadCenter.init(this) }
"""),

    # ---------- 6) engine ----------
    (CRASH,
     '        initialize("Engine.start") { Engine.start(this) }\n',
     "        // The engine starts when the app is opened (DownloadViewModel) or a download starts (DownloadService),\n"
     "        // not for the background update check.\n"),

    (SVC,
     "        acquireWakeLock()\n        spawnWorkers()\n",
     "        Engine.start(applicationContext)\n        acquireWakeLock()\n        spawnWorkers()\n"),

    (ENG,
     r"""                    Thread.sleep(25000) // let the first video lookups finish first
                    YoutubeDL.getInstance().updateYoutubeDL(app)
                    prefs.edit().putLong("engine_update_at", System.currentTimeMillis()).apply()
""",
     r"""                    Thread.sleep(25000) // let the first video lookups finish first
                    // Never swap the engine files while a download is running: wait for it (up to 10 minutes)
                    var waited = 0
                    while (DownloadCenter.activeCount > 0 && waited < 120) {
                        Thread.sleep(5000)
                        waited++
                    }
                    if (DownloadCenter.activeCount == 0) {
                        YoutubeDL.getInstance().updateYoutubeDL(app)
                        prefs.edit().putLong("engine_update_at", System.currentTimeMillis()).apply()
                    }
"""),

    (ENG,
     "        if (updating || !upFlag) return\n",
     r"""        if (updating || !upFlag) return
        if (DownloadCenter.activeCount > 0) {
            main.post { updateMessage = "Wait for the running downloads to finish, then tap Update engine again." }
            return
        }
"""),

    # ---------- 7) backup ----------
    (MANIFEST,
     '        android:allowBackup="true"\n',
     '        android:allowBackup="false"\n'),

    # ---------- small things ----------
    (DC,
     '            msg.contains("429") || msg.contains("Too Many Requests", true) ->\n',
     '            msg.contains("HTTP Error 429", true) || msg.contains("Too Many Requests", true) ->\n'),

    (MAIN,
     '                    "Deleting a download in the Library also deletes the file from your phone, after you confirm.",\n',
     '                    "Deleting a download in the Library also deletes the file from your phone, after you confirm. '
     'Videos you added from your phone are only removed from the app.",\n'),

    # ---------- README ----------
    (README,
     "| 📚 | **Download queue**: paste many links, they download one after another |\n",
     "| 📚 | **Download queue**: paste many links, they download in turn (1 to 3 at the same time, set in Settings) |\n"),
    (README,
     "| 🔎 | **Library search** |\n",
     "| 🔎 | **Library search** |\n"
     "| 📁 | **Folders with seasons** made from videos already on your phone (S1, S2 ...): open or hide each season, "
     "add episodes, play in order. Your files are never copied, moved or deleted |\n"),
    (README,
     "- [ ] Settings screen (default quality, Wi-Fi only, custom folder)\n",
     "- [x] Settings screen (updates, downloads at the same time)\n"
     "- [ ] More settings (default quality, Wi-Fi only, custom folder)\n"),
    (README,
     "- [ ] Parallel downloads\n",
     "- [x] Parallel downloads\n"
     "- [x] Folders with seasons for phone videos\n"),
    (README,
     "Engine.kt              # yt-dlp + ffmpeg startup\n",
     "Engine.kt              # yt-dlp + ffmpeg startup\n"
     "    │   ├── TikTokPhotos.kt        # TikTok photo posts\n"
     "    │   ├── PhotoViewerActivity.kt # photo slideshow with music\n"
     "    │   ├── FileDeleter.kt         # deleting files from the phone\n"
     "    │   ├── AppSettings.kt         # the few settings\n"),

    # ---------- release workflow: the tag must match the app version ----------
    (WORKFLOW,
     "      - name: Prepare signing key\n",
     r"""      - name: Check that the tag matches the app version
        if: startsWith(github.ref, 'refs/tags/')
        run: |
          TAG="${GITHUB_REF_NAME#v}"
          VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
          if [ "$TAG" != "$VERSION" ]; then
            echo "Tag v$TAG does not match versionName $VERSION in app/build.gradle.kts"
            exit 1
          fi

      - name: Prepare signing key
"""),

    # ---------- version ----------
    ("app/build.gradle.kts", "versionCode = 17\n", "versionCode = 18\n"),
    ("app/build.gradle.kts", 'versionName = "1.14.0"\n', 'versionName = "1.14.1"\n'),
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
        print("Did you run the earlier scripts first, in order? If yes, send me that file and I will adjust the edit.")
        sys.exit(1)

    for path, t in texts.items():
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(t)

    print("Done: %d edits applied, %d already applied." % (len(EDITS) - skipped, skipped))
    print("Version is now 1.14.1 (versionCode 18).")


if __name__ == "__main__":
    main()
