# Build verification — 2026-10-08

- Clean Gradle build: `clean assembleDebug lintDebug` succeeded.
- Android lint: 0 errors, 12 warnings; full report in LINT_REPORT.txt. Remaining warnings include English-only hardcoded/concatenated text, TV landscape orientation and minimum-SDK advice.
- Pure Java core tests: 16 passed.
- APK signatures: verified (v1 and v2).
- Android minimum SDK: 23; target/compile SDK: 35.
- Package: tv.aurora.player; version: 0.1.0 (1).
- Java: 17; Gradle wrapper: 8.9; Android Gradle plugin: 8.7.3.
- No emulator, real Fire TV, authenticated IPTV account or end-to-end playback test was available. Provider support and visual/remote usability remain unverified on hardware.
- APK is a debug development build, not an app-store release.
