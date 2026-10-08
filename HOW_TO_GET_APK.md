# Build an AuroraTV APK on GitHub

1. Extract this ZIP and upload all contents of the inner `AuroraTV` folder, including its hidden `.github` folder, into your `Bmay0000/AuroraTV` repository's main branch. Do not upload the enclosing folder itself.
2. Visit the repository's Actions tab, select "Build AuroraTV APK", and select "Run workflow" if there is no automatic run after uploading.
3. Open the completed run, under Artifacts download `AuroraTV-debug-APK`.
4. Extract the downloaded artifact ZIP and install `app-debug.apk` onto your Fire TV. This is a debug APK; it is not a store-signed release.

The workflow requires GitHub-hosted Android SDK and access to Google's and Maven Central's Android build dependencies. The source ZIP alone is not an APK. A workflow file cannot prove the Android build succeeded until GitHub actually runs it.
