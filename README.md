# SalviaBrowxer Web

A modern web application rewrite of **SalviaBrowxer** (`SaliSalvia/Salvia--Browxer`) built with React, TypeScript, Vite, and Tailwind CSS v4.

## Features

- **Home Hub & Link Classifier**: Paste a webpage or direct media URL (`.mp4`, `.m3u8`, `.mp3`, etc.) with automatic clipboard detection and direct routing into browsing or quality selection.
- **Proxied Web Browser**: Browse external URLs through `/api/browse`, with search-engine switching (Google, DuckDuckGo, Bing, Yahoo, Ecosia), bookmark/history autocomplete suggestions, Desktop/Mobile user-agent toggle, per-site ad & tracker host blocking, Find in Page, HTTPS-first cleartext prompt, and 8-tab LRU hibernation + Private tabs.
- **Media Detection & Quality Selection**: Automatic HTML media scanning and DOM playback observation feeding a draggable floating download orb (tap to download main stream, long-press to open the full media tray) and quality selector modal with HLS variant parsing and stream probing.
- **Download Manager & Locked Folder**: Simultaneous queue scheduling, pause/resume/cancel/retry, Wi-Fi hold guard, live speed and ETA telemetry, browser file export, and a local PBKDF2-HMAC-SHA256 (120,000 iterations) password-protected folder with progressive lockout.
- **In-App Media Player**: HTML5 + `hls.js` video/audio playback with playback speed controls, sequential/loop/reverse/shuffle queue modes, seek controls, and fullscreen mode.
- **Bilingual UI (English / Persian RTL)**: Dynamic switching between `English`, `فارسی` (RTL with Vazirmatn typography), or `System default`.
- **In-App Self-Update**: A silent daily check against this repository's GitHub Releases; the signed APK downloads through the app's own download engine and opens the system installer (no external browser needed).
- **Crash Capture & Diagnostics**: Native crash reports (stack + device info) are stored locally and can be shared as a zip from Settings → About — nothing is ever sent automatically.
- **Default Browser & Share Target**: Handles http/https links as a default browser candidate, appears in the "Open in browser" text-selection menu, and receives URLs shared from other apps.

## Development

```bash
npm install
npm run dev
```

Starts the Vite dev server and browser middleware on port `3000`.

## Production Build

```bash
npm run build
npm run lint
```

## License

Licensed under the terms in [LICENSE](LICENSE) (GPL-3.0).

## Android APK (GitHub Actions)

The Android app (`app/`) is a WebView shell that loads this web UI from `assets/www/` and adds a
native download service. The web bundle is **generated at build time** (it is git-ignored), so the
workflow in `.github/workflows/build-apk.yml` does everything:

1. `npm ci` + `vite build` → copied into `app/src/main/assets/www/`
2. `./gradlew :app:assembleDebug` → `SalviaBrowxer-debug.apk` (artifact, always)
3. `./gradlew :app:assembleRelease` → `SalviaBrowxer-release.apk` (artifact, best effort)

Push to any branch to get the APK under the run's **Artifacts**; push a tag (`git tag v1.0.0 &&
git push origin v1.0.0`) to also get it on the **Releases** page.

Local build (needs JDK 17 + Android SDK): `npm ci && npm run build:android && ./gradlew :app:assembleDebug`.

Signing: without secrets both APKs are signed with the shared debug key in `app/debug.keystore`
(installable, updates in place, not for the Play Store). For a real key add the repository secrets
`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS` (and optionally
`SIGNING_KEY_PASSWORD`).

Debugging the WebView of a debug build: open `chrome://inspect` on a computer with the phone
attached over USB.

### Native browser engine (Android)

On Android every tab is a real WebView (`browser/BrowserHost.kt`) drawn under the transparent
React UI; `TouchRoutingLayout` routes touches between the page and the UI. Media is detected from
network traffic (`MediaSniffer`), a Content-Type prober for extension-less URLs (`MediaProber`),
and an injected DOM script (`BrowserDetectorScript`) that also maps MSE `blob:` playback to its
feed URLs. The downloader retries transient failures, replays the page's own
Authorization/Origin headers (`RequestHeaderStore`), and handles HLS (AES-128, BYTERANGE,
EXT-X-MAP) and static DASH including multi-period and SegmentBase/sidx (`media/DashManifest.kt`,
`media/SidxParser.kt`). Downloads resume truly (segments persist across pause / app kill /
reboot via `DownloadQueueStore`), obey Wi-Fi-only, run as a `specialUse` foreground service
(no 6-hour Android 14+ dataSync cap) with per-job pause/cancel notification actions, and can
save into a user-chosen SAF folder. The old iframe path is kept for non-Android builds.

Daily-browsing features implemented natively: file uploads, camera/microphone/geolocation
permission prompts with per-site memory, popups & `target=_blank` as new tabs, SSL interstitial
with proceed option, HTTP basic auth, JS dialogs, real private tabs (WebView multi-profile),
Google sign-in UA workaround + "open in external browser", Safe Browsing, autofill,
picture-in-picture for fullscreen video, print/PDF, Android 15 edge-to-edge insets and full
browsing-data clearing. See `CHANGES-fa.md` for the full list and `docs/TEST-SITES.md` for the
on-device test matrix (the app builds and passes its JVM tests, but has not yet been exercised
on a physical phone).
