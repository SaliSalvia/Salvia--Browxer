# SalviaBrowxer — Test Matrix (real-device checklist)

Everything in this list must be run **on a real phone**; the emulator hides touch, codec and
memory problems. Tick an item only when it behaved correctly. Keep notes of anything odd —
the touch layer (`TouchRoutingLayout` + transparent React UI over the page WebView) and
fullscreen video are the highest-risk parts of this architecture.

## 1. Core browsing sanity (Phase 0)

| # | Site / action | What to verify |
|---|---|---|
| 1 | `example.com` | Page loads under the transparent UI; address bar readable; scroll smooth |
| 2 | Wikipedia (en + fa) | RTL rendering, text zoom from settings, find-in-page |
| 3 | BBC.com / CNN.com | Heavy news page: load time, RAM, no renderer crash when switching 5+ tabs |
| 4 | Google search → follow 3 results | Search engine works, suggestions, back/forward stack |
| 5 | DuckDuckGo / Bing | Alternative engines from settings |
| 6 | Any login form (e.g. GitHub) | **File upload** (`<input type=file>`) opens the picker; photo + document |
| 7 | webqr.com or similar | **Camera permission** prompt appears once, choice is remembered per site |
| 8 | onlinemictest.com | **Microphone** prompt + actually records |
| 9 | browserleaks.com/geo | **Geolocation** prompt; "remember" respected; deny works |
| 10 | any site with `target=_blank` (Wikipedia refs) | Opens a **new tab**, not the same tab |
| 11 | site with `window.open` pop-up (popuptest.com) | Blocked without gesture; allowed with gesture |
| 12 | self-signed cert site (e.g. `https://self-signed.badssl.com`) | **SSL warning** dialog; "proceed" works; back is safe |
| 13 | httpbin.org/basic-auth | **HTTP auth dialog** with user/pass |
| 14 | javascript.info/alert or w3schools tryit | **alert / confirm / prompt** dialogs render above the UI |
| 15 | A PDF link (e.g. mozilla pdf sample) | **General file download** to chosen folder + notification |
| 16 | An APK link (fdroid.org) | Download + tap-to-open from notification |
| 17 | Play a YouTube video, swipe home during fullscreen | **Picture-in-Picture** activates |
| 18 | Rotate device during video fullscreen | No crash, layout intact |
| 19 | Back button on: dialog → sheet → tab history → home | Each level closes one thing, then background |
| 20 | Open a link from WhatsApp/Telegram | App registered as browser candidate, opens it in a tab |
| 21 | `intent://` link (Play Store badge) | Confirm dialog → opens Play Store |
| 22 | Google sign-in on any site | If blocked, menu → **Open in external browser**; UA workaround attempt |
| 23 | Private tab: log in somewhere, close tab, reopen site | Session not kept (multi-profile WebView) |
| 24 | Android 15 device: cold start | Nothing under status/navigation bars (edge-to-edge insets applied) |
| 25 | Keyboard on an input field | Page resizes / input not covered by IME |

## 2. Media detection (Phase 2)

| # | Site | Expected |
|---|---|---|
| 26 | Blender open movies ( Tears of Steel page ) | MP4 + WebM candidates with sizes |
| 27 | Mux HLS test stream (`test-streams.mux.dev`) | `.m3u8` detected; variants listed with quality labels |
| 28 | Akamai DASH test (`.akamaized.net` mpd) | DASH manifest detected, video+audio merged |
| 29 | Unified Streaming demo page | Both HLS + DASH offered |
| 30 | Any JW Player news site | Manifest detected from network traffic |
| 31 | YouTube (in-page playback) | MSE hook reports feeds; DASH via sidx downloads (video only is OK for now) |
| 32 | Instagram / Twitter / Reddit video post | MP4 or HLS detected, downloads with correct Referer |
| 33 | Site with extension-less media URL (e.g. `/getvideo?id=1`) | Content-Type probe offers the media |
| 34 | A site with subtitles track (`.vtt` in HLS) | Subtitle rendition appears with a language label |

## 3. Download engine (Phase 3)

| # | Scenario | Expected |
|---|---|---|
| 35 | Direct MP4, 4 connections | Progress, speed, ETA; final file plays in the in-app player |
| 36 | HLS (AES-128) stream | Segments decrypt, output MP4 plays |
| 37 | Pause a 50 % done HLS download, resume | **Resumes from disk**, does not restart (watch byte counter) |
| 38 | Kill the app during a download, reopen | Download auto-resumes, UI shows it running |
| 39 | Reboot the phone during a download (Android ≤13) | Download resumes (14+ resumes on next app open) |
| 40 | Wi-Fi-only ON + switch to mobile data | Job pauses with "Waiting for Wi-Fi"; resumes on Wi-Fi |
| 41 | Storage nearly full | Clear "insufficient storage" error, no partial junk |
| 42 | Notification buttons | Pause / cancel work from the notification |
| 43 | Download > 6 h on Android 14+ | `specialUse` FGS keeps it alive (verify in battery settings) |
| 44 | VP9 DASH stream | Output falls back to `.webm` (WebM muxer) or honest raw copy |
| 45 | SAF folder chosen in settings | Files land in the chosen folder |

## 4. Reference test streams

- HLS: `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8` (multi-bitrate + subtitles)
- HLS AES-128: `https://test-streams.mux.dev/pts_shift/master.m3u8`
- DASH: `https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd`
- DASH multi-period: `https://media.axprod.net/TestVectors/v7-Clear/MultiPeriod_Manifest.mpd`
- MP4: `https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4`

Log every failure with: device model, Android version, WebView version (`chrome://inspect`),
site, and what happened.
