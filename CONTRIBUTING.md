# Contributing to ADMOSS Downloader

Thanks for helping! Here is how.

## Reporting a bug

Open an issue with the **Bug report** form. The more detail the better:
the app version (shown under the app name), your phone and Android version,
the link type (YouTube, TikTok ...) and the error text the app shows.

## Suggesting a feature

Open an issue with the **Feature request** form and describe what you need and why.

## Sending code

1. Fork the repository and create a branch (`feature/my-idea`).
2. Keep changes small and focused.
3. Make sure the **Build APK** workflow passes on your fork.
4. Open a pull request and describe what changed and how you tested it.

## Code style

- Kotlin with the official style (`kotlin.code.style=official`).
- Compose UI lives in `MainActivity.kt` and `PlayerActivity.kt`; download logic lives in `DownloadCenter.kt`.
- Do not commit signing keys, passwords or personal data.

## Be kind

Be respectful to everyone in issues and pull requests.
