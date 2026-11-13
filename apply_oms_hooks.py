#!/usr/bin/env python3
"""
ADMOSS Share (offline media sharing): the small edits to your existing files.

1. Unzip admoss-share-feature.zip into the project root (it adds the new Oms*.kt files and two icons).
2. Run this from the project root:

       python apply_oms_hooks.py

It adds: the permissions, the QR + camera libraries, the "Share" tab in the bottom bar, and bumps the version.
Every step is checked. If something does not match your file, the script tells you the one line to add by hand.
"""
import os
import re
import sys

JAVA = "app/src/main/java/com/admoss/downloader/"
MANIFEST = "app/src/main/AndroidManifest.xml"
GRADLE = "app/build.gradle.kts"
MAIN = JAVA + "MainActivity.kt"

NEW_FILES = [JAVA + n for n in ("OmsProtocol.kt", "OmsMedia.kt", "OmsNet.kt", "OmsQr.kt", "OmsCenter.kt", "OmsUi.kt")] + [
    "app/src/main/res/drawable/ic_send.xml",
    "app/src/main/res/drawable/ic_share.xml",
]

PERMISSIONS = [
    ("android.permission.ACCESS_WIFI_STATE", '<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />'),
    ("android.permission.CHANGE_WIFI_STATE", '<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />'),
    ("android.permission.ACCESS_NETWORK_STATE", '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />'),
    ("android.permission.CHANGE_NETWORK_STATE", '<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />'),
    ("android.permission.NEARBY_WIFI_DEVICES",
     '<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />'),
    ("android.permission.ACCESS_FINE_LOCATION",
     '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32" />'),
    ("android.permission.CAMERA", '<uses-permission android:name="android.permission.CAMERA" />'),
    ("android.hardware.camera", '<uses-feature android:name="android.hardware.camera" android:required="false" />'),
    ("android.hardware.wifi.direct", '<uses-feature android:name="android.hardware.wifi.direct" android:required="false" />'),
]

DEPENDENCIES = [
    ("com.google.zxing:core", 'implementation("com.google.zxing:core:3.5.3")'),
    ("androidx.camera:camera-camera2", 'implementation("androidx.camera:camera-camera2:1.3.4")'),
    ("androidx.camera:camera-lifecycle", 'implementation("androidx.camera:camera-lifecycle:1.3.4")'),
    ("androidx.camera:camera-view", 'implementation("androidx.camera:camera-view:1.3.4")'),
]


def read(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def nl_of(text):
    return "\r\n" if "\r\n" in text else "\n"


def main():
    if not os.path.isdir("app/src/main/java/com/admoss/downloader"):
        print("Run this from the project root (the folder that contains app/).")
        sys.exit(1)
    missing = [p for p in NEW_FILES if not os.path.exists(p)]
    if missing:
        print("These files are missing. Unzip admoss-share-feature.zip into the project root first:")
        for p in missing:
            print("  - " + p)
        sys.exit(1)

    manual = []
    done = []
    applied = False

    # ---------- manifest ----------
    t = read(MANIFEST)
    nl = nl_of(t)
    add = [line for key, line in PERMISSIONS if key not in t]
    if not add:
        done.append("manifest: already has everything")
    else:
        m = re.search(r'^([ \t]*)<uses-permission android:name="android.permission.INTERNET"[^>]*/>[ \t]*\r?\n', t, re.M)
        if m is None:
            m = re.search(r'^([ \t]*)<application\b', t, re.M)
            if m is None:
                manual.append("AndroidManifest.xml: add these lines inside <manifest>, above <application>:\n    " + "\n    ".join(add))
            else:
                ind = m.group(1)
                t = t[:m.start()] + nl.join(ind + a for a in add) + nl + t[m.start():]
                write(MANIFEST, t)
                applied = True
                done.append("manifest: %d lines added" % len(add))
        else:
            ind = m.group(1)
            t = t[:m.end()] + nl.join(ind + a for a in add) + nl + t[m.end():]
            write(MANIFEST, t)
            applied = True
            done.append("manifest: %d lines added" % len(add))

    # ---------- gradle ----------
    t = read(GRADLE)
    nl = nl_of(t)
    add = [line for key, line in DEPENDENCIES if key not in t]
    if not add:
        done.append("gradle: already has the libraries")
    else:
        m = re.search(r'^([ \t]*)implementation\("androidx\.core:core-ktx[^\n]*\n', t, re.M)
        if m is None:
            manual.append("app/build.gradle.kts: add inside dependencies { }:\n    " + "\n    ".join(add))
        else:
            ind = m.group(1)
            block = ind + "// Offline sharing: QR codes (ZXing) and the camera preview (CameraX)" + nl
            block += nl.join(ind + a for a in add) + nl
            t = t[:m.end()] + block + t[m.end():]
            write(GRADLE, t)
            applied = True
            done.append("gradle: %d libraries added" % len(add))

    # ---------- the Share tab in MainActivity ----------
    t = read(MAIN)
    nl = nl_of(t)
    changed = False
    if "OmsTab()" in t:
        done.append("MainActivity: Share tab already hooked")
    else:
        m = re.search(r'^([ \t]*)else -> SettingsTab\(\)', t, re.M)
        if m is None:
            manual.append('MainActivity.kt: in the "when (tab)" block add the line   4 -> OmsTab()   above   else -> SettingsTab()')
        else:
            ind = m.group(1)
            t = t[:m.start()] + ind + "4 -> OmsTab()" + nl + t[m.start():]
            changed = True
    if 'NavItem("Share"' in t:
        done.append("MainActivity: Share button already in the bottom bar")
    else:
        m = re.search(r'^([ \t]*)NavItem\("Settings"', t, re.M)
        if m is None:
            manual.append(
                'MainActivity.kt: in BottomBar, above the Settings NavItem, add:\n'
                '        NavItem("Share", R.drawable.ic_share, selected == 4, 0, Modifier.weight(1f)) {\n'
                '            onSelect(4)\n'
                '        }'
            )
        else:
            ind = m.group(1)
            block = (
                ind + 'NavItem("Share", R.drawable.ic_share, selected == 4, 0, Modifier.weight(1f)) {' + nl +
                ind + "    onSelect(4)" + nl +
                ind + "}" + nl
            )
            t = t[:m.start()] + block + t[m.start():]
            changed = True
    if changed:
        write(MAIN, t)
        applied = True
        done.append("MainActivity: Share tab added")

    # ---------- version ----------
    t = read(GRADLE)
    code = re.search(r"versionCode = (\d+)", t)
    name = re.search(r'versionName = "(\d+)\.(\d+)\.(\d+)"', t)
    if not applied:
        done.append("version: unchanged (nothing new was added in this run)")
    elif code and name:
        new_code = int(code.group(1)) + 1
        new_name = "%s.%d.0" % (name.group(1), int(name.group(2)) + 1)
        t = t.replace(code.group(0), "versionCode = %d" % new_code, 1)
        t = t.replace(name.group(0), 'versionName = "%s"' % new_name, 1)
        write(GRADLE, t)
        done.append("version: %s (versionCode %d)" % (new_name, new_code))
    else:
        manual.append("app/build.gradle.kts: raise versionCode and versionName by hand")

    print("Done:")
    for d in done:
        print("  - " + d)
    if manual:
        print("")
        print("Please do these by hand (the file looks different from what I expected):")
        for m_ in manual:
            print("  * " + m_)
        sys.exit(2)


if __name__ == "__main__":
    main()
