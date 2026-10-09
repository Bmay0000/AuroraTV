# AuroraTV — Android TV & Fire TV IPTV Player

Modern dark navy/teal Fire TV design. Single source, no profiles. Java 17, Android Views, Media3 ExoPlayer 1.9.3. Minimum Android 6 / API 23; Fire OS devices only, not non-Android Vega OS devices.

## Implemented
- Xtream Codes account authentication, live/movie/series catalogs and episode selection.
- M3U URL import, group-title and tvg-id support. Explicit media-type="movie" / "series" or Xtream-style URL paths identify VOD; generic playlists default to live. Generic M3U cannot reliably reconstruct series seasons.
- Remote navigation, highlighted controls, recycled channel rows, physical Play/Pause, Back, Menu and long Select actions.
- Separate Live TV, Movies and TV Shows sections; per-section favorites and search.
- Individual hiding, bulk category visibility, hidden-content restore.
- Live language rules with preview, protected favorites and undo of the last bulk action. Rules apply on refresh; refresh is manual in this build.
- XMLTV now/next guide; Xtream XMLTV fetched automatically after import, optional manual XMLTV URL.
- Playback controls; VOD resume; Go to live when the player exposes a live timeline.
- Encrypted local provider credentials and cached catalog using Android Keystore AES-GCM. Android backup is disabled. No credentials in source or logs.

## Build
Open this folder in Android Studio, select JDK 17, install SDK 35 and sync Gradle. Use Build > Build APK(s). Alternatively install Gradle 8.9 and run `gradle wrapper`, then `./gradlew assembleDebug`.
Output: `app/build/outputs/apk/debug/app-debug.apk`.

The project intentionally contains no account credentials. Enter your own provider account on the device. HTTP sources are supported for provider compatibility; use HTTPS when available.

## Download AuroraTV on Fire TV

**Downloader code: `6700987`**

Open the **Downloader** app on your Fire TV, enter **6700987**, and follow the destination shown to download the AuroraTV APK. This code was supplied by the project owner; confirm that its destination points to the intended AuroraTV APK before installing.

Alternatively, obtain the latest APK from [GitHub Actions](https://github.com/Bmay0000/AuroraTV/actions) by opening a successful **Build AuroraTV APK** workflow run and downloading the `AuroraTV-debug-APK` artifact (requires extracting the ZIP).

Detailed instructions: [HOW_TO_GET_APK.md](HOW_TO_GET_APK.md).

## Install and smoke test
Enable developer options and ADB debugging on an Android/Fire OS Fire TV. From a computer with Android platform-tools:

```
adb connect FIRE_TV_IP:5555
adb install -r app-debug.apk
```

1. Open Aurora TV from the applications list.
2. Connect an Xtream account or M3U URL. First import opens Smart Library.
3. Select English, leave uncertain channels visible, preview and apply.
4. Play a channel and test remote Play/Pause, Back and Go to live.
5. Hold Select on a title: favorite, hide and restore it.
6. Menu > Manage categories: hide several categories and verify search excludes them.
7. Restart and refresh; verify favorite/hidden state persists.
8. Import XMLTV if your provider has not supplied it automatically; check now/next labels.
9. Open a movie, seek and exit; reopen to verify resume.
10. Open a series and select an episode.

## Validation
Pure Java tests: 16 checks of M3U parsing, quoted commas, stable IDs, language detection, ambiguity, favorite protection and explicit hiding. Run:

```
javac -d /tmp/aurora-tests app/src/main/java/tv/aurora/player/LibraryCore.java tests/CoreTest.java
java -cp /tmp/aurora-tests CoreTest
```

## Known development limitations
This is a functional foundation, not the finished premium UI. No device/provider smoke test has been performed. Android compilation status is recorded in BUILD_STATUS.md.
- EPG currently displays now/next; the full scrollable timeline, guide cache, timezone/device verification and compressed XMLTV are pending.
- Movie and show browsing is currently a clean text list; poster grids, artwork caching and detail screens are pending.
- Languages currently supported by smart classification: English, French, German, Spanish and Arabic; other explicitly tagged languages classify as other, ambiguous regions remain unknown. Classification uses metadata/name clues, not audio analysis. Canada is intentionally ambiguous. NA is a user-requested English clue and may be inaccurate.
- Favorites override category/smart hiding; explicit title hiding wins. Favorites remain saved when hidden.
- No disk timeshift buffer or provider catch-up integration. Pausing live and retaining history depend on the stream's available live window; Go to live does not manufacture rewind support.
- Import is bounded to 64 MB per response; catalogs are kept in memory. Very large catalogs need database-backed paging before release.
- EPG URL and metadata must be supported by your provider. A missing guide does not block playback.
- No automatic scheduled refresh, background playback, subtitle/audio-track settings screen or app-store release signing yet.
- A development signing key is included so later test APKs can update this build. Never use this shared debug key for a public release. Uninstall clears device-local data.

## Next work
Compile and test on the exact Fire TV model, then implement the timeline EPG and poster UI. Provider response fixtures and UI instrumentation are needed before calling this release-ready.
