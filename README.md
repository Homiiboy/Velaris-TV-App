# Velaris TV

<p align="center">
  <img src="branding/NewAppIcon.png" alt="Velaris TV App Icon" width="260">
</p>

Velaris TV is a native Android TV / Google TV client with a custom Velaris interface. It connects directly to a Jellyfin server and uses Jellyfin for authentication, libraries, metadata, images and playback progress.

## Phase 2 — Complete

- Native Android TV / Google TV app
- Custom Velaris UI instead of the Jellyfin Web UI
- Remote / D-pad friendly navigation
- First-run Jellyfin server setup
- Jellyfin user login
- Stored server URL and session
- Home screen with Continue Watching, latest media, movies, series and My List
- Native movie, series, season and episode navigation
- Search across movies and series
- Jellyfin favorites / My List integration
- Automatic next-episode playback
- Native Media3 / ExoPlayer playback
- Resume playback from Jellyfin progress
- Playback progress reporting to Jellyfin
- Poster and backdrop loading from Jellyfin
- Local HTTP server support for home networks
- Fullscreen landscape TV experience
- GitHub Actions build, lint and debug APK artifact

## Jellyfin server

On first launch, enter the URL of your Jellyfin server, for example:

```text
http://192.168.1.50:8096
```

Velaris TV talks directly to the Jellyfin API. A separate Velaris Web instance is not required by the TV app.

## Phase 3 — Complete

- Context-aware TV back navigation
- Rich episode cards with thumbnails and descriptions
- Improved D-pad focus behavior
- Jellyfin PlaybackInfo negotiation
- Direct-play media source selection with safe legacy fallback
- Jellyfin transcoding URL support when supplied by the server
- User-aware playback negotiation
- Automatic next-episode playback retained across the native player flow

## Phase 4 — Complete

- Audio and subtitle track selection from the TV remote menu/settings key
- Subtitle disable option
- Personalized "Für dich" home row
- Richer movie metadata with year, runtime and community rating
- Safer Jellyfin server URL validation
- Hardened playback progress connection cleanup
- Existing Jellyfin user account acts as the active Velaris profile

## Phase 5 — Complete

- TV profile entry point with Jellyfin user discovery
- Safe account switching through Jellyfin re-authentication
- Mark movies and episodes as watched / unwatched
- Watch-state synchronization through Jellyfin
- Existing personalized recommendations and My List retained per account
- Release identity aligned to Velaris TV 0.5.0
- Jellyfin client headers aligned to the app version

## Phase 6 — Complete (Release Candidate)

- Parallelized network/image work for faster TV home loading
- Recoverable home-screen server/offline error state with retry
- Removed unnecessary keep-awake behavior outside playback
- Accessibility labels and minimum TV button target sizing
- Continuous series autoplay using Jellyfin NextUp discovery
- Player closes cleanly when playback finishes without a next episode
- Release-candidate identity aligned to Velaris TV 0.6.0
- Jellyfin client headers aligned to the release-candidate version
- Intro Skipper / Jellyfin Media Segments integration with a TV-friendly "Intro überspringen" button

## Plugin compatibility before 1.0

Velaris TV stays API-driven and does not require plugin-specific client SDKs.

- **Intro Skipper:** Intro, recap, outro/credits and preview segments are consumed through Jellyfin Media Segments.
- **TheIntroDB:** Compatible automatically when it exposes the same Jellyfin Media Segments; no separate client configuration is required.
- **AniList / AniDB / TheTVDB metadata:** Provider-enriched Jellyfin metadata is consumed from the normal item API; genres and provider-updated artwork/metadata therefore appear in Velaris without coupling the app to one provider.
- **Themerr:** Theme media remains server-managed. Velaris deliberately does not autoplay theme audio before 1.0, avoiding unexpected TV audio; metadata/artwork supplied through Jellyfin remains compatible.
- **Trakt:** Watch-state synchronization remains server-side. Velaris reads and writes Jellyfin's canonical watched state, so Trakt synchronization can operate without exposing Trakt credentials to the TV client.

## Phase 7 — Complete (0.7.0 Release Candidate)

- Configurable automatic intro, recap and credits skipping
- Jellyfin Media Segments skip controls retained for manual use
- Similar-title discovery on detail pages
- Random episode playback for series
- Playback preferences stored locally per TV installation
- Playback diagnostics overlay via the TV INFO key (Direct Play / Transcoding, codec, width and bitrate)
- Provider/plugin compatibility retained for Intro Skipper, TheIntroDB, metadata providers, Themerr and Trakt
- Foundation retained for continuous next-episode playback, profiles, My List, watch-state sync, subtitles/audio tracks and recoverable networking
- Continue Watching cards now expose playback progress
- Player survives temporary activity stops and resumes from its local position
- Feature freeze milestone reached at 0.7.0 RC; remaining work is real-device validation and release hardening

## Roadmap

Phase 7 / 0.7.0 RC is the feature-complete milestone. Remaining release work is operational: real-device regression testing, signing credentials, final store screenshots/listing and production publishing.

## Build

Open the project in a current Android Studio version, or build with Gradle 9.6+ and JDK 17:

```bash
gradle :app:lintDebug :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds, lints and uploads a debug APK on every push to `main`.

## App identity

- Application ID: `com.novarion.velaristv`
- Version: `0.7.0`
- Minimum Android: API 26
- Target API: 34
- Compile API: 36
- Player: AndroidX Media3 / ExoPlayer 1.8.0

## Architecture

```text
Velaris TV
    |
    +-- Native Velaris TV interface
    |
    +-- Jellyfin API
          |-- Authentication
          |-- Libraries and metadata
          |-- Images
          |-- Video streams
          +-- Playback progress
```

Velaris Web remains a separate frontend project. Velaris TV no longer embeds Velaris Web or the Jellyfin Web UI in a WebView.
