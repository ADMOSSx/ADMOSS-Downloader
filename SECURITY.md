# Security policy

## Supported versions

Security fixes are applied to the current default branch and the latest published release where practical.

## Reporting a vulnerability

Please do not publish exploit details, private signing material, access tokens, or personal data in a public issue.

Use GitHub's private vulnerability reporting feature on the repository's Security tab if it is enabled, or contact the maintainer privately through the contact information on the GitHub profile. Do not post exploit details in a public issue. Include the affected app version, Android version, reproduction steps, and expected versus actual behavior only in the private report.

## Release signing

- Never commit a keystore, signing password, Base64-encoded keystore, or private key.
- Release APKs must use the permanent certificate already trusted by existing installations.
- The Actions workflow requires the pinned `EXPECTED_CERT_SHA256` variable before it can publish a tagged release.
- Branch builds may produce debug APKs for testing; these are not upgrade-compatible with official release installs.
