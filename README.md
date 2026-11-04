# Iris Cam

[![Download Iris Cam](https://img.shields.io/badge/Download-Iris%20Cam-blue?logo=android)](https://github.com/mrjdm2007/Iris-Cam/releases/latest/download/IrisCam.apk)

A manual-control camera app for Android.

## Download

Use the **Download Iris Cam** button above to get the latest APK from GitHub Releases.

## Publishing an update

1. Make and push your changes to the repository.
2. Update the app version if needed.
3. Create and push a version tag, for example:

   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```

4. GitHub Actions builds the APK and publishes it as a GitHub Release. The download button always points to the latest release asset.

## Signing for seamless updates

For Android to install an update over an existing installation, every release must be signed with the **same private upload key**. Add these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If those secrets are not configured, the release workflow publishes a debug-signed APK for testing. Debug signing keys on hosted runners are not persistent, so Android may require uninstalling the previous test build before installing a later one. Never commit the keystore or passwords to this repository.
