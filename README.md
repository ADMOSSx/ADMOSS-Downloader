# ADMOSS Downloader

A modern Android app that downloads videos and audio from **YouTube** and **TikTok** just by pasting a link.

![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84)
![Language](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF)
![Engine](https://img.shields.io/badge/engine-yt--dlp%20%2B%20ffmpeg-00E5FF)

## Features

- Download from YouTube, TikTok, and many other sites supported by yt-dlp
- Video quality choice: Best, 1080p, 720p, 480p, 360p
- Audio-only mode (MP3)
- Live download progress with percentage, time left, and a Cancel button
- Dark, modern UI built with Jetpack Compose
- Paste button, automatic site detection, and **Share to ADMOSS Downloader** support from other apps
- Saves to your gallery: videos in `Movies/ADMOSS`, audio in `Music/ADMOSS`
- Recent downloads list, tap to play
- yt-dlp updates itself when you are online

## Download the APK

Go to the **Actions** tab, open the latest successful run, and download **ADMOSS-Downloader-APK** from the Artifacts section. Unzip it and install `app-debug.apk` on your phone (allow "Install unknown apps" when asked).

## Build it yourself

### Without Android Studio (GitHub Actions)
1. Fork or upload this project to your own GitHub repository.
2. Open the **Actions** tab. The **Build APK** workflow runs on every push (or start it with **Run workflow**).
3. When it finishes, download the APK from Artifacts.

### With Android Studio
1. Open the project folder in Android Studio.
2. Let Gradle sync, then press **Run**.

## Requirements

- Android 10 (API 29) or newer
- Internet connection
- About 100 MB of free space for the app

## How to use

1. Copy a YouTube or TikTok link.
2. Open ADMOSS Downloader and tap the paste icon (or use Share from the YouTube/TikTok app).
3. Choose Video or Audio MP3, and a quality.
4. Tap **DOWNLOAD** and keep the app open until it finishes.

## Tech stack

- Kotlin and Jetpack Compose (Material 3)
- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp and ffmpeg for Android)
- MediaStore for saving files
- GitHub Actions for building the APK

## Known limitations

- Downloads stop if the app is closed (no background service yet)
- YouTube sometimes shows a verification check, which can block a download. Try again later or try another video
- The debug APK is large because it bundles Python and ffmpeg

## Disclaimer

This project is for personal and educational use. Only download content you have the right to save, respect the creators, and follow the terms of service of the sites you use. Do not re-upload other people's videos as your own.

## License

MIT License. Add a `LICENSE` file to your repo if you want to publish it under this license.
