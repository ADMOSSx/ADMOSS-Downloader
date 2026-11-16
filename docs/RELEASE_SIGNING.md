# Release signing setup

Release APKs must be signed with the same permanent certificate used by previously published ADMOSS Downloader releases. A different valid keystore is not interchangeable: Android will reject an in-place update signed by a different key.

## 1. Identify the existing release certificate

Use an APK from a previously published official release and inspect its signer:

```bash
apksigner verify --print-certs ADMOSS-Downloader-arm64-v8a.apk
```

Record the value shown as `Signer #1 certificate SHA-256 digest`. This is the fingerprint that must be pinned. Do not substitute a newly generated keystore fingerprint unless you have confirmed it matches the old release certificate.

You can also inspect a keystore locally with:

```bash
keytool -list -v -keystore release.keystore -alias YOUR_KEY_ALIAS
```

Compare its `SHA256` fingerprint with the old APK fingerprint. Keep the keystore and passwords private.

## 2. Configure GitHub Actions secrets

Open the repository's **Settings → Secrets and variables → Actions → Secrets** and add:

- `KEYSTORE_BASE64`: Base64-encoded bytes of the permanent release keystore.
- `KEYSTORE_PASSWORD`: Keystore password.
- `KEY_ALIAS`: Private-key alias.
- `KEY_PASSWORD`: Private-key password.

On Windows PowerShell, this command creates the Base64 value (copy it directly into the secret field; never commit it):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\release.keystore"))
```

## 3. Pin the certificate fingerprint

Under **Settings → Secrets and variables → Actions → Variables**, add:

- Name: `EXPECTED_CERT_SHA256`
- Value: the 64-character SHA-256 fingerprint recorded from the existing official release APK. Colons and letter case are ignored.

The workflow checks the keystore certificate before building and checks every produced release APK with `apksigner`. A missing, malformed, or different fingerprint blocks release publication.

## 4. Build behavior

- Local debug builds use `gradle assembleDebug` and do not need release credentials.
- Release packaging tasks fail when permanent signing credentials are missing.
- Branch builds without signing secrets produce clearly named debug APK artifacts only.
- Tagged releases require all four secrets and the pinned certificate variable.
- The workflow never publishes debug-signed APKs as official releases.

If the original signing key is lost, do not generate a replacement and overwrite the expected fingerprint. First determine whether the app has a configured Play App Signing key or another supported signing-key recovery path. Otherwise existing installations may not accept the new APK as an update.
