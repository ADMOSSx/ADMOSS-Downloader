# Test checklist (v1.11.0)

The code was checked without a phone: balanced code, resources, references and the logic tests
(version compare, release notes cleaning, file names, restore). These steps are for a real phone.

## Faster downloads
- [ ] Paste a YouTube link: title and cover show up first, sizes follow.
- [ ] Paste the same link again: the info shows at once (cache).
- [ ] Download a video right after the lookup: it starts without a long "Starting…" wait.
- [ ] Add 3 links to the queue with "Downloads at the same time" = 2: two run together, one waits.
- [ ] Set it to 1 in Settings: they run one after another.
- [ ] Download a TikTok photo post with 10+ photos: photos and music save quickly, all in order.
- [ ] Load a playlist, tap "Calculate total size": it checks several videos at once and can be stopped.

## Deleting files
- [ ] Library > trash icon on one item > Delete: the file is gone from the gallery / file manager.
- [ ] "Only remove from the list" keeps the file on the phone.
- [ ] Long-press an item, select more, tap the trash icon at the top: confirmation, then all are deleted.
- [ ] Playlist page > "Delete all files in this playlist".
- [ ] After a reinstall + "find my saved files": delete a restored item. Android may show its own
      confirmation. Confirm it: the item must disappear and the file must be gone.
- [ ] Say no in Android's confirmation: the item must stay in the Library.

## Updates
- [ ] Settings > "Check for updates now" with no new release: "You have the latest version".
- [ ] Publish a newer release (for example v1.11.1) built by the workflow. The app shows
      "Update available" with the version, size and release notes.
- [ ] "Update now": progress bar, "Checking the file…", then Android's installer asks to confirm.
- [ ] After installing: the Library, the settings and the theme are still there.
- [ ] Turn "Check for updates automatically" off: no card at launch and no notification.
- [ ] Turn it on and leave the app: a notification appears within about 12 hours after a release.
- [ ] Check that the release has the `.apk.sha256` files next to the APKs.
