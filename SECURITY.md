# Security policy

## Supported versions

Security fixes are applied to the current default branch and the latest published release where practical.

## Reporting a vulnerability

Please do not publish exploit details, private signing material, access tokens, or personal data in a public issue.

Open a GitHub issue with a short, non-sensitive description and request a private disclosure channel, or contact the repository maintainer through the contact information on the GitHub profile. Include affected app version, Android version, reproduction steps, and expected versus actual behavior.

## Release signing

- Never commit a keystore, signing password, Base64-encoded keystore, or private key.
- Release APKs must use the permanent certificate already trusted by existing installations.
- The Actions workflow requires the pinned `EXPECTED_CERT_SHA256` variable before it can publish a tagged release.
- Branch builds may produce debug APKs for testing; these are not upgrade-compatible with official release installs.
