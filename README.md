# Stealth Vault Simple

A lightweight Android app for keeping selected photos, videos, and documents out of your device's public gallery and browsing them inside the app.

> **Important privacy note:** This edition stores imported media in the app's private internal storage. It does not claim per-file encryption for those media files. Access is protected by Android's app sandbox and the device's own security. Private app data can still be at risk on a compromised or unlocked device. Do not use this app as a substitute for a properly encrypted backup.

## What it does

- Import files using Android's system file picker.
- Browse supported photos, videos, and documents inside the app.
- Organize content into folders.
- Keep private notes in the app.
- Restore or export files when needed.
- Keep vault data in app-private storage rather than placing imported media in the public gallery.
- Read and restore supported legacy encrypted files from the earlier Stealth Vault Notes edition.

## Privacy and storage

- Imported media in this Simple edition is stored in the app's private internal storage.
- The app's private index, folder information, and private notes use the app's encryption mechanisms.
- Android's app sandbox helps prevent other ordinary apps from directly accessing this private storage.
- Uninstalling the app or clearing its data can permanently remove locally stored vault content. **Export or restore important files before uninstalling, resetting the device, or clearing app data.**
- Keep your device updated and protected with a secure screen lock.

## Download

Open the [Releases](../../releases) page and download the latest APK asset. On Android, allow installation from the browser or file manager if prompted, then install the APK.

## Install and update

- Install the APK on a compatible Android device.
- For an update, install the newer APK over the existing app; do not uninstall the existing app first.
- Keep a separate copy of important files before updating.

## First stable release

**Version:** 1.0.0

See the [v1.0.0 release notes](../../releases/tag/v1.0.0) for the release details.

## Project relationship

Stealth Vault Simple is maintained as a separate, lightweight edition. The original [Stealth Vault Notes repository](https://github.com/ahmedbadrnote-code/Stealth-Vault-Notes-) remains separate and is not replaced by this project.

## Support

If you find a bug, open an issue in this repository and include:
- Your Android version and device model.
- The app version.
- The steps that reproduce the issue.
- Any relevant error message (do not include private files or personal information).
