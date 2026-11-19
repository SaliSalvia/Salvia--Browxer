package com.salvia.salviabrowxer.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.salvia.salviabrowxer.core.AdBlockHostRules
import com.salvia.salviabrowxer.core.MediaProber
import com.salvia.salviabrowxer.core.NativeProxyClient
import com.salvia.salviabrowxer.core.RequestHeaderStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real WebView browsing for SalviaBrowxer.
 *
 * One WebView per tab (least recently used ones are destroyed beyond [MAX_TABS]) lives in
 * [container], underneath the transparent React UI. Everything the page loads passes through
 * [BrowserClient.shouldInterceptRequest], where media is detected from the network traffic, media
 * URLs without extensions are Content-Type probed, request headers for signed CDNs are captured
 * and ad / tracker requests are blocked. Events are sent to the UI through [emit] (always on the
 * main thread, in order).
 *
 * Beyond plain navigation this class implements what a daily-driver browser needs: file uploads,
 * camera / microphone / location permission prompts with per-site memory, popups and
 * target=_blank as new tabs, SSL interstitials with a proceed option, HTTP authentication,
 * JavaScript dialogs, real private tabs (separate WebView profile), printing and darkening.
 *
 * All public methods must be called on the main thread.
 */
class BrowserHost(
    private val activity: Activity,
    private val container: FrameLayout,
    private val fullscreenHolder: FrameLayout,
    private val routing: TouchRoutingLayout? = null,
    private val delegate: BrowserHostDelegate,
    private val emit: (JSONObject) -> Unit
) {

    private class BrowserTab(val id: String, val webView: WebView, val isPrivate: Boolean) {
        @Volatile var desktop: Boolean = false
        @Volatile var adBlock: Boolean = true
        @Volatile var currentUrl: String = ""
        @Volatile var lastProgress: Int = 0
        @Volatile var lastFindQuery: String = ""
        @Volatile var lastAccess: Long = System.currentTimeMillis()
        @Volatile var reportWindowStart: Long = 0L
        @Volatile var googleLoginUaOverride: Boolean = false
        val reportCount = AtomicInteger(0)
        val blockedCount = AtomicInteger(0)
        val seenMedia: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    }

    private val tabs = LinkedHashMap<String, BrowserTab>()
    private var activeId: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val density: Float = activity.resources.displayMetrics.density
    private val bounds = Rect()

    private var jsEnabled = true
    private var cookiesEnabled = true
    private var textZoom = 100
    private var darkPages = false
    @Volatile private var cleartextAllowed = false

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    /** Hosts the user explicitly allowed through the SSL warning during this session. */
    private val sslAllowedHosts = Collections.synchronizedSet(HashSet<String>())

    private val supportsDocumentStart: Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    } catch (ignored: Exception) {
        false
    }

    private val supportsMultiProfile: Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    } catch (ignored: Exception) {
        false
    }

    private val supportsAlgorithmicDarkening: Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
    } catch (ignored: Exception) {
        false
    }

    private val playlistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "salvia-playlist-enrich").apply { isDaemon = true }
    }

    init {
        CookieManager.getInstance().setAcceptCookie(true)
    }

    // ------------------------------------------------------------------ public API

    fun show(tabId: String, url: String, desktop: Boolean, adBlock: Boolean, isPrivate: Boolean) {
        var tab: BrowserTab? = tabs[tabId]
        if (tab == null) {
            tab = createTab(tabId, desktop, adBlock, isPrivate)
            if (url.isNotBlank()) {
                tab.currentUrl = url
                tab.webView.loadUrl(url)
            }
        } else {
            tab.adBlock = adBlock
            if (tab.desktop != desktop) applyDesktop(tab, desktop)
        }
        activate(tab)
    }

    fun hasTab(tabId: String): Boolean = tabs.containsKey(tabId)

    /** Loads a URL in a tab without changing what is on screen. */
    fun load(tabId: String, url: String, desktop: Boolean, adBlock: Boolean, isPrivate: Boolean) {
        var tab: BrowserTab? = tabs[tabId]
        if (tab == null) {
            tab = createTab(tabId, desktop, adBlock, isPrivate)
        }
        tab.adBlock = adBlock
        if (tab.desktop != desktop) applyDesktop(tab, desktop)
        if (url.isNotBlank()) {
            tab.currentUrl = url
            tab.webView.loadUrl(url)
        }
    }

    fun canGoBack(tabId: String): Boolean = tabs[tabId]?.webView?.canGoBack() == true

    fun canGoForward(tabId: String): Boolean = tabs[tabId]?.webView?.canGoForward() == true

    fun goBack(tabId: String): Boolean {
        val wv = tabs[tabId]?.webView ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun goForward(tabId: String): Boolean {
        val wv = tabs[tabId]?.webView ?: return false
        return if (wv.canGoForward()) {
            wv.goForward()
            true
        } else {
            false
        }
    }

    fun setAdBlock(tabId: String, adBlock: Boolean) {
        tabs[tabId]?.adBlock = adBlock
    }

    fun reload(tabId: String) {
        tabs[tabId]?.webView?.reload()
    }

    fun stop(tabId: String) {
        tabs[tabId]?.webView?.stopLoading()
    }

    fun setDesktop(tabId: String, desktop: Boolean) {
        val tab = tabs[tabId] ?: return
        if (tab.desktop == desktop) return
        applyDesktop(tab, desktop)
        tab.webView.reload()
    }

    fun destroy(tabId: String) {
        destroyTab(tabId)
    }

    fun hide() {
        routing?.browserVisible = false
        container.visibility = View.GONE
        val id = activeId
        if (id != null) tabs[id]?.webView?.onPause()
    }

    fun setBounds(leftCss: Float, topCss: Float, widthCss: Float, heightCss: Float) {
        if (widthCss <= 0f || heightCss <= 0f) return
        val left = (leftCss * density).toInt()
        val top = (topCss * density).toInt()
        val width = (widthCss * density).toInt()
        val height = (heightCss * density).toInt()
        bounds.set(left, top, left + width, top + height)
        routing?.browserRect = Rect(bounds)
        for (tab in tabs.values) {
            tab.webView.layoutParams = boundsLayoutParams()
        }
    }

    fun setInteraction(shield: Boolean, hotRectsJson: String) {
        routing?.shield = shield
        val list = ArrayList<Rect>()
        try {
            val arr = JSONArray(hotRectsJson)
            for (i in 0 until arr.length()) {
                val r = arr.optJSONArray(i) ?: continue
                if (r.length() < 4) continue
                list.add(
                    Rect(
                        (r.optDouble(0) * density).toInt(),
                        (r.optDouble(1) * density).toInt(),
                        (r.optDouble(2) * density).toInt(),
                        (r.optDouble(3) * density).toInt()
                    )
                )
            }
        } catch (ignored: Exception) {
        }
        routing?.hotRects = list
    }

    fun find(tabId: String, query: String, backwards: Boolean) {
        val tab = tabs[tabId] ?: return
        val q = query.trim()
        if (q.isEmpty()) {
            tab.webView.clearMatches()
            tab.lastFindQuery = ""
            post(JSONObject().put("type", "find-result").put("tabId", tabId).put("matches", 0).put("active", 0))
            return
        }
        if (q != tab.lastFindQuery) {
            tab.lastFindQuery = q
            tab.webView.findAllAsync(q)
        } else {
            tab.webView.findNext(!backwards)
        }
    }

    fun applySettings(
        javaScript: Boolean,
        cookies: Boolean,
        zoomPercent: Int,
        darkPagesAllowed: Boolean,
        allowCleartext: Boolean = cleartextAllowed
    ) {
        jsEnabled = javaScript
        cookiesEnabled = cookies
        textZoom = zoomPercent.coerceIn(50, 200)
        darkPages = darkPagesAllowed
        cleartextAllowed = allowCleartext
        if (!activeTabIsPrivate()) {
            CookieManager.getInstance().setAcceptCookie(cookies)
        }
        for (tab in tabs.values) {
            applyTabSettings(tab)
        }
    }

    fun focusActive() {
        val id = activeId ?: return
        tabs[id]?.webView?.requestFocus()
    }

    fun isFullscreenActive(): Boolean = customView != null

    fun exitFullscreen(): Boolean {
        val view = customView ?: return false
        fullscreenHolder.removeView(view)
        fullscreenHolder.visibility = View.GONE
        customView = null
        val cb = customCallback
        customCallback = null
        try {
            cb?.onCustomViewHidden()
        } catch (ignored: Exception) {
        }
        setSystemBarsHidden(false)
        return true
    }

    fun destroyAll() {
        exitFullscreen()
        for (id in ArrayList(tabs.keys)) destroyTab(id)
        wipePrivateProfileIfIdle()
    }

    /** Prints / saves the given tab as PDF through the system print dialog. */
    fun printTab(tabId: String) {
        val tab = tabs[tabId] ?: return
        try {
            val printManager = activity.getSystemService(Activity.PRINT_SERVICE) as? PrintManager ?: return
            val jobName = (tab.webView.title ?: "page").take(60).ifBlank { "SalviaBrowxer page" }
            val adapter: PrintDocumentAdapter = tab.webView.createPrintDocumentAdapter(jobName)
            val attrs = android.print.PrintAttributes.Builder().build()
            printManager.print(jobName, adapter, attrs)
        } catch (ignored: Exception) {
        }
    }

    /** Wipes every piece of browsing data the engine keeps (cookies, storage, cache, form data). */
    fun clearBrowsingData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().removeSessionCookies(null)
        } catch (ignored: Exception) {
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (ignored: Exception) {
        }
        try {
            val db = WebViewDatabase.getInstance(activity)
            db.clearFormData()
            db.clearHttpAuthUsernamePassword()
        } catch (ignored: Exception) {
        }
        try {
            GeolocationPermissions.getInstance().clearAll()
        } catch (ignored: Exception) {
        }
        for (tab in tabs.values) {
            try {
                tab.webView.clearCache(true)
                tab.webView.clearHistory()
            } catch (ignored: Exception) {
            }
        }
        SitePermissionStore.clear(activity)
        wipePrivateProfileIfIdle()
    }

    // ------------------------------------------------------------------ internals

    private fun activeTabIsPrivate(): Boolean = activeId?.let { tabs[it]?.isPrivate } == true

    private fun applyTabSettings(tab: BrowserTab) {
        val s = tab.webView.settings
        s.javaScriptEnabled = jsEnabled
        s.textZoom = textZoom
        if (tab.isPrivate && !supportsMultiProfile) {
            // Fallback private mode on old WebViews: no third-party cookies, no storage writes.
            CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, false)
            s.domStorageEnabled = false
            s.cacheMode = WebSettings.LOAD_NO_CACHE
            s.saveFormData = false
        } else {
            CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, cookiesEnabled)
            s.domStorageEnabled = true
            s.cacheMode = WebSettings.LOAD_DEFAULT
            s.saveFormData = true
        }
        if (supportsAlgorithmicDarkening) {
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, darkPages)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun boundsLayoutParams(): FrameLayout.LayoutParams {
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val lp = FrameLayout.LayoutParams(bounds.width(), bounds.height())
        lp.leftMargin = bounds.left
        lp.topMargin = bounds.top
        return lp
    }

    private fun applyDesktop(tab: BrowserTab, desktop: Boolean) {
        tab.desktop = desktop
        tab.googleLoginUaOverride = false
        tab.webView.settings.userAgentString =
            if (desktop) NativeProxyClient.DESKTOP_UA else NativeProxyClient.MOBILE_UA
        tab.webView.settings.loadWithOverviewMode = desktop
    }

    private fun activate(tab: BrowserTab) {
        activeId = tab.id
        tab.lastAccess = System.currentTimeMillis()
        for (other in tabs.values) {
            if (other === tab) {
                other.webView.visibility = View.VISIBLE
                other.webView.onResume()
            } else if (other.webView.visibility != View.GONE) {
                other.webView.visibility = View.GONE
                other.webView.onPause()
            }
        }
        container.visibility = View.VISIBLE
        routing?.browserVisible = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(tabId: String, desktop: Boolean, adBlock: Boolean, isPrivate: Boolean): BrowserTab {
        val wv = WebView(activity)
        val tab = BrowserTab(tabId, wv, isPrivate)
        tab.desktop = desktop
        tab.adBlock = adBlock

        if (isPrivate && supportsMultiProfile) {
            // Real private tabs: a separate WebView profile with its own cookie jar and storage.
            try {
                ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE_NAME)
                WebViewCompat.setProfile(wv, PRIVATE_PROFILE_NAME)
            } catch (ignored: Exception) {
            }
        }

        wv.setBackgroundColor(Color.BLACK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES)
        }
        val s = wv.settings
        s.javaScriptEnabled = jsEnabled
        s.mediaPlaybackRequiresUserGesture = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = desktop
        // With multiple windows enabled, target=_blank and window.open arrive in onCreateWindow
        // where they become real new tabs instead of hijacking the current one.
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.textZoom = textZoom
        s.userAgentString = if (desktop) NativeProxyClient.DESKTOP_UA else NativeProxyClient.MOBILE_UA
        applyTabSettings(tab)

        wv.addJavascriptInterface(DetectorBridge(tab), "SalviaBrowserBridge")
        wv.webViewClient = BrowserClient(tab)
        wv.webChromeClient = BrowserChrome(tab)

        wv.setOnLongClickListener { view ->
            val webView = view as? WebView ?: return@setOnLongClickListener false
            val hit = webView.hitTestResult
            val extra = hit.extra?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    if (extra == null) return@setOnLongClickListener false
                    post(
                        JSONObject()
                            .put("type", "context-menu")
                            .put("tabId", tab.id)
                            .put("linkUrl", extra)
                    )
                    true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    if (extra == null) return@setOnLongClickListener false
                    post(
                        JSONObject()
                            .put("type", "context-menu")
                            .put("tabId", tab.id)
                            .put("imageUrl", extra)
                    )
                    true
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val hrefHandler = Handler(Looper.getMainLooper()) { msg ->
                        val data = msg.data
                        val linkUrl = data?.getString("url")?.takeIf {
                            it.startsWith("http://") || it.startsWith("https://")
                        }
                        val imageUrl = data?.getString("src")?.takeIf {
                            it.startsWith("http://") || it.startsWith("https://")
                        } ?: extra
                        val title = data?.getString("title")?.takeIf { it.isNotBlank() }
                        if (linkUrl != null || imageUrl != null) {
                            val obj = JSONObject()
                                .put("type", "context-menu")
                                .put("tabId", tab.id)
                            if (linkUrl != null) obj.put("linkUrl", linkUrl)
                            if (imageUrl != null) obj.put("imageUrl", imageUrl)
                            if (title != null) obj.put("title", title)
                            post(obj)
                        }
                        true
                    }
                    webView.requestFocusNodeHref(hrefHandler.obtainMessage())
                    true
                }
                else -> false
            }
        }

        wv.setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            post(
                JSONObject()
                    .put("type", "download-request")
                    .put("tabId", tab.id)
                    .put("url", url ?: "")
                    .put("mimeType", mimeType ?: "")
                    .put("filename", name ?: "")
                    .put("length", contentLength)
            )
        }
        wv.setFindListener { activeOrdinal, matches, done ->
            if (done) {
                post(
                    JSONObject()
                        .put("type", "find-result")
                        .put("tabId", tab.id)
                        .put("matches", matches)
                        .put("active", if (matches > 0) activeOrdinal + 1 else 0)
                )
            }
        }

        if (supportsDocumentStart) {
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, BrowserDetectorScript.SOURCE, setOf("*"))
            } catch (ignored: Exception) {
            }
        }

        wv.visibility = View.GONE
        container.addView(wv, boundsLayoutParams())
        tabs[tabId] = tab
        evictIfNeeded(tabId)
        return tab
    }

    private fun evictIfNeeded(keepId: String) {
        while (tabs.size > MAX_TABS) {
            var victim: BrowserTab? = null
            for (candidate in tabs.values) {
                if (candidate.id == keepId || candidate.id == activeId) continue
                if (victim == null || candidate.lastAccess < victim.lastAccess) victim = candidate
            }
            val v = victim ?: return
            destroyTab(v.id)
        }
    }

    private fun destroyTab(tabId: String) {
        val tab = tabs.remove(tabId) ?: return
        if (activeId == tabId) activeId = null
        try {
            container.removeView(tab.webView)
            tab.webView.stopLoading()
            tab.webView.removeJavascriptInterface("SalviaBrowserBridge")
            tab.webView.destroy()
        } catch (ignored: Exception) {
        }
        if (tabs.values.none { it.isPrivate }) {
            wipePrivateProfileIfIdle()
        }
    }

    private fun wipePrivateProfileIfIdle() {
        if (!supportsMultiProfile) return
        try {
            val profile = ProfileStore.getInstance().getProfile(PRIVATE_PROFILE_NAME) ?: return
            profile.cookieManager?.removeAllCookies(null)
            profile.webStorage?.deleteAllData()
        } catch (ignored: Exception) {
        }
    }

    private fun post(json: JSONObject) {
        main.post { emit(json) }
    }

    private fun allowReport(tab: BrowserTab): Boolean {
        val now = System.currentTimeMillis()
        if (now - tab.reportWindowStart > 1000L) {
            tab.reportWindowStart = now
            tab.reportCount.set(0)
        }
        return tab.reportCount.incrementAndGet() <= 80
    }

    private fun reportSniffed(tab: BrowserTab, sniffed: SniffedMedia) {
        if (!tab.seenMedia.add(sniffed.url)) return
        if (!allowReport(tab)) return
        val item = JSONObject()
            .put("url", sniffed.url)
            .put("tag", if (sniffed.mimeType.startsWith("audio/")) "audio" else "video")
            .put("type", sniffed.mimeType)
            .put("visible", false)
        post(
            JSONObject()
                .put("type", "media")
                .put("tabId", tab.id)
                .put("pageUrl", tab.currentUrl)
                .put("items", JSONArray().put(item))
        )
    }

    /**
     * When a sniffed URL is an HLS master playlist, fetch it (small text) and report the real
     * variants with quality labels plus subtitle / extra-audio renditions, so the quality sheet
     * shows what the stream actually contains instead of one cryptic URL.
     */
    private fun enrichPlaylist(tab: BrowserTab, url: String) {
        if (!tab.seenMedia.add("playlist:$url")) return
        playlistExecutor.execute {
            try {
                val builder = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", NativeProxyClient.MOBILE_UA)
                val referer = tab.currentUrl.ifBlank { NativeProxyClient.refererHint ?: "" }
                if (referer.startsWith("http")) builder.header("Referer", referer)
                RequestHeaderStore.headersFor(url).forEach { (k, v) -> builder.header(k, v) }

                val text = NativeProxyClient.httpClient.newCall(builder.build()).execute().use { res ->
                    if (res.isSuccessful) res.body?.string() else null
                } ?: return@execute

                val variants = MediaSniffer.parseMasterVariants(text, url)
                val renditions = MediaSniffer.parseRenditions(text, url)

                val items = JSONArray()
                for (variant in variants) {
                    if (variant.url == url) continue
                    val label = buildString {
                        if (variant.height > 0) append("${variant.height}p")
                        else if (variant.width > 0) append("${variant.width}w")
                        else append("HLS")
                        append(" · ").append(String.format(java.util.Locale.US, "%.1f Mbps", variant.bandwidth / 1_000_000.0))
                    }
                    items.put(
                        JSONObject()
                            .put("url", variant.url)
                            .put("tag", "video")
                            .put("type", "application/vnd.apple.mpegurl")
                            .put("visible", false)
                            .put("label", label)
                            .put("kind", "variant")
                            .put("width", variant.width)
                            .put("height", variant.height)
                    )
                }
                for (rendition in renditions) {
                    if (rendition.uri.isNullOrBlank() || rendition.uri == url) continue
                    if (rendition.type == "SUBTITLES") {
                        val label = rendition.name ?: rendition.language ?: "Subtitle"
                        items.put(
                            JSONObject()
                                .put("url", rendition.uri)
                                .put("tag", "subtitle")
                                .put("type", "application/vnd.apple.mpegurl")
                                .put("visible", false)
                                .put("label", label)
                                .put("kind", "subtitle")
                                .put("language", rendition.language ?: "")
                        )
                    } else if (rendition.type == "AUDIO") {
                        val label = rendition.name ?: rendition.language ?: "Audio"
                        items.put(
                            JSONObject()
                                .put("url", rendition.uri)
                                .put("tag", "audio")
                                .put("type", "application/vnd.apple.mpegurl")
                                .put("visible", false)
                                .put("label", label)
                                .put("kind", "audio-track")
                                .put("language", rendition.language ?: "")
                        )
                    }
                }
                if (items.length() > 0) {
                    post(
                        JSONObject()
                            .put("type", "media")
                            .put("tabId", tab.id)
                            .put("pageUrl", tab.currentUrl)
                            .put("items", items)
                    )
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            HashMap<String, String>(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        try {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (hidden) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (ignored: Exception) {
        }
    }

    private fun hostOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            java.net.URI(raw).host
        } catch (ignored: Exception) {
            null
        }
    }

    private fun isGoogleLoginHost(host: String?): Boolean {
        return host == "accounts.google.com" || host == "accounts.youtube.com"
    }

    // ------------------------------------------------------------------ clients

    private inner class BrowserClient(private val tab: BrowserTab) : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val req = request ?: return null
            val url = req.url.toString()
            val sniffed = MediaSniffer.classify(url)
            if (sniffed != null) {
                // Remember the headers the page itself used (Authorization, Origin, UA) so the
                // downloader can replay them for token-signed CDNs.
                RequestHeaderStore.capture(url, req.requestHeaders)
                main.post { reportSniffed(tab, sniffed) }
                if (sniffed.mimeType == "application/vnd.apple.mpegurl") {
                    main.post { enrichPlaylist(tab, url) }
                }
            } else if (!req.isForMainFrame) {
                // Extension-less media URLs: probe a hint-filtered subset by Content-Type.
                if (MediaProber.shouldProbe(url)) {
                    val referer = tab.currentUrl
                    MediaProber.probeAsync(url, referer) { foundUrl, mime ->
                        main.post { reportSniffed(tab, SniffedMedia(foundUrl, mime)) }
                    }
                }
            }
            if (tab.adBlock && !req.isForMainFrame && AdBlockHostRules.isBlockedUrl(url)) {
                tab.blockedCount.incrementAndGet()
                return emptyResponse()
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val req = request ?: return false
            val scheme = req.url.scheme?.lowercase() ?: return false
            if (scheme == "http" || scheme == "https") {
                if (scheme == "http" && req.isForMainFrame && !cleartextAllowed) {
                    post(
                        JSONObject()
                            .put("type", "cleartext-blocked")
                            .put("tabId", tab.id)
                            .put("url", req.url.toString())
                    )
                    return true
                }
                val host = req.url.host
                // Google blocks sign-in from embedded WebViews; using the desktop UA for the
                // login screens themselves lets most flows through. Restore on leaving.
                if (isGoogleLoginHost(host)) {
                    if (!tab.desktop && !tab.googleLoginUaOverride && view != null) {
                        tab.googleLoginUaOverride = true
                        view.settings.userAgentString = NativeProxyClient.DESKTOP_UA
                    }
                } else if (tab.googleLoginUaOverride && view != null) {
                    tab.googleLoginUaOverride = false
                    view.settings.userAgentString =
                        if (tab.desktop) NativeProxyClient.DESKTOP_UA else NativeProxyClient.MOBILE_UA
                }
                return false
            }
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") {
                return false
            }

            val url = req.url.toString()
            if (scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "geo" || scheme == "market") {
                if (req.hasGesture()) {
                    confirmExternal(
                        Intent(Intent.ACTION_VIEW, req.url),
                        url
                    )
                }
                return true
            }
            if (scheme == "intent") {
                if (!req.hasGesture()) return true
                try {
                    val target = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    target.addCategory(Intent.CATEGORY_BROWSABLE)
                    target.component = null
                    target.selector = null
                    // When the target app is missing, fall back to the browser fallback URL if present.
                    val fallback = target.getStringExtra("browser_fallback_url")
                    if (activity.packageManager.resolveActivity(target, 0) == null && fallback != null) {
                        val fallbackUri = android.net.Uri.parse(fallback)
                        if (fallbackUri.scheme == "http" || fallbackUri.scheme == "https") {
                            tabs[tab.id]?.webView?.loadUrl(fallback)
                            return true
                        }
                    }
                    confirmExternal(target, url)
                } catch (ignored: Exception) {
                }
                return true
            }
            // Other custom schemes: only with a gesture, after asking the user.
            if (req.hasGesture()) {
                confirmExternal(Intent(Intent.ACTION_VIEW, req.url), url)
            }
            return true
        }

        private fun confirmExternal(intent: Intent, url: String) {
            val appLabel = try {
                val resolved = activity.packageManager.resolveActivity(intent, 0)
                resolved?.loadLabel(activity.packageManager)?.toString() ?: ""
            } catch (ignored: Exception) {
                ""
            }
            activity.runOnUiThread {
                WebDialogs.showOpenExternalConfirm(activity, appLabel, url) {
                    delegate.openExternalIntent(intent, url)
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            val u = url ?: return
            tab.currentUrl = u
            tab.seenMedia.clear()
            tab.lastProgress = 0
            MediaProber.onPageStart()
            if (!u.startsWith("about:")) NativeProxyClient.refererHint = u
            post(
                JSONObject()
                    .put("type", "loading")
                    .put("tabId", tab.id)
                    .put("url", u)
                    .put("progress", 5)
                    .put("started", true)
            )
            if (!supportsDocumentStart) {
                view?.evaluateJavascript(BrowserDetectorScript.SOURCE, null)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val u = url ?: return
            if (!supportsDocumentStart) {
                view?.evaluateJavascript(BrowserDetectorScript.SOURCE, null)
            }
            post(
                JSONObject()
                    .put("type", "finished")
                    .put("tabId", tab.id)
                    .put("url", u)
                    .put("title", view?.title ?: "")
                    .put("blocked", tab.blockedCount.getAndSet(0))
                    .put("canGoBack", view?.canGoBack() == true)
                    .put("canGoForward", view?.canGoForward() == true)
            )
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            val u = url ?: return
            if (u.startsWith("about:")) return
            tab.currentUrl = u
            post(
                JSONObject()
                    .put("type", "url")
                    .put("tabId", tab.id)
                    .put("url", u)
                    .put("canGoBack", view?.canGoBack() == true)
                    .put("canGoForward", view?.canGoForward() == true)
            )
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val req = request ?: return
            if (!req.isForMainFrame) return
            post(
                JSONObject()
                    .put("type", "error")
                    .put("tabId", tab.id)
                    .put("url", req.url.toString())
                    .put("message", error?.description?.toString() ?: "")
            )
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            val h = handler ?: return
            val host = hostOf(error?.url ?: tab.currentUrl) ?: return
            if (sslAllowedHosts.contains(host)) {
                h.proceed()
                return
            }
            if (error?.primaryError == null) {
                h.cancel()
                return
            }
            val description = sslErrorDescription(error.primaryError)
            activity.runOnUiThread {
                WebDialogs.showSslWarning(
                    activity,
                    host,
                    description,
                    onProceed = {
                        sslAllowedHosts.add(host)
                        h.proceed()
                    },
                    onCancel = {
                        h.cancel()
                        post(
                            JSONObject()
                                .put("type", "error")
                                .put("tabId", tab.id)
                                .put("url", error.url ?: tab.currentUrl)
                                .put("message", "SSL certificate error")
                        )
                    }
                )
            }
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView?,
            handler: android.webkit.HttpAuthHandler?,
            host: String?,
            realm: String?
        ) {
            val h = handler ?: return
            activity.runOnUiThread {
                WebDialogs.showHttpAuth(activity, host ?: "", realm ?: "") { user, pass ->
                    if (user != null) {
                        h.proceed(user, pass)
                    } else {
                        h.cancel()
                    }
                }
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            post(
                JSONObject()
                    .put("type", "error")
                    .put("tabId", tab.id)
                    .put("url", tab.currentUrl)
                    .put("message", "The page crashed (out of memory?)")
            )
            main.post { destroyTab(tab.id) }
            return true
        }
    }

    private fun sslErrorDescription(code: Int): String {
        val fa = java.util.Locale.getDefault().language == "fa"
        val key = when (code) {
            SslError.SSL_EXPIRED -> if (fa) "گواهی منقضی شده است" else "The certificate has expired."
            SslError.SSL_IDMISMATCH -> if (fa) "نام دامنه با گواهی نمی‌خواند" else "The hostname does not match the certificate."
            SslError.SSL_NOTYETVALID -> if (fa) "گواهی هنوز معتبر نشده است" else "The certificate is not yet valid."
            SslError.SSL_UNTRUSTED -> if (fa) "گواهی توسط مرجع شناخته‌شده‌ای امضا نشده است" else "The certificate authority is not trusted."
            SslError.SSL_DATE_INVALID -> if (fa) "تاریخ گواهی نامعتبر است" else "The certificate date is invalid."
            SslError.SSL_INVALID -> if (fa) "خطای عمومی گواهی" else "A generic certificate error occurred."
            else -> if (fa) "خطای ناشناخته" else "Unknown error"
        }
        return key
    }

    // ------------------------------------------------------------------ web permissions

    private fun requestWebPermission(tab: BrowserTab, request: PermissionRequest) {
        val resources = request.resources
        val wantsCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources
        val wantsMic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources

        val origin = SitePermissionStore.originOf(tab.currentUrl)
            ?: SitePermissionStore.originOf(request.origin.toString())

        if (!wantsCamera && !wantsMic) {
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
                // Protected media (Widevine level requests) is granted; playback itself is
                // still enforced by the DRM system.
                request.grant(resources)
            } else {
                request.deny()
            }
            return
        }

        fun label(): String {
            val fa = java.util.Locale.getDefault().language == "fa"
            return when {
                wantsCamera && wantsMic -> if (fa) "دوربین و میکروفون" else "camera and microphone"
                wantsCamera -> if (fa) "دوربین" else "camera"
                else -> if (fa) "میکروفون" else "microphone"
            }
        }

        fun androidPermissions(): List<String> {
            val out = ArrayList<String>()
            if (wantsCamera) out.add(Manifest.permission.CAMERA)
            if (wantsMic) out.add(Manifest.permission.RECORD_AUDIO)
            return out
        }

        fun finish(grant: Boolean) {
            try {
                if (grant) request.grant(resources) else request.deny()
            } catch (ignored: Exception) {
            }
        }

        fun checkRuntimeAndFinish(grant: Boolean) {
            if (!grant) {
                finish(false)
                return
            }
            val missing = androidPermissions().filterNot { delegate.hasAndroidPermission(it) }
            if (missing.isEmpty()) {
                finish(true)
                return
            }
            val permission = missing[0]
            delegate.requestAndroidPermission(permission) { granted ->
                finish(granted && missing.all { it == permission || delegate.hasAndroidPermission(it) })
            }
        }

        if (origin != null) {
            val rememberedCamera = if (wantsCamera) SitePermissionStore.get(activity, origin, SitePermissionStore.KIND_CAMERA) else null
            val rememberedMic = if (wantsMic) SitePermissionStore.get(activity, origin, SitePermissionStore.KIND_MICROPHONE) else null
            val remembered = when {
                wantsCamera && wantsMic ->
                    if (rememberedCamera != null && rememberedMic != null) rememberedCamera && rememberedMic else null
                wantsCamera -> rememberedCamera
                else -> rememberedMic
            }
            if (remembered != null) {
                checkRuntimeAndFinish(remembered)
                return
            }
        }

        val host = hostOf(tab.currentUrl) ?: request.origin.toString()
        WebDialogs.showPermissionPrompt(activity, host, label()) { granted, remember ->
            if (remember && origin != null) {
                if (wantsCamera) SitePermissionStore.put(activity, origin, SitePermissionStore.KIND_CAMERA, granted)
                if (wantsMic) SitePermissionStore.put(activity, origin, SitePermissionStore.KIND_MICROPHONE, granted)
            }
            checkRuntimeAndFinish(granted)
        }
    }

    private fun requestGeolocation(tab: BrowserTab, origin: String, callback: GeolocationPermissions.Callback) {
        fun finish(grant: Boolean, retain: Boolean) {
            callback.invoke(origin, grant, retain)
        }

        fun checkRuntimeAndFinish(grant: Boolean, retain: Boolean) {
            if (!grant) {
                finish(false, retain)
                return
            }
            val fine = Manifest.permission.ACCESS_FINE_LOCATION
            val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
            if (delegate.hasAndroidPermission(fine) || delegate.hasAndroidPermission(coarse)) {
                finish(true, retain)
                return
            }
            delegate.requestAndroidPermission(fine) { granted ->
                if (!granted) {
                    // Fine was denied: approximate location is still enough for most sites.
                    delegate.requestAndroidPermission(coarse) { coarseGranted ->
                        finish(coarseGranted, retain)
                    }
                } else {
                    finish(true, retain)
                }
            }
        }

        val remembered = SitePermissionStore.get(activity, origin, SitePermissionStore.KIND_LOCATION)
        if (remembered != null) {
            checkRuntimeAndFinish(remembered, true)
            return
        }

        val host = try {
            java.net.URI(origin).host ?: origin
        } catch (ignored: Exception) {
            origin
        }
        val fa = java.util.Locale.getDefault().language == "fa"
        val locationLabel = if (fa) "موقعیت مکانی" else "location"
        WebDialogs.showPermissionPrompt(activity, host, locationLabel) { granted, remember ->
            if (remember) SitePermissionStore.put(activity, origin, SitePermissionStore.KIND_LOCATION, granted)
            checkRuntimeAndFinish(granted, remember)
        }
    }

    // ------------------------------------------------------------------ chrome client

    private inner class BrowserChrome(private val tab: BrowserTab) : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress < 100 && newProgress - tab.lastProgress < 8) return
            tab.lastProgress = newProgress
            post(
                JSONObject()
                    .put("type", "loading")
                    .put("tabId", tab.id)
                    .put("url", tab.currentUrl)
                    .put("progress", newProgress)
            )
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            val t = title ?: return
            post(JSONObject().put("type", "title").put("tabId", tab.id).put("title", t))
        }

        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view == null || customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            fullscreenHolder.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            fullscreenHolder.visibility = View.VISIBLE
            setSystemBarsHidden(true)
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        // ------------------------------------------------------------ file upload

        override fun onShowFileChooser(
            view: WebView?,
            filePathCallback: ValueCallback<Array<android.net.Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            val callback = filePathCallback ?: return false
            val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
            val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            return delegate.openFileChooser(callback, acceptTypes, multiple)
        }

        // ------------------------------------------------------------ popups / new windows

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?
        ): Boolean {
            // Popup blocker: only gestures may open windows, exactly like Chrome's default.
            if (!isUserGesture || resultMsg == null) return false
            val temp = WebView(activity)
            temp.settings.javaScriptEnabled = true
            var delivered = false
            fun destroyTemp() {
                try {
                    container.removeView(temp)
                    temp.destroy()
                } catch (ignored: Exception) {
                }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        if (!delivered) {
                            delivered = true
                            post(JSONObject().put("type", "new-tab").put("url", url))
                            main.postDelayed({ destroyTemp() }, 300)
                        }
                        return true
                    }
                    return false
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    val u = url ?: return
                    if ((u.startsWith("http://") || u.startsWith("https://")) && !delivered) {
                        delivered = true
                        post(JSONObject().put("type", "new-tab").put("url", u))
                        main.postDelayed({ destroyTemp() }, 300)
                    }
                }
            }
            temp.visibility = View.INVISIBLE
            container.addView(temp, FrameLayout.LayoutParams(1, 1))
            // Give JS that opens about:blank and then navigates a moment to deliver the URL.
            main.postDelayed({ if (!delivered) destroyTemp() }, 20_000)

            val transport = resultMsg.obj as? WebView.WebViewTransport
            transport?.webView = temp
            resultMsg.sendToTarget()
            return true
        }

        // ------------------------------------------------------------ web permissions

        override fun onPermissionRequest(request: PermissionRequest?) {
            val req = request ?: return
            activity.runOnUiThread { requestWebPermission(tab, req) }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            val cb = callback ?: return
            val o = origin ?: return
            activity.runOnUiThread { requestGeolocation(tab, o, cb) }
        }

        // ------------------------------------------------------------ JS dialogs

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            val r = result ?: return false
            activity.runOnUiThread { WebDialogs.showJsAlert(activity, url ?: "", message ?: "") { r.confirm() } }
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            val r = result ?: return false
            activity.runOnUiThread { WebDialogs.showJsConfirm(activity, url ?: "", message ?: "") { ok -> if (ok) r.confirm() else r.cancel() } }
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            val r = result ?: return false
            activity.runOnUiThread {
                WebDialogs.showJsPrompt(activity, url ?: "", message ?: "", defaultValue ?: "") { value ->
                    if (value != null) r.confirm(value) else r.cancel()
                }
            }
            return true
        }

        override fun onJsBeforeUnload(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            val r = result ?: return false
            activity.runOnUiThread { WebDialogs.showJsBeforeUnload(activity) { ok -> if (ok) r.confirm() else r.cancel() } }
            return true
        }
    }

    /** The only object page scripts can reach; every argument is untrusted. */
    private inner class DetectorBridge(private val tab: BrowserTab) {

        @JavascriptInterface
        fun report(type: String?, payload: String?) {
            if (type == null || payload == null || payload.length > 200000) return
            if (!allowReport(tab)) return
            try {
                val json = JSONObject(payload)
                if (type == "media-dom") {
                    val raw = json.optJSONArray("items") ?: return
                    val items = JSONArray()
                    for (i in 0 until raw.length()) {
                        val entry = raw.optJSONObject(i) ?: continue
                        val u = entry.optString("url", "")
                        if (u.startsWith("http://") || u.startsWith("https://")) items.put(entry)
                    }
                    if (items.length() == 0) return
                    post(
                        JSONObject()
                            .put("type", "media")
                            .put("tabId", tab.id)
                            .put("pageUrl", tab.currentUrl)
                            .put("items", items)
                    )
                } else if (type == "visible-media") {
                    val mediaUrl = json.optString("mediaUrl", "")
                    if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
                        post(
                            JSONObject()
                                .put("type", "visible-media")
                                .put("tabId", tab.id)
                                .put("pageUrl", tab.currentUrl)
                                .put("mediaUrl", mediaUrl)
                        )
                    }
                } else if (type == "mse-media") {
                    // A video element is playing a blob: URL backed by MediaSource. Report the
                    // SourceBuffer mimeTypes and the URLs that fed them so the downloader can
                    // fetch the same streams directly.
                    val bufferMimes = json.optJSONArray("mimes") ?: JSONArray()
                    val feeds = json.optJSONArray("feeds") ?: JSONArray()
                    val items = JSONArray()
                    for (i in 0 until feeds.length()) {
                        val u = feeds.optJSONObject(i)?.optString("url", "") ?: ""
                        if (!u.startsWith("http://") && !u.startsWith("https://")) continue
                        val entry = JSONObject()
                            .put("url", u)
                            .put("tag", "video")
                            .put("type", if (bufferMimes.length() > 0) bufferMimes.optString(0) else "video/mp4")
                            .put("visible", true)
                            .put("kind", "mse-feed")
                        items.put(entry)
                    }
                    if (items.length() > 0) {
                        post(
                            JSONObject()
                                .put("type", "media")
                                .put("tabId", tab.id)
                                .put("pageUrl", tab.currentUrl)
                                .put("items", items)
                        )
                    }
                }
            } catch (ignored: Exception) {
            }
        }
    }

    companion object {
        private const val MAX_TABS = 8
        private const val PRIVATE_PROFILE_NAME = "salvia_private"
    }
}
