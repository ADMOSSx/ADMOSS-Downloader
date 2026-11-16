# ADMOSS Downloader testing checklist

Run these checks before merging changes or publishing a release. Automated checks do not replace testing on physical Android devices.

## Automated checks

The GitHub Actions workflow runs:

- `testDebugUnitTest` — JVM unit tests.
- `lintDebug` for branch/debug builds.
- `lintRelease` and `assembleRelease` when the permanent signing key and pinned certificate fingerprint are configured.
- `apksigner verify` plus a certificate SHA-256 comparison for every release APK.

For a local development build:

```bash
gradle testDebugUnitTest lintDebug assembleDebug --no-daemon
```

## Faster downloads and library

- [ ] Paste a YouTube link: title and cover show up first, sizes follow.
- [ ] Paste the same link again: the info shows at once (cache).
- [ ] Download a video right after the lookup: it starts without a long "Starting…" wait.
- [ ] Add 3 links to the queue with "Downloads at the same time" = 2: two run together, one waits.
- [ ] Set it to 1 in Settings: they run one after another.
- [ ] Download a YouTube video in at least two qualities.
- [ ] Download audio-only and verify the saved file opens.
- [ ] Download a TikTok photo post with 10+ photos: photos and music save quickly, all in order.
- [ ] Download a TikTok video and a photo slideshow.
- [ ] Load a playlist, select a subset, and verify order and titles.
- [ ] Load a playlist, tap "Calculate total size": it checks several videos at once and can be stopped.
- [ ] Queue multiple links, cancel/retry where supported, and verify no item is duplicated.
- [ ] Force a network interruption and verify the item ends in a recoverable state.
- [ ] Verify downloads remain visible after closing and reopening the app.
- [ ] Simulate insufficient storage and confirm a useful error with no ghost MediaStore item.

## Deleting files and restoring the library

- [ ] Library > trash icon on one item > Delete: the file is gone from the gallery / file manager.
- [ ] "Only remove from the list" keeps the file on the phone.
- [ ] Long-press an item, select more, tap the trash icon at the top: confirmation, then all are deleted.
- [ ] Playlist page > "Delete all files in this playlist".
- [ ] After a reinstall + "find my saved files": delete a restored item. Android may show its own confirmation. Confirm it: the item must disappear and the file must be gone.
- [ ] Say no in Android's confirmation: the item must stay in the Library.
- [ ] Verify that incomplete MediaStore rows are removed after a failed copy/publish operation.

## Offline sharing (OMS)

Use two physical Android devices, preferably one Android 10–12 device and one recent Android version.

- [ ] Send one photo, one video, and one audio file.
- [ ] Send a batch containing several files and verify names, sizes, and order.
- [ ] Verify duplicate Series episodes are skipped as expected.
- [ ] Decline a transfer and verify neither device is left stuck.
- [ ] Cancel during transfer and verify both devices return to a usable state.
- [ ] Turn off the receiving phone's Wi-Fi during transfer and verify a clear failure.
- [ ] Fill or restrict receiver storage and verify incomplete MediaStore rows are removed.
- [ ] Verify invalid MIME types, negative sizes, per-file over-limit sizes, file-count limits, and aggregate over-limit sizes are rejected by unit tests.

## Updates

- [ ] Settings > "Check for updates now" with no new release: "You have the latest version".
- [ ] Publish a newer release built by the workflow. The app shows "Update available" with the version, size and release notes.
- [ ] "Update now": progress bar, "Checking the file…", then Android's installer asks to confirm.
- [ ] After installing: the Library, settings and theme are still there.
- [ ] Turn "Check for updates automatically" off: no card at launch and no notification.
- [ ] Turn it on and leave the app: a notification appears within about 12 hours after a release.
- [ ] Check that the release has the `.apk.sha256` files next to the APKs.
- [ ] Verify a valid download's size and SHA-256.
- [ ] Confirm invalid APKs, a different package ID, an older version, and a different signing certificate are rejected.
- [ ] Confirm only HTTPS GitHub URLs and known GitHub asset redirect hosts are accepted.
- [ ] Cancel Android's installer prompt and confirm the current app remains usable.
- [ ] Install an update signed by the same permanent certificate and verify app data remains intact.

## Release-signing checklist

- [ ] The four signing secrets are configured.
- [ ] `EXPECTED_CERT_SHA256` matches the certificate of an already-published official APK.
- [ ] A tagged workflow run passes the keystore check and `apksigner` verification.
- [ ] The produced APK installs over the previous official version without uninstalling it.
- [ ] The in-app updater can download and hand off the same release to Android's installer.

Do not publish a release based only on a successful compile. Complete the device checks for the features touched by the change.
