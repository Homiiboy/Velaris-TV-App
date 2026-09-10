# Velaris TV

Native Android / Google TV shell for Velaris.

<p align="center">
  <img src="branding/velaris-tv-logo.svg" alt="Velaris TV" width="260">
</p>

Velaris TV turns a self-hosted Velaris Web instance into a dedicated TV app for Android TV and Google TV devices.

## First milestone

- Native Android TV launcher app
- Remote / D-pad friendly
- First-run Velaris server setup
- Stored server URL
- Fullscreen WebView shell
- HTML5 video fullscreen support
- Local HTTP server support for home networks
- Connection error and retry screen
- Long-press Back to reopen server settings
- CI-built debug APK

## Server URL

On first launch, enter the URL of your Velaris Web instance, for example:

```text
http://192.168.1.50:8097
```

Velaris Web then connects to Jellyfin as usual.

## Build

Open the project in a current Android Studio version, or build with Gradle 9.6+ and JDK 17:

```bash
gradle :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions also builds and uploads a debug APK on every push to `main`.

## App identity

- Application ID: `com.novarion.velaristv`
- Version: `0.1.0`
- Minimum Android: API 26
- Target API: 34
- Compile API: 36

## Velaris Web

The frontend itself is maintained separately in `Homiiboy/velaris-web` on the `velaris` branch.
