# AuroraTV — Fire TV & Android TV IPTV Player

**Downloader code: `6700987`** · [Fire TV installation guide](HOW_TO_GET_APK.md) · [Latest APK builds](https://github.com/Bmay0000/AuroraTV/actions)

AuroraTV is an independent, original dark-themed streaming interface for Android TV and Fire OS devices that support Android APKs. It is **not affiliated with Netflix, Amazon, or any IPTV provider**. AuroraTV includes no channels or subscriptions; users must supply their own legally accessible IPTV service.

## Features

- Xtream Codes and M3U login, with local encrypted credentials and per-title AES-GCM encrypted stream URLs.
- Live TV, movies, series/episodes, search, favorites, language filtering, category hiding/restoration and poster-based browsing.
- Distinctive AuroraTV navigation for Fire TV remotes, high-contrast selection outlines and poster/title spotlights.
- A TV Guide combining compatible Xtream/short EPG with configurable XMLTV feeds.
- **Guide preview panel:** move focus onto a station to begin an automatic, silent preview after a short delay. Only one preview connection is active. Press Select to watch fullscreen.
- Fullscreen Media3 playback, stable buffering/recovery options, live restart, VOD resume, seekbar, rewind/fast forward, selectable playback speeds, audio/subtitle track choices (when streams provide them), aspect controls and stream-health diagnostics.

## How fast does it start?

**After the first successful import, AuroraTV loads from an indexed local SQLite library.** It does not need to download all channels on each launch. Home shelves and searches load asynchronously without blocking the navigation UI.

**For a first Xtream Codes login**, Live TV is downloaded and committed **first**. AuroraTV then opens the home screen while films and series download and index in the background. If the app is interrupted, pending media types can resume on the next launch; catalog work pauses during video playback so decoding takes priority.

Initial network import duration depends on the IPTV server, transfer size and device processing speed. Importing 100,000-plus titles cannot be guaranteed to finish in 5–10 seconds. The objective is to make usable Live TV available before the entire catalog has arrived.

Check **Sources & Settings → Library status & import speed** on the device to see saved title counts, pending media and actual import timings.

## Get the APK

On Fire TV, open the **Downloader** app and enter **`6700987`**. The destination of this separately managed code should be checked before installation; publishing a new GitHub Actions build does not automatically update the code's destination.

Alternatively visit [GitHub Actions](https://github.com/Bmay0000/AuroraTV/actions), open a successful **Build AuroraTV APK** workflow and download the **AuroraTV-debug-APK** artifact. Extract its ZIP and install `app-debug.apk`.

## Build locally

Requires JDK 17, Android SDK 35 and Gradle wrapper. Run:

```bash
./gradlew assembleDebug
```

Result: `app/build/outputs/apk/debug/app-debug.apk`.

The included signing key is for **development/testing**, not store releases. Minimum Android SDK is 23; Fire OS compatibility depends on the actual Android runtime (not Vega OS).

## Technical notes and limitations

- Large imports use streaming Xtream JSON/M3U parsing, database transactions, WAL-enabled reads and an encrypted catalog. If the provider returns its API slowly, AuroraTV cannot eliminate that network delay.
- Muted guide previews require an additional temporary stream connection and a supported hardware decoder. Rapid remote focus changes are debounced; failed previews stop after a timeout. Some providers limit simultaneous stream connections.
- Channel guide entries require genuine programme data. Missing schedules may need an independent XMLTV source or manual channel matching.
- Subtitle/audio menus depend on tracks actually present in the video stream; not every codec or provider supports every playback speed.
- Hardware performance and remote behavior need testing on specific Fire TV models. Passing CI compilation is not a substitute for device playback testing.

AuroraTV is an independently designed player; it does not include copied commercial streaming-service layouts, names or artwork.
