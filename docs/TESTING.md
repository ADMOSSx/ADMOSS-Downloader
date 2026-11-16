# ADMOSS Downloader testing checklist

Run these checks before merging changes or publishing a release.

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

## Download and library regression

- [ ] Download a YouTube video in at least two qualities.
- [ ] Download audio-only and verify the saved file opens.
- [ ] Load a playlist, select a subset, and verify order and titles.
- [ ] Download a TikTok video and a photo slideshow.
- [ ] Queue multiple links, pause/cancel where supported, and retry a failed item.
- [ ] Run the configured parallel-download limit and verify no item is duplicated.
- [ ] Force a network interruption and verify the item ends in a recoverable state.
- [ ] Verify downloads remain visible after closing and reopening the app.
- [ ] Delete a downloaded item and verify both the file and library entry are handled correctly.
- [ ] Simulate insufficient storage and confirm a useful error with no ghost MediaStore item.

## Offline sharing (OMS) regression

Use two physical Android devices, preferably one Android 10–12 device and one recent Android version.

- [ ] Send one photo, one video, and one audio file.
- [ ] Send a batch containing several files and verify names, sizes, and order.
- [ ] Verify duplicate Series episodes are skipped as expected.
- [ ] Decline a transfer and verify neither device is left stuck.
- [ ] Cancel during transfer and verify both devices return to a usable state.
- [ ] Turn off the receiving phone's Wi-Fi during transfer and verify a clear failure.
- [ ] Fill or restrict receiver storage and verify incomplete MediaStore rows are removed.
- [ ] Verify invalid MIME types, negative sizes, per-file over-limit sizes, and aggregate over-limit sizes are rejected by unit tests.

## Updater regression

- [ ] Check for updates when there is no newer release.
- [ ] Check a newer release and verify the correct ABI APK is selected.
- [ ] Verify a valid download's size and SHA-256.
- [ ] Confirm invalid APKs, a different package ID, an older version, and a different signing certificate are rejected.
- [ ] Confirm only HTTPS GitHub URLs and the known GitHub asset redirect hosts are accepted.
- [ ] Cancel Android's installer prompt and confirm the current app remains usable.
- [ ] Install an update signed by the same permanent certificate and verify app data remains intact.

## Release-signing checklist

- [ ] The four signing secrets are configured.
- [ ] `EXPECTED_CERT_SHA256` matches the certificate of an already-published official APK.
- [ ] A tagged workflow run passes the keystore check and `apksigner` verification.
- [ ] The produced APK installs over the previous official version without uninstalling it.
- [ ] The in-app updater can download and hand off the same release to Android's installer.

Do not publish a release based only on a successful compile. Complete the device checks for the features touched by the change.
