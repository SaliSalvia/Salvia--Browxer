# Agent handoff

Read `README.md`, then this file. Everything below describes the code that is actually in this
repository — one Gradle module, Kotlin + Jetpack Compose, no web/TypeScript layer. Numbers here were
taken from the tree at commit `aef3c45` and re-checked with a real build.

If a document points at something missing from this list (`:core:model`, `:media:resolver`,
`RoomDatabase`, `scripts/test-media-sniffer.cjs`, `tsc`, `vite`), the document is stale: those belong
to an earlier incarnation of the project and have no counterpart in the code.

## Repository shape

| Fact | Value |
|---|---|
| Modules (`settings.gradle.kts`) | `:app` only |
| AGP / Kotlin / Gradle | 8.7.3 / 2.0.21 / wrapper 8.11.1 |
| JDK / SDK | JDK 17, compileSdk 35, targetSdk 35, **minSdk 26** |
| Version | `versionName 1.0.0`, `versionCode 10000` (`app/build.gradle.kts`) |
| Sources | 51 Kotlin files, ~18.6 k lines under `app/src/main/java/com/salvia/salviabrowxer` |
| Tests | 5 classes, 26 `@Test` cases under `app/src/test/...` |
| Instrumented tests | **none** — there is no `androidTest` source set |

### Package map

| Path | What lives there |
|---|---|
| `MainActivity.kt`, `SalviaApplication.kt` | Activity + `Application`; WebView host wiring, crash handler install, app-wide init (`NativeProxyClient.initFromWebView`, `MediaPublisher.restoreDownloadTreeUri`) |
| `browser/BrowserHost.kt` | The real browser: one WebView per tab, 8 live tabs (LRU destroys the rest), `shouldInterceptRequest` for detection/blocking, private profile via `ProfileStore`, popups, SSL interstitial, HTTP auth, JS dialogs, print, PiP, fullscreen |
| `browser/BrowserDetectorScript.kt` | Script injected into every frame (DOM, `fetch`/XHR, MSE/blob hooks) |
| `browser/MediaSniffer.kt`, `core/MediaProber.kt` | Network-response sniffing and the bounded Content-Type prober (12 probes per page, one per URL) |
| `browser/TouchRoutingLayout.kt` | Decides whether a touch goes to the page or to the floating Compose UI above it |
| `browser/SitePermissionStore.kt`, `browser/WebDialogs.kt` | Per-site camera/mic/location memory, dialog plumbing |
| `core/AppDomainLogic.kt` | The pure rules: URL/format classification, ad and "suggested video" scoring, main-candidate selection, filename planning, SAF/clipboard rules, `SalviaConstants`, `LockoutPolicy`, `SecurityManager` (PBKDF2-HMAC-SHA256, 120 000 iterations) |
| `core/MediaProbeAndExtract.kt` | Range/HEAD probing, HLS master parsing, metadata extraction, GitHub release lookup for the self-updater |
| `core/NativeProxyClient.kt` | OkHttp stack with the WebView's own cookies/UA; serves media requests and ranged reads |
| `core/RangeChunkPlanner.kt`, `core/RequestHeaderStore.kt` | Multi-connection ranged plans; per-URL request headers (Authorization/Origin/Referer) replayed to CDNs |
| `core/AdBlockHostRules.kt`, `core/BrowserBridgeInjector.kt` | Host-based ad/tracker rules (opt-in), injected-bridge escaping |
| `core/SalviaLog.kt`, `core/CrashReporter.kt` | Bounded in-memory log (`buffer`), crash reports under `files/crash/` (max 10) |
| `data/AppStateRepository.kt`, `data/Models.kt`, `data/Translations.kt` | JSON-backed state (tabs, bookmarks, history, settings), models, EN/FA strings |
| `media/DashManifest.kt`, `media/SidxParser.kt`, `media/HlsKeyParser.kt`, `media/NativeMediaMuxer.kt` | DASH parsing, `sidx` byte-range index, HLS AES-128-CBC keys, muxing of separated video/audio |
| `service/NativeDownloadService.kt` | Foreground download service (`dataSync|specialUse`), job queue, retry/backoff, Wi-Fi hold, notifications |
| `service/DownloadQueueStore.kt`, `service/DownloadBootReceiver.kt` | `download_queue.json` (tmp + rename), resume after reboot |
| `storage/MediaPublisher.kt` | MediaStore publish on API 29+, public-folder copy below, SAF tree output, FileProvider sharing |
| `ui/` | Compose UI: `SalviaApp`, `SalviaViewModel` (the single ViewModel), screens and components, theme |

There is no Room: persistence is JSON in `filesDir` (`download_queue.json`, app state) plus
`SharedPreferences` for the locked-folder verifier.

### Request path (how a download happens)

1. Page loads → `BrowserHost.shouldInterceptRequest` → ad/tracker veto (only when
   `AppSettings.isAdBlockEnabled`, default **false**) → `MediaSniffer` sees media responses and
   captures headers → iframe/segment/blob filtering.
2. The injected script reports DOM/MSE evidence through `BrowserHost$DetectorBridge.report(String,
   String)` — the only JS bridge exposed to web content, and the reason for the keep rule in
   `proguard-rules.pro`.
