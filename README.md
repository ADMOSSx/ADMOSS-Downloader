<div align="center">

# 🚀 ADMOSS Downloader

### ⚡ Download YouTube & TikTok videos in seconds, just paste a link

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![yt-dlp](https://img.shields.io/badge/Engine-yt--dlp%20%2B%20ffmpeg-00E5FF?style=for-the-badge)
![Build](https://img.shields.io/badge/Build-GitHub%20Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)

[**⬇️ Download the APK**](../../releases/latest) · [**🐞 Report a bug**](../../issues)

</div>

---

## 📖 About

**ADMOSS Downloader** is a modern Android app that saves videos and audio from **YouTube**, **TikTok** and many other sites. Paste a link, pick a quality, and tap download. Everything is saved straight to your gallery.

---

## ✨ Features

| | Feature |
|---|---|
| 🎬 | Download from **YouTube**, **TikTok** and many more sites |
| 🔍 | **Video info before downloading**: thumbnail, title, uploader, duration and estimated file size |
| 🎚️ | Quality choice with the size of each option: **Best · 1080p · 720p · 480p · 360p** |
| 🎵 | **Audio-only** mode (MP3) |
| 📊 | Live progress with percentage, time left and a **Cancel** button |
| 📚 | **Download queue**: add many links, they download one after another, cancel any of them |
| 📃 | **Playlist support**: load a whole YouTube playlist, pick the videos you want |
| 🔔 | **Background downloads** with a notification (progress, cancel, tap to open) |
| 🌗 | Modern UI with **dark and light themes** (switch with the sun/moon button) |
| 🔎 | **Library tab** with search for your finished downloads |
| 📋 | One-tap **Paste**, automatic site detection |
| 🔗 | **Share to ADMOSS Downloader** from the YouTube and TikTok apps |
| 📁 | Saves to `Movies/ADMOSS` and `Music/ADMOSS` |
| 🕘 | **Recent downloads** list, tap to play |
| 🔄 | yt-dlp updates itself when you are online |

---

## 📲 Install

1. Go to the [**Releases**](../../releases/latest) page
2. Download **`ADMOSS-Downloader-arm64-v8a.apk`** (for most phones)
3. Open it on your phone and allow **"Install unknown apps"** if asked
4. Open the app, paste a link, and tap **DOWNLOAD**

**Requirements:** Android 10 or newer, about 100 MB of free space, and an internet connection.

---

## 🧑‍💻 How to use

1. 📋 Copy a YouTube or TikTok link
2. 📱 Open ADMOSS Downloader and tap the **paste** icon (or use **Share** inside YouTube/TikTok)
3. 🎛️ Choose **Video** or **Audio MP3**, then a quality
4. ⬇️ Tap **DOWNLOAD**. You can leave the app, the download continues in the background
5. 🎉 Find your file in the gallery or in the **Recent downloads** list

---

## 🛠️ Build it yourself

### Without Android Studio (GitHub Actions)

1. Fork this repo, or upload the project to your own repository
2. Open the **Actions** tab. The **Build APK** workflow runs on every push (or start it with **Run workflow**)
3. When it turns green, open the run and download **ADMOSS-Downloader-APK** from **Artifacts**

### With Android Studio

1. Open the project folder in Android Studio
2. Wait for Gradle to sync
3. Press **Run**

### Publish a release automatically

Push a version tag and the workflow attaches the APK to a new release:

```bash
git tag v1.0
git push origin v1.0
```

---

## 🧱 Tech stack

- **Kotlin** and **Jetpack Compose** (Material 3)
- [**youtubedl-android**](https://github.com/JunkFood02/youtubedl-android) (yt-dlp and ffmpeg for Android)
- **MediaStore** for saving files to the gallery
- **GitHub Actions** for building the APK

---

## 📂 Project structure

```
ADMOSS-Downloader/
├── .github/workflows/build.yml      # builds the APK in the cloud
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/admoss/downloader/
│       │   ├── MainActivity.kt      # Compose UI
│       │   ├── DownloadViewModel.kt # link, options, video info
│       │   ├── DownloadService.kt   # background service + notifications
│       │   ├── DownloadCenter.kt    # download logic, progress, saving
│       │   ├── VideoInfo.kt         # title / size estimates
│       │   └── Engine.kt            # yt-dlp + ffmpeg startup
│       └── res/                     # theme, colors, app icon, vector icons
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## ⚠️ Known limitations

- Playlists load up to 200 videos at a time
- Downloads run one after another (not in parallel)
- YouTube may sometimes show a verification check and block a download. Try again later or try another video
- The APK is still fairly big because it bundles Python and ffmpeg. Install the **arm64-v8a** APK on most phones (it is the smaller one); use **armeabi-v7a** only on very old phones

---

## 🗺️ Roadmap

- [x] Background downloads with a notification
- [x] Video info and size before downloading
- [x] Download queue
- [x] Playlist support
- [x] Light theme
- [x] Library search

---

## 📜 Disclaimer

This project is for **personal and educational use** only. Download only content you have the right to save, respect the creators, and follow the terms of service of the sites you use. Do not re-upload other people's videos as your own.

---

<div align="center">

**Made with ❤️ by ADMOSS**

⭐ If you like the project, give it a star!

</div>
