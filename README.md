<div align="center">

<img src="docs/banner.png" alt="ADMOSS Downloader" width="100%">

<br>

[![Latest release](https://img.shields.io/github/v/release/ADMOSSx/ADMOSS-Downloader?style=for-the-badge&color=C2410C&label=Latest)](https://github.com/ADMOSSx/ADMOSS-Downloader/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/ADMOSSx/ADMOSS-Downloader/total?style=for-the-badge&color=5B5348&label=Downloads)](https://github.com/ADMOSSx/ADMOSS-Downloader/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/ADMOSSx/ADMOSS-Downloader/build.yml?style=for-the-badge&label=Build)](https://github.com/ADMOSSx/ADMOSS-Downloader/actions)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
[![License](https://img.shields.io/github/license/ADMOSSx/ADMOSS-Downloader?style=for-the-badge)](LICENSE)

### Download YouTube and TikTok videos, whole playlists and MP3 audio on Android.
### Queue, background downloads, a built-in player and in-app updates.

[**⬇️ Download the APK**](https://github.com/ADMOSSx/ADMOSS-Downloader/releases/latest) &nbsp;•&nbsp;
[**🐞 Report a bug**](https://github.com/ADMOSSx/ADMOSS-Downloader/issues/new/choose) &nbsp;•&nbsp;
[**💡 Request a feature**](https://github.com/ADMOSSx/ADMOSS-Downloader/issues/new/choose)

</div>

---

## Contents

- [Features](#-features)
- [Install](#-install)
- [How to use](#-how-to-use)
- [Screenshots](#-screenshots)
- [FAQ](#-faq)
- [Build it yourself](#-build-it-yourself)
- [Tech stack](#-tech-stack)
- [Project structure](#-project-structure)
- [Roadmap](#-roadmap)
- [Contributing](#-contributing)
- [Disclaimer](#-disclaimer)

---

## ✨ Features

| | Feature |
|---|---|
| 🎬 | Download from **YouTube**, **TikTok** and many other sites |
| 🔍 | See the **title, cover, duration and estimated size** before you download |
| 🎚️ | Choose the quality (Best, 1080p, 720p, 480p, 360p) with the size of each option |
| 🎵 | **MP3 audio** mode |
| 🖼️ | **TikTok photo posts**: save every photo plus the post's music, then watch them as a slideshow with the music playing |
| 📚 | **Download queue**: paste many links, they download in turn (1 to 3 at the same time, set in Settings) |
| 📃 | **Playlist support**: load a whole playlist and pick the videos you want |
| 🗂️ | **Playlists section** in the Library: each playlist is a folder with all its videos in order |
| ▶️ | **Built-in player** with speed control (0.5x to 2x), double-tap to skip 10 seconds, a seek bar and a **playlist menu** to jump to any video |
| 🔀 | **PLAY ALL** and **SHUFFLE** for playlists |
| 🖼️ | **Cover images** for every download and playlist |
| 🔔 | **Background downloads** with a notification (progress, cancel) |
| 🔎 | **Library search** |
| 📁 | **Series tab**: folders with seasons made from videos already on your phone (S1, S2 ...): open or hide each season, add episodes, play in order. Your files are never copied, moved or deleted |
| 🌗 | **Dark and light** themes |
| 🔄 | **Automatic updates from GitHub**: the app notices a new release, shows the version and release notes, downloads it with progress, checks the file (size, SHA-256, same app, same signing key) and opens Android's own installer. Your downloads and settings are kept |
| 🛡️ | Automatic **second try** with different network settings when YouTube blocks the first one |

---

## 📲 Install

1. Open the [**latest release**](https://github.com/ADMOSSx/ADMOSS-Downloader/releases/latest).
2. Download the APK for your phone:

| Your phone | File |
|---|---|
| Almost all phones | **`ADMOSS-Downloader-arm64-v8a.apk`** |
| Very old phones | `ADMOSS-Downloader-armeabi-v7a.apk` |

3. Open the file and allow **"Install unknown apps"** when Android asks.
4. Open the app, paste a link and tap **DOWNLOAD**.

**Requirements:** Android 10 or newer and an internet connection.

> **Updating:** since v1.4 every build is signed with the same key, so new versions install **over the old one** and the app can update itself. If you have a version older than 1.4, uninstall it one last time.

---

## 🧑‍💻 How to use

1. Copy a YouTube or TikTok link. You can also use **Share** inside those apps and pick ADMOSS Downloader.
2. Paste it in the app. The cover, title and size appear.
3. Choose **Video** or **Audio MP3** and a quality.
4. Tap **DOWNLOAD**. You can leave the app, it keeps working in the background.
5. Find your files in the **Library** tab. Playlists have their own section.

---

## 🖼️ Screenshots

<!--
Add your screenshots to docs/screenshots/ and remove the comment markers around the block below.

<div align="center">
<img src="docs/screenshots/home.png" width="23%">
<img src="docs/screenshots/queue.png" width="23%">
<img src="docs/screenshots/playlist.png" width="23%">
<img src="docs/screenshots/player.png" width="23%">
</div>
-->

*Screenshots coming soon.*

---

## ❓ FAQ

**My antivirus or Play Protect says the app is dangerous.**
The app is open source and you can read all the code here. Antivirus tools often flag apps that are installed from outside the Play Store, or that bundle download engines such as Python and ffmpeg. Tap **More details** and **Install anyway**, or mark the app as safe. Only download the APK from this page.

**"App not installed as package conflicts with an existing package".**
You have a version older than 1.4 installed. Uninstall it once and install the new one. After that, updates install over the old version.

**Why is the app not on Google Play?**
Google Play does not allow apps that download from YouTube, so it is shared here instead.

**"Couldn't load video info".**
Tap **Try again**. If it still fails, tap **Update engine** (YouTube changes often), turn off any VPN, and wait a few minutes if it says too many requests.

**Where are my files saved?**
Videos in `Movies/ADMOSS`, audio in `Music/ADMOSS` and TikTok photos in `Pictures/ADMOSS`. Playlist videos go in a folder named after the playlist. They show up in your gallery and music apps.

**Which phones are supported?**
Android 10 or newer. Use the `arm64-v8a` APK unless your phone is very old.

---

## 🛠️ Build it yourself

### With GitHub Actions

1. Fork or clone this repository.
2. Open **Actions → Build APK**. The workflow runs on pushes, pull requests to `main`, and manual dispatch.
3. Branch and fork builds without a verified release key produce clearly named **debug APKs** for testing.
4. Download APKs from the **Artifacts** section of a successful run.

### Permanent release signing key

To publish updates that install over an existing version, use the same permanent keystore that signed the earlier official releases. Add these repository **Actions secrets** under **Settings → Secrets and variables → Actions → Secrets**:

| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded permanent keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Private-key alias |
| `KEY_PASSWORD` | Private-key password |

Then add this repository **Actions variable** under **Settings → Secrets and variables → Actions → Variables**:

| Variable | What it is |
|---|---|
| `EXPECTED_CERT_SHA256` | SHA-256 certificate fingerprint from an already-published official APK |

The workflow compares the keystore and every release APK against this pinned fingerprint. A missing or mismatched fingerprint blocks tagged release publishing. See [Release signing setup](docs/RELEASE_SIGNING.md).

Never commit the keystore, passwords, Base64 secret, or signing reports containing private information.

### Publish a release

Only after the signing secrets and expected certificate fingerprint are configured:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow checks that the tag matches `versionName`, runs tests and lint, verifies the APK signatures, then attaches the APKs and SHA-256 sidecars to the GitHub release.

### Local build

For normal development, use a debug build (no release keystore required):

```bash
gradle testDebugUnitTest lintDebug assembleDebug --no-daemon
```

A release build requires the permanent signing credentials. Do not use a newly generated key for an existing app installation.

### With Android Studio

Open the project, wait for Gradle sync, and press **Run**. This uses the debug build.

---

## 🧪 Quality checks

See [the testing checklist](docs/TESTING.md) for CI commands and the device-level regression tests required before publishing a release.

## 🧱 Tech stack

- **Kotlin** and **Jetpack Compose** (Material 3)
- [**youtubedl-android**](https://github.com/JunkFood02/youtubedl-android) (yt-dlp and ffmpeg for Android)
- **Foreground service** for background downloads
- **MediaStore** to save files to the gallery
- **GitHub Actions** to build and sign the APKs

---

## 📂 Project structure

```
ADMOSS-Downloader/
├── .github/
│   ├── workflows/build.yml        # builds and signs the APKs
│   └── ISSUE_TEMPLATE/            # bug report and feature request forms
├── docs/                          # banner and screenshots
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/admoss/downloader/
    │   ├── MainActivity.kt        # screens (Download, Library, Playlists)
    │   ├── PlayerActivity.kt      # built-in player
    │   ├── DownloadViewModel.kt   # link, options, video info
    │   ├── DownloadService.kt     # background queue + notifications
    │   ├── DownloadCenter.kt      # download logic and history
    │   ├── Updater.kt             # in-app updates
    │   ├── Engine.kt              # yt-dlp + ffmpeg startup
    │   ├── TikTokPhotos.kt        # TikTok photo posts
    │   ├── PhotoViewerActivity.kt # photo slideshow with music
    │   ├── FileDeleter.kt         # deleting files from the phone
    │   ├── AppSettings.kt         # the few settings
    │   ├── VideoInfo.kt           # video and playlist info
    │   ├── Thumb.kt               # cover images
    │   └── YtOptions.kt           # retry settings
    └── res/                       # icons, theme
```

---

## 🗺️ Roadmap

- [x] Download queue
- [x] Playlist support and Playlists section
- [x] Light theme
- [x] Library search
- [x] Built-in player with speed control
- [x] In-app updates
- [x] Settings screen (updates, downloads at the same time)
- [ ] More settings (default quality, Wi-Fi only, custom folder)
- [x] Parallel downloads
- [x] Folders with seasons for phone videos
- [ ] Subtitles
- [ ] Arabic interface (right-to-left)

Have an idea? [Open a feature request](https://github.com/ADMOSSx/ADMOSS-Downloader/issues/new/choose).

---

## 🤝 Contributing

Bug reports, ideas and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first.

---

## 📜 Disclaimer

This project is for **personal and educational use**. Download only content you have the right to save, respect the creators, and follow the terms of service of the sites you use. Do not re-upload other people's videos as your own. The developers are not responsible for how the app is used.

---

<div align="center">

**Made with ❤️ by ADMOSS**

⭐ If you like the project, give it a star!

</div>
