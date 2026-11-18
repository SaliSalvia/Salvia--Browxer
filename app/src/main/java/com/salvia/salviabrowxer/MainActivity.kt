package com.salvia.salviabrowxer

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.salvia.salviabrowxer.bridge.SalviaNativeBridge
import com.salvia.salviabrowxer.browser.BrowserHost
import com.salvia.salviabrowxer.browser.BrowserHostDelegate
import com.salvia.salviabrowxer.browser.TouchRoutingLayout
import com.salvia.salviabrowxer.core.NativeProxyClient
import com.salvia.salviabrowxer.service.NativeDownloadBus
import com.salvia.salviabrowxer.service.NativeDownloadService
import org.json.JSONObject

class MainActivity : AppCompatActivity(), BrowserHostDelegate {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var browserHost: BrowserHost? = null
    private var contentHolder: FrameLayout? = null

    /** External URL that arrived before the React UI was ready (cold start via link / share). */
    private var pendingIncomingUrl: String? = null
    /** True once the React UI page finished loading and can receive `__salviaOpenUrl`. */
    private var isUiLoaded = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private var permissionResultCallback: ((Boolean) -> Unit)? = null
    private val androidPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = permissionResultCallback
            permissionResultCallback = null
            cb?.invoke(granted)
        }

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            val uris: Array<Uri>? = result.data?.let { data ->
                val clip = data.clipData
                when {
                    clip != null -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }.toTypedArray()
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            }
            if (uris == null && result.resultCode != RESULT_OK) {
                callback?.onReceiveValue(null)
            } else {
                callback?.onReceiveValue(uris ?: arrayOf())
            }
        }

    private var folderPickCallback: ((Uri?) -> Unit)? = null
    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val cb = folderPickCallback
            folderPickCallback = null
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (ignored: Exception) {
                }
                getSharedPreferences(DOWNLOAD_PREFS, MODE_PRIVATE).edit().putString(PREF_DOWNLOAD_TREE, uri.toString()).apply()
                dispatchToUi(
                    "__salviaNativeBrowserEvent",
                    JSONObject().put("type", "download-folder-changed").put("uri", uri.toString())
                )
            }
            cb?.invoke(uri)
        }

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A link/share can be the reason this process exists; remember it until the UI is up.
        pendingIncomingUrl = extractUrlFromIntent(intent)

        // Real WebView UA (minus the `; wv` marker) for every request this app makes itself.
        NativeProxyClient.initFromWebView(this)

        window.decorView.setBackgroundColor(Color.BLACK)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Opt in to Google Safe Browsing for every WebView of the app.
        try {
            WebViewCompat.startSafeBrowsing(this, null)
        } catch (ignored: Exception) {
        }

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Layering, bottom to top: [contentHolder: native browser pages + the (transparent)
        // React UI] and a full-screen holder for page video. TouchRoutingLayout decides who gets
        // each gesture. contentHolder is padded by system bar / IME insets so that on Android 15
        // (enforced edge-to-edge) neither layer is hidden behind the status or navigation bar,
        // while fullscreen video keeps covering the whole screen.
        val root = TouchRoutingLayout(this)
        val contentHolder = FrameLayout(this)
        this.contentHolder = contentHolder
        val browserContainer = FrameLayout(this)
        browserContainer.visibility = View.GONE
        val fullscreenHolder = FrameLayout(this)
        fullscreenHolder.setBackgroundColor(Color.BLACK)
        fullscreenHolder.visibility = View.GONE

        browserHost = BrowserHost(this, browserContainer, fullscreenHolder, root, this) { payload ->
            dispatchToUi("__salviaNativeBrowserEvent", payload)
        }

        val nativeBridge = SalviaNativeBridge(this, { browserHost }) { handler, payload ->
            runOnUiThread { dispatchToUi(handler, payload) }
        }

        webView = WebView(this).apply {
            // Transparent so the native page underneath shows through the browser viewport.
            // Behind everything the window background is black, so other screens look the same.
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(nativeBridge, "SalviaNative")

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Deliver the bridge arm token to the trusted main frame (see
                    // SalviaNativeBridge.injectArmToken). Runs on the UI page only.
                    if (url?.startsWith("https://appassets.androidplatform.net") == true) {
                        nativeBridge.injectArmToken(view ?: return)
                        // Now the React layer can receive a URL that arrived before it was ready.
                        isUiLoaded = true
                        pendingIncomingUrl?.let { pending ->
                            pendingIncomingUrl = null
                            val quoted = JSONObject.quote(pending)
                            webView.evaluateJavascript(
                                "window.__salviaOpenUrl && window.__salviaOpenUrl($quoted);", null
                            )
                        }
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val req = request ?: return null
                    val uri = req.url ?: return null
                    if (uri.host == "appassets.androidplatform.net") {
                        val path = uri.path.orEmpty()
                        if (path.startsWith("/api/")) {
                            return NativeProxyClient.handleApiRequest(this@MainActivity, req)
                        }
                        // The bundled CSS refers to fonts as absolute "/fonts/..." URLs, but the web
                        // bundle lives under /assets/www/, so map those root-level paths there.
                        if (path.startsWith("/fonts/")) {
                            val mapped = uri.buildUpon().path("/assets/www$path").build()
                            return assetLoader.shouldInterceptRequest(mapped)
                        }
                        return assetLoader.shouldInterceptRequest(uri)
                    }
                    return super.shouldInterceptRequest(view, req)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    // Without this the app process dies whenever the WebView renderer is killed
                    // (low memory) or crashes. Recreate the activity instead — but never more
                    // than twice within a minute, or a persistently crashing page would put the
                    // app into an endless restart loop.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRenderCrashAt > 60_000L) renderCrashCount.set(0)
                    val count = renderCrashCount.incrementAndGet()
                    lastRenderCrashAt = now
                    if (count > 2) {
                        finish()
                        return true
                    }
                    runOnUiThread { recreate() }
                    return true
                }
            }
        }

        contentHolder.addView(
            browserContainer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        contentHolder.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            contentHolder,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            fullscreenHolder,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.uiView = webView
        root.browserView = browserContainer
        root.fullscreenView = fullscreenHolder
        root.onBrowserGestureStart = { browserHost?.focusActive() }
        root.onUiGestureStart = { webView.requestFocus() }
        applyWindowInsets()
        setContentView(root)

        NativeDownloadBus.listener = { payload ->
            runOnUiThread { dispatchToUi("__salviaNativeDownloadEvent", payload) }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (browserHost?.exitFullscreen() == true) return
                    webView.evaluateJavascript(
                        "(function(){ return window.__salviaNativeBack ? window.__salviaNativeBack() : false; })()"
                    ) { result ->
                        if (result != "true") {
                            moveTaskToBack(true)
                        }
                    }
                }
            }
        )

        requestNotificationPermissionIfNeeded()

        // Downloads that were running when the app was killed (or when the device rebooted on
        // Android versions where the boot receiver is not allowed to start the service) resume
        // here, silently and without user interaction.
        NativeDownloadService.resumeRunningJobs(applicationContext)

        val initialUrl = intent?.dataString
        val startUrl = if (!initialUrl.isNullOrBlank()) {
            "https://appassets.androidplatform.net/assets/www/index.html?autostart=1&open=${
                java.net.URLEncoder.encode(initialUrl, "UTF-8")
            }"
        } else {
            "https://appassets.androidplatform.net/assets/www/index.html?autostart=1"
        }
        webView.loadUrl(startUrl)
    }

    /**
     * Applies status bar / navigation bar / keyboard insets as padding of the content area so the
     * UI and the page never sit under them, on Android 15's enforced edge-to-edge as well as on
     * older versions. Fullscreen video lives outside this padded area and still covers everything.
     */
    private fun applyWindowInsets() {
        val holder = contentHolder ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(holder) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            v.setPadding(bars.left, bars.top, bars.right, bottom)
            // TouchRoutingLayout compares gestures in root-view (full screen) coordinates, but
            // the UI WebView's CSS space starts inside this padding. Publish the offsets so
            // routing lands on what the user actually tapped.
            (v.parent as? TouchRoutingLayout)?.let { routing ->
                routing.uiOffsetX = v.paddingLeft
                routing.uiOffsetY = v.paddingTop
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // When the user swipes home while a page video is fullscreen, follow as PiP like Chrome.
        if (browserHost?.isFullscreenActive() == true) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val metrics = windowManager.currentWindowMetrics
                    val width = metrics.bounds.width().toFloat()
                    val height = metrics.bounds.height().toFloat()
                    val rational = if (height > width) Rational(9, 16) else Rational(16, 9)
                    enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(rational)
                            .setSourceRectHint(android.graphics.Rect(0, 0, width.toInt(), height.toInt()))
                            .build()
                    )
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /** Calls `window.<handler>(payload)` inside the React UI. Must run on the main thread. */
    private fun dispatchToUi(handler: String, payload: JSONObject) {
        if (!::webView.isInitialized) return
        val encoded = JSONObject.quote(payload.toString())
        webView.evaluateJavascript(
            "window.$handler && window.$handler(JSON.parse($encoded));",
            null
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingUrl(intent)
    }

    /**
     * URL arriving from outside the app: either a VIEW link (default-browser flow) or the first
     * http(s) link inside text shared from another app (ACTION_SEND / ACTION_PROCESS_TEXT).
     * Returns the URL, or null when the intent carries nothing usable.
     */
    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val data = intent.dataString?.trim()
        if (!data.isNullOrBlank() && isHttpUrl(data)) return data.take(2048)
        val text = (
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            )?.take(8192) ?: return null
        val match = Regex("(?i)https?://[^\\s\"'<>]+").find(text)?.value
            ?.trimEnd('.', ',', ';', ')', ']', '}', '»', '«') ?: return null
        return if (isHttpUrl(match)) match.take(2048) else null
    }

    private fun isHttpUrl(raw: String): Boolean = try {
        val scheme = android.net.Uri.parse(raw).scheme?.lowercase()
        scheme == "http" || scheme == "https"
    } catch (ignored: Exception) {
        false
    }

    /** Forwards an external URL into the React UI; keeps it for later when the UI is not up yet. */
    private fun consumeIncomingUrl(intent: Intent?) {
        val url = extractUrlFromIntent(intent) ?: return
        if (::webView.isInitialized && isUiLoaded) {
            val quoted = JSONObject.quote(url)
            webView.evaluateJavascript("window.__salviaOpenUrl && window.__salviaOpenUrl($quoted);", null)
        } else {
            pendingIncomingUrl = url
        }
    }

    override fun onDestroy() {
        NativeDownloadBus.listener = null
        browserHost?.destroyAll()
        browserHost = null
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ BrowserHostDelegate

    override fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        allowMultiple: Boolean
    ): Boolean {
        return try {
            fileChooserCallback = callback
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = normalizeAcceptType(acceptTypes)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }
            fileChooserLauncher.launch(intent)
            true
        } catch (ignored: Exception) {
            fileChooserCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    private fun normalizeAcceptType(acceptTypes: Array<String>): String {
        val cleaned = acceptTypes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val interesting = cleaned.filter { it.contains("/") || it == "audio" || it == "video" || it == "image" }
        return when {
            interesting.any { it.startsWith("video/") || it == "video" } &&
                interesting.none { it.startsWith("image/") || it == "image" } -> "video/*"
            interesting.any { it.startsWith("audio/") || it == "audio" } &&
                interesting.none { it.startsWith("image/") || it == "image" } -> "audio/*"
            interesting.any { it.startsWith("image/") || it == "image" } &&
                interesting.none { it.startsWith("video/") || it == "video" || it.startsWith("audio/") || it == "audio" } -> "image/*"
            else -> "*/*"
        }
    }

    override fun hasAndroidPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun requestAndroidPermission(permission: String, onResult: (Boolean) -> Unit) {
        if (hasAndroidPermission(permission)) {
            onResult(true)
            return
        }
        if (permission == Manifest.permission.ACCESS_FINE_LOCATION &&
            hasAndroidPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            onResult(true)
            return
        }
        permissionResultCallback = onResult
        androidPermissionLauncher.launch(permission)
    }

    override fun openExternalIntent(intent: Intent, url: String): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (ignored: Exception) {
            try {
                // Fall back to the Play Store page for market-style intents.
                val fallback = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/search?q=${Uri.encode(url)}&c=apps")
                )
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallback)
                true
            } catch (ignored2: Exception) {
                Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    // ------------------------------------------------------------------ helpers for the bridge

    /** Opens the SAF folder picker and remembers the chosen download location. */
    fun pickDownloadFolder(onDone: ((Uri?) -> Unit)? = null) {
        folderPickCallback = onDone
        try {
            folderPickerLauncher.launch(null)
        } catch (ignored: Exception) {
            folderPickCallback = null
            onDone?.invoke(null)
        }
    }

    fun downloadFolderName(): String {
        val uriString = getSharedPreferences(DOWNLOAD_PREFS, MODE_PRIVATE).getString(PREF_DOWNLOAD_TREE, null) ?: return ""
        return try {
            val uri = Uri.parse(uriString)
            val docId = uri.lastPathSegment ?: return ""
            // "primary:Download/foo" -> "Download/foo"
            docId.substringAfter(':')
        } catch (ignored: Exception) {
            ""
        }
    }

    fun downloadFolderUri(): Uri? {
        val uriString = getSharedPreferences(DOWNLOAD_PREFS, MODE_PRIVATE).getString(PREF_DOWNLOAD_TREE, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (ignored: Exception) {
            null
        }
    }

    fun clearDownloadFolder() {
        getSharedPreferences(DOWNLOAD_PREFS, MODE_PRIVATE).edit().remove(PREF_DOWNLOAD_TREE).apply()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private companion object {
        const val PREF_DOWNLOAD_TREE = "download_tree_uri"
        const val DOWNLOAD_PREFS = "salvia_prefs"

        /** Guards against an endless activity-recreate loop when the renderer keeps dying. */
        private val renderCrashCount = java.util.concurrent.atomic.AtomicInteger(0)
        private var lastRenderCrashAt = 0L
    }
}
