# AuroraTV — Fire TV & Android TV IPTV Player

**Downloader code: `6700987`** · [Fire TV installation guide](HOW_TO_GET_APK.md) · [Latest APK builds](https://github.com/Bmay0000/AuroraTV/actions)

AuroraTV is an independent, original dark-themed streaming interface for Android TV and Fire OS devices that support Android APKs. It is **not affiliated with Netflix, Amazon, or any IPTV provider**. AuroraTV includes no channels or subscriptions; users must supply their own legally accessible IPTV service.

## Features

- Xtream Codes and M3U login, with locally encrypted credentials, compact credential-free Xtream references, and AES-GCM encryption for playlist URLs.
- Live TV, movies, series/episodes, search, favorites, language filtering, category hiding/restoration and poster-based browsing.
- A single compact persistent navigation row with no redundant Live TV or homepage shortcut tiles; full-screen horizontal media shelves and density-adjustable grids; Continue Watching and My List; D-pad focus outlines.
- English-language release recommendations lead Home, followed by English Movies, TV Series, North American live channels and English genres. Unverified-language and international titles appear **later**, never in the first featured position. Release years are only used when supported by actual title/provider evidence.
- Adjustable **Display density** under Settings: Comfortable, Compact (default) or Extra compact for more movie posters and more rows per TV screen.
- Existing v0.5/v0.6 catalogs and settings are preserved when updating. Full release-year and guide data depend on the IPTV provider; installing the UI update does not require importing your entire library again.
- **Unified Live TV & Guide:** continuous, vertically scrollable channel directory (no oversized Next page), compact category tabs for North America, Sports, News, Movies, Entertainment, Kids, English and International, a provider category picker, channel-name search, and an optional muted preview in the right sidebar.
- National US cable/satellite channels are ordered according to selected reference numbers published in the [DIRECTV via Satellite English channel lineup (July 2026)](https://www.directv.com/dtvassets/pdfs/channel_lineups/DIRECTV_ChannelPackageLineUp_AllPackages.pdf), followed by regional North American, other English and international channels. **These are reference ordering values, not IPTV stream numbers.** AuroraTV does not include, access or affiliate with DIRECTV service and displays only user-supplied channels.
- Movies and TV Shows open to English-first genre/category shelves with international content lower down; a separate **All Titles** grid loads additional titles while you scroll instead of using next-page buttons.
- A TV Guide combining compatible Xtream/short EPG with configurable XMLTV feeds.
- **Guide preview panel:** move focus onto a station to begin an automatic, silent preview after a short delay. Only one preview connection is active. Press Select to watch fullscreen.
- Fullscreen Media3 playback, stable buffering/recovery options, live restart, VOD resume, seekbar, rewind/fast forward, selectable playback speeds, audio/subtitle track choices (when streams provide them), aspect controls and stream-health diagnostics.

## How fast does it start?

**After the first successful import, AuroraTV loads from an indexed local SQLite library.** It does not need to download all channels on each launch. Home shelves and searches load asynchronously without blocking the navigation UI.

**On an initial Xtream Codes login**, AuroraTV now imports Live TV, Movies and TV Shows together and only marks the new library ready when the full catalog has been committed. This corrects an earlier release that interrupted Movies/TV Shows during guide navigation. Existing staged installs with pending imports automatically repair their catalog on the next launch.

The importer now requests **gzip-compressed JSON** when available, streams provider responses rather than loading giant arrays in memory, uses faster SHA-256 ID construction, and writes compact credential-free Xtream references rather than repeatedly encrypting a full username/password URL for every title. A single transaction replaces the catalog atomically, leaving an existing valid library intact if refresh fails.

**Subsequent launches load the saved database directly**, without re-importing all titles. Initial network transfer still depends on the provider, connection and Fire TV storage; a 150,000-title first download cannot be guaranteed to complete in 5–10 seconds.

Check **Sources & Settings → Library status & import speed** for the actual Live TV, Movie and Series import timings on your device. Those measurements help identify whether provider download or local indexing is still slow.

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

- Large imports use streaming Xtream JSON/M3U parsing, gzip (when supported), atomic SQLite staging transactions and indexed reads. M3U URLs remain encrypted at rest; Xtream titles store only non-secret IDs and formats. If the provider returns its API slowly, AuroraTV cannot eliminate that network delay.
- Muted guide previews require an additional temporary stream connection and a supported hardware decoder. Rapid remote focus changes are debounced; failed previews stop after a timeout. Some providers limit simultaneous stream connections.
- Channel guide entries require genuine programme data. Missing schedules may need an independent XMLTV source or manual channel matching.
- The channel-reference order is based on publicly available network/channel-number facts, not a copied DIRECTV interface. Local station numbers vary by region, so AuroraTV does not invent local network numbers.
- Subtitle/audio menus depend on tracks actually present in the video stream; not every codec or provider supports every playback speed.
- Release recommendations only claim a verified English language when provider metadata, explicit language or recognized category codes support it. Movies without a trustworthy year are not presented as new releases; recommendations depend on the IPTV provider's available data.
- Hardware performance and remote behavior need testing on specific Fire TV models. Passing CI compilation is not a substitute for device playback testing.

AuroraTV is an independently designed player; it does not include copied commercial streaming-service layouts, names or artwork.
