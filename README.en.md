# OTV

[简体中文](README.md) | [繁體中文](README.zh-TW.md) | English

A modern terrestrial digital TV (DTMB) player built for Xiaomi Android TV.

## Why OTV?

The stock DTMB live TV app on Xiaomi TVs is overly bare-bones—it has an outdated UI, lacks a proper program guide, does not let you freely reorder or hide channels, and makes switching audio tracks or subtitles cumbersome.

Built on the native Android TV TIF framework, OTV talks directly to the TV's built-in tuner to deliver a clean, modern watching experience:

- **Immersive Channel Bar**: Horizontal cards preview currently airing programs and progress bars for effortless zapping.
- **Matrix Program Guide (EPG)**: Timeline + multi-channel grid with local caching and XMLTV import to fill in missing schedule data.
- **Channel Management & Sorting**: Reorder channels freely, favorite your go-to stations, and hide unwanted channels.
- **Audio & Subtitle Controls**: Quickly switch audio tracks and broadcast subtitles, plus a built-in **Xiaomi Audio Compatibility** toggle to fix muted channels on Xiaomi firmware.

## Screenshots

| Channel Bar | Matrix Program Guide (EPG) |
| :---: | :---: |
| ![Channel Bar](docs/screenshots/channel-bar.jpg) | ![Matrix Program Guide](docs/screenshots/epg-guide.jpg) |
| **Channel Management & Sorting** | **Settings & Options** |
| ![Channel Management & Sorting](docs/screenshots/channel-manager.jpg) | ![Settings & Options](docs/screenshots/settings-menu.jpg) |

## Getting Started

### 1. Initial Setup
1. Scan for DTMB channels in the stock Xiaomi Live TV app first (you can also jump there from OTV's settings menu).
2. Open OTV and go to **Settings → Grant TV Listings Permission**. OTV will automatically load your scanned channels.

### 2. Remote Controls

| Key | Action |
| :--- | :--- |
| **Up / Down** | Switch channels and show the top now/next info banner |
| **OK / Down** | Open the bottom channel bar (Left/Right to browse, Up for filters, EPG, and settings) |
| **Right / Guide** | Open the Matrix Program Guide (EPG) |
| **Left** | View current program summary (press Left again for full description if available) |
| **Menu** | Open the Settings & Options sidebar (audio tracks, subtitles, picture/sound, favorite & hide) |

### 3. Tips
- **Video with No Audio**: Enable **Xiaomi Audio Compatibility** under Settings.
- **Missing EPG Data**: Broadcast signals sometimes carry limited schedule data. Go to **Settings → Import XMLTV Guide** to fetch Hong Kong / HOY sources or import your own `.xml` / `.xml.gz` file.

## Build & Development

Requires JDK 17, Android SDK Platform 35, and Build-Tools 34.0.0:

```sh
./gradlew :app:assembleDebug
adb -s <TV_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <TV_SERIAL> shell am start -n com.xiaomitv.dtmb/.MainActivity
```

For architecture details, source overview, and on-device testing, see [Developer Docs](docs/README.md).

## License

Original OTV code is licensed under the [MIT License](LICENSE).
