# SalviaBrowxer (Android)

A native Android browser with automatic media detection and a multi-connection download engine, built in **Kotlin** and **Jetpack Compose (Material 3)**.

## Features

- **Home Hub & Link Classifier**: Paste a webpage or direct media URL (`.mp4`, `.m3u8`, `.mpd`, `.mp3`, etc.) with clipboard monitoring, search-engine selector (Google, DuckDuckGo, Bing, Yahoo, Ecosia), speed dials, bookmarks/history autocomplete suggestions, and direct video/audio extraction.
- **Native WebView Browser**: Real multi-tab WebView browsing (`BrowserHost`) with 8-tab LRU hibernation, Private tabs (isolated WebView profile where supported), Desktop/Mobile UA toggle, per-site Ad & Tracker blocking (`AdBlockHostRules`), Find in Page, HTTPS-first cleartext prompt, file uploads, camera/mic/location permission prompts with per-site memory, SSL interstitials, HTTP auth, Print/Save as PDF, and Android 15 edge-to-edge + Picture-in-Picture support.
- **Media Detection & Quality Selection**: Automatic network + DOM + MSE media sniffing (`MediaSniffer`, `MediaProber`, `BrowserDetectorScript`) feeding a draggable floating download orb (tap to download main stream, long-press to open the full media tray) and quality selector modal with HLS playlist variant parsing, DASH manifest parsing, and stream probing.
- **Download Manager & Locked Folder**: Foreground `NativeDownloadService` with simultaneous queue scheduling, multi-connection ranged downloads, HLS (AES-128, BYTERANGE, `EXT-X-MAP`) and static DASH support, pause/resume/cancel/retry across app restarts, Wi-Fi hold guard, live speed and ETA telemetry, SAF folder picker + MediaStore publishing (`MediaPublisher`), and a local PBKDF2-HMAC-SHA256 (120,000 iterations) password-protected folder with progressive lockout.
- **In-App Media Player**: Native `Media3 ExoPlayer` video/audio playback with playback speed controls, sequential/loop/reverse/shuffle queue modes, seek controls, Picture-in-Picture, and fullscreen mode.
- **Bilingual UI (English / Persian RTL)**: Dynamic switching between `English`, `فارسی` (RTL with bundled Vazirmatn & Inter typography), or `System default`.
- **In-App Self-Update & Diagnostics**: Daily check against GitHub Releases (downloading APKs through the app's own downloader), local diagnostics export, and native crash report packaging (`CrashReporter`).
- **Default Browser & Share Target**: Handles `http`/`https` links as a default browser candidate, appears in the text-selection "Open in browser" menu, and receives URLs shared from other apps.

## Build

Open in Android Studio or build with Gradle (JDK 17 + Android SDK 35):

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

## License

Licensed under the terms in [LICENSE](LICENSE) (GPL-3.0).
