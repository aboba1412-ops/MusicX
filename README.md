# MusicX 2.0 — Android offline music player

Android Studio project: Kotlin, Jetpack Compose and AndroidX Media3.

## What is implemented

- Local audio playback without an internet connection.
- Background playback through `MediaSessionService` (continues when the screen is locked).
- Media notification and lock-screen controls: play/pause, previous/next, track metadata.
- Audio focus handling, headphone-disconnect pause and playback wake mode.
- Scan music on the device and import multiple audio files with the Android file picker.
- Library search by track, artist and album.
- Favorites, user-created playlists, album and artist collections.
- Full player with seek bar, shuffle, repeat and playback speed.
- Persistent lyrics/notes per track.
- Android system equalizer when supported by the device (the number of bands depends on the phone).
- Recently played history.
- Russian interface and dark theme.

The player works with music files available on the device. It does not include a licensed online streaming catalogue or download copyrighted tracks from third-party services.

## Build APK

1. Open the project in Android Studio with JDK 17 and Android SDK 35.
2. Sync Gradle.
3. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions is configured to build the debug APK on pushes to `main` or manually. Open **Actions → Build MusicX APK → latest run → Artifacts** and download `MusicX-2.0-apk`.

Application ID remains `com.musicx.player`. A stable debug signing key is included so APKs built by later GitHub Actions runs keep the same signature. The first update from an older APK may still require uninstalling the old app once if it was signed with GitHub's temporary key. This is a debug key only, not a production release key. Minimum Android version: 8.0 (API 26).
