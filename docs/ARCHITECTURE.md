# Project architecture and boundaries

ADMOSS Downloader is currently a single Android application module built with Kotlin and Jetpack Compose. This document records the existing responsibilities and the safe direction for future modularization; it does not claim that the current source tree is already fully layered.

## Current main responsibilities

| Area | Main source files |
|---|---|
| App entry point and main navigation | `MainActivity.kt`, `AdmossApp.kt` |
| Download link and video metadata state | `DownloadViewModel.kt`, `VideoInfo.kt`, `Oembed.kt` |
| Queue, download execution, history and MediaStore | `DownloadCenter.kt`, `DownloadService.kt` |
| yt-dlp / ffmpeg lifecycle | `Engine.kt`, `YtOptions.kt`, `Parallel.kt` |
| In-app update checking and installation | `Updater.kt`, `UpdateParsing.kt`, `UpdateCheckJobService.kt` |
| Offline media sharing | `OmsCenter.kt`, `OmsNet.kt`, `OmsProtocol.kt`, `OmsMedia.kt`, `OmsQr.kt`, `OmsReceived.kt`, `OmsSeries.kt`, `OmsUi.kt` |
| Player, slideshow and phone media organization | `PlayerActivity.kt`, `PhotoViewerActivity.kt`, `SeriesUi.kt`, `TikTokPhotos.kt` |

## Boundaries strengthened in this change

- Update version comparison, release-note cleanup, and GitHub URL allow-listing are pure helpers in `UpdateParsing.kt`, so they can be unit-tested without starting Android services or making network requests.
- OMS transfer-size arithmetic and MIME validation are centralized in `OmsProtocol.kt`.
- MediaStore publishing treats a pending row as a transaction: if copying or publishing fails, the row is deleted.

## Safe next refactor

The two largest UI/state files are still `MainActivity.kt` and `OmsUi.kt`. Split them incrementally by screen or feature, preserving existing public entry points and running the automated checks after each extraction. Do not combine that refactor with download-engine or transfer-protocol changes in one commit.
