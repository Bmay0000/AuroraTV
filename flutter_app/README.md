# AuroraTV Flutter rewrite

The new Flutter application lives in this folder. The existing Java Android application remains intact until the new Android TV APK passes CI and device testing.

## Goals / acceptance criteria (compiled from user feedback)
- **Android TV and Fire TV:** landscape, television-sized focus indicators, full D-pad/Back remote operation, no touchscreen assumptions.
- **Provider support:** Xtream Codes username/password/server URL, M3U URL or pasted playlist, multi-source roadmap, refresh without destroying user preferences.
- **Live TV:** English / US / Canada / UK / NZ / AU category filters, favorites, manually hidden channels, a clean custom compact EPG with real timeline and a bounded picture-in-picture preview; use genuine provider XMLTV and external EPG, not invented schedule entries.
- **VOD:** cinema-first full-screen landscape backdrop and overlay of title, rating, genres, plot and actions, following the original AuroraTV concept artwork rather than copying third-party visual trade dress.
- **Discovery:** independent Movies, TV Shows, genres, Continue Watching, My List, Recently Added, English-first discovery, and **separate daily Top 20 Movies / Top 20 TV Shows** matched against actually playable provider titles. Third-party metadata requires a user-supplied TMDB key; never show made-up ranking.
- **Navigation:** top-level persistent screen states, indexed SQLite catalog and incremental loading; focus changes never trigger full provider import, EPG requests, or blocking network activity.
- **Trailers:** muted opt-in trailers, only through official playable sources, after focus settles, with a single shared player. Do not claim YouTube embeds work on every Fire TV variant.
- **Playback:** Android hardware decoder / ExoPlayer-backed video_player, HLS and supported progressive live/VOD sources; playback error feedback, retry, channel switching; more advanced media track management may require a bespoke native Media3 platform channel.
- **Device restrictions:** avoid uncontrolled metadata fetching, cap in-flight thumbnails, cache TMDB, use indexed data queries, and retain a fallback if no backdrops are available.
- **Settings & management:** add/refresh connection, language and edit-library controls, provider channel number/reference matching without the old thousands-of-duplicate ESPN situation, opt-in metadata, readable errors and privacy.
- **Delivery:** Flutter APK from CI, never substitute a previous Java APK; only advertise the build when its own artifact has been verified.

## Build
Run `flutter create --platforms=android --project-name aurora_tv .` inside this folder if the generated Android host is absent, then `flutter pub get`, `flutter test`, and `flutter build apk --debug`. CI generates the standard Android host and enables Internet permission before building.

The Flutter project is not a mechanical translation of the old Java Activity. New code is split into domain/data, provider, design-system, guide and playback features. Existing Java implementation remains available for feature comparison.

## Important limitations
User IPTV content must be legally accessible to them. TMDB metadata requires a personal API key. Xtream series episode information and advanced Media3 audio/subtitle selectors are separate migration milestones. M3U playlist URL credentials and imported per-stream URLs should be treated as sensitive, and a follow-on encrypted database migration is recommended before storing large M3U playlists persistently.
