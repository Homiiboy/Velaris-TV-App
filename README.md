# Velaris TV

<p align="center">
  <img src="branding/velaris-tv-logo.webp" alt="Velaris TV" width="260">
</p>

Velaris TV is a native Android TV / Google TV client with a custom Velaris interface. It connects directly to a Jellyfin server and uses Jellyfin for authentication, libraries, metadata, images and playback progress.

## Current status — Phase 2

- Native Android TV / Google TV app
- Custom Velaris UI instead of the Jellyfin Web UI
- Remote / D-pad friendly navigation
- First-run Jellyfin server setup
- Jellyfin user login
- Stored server URL and session
- Home screen with Continue Watching, latest media and movies
- Native movie / media details
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

## Roadmap

Phase 2 continues with series, seasons and episodes, next-episode playback, search, favorites / My List and a more polished streaming-TV interface.

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
- Version: `0.2.0`
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
