# AuroraTV Rust rebuild — phase 1

This is the **incremental source migration**, not yet a replacement of the Fire TV user interface.

## Current boundary

- **Rust:** credential-free Xtream references and URL construction, streaming Xtream/M3U parsing, deterministic US network matching, streaming XMLTV EPG parsing/now-next, atomic SQLite catalog/guide caches, favorites, hidden channels, resume progress, and a JNI adapter.
- **Android Java/Media3 (retained):** Fire TV focus/navigation, catalog fetching from provider endpoints, Android Keystore-backed credentials and M3U URL sealing, preview/fullscreen playback, hardware codecs, subtitle/audio and aspect-ratio controls, wake lock / idle handling, platform activity and APK packaging.
- **Connected now:** XtreamReference.of() and XtreamReference.url() call Rust when the JNI library is bundled. Without it, they fall back to existing Java logic, including JVM unit tests. This intentionally prevents broken installs from a missing native ABI.
- **Not connected yet:** Native cache, Xtream catalog import, M3U import and guide pipeline have Rust implementations but require transactional migration from the existing Java SQLite schema and storage of encrypted M3U URLs before they should replace the deployed Java versions. Current playback remains Media3.

## Security and compatibility

No IPTV providers, channels or login data are included. Rust stream references never embed credentials. Xtream user/password values are supplied only at playback/import time by Android. The native database rejects plaintext HTTP M3U URLs: encrypt through the Android vault first. Do not put provider logins or API keys into repository test fixtures.

Rust targeting: arm64-v8a, armeabi-v7a, x86_64. App minimum Android API 23. Fire TV and Android TV hardware playback still require testing on each target device. Android TV/Fire OS still require native Android activity and playback interfaces; a mostly Rust app can still use Media3 through a minimal Android bridge.

## Build and test

Run on a machine with Rust stable:

    cd rust
    cargo test --workspace

For a local Android build, install Android SDK platform 35, NDK 27.2.12479018, Android target toolchains and cargo-ndk:

    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
    cargo install cargo-ndk --locked
    export CARGO_NDK_PLATFORM=23
    cd rust
    cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release --package auroratv-android
    cd ..
    ./gradlew assembleDebug

Or use the **Validate Rust Engine and Build Android APK** GitHub Actions workflow; its successful artifact is named AuroraTV-RustCore-debug-APK. The previous Java APK workflow stays available.

## Migration milestones

1. Complete phase 1 CI and Fire TV JNI smoke test; confirm real stream URLs match the original Java output across live/HD/4K/M3U/series cases.
2. Build a Rust streaming network fetcher with bounded gzip decoding, cancellation and retries; transfer login/import to native Rust in a database migration with rollback preserving the current catalog.
3. Migrate Android's stored library to the Rust SQLite schema without exposing encrypted URLs, favorites, hidden categories or resume state. Measure import time and warm-start time on actual hardware.
4. Attach native XMLTV importer and the provider's short-EPG fallback to the Rust guide; add manual channel-to-EPG mapping and offline cached guide behavior.
5. Move navigation state, filters, favorites and remote-focus transitions to a Rust presentation state machine. Keep an Android view/Media3 host until a Rust renderer meets accessibility and performance requirements.
6. Harden playback and preview handling with codec/URL probes, playback logs redacted of credentials, audio/subtitle selection, aspect controls, Doze/wake/keep-screen-on tests and decoder errors across HD/4K releases.
7. Verify Fire TV 1080p/4K performance, large catalog imports, EPG validity, US-vs-CA channel priority, TV remote traversal and clean install/upgrade before considering replacing the original app.

A green Rust CI build verifies compilation and packaging, **not** stream compatibility or functioning UI on Fire TV. The user must still supply a legally accessible provider for device testing.
