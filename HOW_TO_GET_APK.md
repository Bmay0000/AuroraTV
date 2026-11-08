# Download or build an AuroraTV APK

## Direct APK download (recommended)

**[Download the published AuroraTV 2.1 APK](https://github.com/Bmay0000/AuroraTV/releases/download/2.1/AuroraTV-Flutter-Compact-UI.apk)**

This is a direct `.apk` file. You do **not** need to sign in to GitHub, run a build workflow, or extract a ZIP. Open the link in a browser, save the APK, and install it on a supported Fire TV or Android TV device. If installing from the Downloader app, you may have to allow app installs from that source in device settings.

[Browse all published versions](https://github.com/Bmay0000/AuroraTV/releases).

## Experimental Rust preview APK

**[Download AuroraTV Rust Preview (.apk)](https://github.com/Bmay0000/AuroraTV/releases/download/rust-preview/AuroraTV-Rust-Preview.apk)**

This link downloads the Rust-backed test APK directly from GitHub Releases, without a ZIP or GitHub sign-in. It is **experimental**; the existing Android Media3 player and interface remain, and successful CI packaging does not establish Fire TV playback compatibility.

## Fire TV — Downloader code

**AuroraTV Downloader code: `6700987`**

1. Open the **Downloader** app on your Fire TV.
2. Enter **6700987** in the URL/code field.
3. Follow the displayed destination and download the AuroraTV APK.
4. Check that the downloaded APK is the intended AuroraTV build, then install it. You may need to allow installing apps from Downloader in Fire TV settings.

This code is supplied by the project owner and is not generated or verified by the GitHub build workflow. If the code no longer works, use the GitHub Actions method below.

## Download the latest test build from GitHub Actions

1. Visit [AuroraTV Actions](https://github.com/Bmay0000/AuroraTV/actions). If building from a fresh source ZIP instead, upload the repository contents (including `.github`) to the `main` branch first.
2. Select **Build AuroraTV APK** and open the latest successful run. If no run exists, use **Run workflow**.
3. Under **Artifacts**, download `AuroraTV-debug-APK`.
4. Extract the downloaded artifact ZIP and install `app-debug.apk` onto your Fire TV. This is a debug APK; it is not a store-signed release.

The workflow requires GitHub-hosted Android SDK and access to Google's and Maven Central's Android build dependencies. The source ZIP alone is not an APK. A workflow file cannot prove the Android build succeeded until GitHub actually runs it.
