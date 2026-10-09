# AuroraTV — Fire TV & Android TV IPTV Player

**Downloader code: `6700987`** · [Fire TV installation guide](HOW_TO_GET_APK.md) · [Latest APK builds](https://github.com/Bmay0000/AuroraTV/actions)

AuroraTV is an independent, original dark-themed streaming interface for Android TV and Fire OS devices that support Android APKs. It is **not affiliated with Netflix, Amazon, or any IPTV provider**. AuroraTV includes no channels or subscriptions; users must supply their own legally accessible IPTV service.

## Features

- Xtream Codes and M3U login, with locally encrypted credentials, compact credential-free Xtream references, and AES-GCM encryption for playlist URLs.
- Live TV, movies, series/episodes, search, favorites, language filtering, category hiding/restoration and poster-based browsing.
- A single compact persistent navigation row with no redundant Live TV or homepage shortcut tiles; full-screen horizontal media shelves and density-adjustable grids; Continue Watching and My List; D-pad focus outlines.
- English-language release recommendations lead Home, followed by English Movies, TV Series, North American live channels and English genres. Unverified-language and international titles appear **later**, never in the first featured position. Release years are only used when supported by actual title/provider evidence.
- Adjustable **Display density** under Settings: Comfortable, Compact (default) or Extra compact for more movie posters and more rows per TV screen.
- **Fast section switching (v0.8):** bounded in-memory SQLite result caching for recent releases, English/international shelves, category menus and the main North American guide. Abandoned catalog queries and queued poster downloads are canceled as you change screens. The poster loader shares duplicate image requests, reuses memory and keeps a 96 MB on-device poster cache; database caches are cleared when the provider catalog or your filters change.
- **Performance timings:** Settings → Library status & import speed now shows the last measured section-open times and catalog cache hits/misses on your own Fire TV.

- Existing v0.5/v0.6 catalogs and settings are preserved when updating. Full release-year and guide data depend on the IPTV provider; installing the UI update does not require importing your entire library again.
- **Unified Live TV & Guide:** continuous, vertically scrollable channel directory (no oversized Next page), compact category tabs for North America, Sports, News, Movies, Entertainment, Kids, English and International, a provider category picker, channel-name search, and an optional muted preview in the right sidebar.
- The main North American guide follows supported network order from [DIRECTV's official Satellite Channel Guide](https://www.directv.com/guide/channel-guide/) where AuroraTV can match a channel actually provided by the user. It shows **one chosen stream per network**, not 1,000 duplicated ESPN/HD/event feeds. Numbered events and other variations remain accessible through **All Streams**; use a network's **Channel Options → Change stream quality / alternate source** to choose another HD/FHD/4K feed. The initial guide continues into regional North American networks after the national references. These numbers are organizational references, not the actual IPTV provider's channel numbers. AuroraTV is independent and supplies no programming.
- Movies and TV Shows open to English-first genre/category shelves with international content lower down; a separate **All Titles** grid loads additional titles while you scroll instead of using next-page buttons.
- A TV Guide combining compatible Xtream/short EPG with configurable XMLTV feeds.
- The channel lineup and EPG remain separate: a station only gets Now/Next programme details when a real matching EPG source exists. The guide can still list stations without EPG.
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

## Daily Trending Movies and TV Shows (TMDB)

AuroraTV can display separate **Top 20 Movies Today** and **Top 20 TV Shows Today** shelves. Open **Manage Connection → Daily Top 20 / TMDB key** and enter your own TMDB API v3 key. The key is kept in the app's local preferences and is not committed to this repository. TMDB daily rankings refresh at most once every 24 hours, with the previous cached ranking available offline. Only titles matched to the user's imported IPTV catalogue appear as playable cards; non-English and obviously mismatched title/year combinations are excluded. Without a key the trending shelves are hidden, and regular library browsing remains available.

### Cinema 0.9 update

- **Sticky cinematic heroes** on Home, Movies, and TV Shows remain visible while scrolling content shelves. They display a clean title/year/type, a provider poster in its own portrait frame, and **Watch Now** / **Browse Library** actions.
- **Landscape background images** are matched by title and year from TMDB when the user enters their own TMDB v3 API key. Artwork crossfades only once the new image is available; provider portrait posters are not stretched into widescreen backdrops.
- **Curated featured title** prefers confirmed English movies with stronger artwork, genre/rating evidence and verified TMDB trending matches, rather than blindly choosing the first provider-added title.
- **Clean poster rails:** provider language and quality prefixes are stripped for display, year/category captions and most ornamental badges are removed, and D-pad focus retains mint highlights.
- **Live guide redesign:** clearer rows, category chips and a bordered preview panel, with asynchronous directory loading and cancellation of superseded category scans.
- **Optional muted trailer preview:** Enable **Manage Connection → Cinematic trailer previews** after configuring TMDB. If a YouTube trailer is available, AuroraTV attempts to load its official YouTube embed after the focused item remains selected for 2.8 seconds. This is **off by default** because WebView / YouTube autoplay may not work on all Fire TV software and can consume bandwidth. No trailer is guaranteed.
- **Separate networking:** TMDB trending fetches no longer occupy the same workers that render the IPTV library.
- **Version identification:** `0.9` is visible beside the AuroraTV brand. APK version code is 9.

**Limitations:** TMDB-based ranking, proper landscape backdrops and official trailers all require a working user-provided TMDB API v3 key. If none is configured, the UI remains fully usable but uses the provider's portrait artwork. Because this project builds remotely, CI can verify compilation and package creation but cannot measure frame-rate, playback compatibility or remote responsiveness on your specific Fire TV. TMDB attribution/data terms: [TMDB](https://www.themoviedb.org/).
