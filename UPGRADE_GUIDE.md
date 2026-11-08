# Updating AuroraTV without signing in again

**For users upgrading an existing AuroraTV installation:** download the latest APK from the [AuroraTV Rust Preview release](https://github.com/Bmay0000/AuroraTV/releases/tag/rust-preview) and choose **Install / Update** on your Fire TV or Android TV. **Do not uninstall the existing app first.**

On a compatible in-place Android update, the system preserves the app's private data:

- Encrypted Xtream Codes credentials or encrypted M3U playlist URL.
- Your downloaded SQLite library and EPG data.
- Favorites, hidden channels and categories, language filters, guide settings.
- Recently watched titles and saved playback positions.

AuroraTV opens the saved catalog immediately on startup. If a previous import was interrupted, it tries to repair it while leaving any usable cached library available. If the catalog is missing but the encrypted provider login still exists, it rebuilds from the saved connection rather than requesting credentials again.

## When Android cannot update in place

The APK must have **the same Android application ID and signing certificate** as the installed version. A newer or equal version code is also required. The release automation verifies that new Rust-preview APKs match the *previous Rust-preview APK's package and certificate*, and rejects updates with regressed version codes. This is an important safety check, but it does not cover every older AuroraTV build.

**Verified legacy exception:** GitHub's signing audit confirmed that the old published **AuroraTV 2.1 Flutter APK is not an in-place-upgrade match for the Rust Preview APK** (its package ID or certificate differs). A 2.1 user should keep that installation unless they have the original IPTV login ready. After signing into the Rust Preview once, subsequent Rust Preview updates can preserve the new installation's credentials.

Android cannot share stored credentials between different package IDs or unrelated signing identities. If you receive **App not installed**, **Package conflicts with an existing package**, or an invalid signature error, **do not uninstall your existing app** until you know how you will transfer your provider login. In particular, an old Flutter-branded release may be a different installation lineage; check the release notes.

Your Android Keystore encryption keys belong to the specific app installation. They are **not** backed up to GitHub and cannot be recovered from an APK. Uninstalling or clearing AuroraTV's app data erases the saved local login and library. Installing the APK onto a different device also requires signing in again unless an explicit secure transfer mechanism is provided.

## For maintainers

- Keep `applicationId 'tv.aurora.player'` unchanged.
- Keep `debug.keystore` as the existing **preview-only** signing identity until a supported signing transition is planned. A public development signing key is not appropriate for production distribution.
- Increase `versionCode` for published builds.
- Never clear `SharedPreferences`, the `AndroidKeyStore`, or `aurora_catalog.db` during routine upgrade migrations.
- `android:allowBackup="false"` is intentional to avoid automatically exporting encrypted IPTV metadata or unrecoverable Keystore-bound material to cloud backups.
- Do not present uninstall/reinstall as a troubleshooting step for users who need to keep their provider information.
