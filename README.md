# ADMOSS Downloader

Android app to download YouTube and TikTok videos (or MP3 audio) from a link.

## Build the APK without Android Studio
1. Make a free account on github.com and create a new repository (any name).
2. Upload ALL files and folders from this project to the repo
   (including the hidden-looking `.github` folder).
   If `.github/workflows/build.yml` didn't upload: click Add file > Create new file,
   type `.github/workflows/build.yml` as the name, and paste the file contents.
3. Open the repo's **Actions** tab. The "Build APK" job starts by itself (takes ~5-10 min).
   You can also start it with **Run workflow**.
4. When it turns green, open the run and download **ADMOSS-Downloader-APK** from Artifacts.
5. Unzip it, send `app-debug.apk` to your phone, and install it
   (allow "Install unknown apps" when asked).
