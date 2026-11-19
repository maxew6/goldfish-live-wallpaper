# Goldfish Live Wallpaper

A native Android live wallpaper featuring a procedurally rendered goldfish aquarium. Version **3.0** (`versionCode 3`).

The project pairs a Java wallpaper renderer with an HTML preview and supports Android 7.0 (API 24) and newer. It uses Android framework APIs and has no third-party runtime dependencies.

## Features

- Animated goldfish with detailed fins, shadows, aquarium lighting, pebbles, and water effects.
- Touch interactions create ripples; fish react to nearby movement. Feed fish using the app's control or double-tap.
- A performance profile that adapts frame pacing to device characteristics.
- A preview and settings screen for configuring the wallpaper.
- Compatibility paths for opening Android's live-wallpaper picker across device variants.

## Downloads

Install either v3 APK directly on a compatible Android device:

- [Debug APK](downloads/GoldfishLiveWallpaper-v3.apk)
- [Release APK](downloads/GoldfishLiveWallpaper-v3-release.apk)

Both APK variants are debug-signed for direct installation. They are development builds, not Google Play distribution packages. To install, download an APK to your Android device and allow installation from that source when prompted. Then open the app and follow the wallpaper setup flow.

## Build from source

Requirements: JDK 17, Android SDK Platform 34, Android Build Tools 34.0.0, and Gradle 8.5.

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
gradle assembleDebug --no-daemon
gradle assembleRelease --no-daemon
```

The project archive does not include `gradle-wrapper.jar`; use an installed Gradle 8.5 distribution or open the project in Android Studio.

## Project details

| Property | Value |
| --- | --- |
| Application ID | `com.onyx.goldfishwallpaper` |
| Version | `3.0` (`versionCode 3`) |
| Minimum Android version | Android 7.0 (API 24) |
| Compile / target SDK | API 34 |
| Runtime dependencies | None |

## Repository contents

- `app/` — Android app source, assets, resources, and manifest.
- `downloads/` — installable v3 debug and release APKs.
- Gradle configuration — project build setup.

## License

No license file is included. All rights remain with the project author unless a license is added.