3. `AppDomainLogic` scores candidates (ads and "suggested/related" clips demoted) and picks the
   one-tap main file; the floating button and media tray read that.
4. Choice → `NativeDownloadService` enqueues a job: `RangeChunkPlanner` for multi-connection files,
   `DashManifest`/`SidxParser`/`HlsKeyParser` for adaptive streams, `NativeMediaMuxer` to join
   separated tracks, `MediaPublisher` to place the result.

## Commands

```bash
./gradlew :app:testDebugUnitTest --no-daemon     # 26 tests, the only automated gate that needs no device
./gradlew :app:assembleDebug --no-daemon         # ~22.6 MB APK
./gradlew :app:assembleRelease --no-daemon       # R8 + resource shrinking, ~4.28 MB APK
```

Always use `--no-daemon` on small machines (2 cores / 3 GB is enough). `gradle.properties` caps the
heap at `-Xmx2g -XX:MaxMetaspaceSize=768m` for that reason; raising it back will OOM the box.

Both APK paths and the 26 green tests were verified locally before the CI gates below were tightened.

## CI — `.github/workflows/build-apk.yml`

Four jobs, in dependency order:

1. **`Unit tests (JVM)`** — `:app:testDebugUnitTest`, a per-suite count in the job summary, JUnit XML
   and HTML reports uploaded, Gradle errors lifted into error annotations.
2. **`Release APK (R8 + shrink)`** — needs the tests. Runs `:app:assembleRelease`, then *fails* if the
   APK is missing, if `mapping.txt` is empty, if `BrowserHost$DetectorBridge` or `report(` is absent
   from the mapping (that would mean the bridge was renamed or stripped and detection dies on
   device), or if `apksigner verify` does not pass. Publishes the APK and the mapping file.
3. **`Debug APK`** — needs the tests; the unminified build to install day to day.
4. **`Publish GitHub Release`** — tag pushes only, after both APKs exist.

What CI proves: the code compiles, the pure Kotlin rules and parsers still pass, and the app survives
minification with its JS bridge intact. What it does **not** prove: anything that needs a running
Android runtime — WebView interception, the transparent-WebView touch routing, MediaStore/SAF output,
codecs, notification actions, PiP.

## Open gates

- **No device run is recorded anywhere in this repo.** Nothing about the browser layer on real
  hardware is evidence; `docs/TEST-SITES.md` is the checklist to run.
- **No instrumented tests exist.** MediaStore publishing, SAF moves, muxing and AES-128 segment
  decryption are only exercised by hand today.
- **Signing.** Without `SIGNING_KEYSTORE_BASE64` + `SIGNING_KEYSTORE_PASSWORD` + `SIGNING_KEY_ALIAS`
  (+ optional `SIGNING_KEY_PASSWORD`) the release APK is signed with the committed
  `app/debug.keystore` (`androiddebugkey` / `android`). That is deliberate — APKs from successive CI
  runs must be installable over each other — but it is **not** Play-Store ready.
- **Lint is advisory** (`abortOnError = false`, `checkReleaseBuilds = false`) so a style warning can
  never block an APK. Standing deprecation warnings: `BrowserHost` `clearFormData`/`saveFormData`,
  `CrashReporter`, `SalviaViewModel:369` ("condition is always true"), `MediaTraySheet:308`
  (`PlaylistPlay`). They were left on purpose, not overlooked.
- The only R8 warning in a clean release build is the harmless `libandroidx.graphics.path.so`
  cannot-be-stripped notice.
- `android:usesCleartextTraffic="true"` with `network_security_config` is the current posture (the
  browser must be able to open plain HTTP). Changing it is a product decision, not a cleanup.
- Self-update reads `SalviaConstants.RELEASES_API_URL`; it must keep pointing at the repository that
  actually publishes releases, or "update available" silently stops appearing.

## Known document drift (fix when touching those files)

- `docs/phase-1-status.md` still describes Room, `:core:model`/`:media:resolver`/`:media:detector`
  modules, a `BrowserViewModel` and an emulator workflow — none of which exist here.
- `docs/analysis-fa.md`, `docs/world-class-roadmap.md`, `docs/inshot-parity-fa.md` predate the Kotlin
  rewrite; treat their file/class names as historical.

## Standing boundaries

No site-specific extractors, no token/signature harvesting, no yt-dlp, no DRM bypass, no encrypted-HLS
key recovery beyond a key the page itself requested, no live-stream recording, no detection probes
outside what WebView actually loaded. Keep GPL-3.0, the applicationId `com.salvia.salviabrowxer`,
`minSdk 26`, the foreground-download-service architecture, resume, the 8 live tabs, the blob bridge,
and the EN/FA string parity (every key in both `values/strings.xml` and `values-fa/strings.xml`).

A capability lands with the test or the device run that proves it, or it does not land — a claim in
the README without evidence behind it is a bug in the documentation.

Version stays `1.0.0` / `10000` until the acceptance matrix passes; bump `SalviaConstants.APP_VERSION`
and `versionCode` together when it does.
