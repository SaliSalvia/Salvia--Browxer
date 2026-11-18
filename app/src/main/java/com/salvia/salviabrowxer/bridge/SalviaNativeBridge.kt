package com.salvia.salviabrowxer.bridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import com.salvia.salviabrowxer.MainActivity
import com.salvia.salviabrowxer.browser.BrowserHost
import com.salvia.salviabrowxer.service.NativeDownloadService
import org.json.JSONObject

/**
 * JavaScript bridge exposed as `window.SalviaNative`.
 *
 * `addJavascriptInterface` exposes this object to every frame of the WebView, including the
 * proxied third-party pages that BrowserScreen renders in a sandboxed iframe. Every argument is
 * therefore treated as untrusted input and validated before it reaches a service or an intent.
 *
 * [eventSink] lets bridge actions push events back into the React UI (the same
 * `window.__salviaNative*Event` handlers the browser/download engines use).
 */
class SalviaNativeBridge(
    private val activity: Activity,
    private val browserProvider: () -> BrowserHost?,
    private val eventSink: ((handler: String, payload: JSONObject) -> Unit)? = null
) {

    /**
     * Defence against third-party frames: `addJavascriptInterface` exposes this object to every
     * frame of the UI WebView, including sandboxed iframes that render proxied pages — they
     * cannot touch the parent DOM, but they CAN call these methods. Only [armUiBridge] called
     * with the one-time secret unlocks the state-changing methods; the secret is delivered to
     * the trusted main frame via WebView.evaluateJavascript (which never reaches sub-frames), so
     * an iframe cannot obtain it. Until armed, every state-changing method is inert.
     */
    private val armed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val armSecret: String = java.util.UUID.randomUUID().toString()

    /** Called by the host Activity once the UI page finished loading (main frame only). */
    fun injectArmToken(webView: android.webkit.WebView) {
        val escaped = armSecret.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript(
            "window.__salviaReceiveArmToken && window.__salviaReceiveArmToken('$escaped');",
            null
        )
    }

    @JavascriptInterface
    fun armUiBridge(token: String) {
        if (token == armSecret) armed.set(true)
    }

    private fun armedOnly(): Boolean = armed.get()

    private fun emitToUi(handler: String, payload: JSONObject) {
        val sink = eventSink ?: return
        activity.runOnUiThread { sink(handler, payload) }
    }

    @JavascriptInterface
    fun isAndroidApp(): Boolean = true

    @JavascriptInterface
    fun getApiBase(): String = "https://appassets.androidplatform.net"

    @JavascriptInterface
    fun startDownload(
        id: String,
        url: String,
        filename: String,
        mimeType: String,
        isHls: Boolean,
        parallelConnections: Int,
        autoSaveToGallery: Boolean,
        audioTrackUrl: String?,
        headersJson: String?,
        wifiOnly: Boolean?
    ) {
        if (!armedOnly()) return
        if (id.isBlank() || id.length > 128) return
        if (!isHttpUrl(url)) return
        val audio = audioTrackUrl?.takeIf { it.isNotBlank() }
        if (audio != null && !isHttpUrl(audio)) return
        val headers = sanitizeHeaders(headersJson)

        NativeDownloadService.start(
            context = activity.applicationContext,
            id = id,
            url = url,
            filename = filename.take(200).ifBlank { "media.mp4" },
            mimeType = mimeType.ifBlank { "video/mp4" },
            isHls = isHls,
            connections = parallelConnections.coerceIn(1, 8),
            autoSave = autoSaveToGallery,
            audioTrackUrl = audio,
            headers = headers,
            wifiOnly = wifiOnly == true,
            referer = null
        )
    }

    /** Only whitelisted header names may be replayed to the server, and values are size-limited. */
    private fun sanitizeHeaders(headersJson: String?): Map<String, String> {
        if (headersJson.isNullOrBlank() || headersJson.length > 4000) return emptyMap()
        return try {
            val obj = JSONObject(headersJson)
            val allowed = setOf("origin", "authorization", "user-agent", "accept", "x-api-key", "token")
            val out = LinkedHashMap<String, String>()
            for (key in obj.keys()) {
                val lower = key.lowercase()
                if (lower in allowed) {
                    val value = obj.optString(key)
                    if (value.isNotBlank()) out[lower] = value.take(500)
                }
            }
            out
        } catch (ignored: Exception) {
            emptyMap()
        }
    }

    @JavascriptInterface
    fun pauseDownload(id: String) {
        if (!armedOnly()) return
        if (id.isBlank() || id.length > 128) return
        NativeDownloadService.pause(activity.applicationContext, id)
    }

    @JavascriptInterface
    fun cancelDownload(id: String) {
        if (!armedOnly()) return
        if (id.isBlank() || id.length > 128) return
        NativeDownloadService.cancel(activity.applicationContext, id)
    }

    /** Re-runs a paused / failed download using the specification persisted by the service. */
    @JavascriptInterface
    fun resumeDownload(id: String) {
        if (!armedOnly()) return
        if (id.isBlank() || id.length > 128) return
        NativeDownloadService.resume(activity.applicationContext, id)
    }

    /** True when the native engine knows this download (so it can be resumed instead of restarted). */
    @JavascriptInterface
    fun hasPersistedDownload(id: String): Boolean {
        if (id.isBlank() || id.length > 128) return false
        return com.salvia.salviabrowxer.service.DownloadQueueStore.getJob(activity.applicationContext, id) != null
    }

    @JavascriptInterface
    fun openNativeFile(uriString: String, mimeType: String): Boolean {
        if (!armedOnly()) return false
        val uri = parseOwnedContentUri(uriString) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "video/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun shareNativeFile(uriString: String, title: String, mimeType: String): Boolean {
        if (!armedOnly()) return false
        val uri = parseOwnedContentUri(uriString) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "video/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, title))
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ downloads

    /** Page URL to send as Referer for the download that is about to be started with this id. */
    @JavascriptInterface
    fun setDownloadReferer(id: String, referer: String) {
        if (id.isBlank() || id.length > 128) return
        if (!isHttpUrl(referer)) return
        NativeDownloadService.setReferer(id, referer.trim())
    }

    // ------------------------------------------------------------------ native browser

    @JavascriptInterface
    fun hasNativeBrowser(): Boolean = browserProvider() != null

    @JavascriptInterface
    fun browserShow(tabId: String, url: String, desktop: Boolean, adBlock: Boolean, isPrivate: Boolean?) {
        if (!armedOnly()) return
        if (!validTabId(tabId) || !isBlankOrHttp(url)) return
        onBrowser { it.show(tabId, url.trim(), desktop, adBlock, isPrivate == true) }
    }

    @JavascriptInterface
    fun browserLoad(tabId: String, url: String, desktop: Boolean, adBlock: Boolean, isPrivate: Boolean?) {
        if (!armedOnly()) return
        if (!validTabId(tabId) || !isBlankOrHttp(url)) return
        onBrowser { it.load(tabId, url.trim(), desktop, adBlock, isPrivate == true) }
    }

    @JavascriptInterface
    fun browserReload(tabId: String) {
        if (!validTabId(tabId)) return
        onBrowser { it.reload(tabId) }
    }

    @JavascriptInterface
    fun browserStop(tabId: String) {
        if (!validTabId(tabId)) return
        onBrowser { it.stop(tabId) }
    }

    @JavascriptInterface
    fun browserDestroy(tabId: String) {
        if (!validTabId(tabId)) return
        onBrowser { it.destroy(tabId) }
    }

    @JavascriptInterface
    fun browserHide() {
        onBrowser { it.hide() }
    }

    @JavascriptInterface
    fun browserSetBounds(left: Double, top: Double, width: Double, height: Double) {
        onBrowser { it.setBounds(left.toFloat(), top.toFloat(), width.toFloat(), height.toFloat()) }
    }

    @JavascriptInterface
    fun browserSetInteraction(shield: Boolean, hotRectsJson: String) {
        if (hotRectsJson.length > 4000) return
        onBrowser { it.setInteraction(shield, hotRectsJson) }
    }

    @JavascriptInterface
    fun browserFind(tabId: String, query: String, backwards: Boolean) {
        if (!validTabId(tabId) || query.length > 200) return
        onBrowser { it.find(tabId, query, backwards) }
    }

    @JavascriptInterface
    fun browserApplySettings(javaScript: Boolean, cookies: Boolean, textZoom: Int, darkPages: Boolean?) {
        onBrowser { it.applySettings(javaScript, cookies, textZoom, darkPages == true) }
    }

    @JavascriptInterface
    fun browserSetDesktop(tabId: String, desktop: Boolean) {
        if (!validTabId(tabId)) return
        onBrowser { it.setDesktop(tabId, desktop) }
    }

    @JavascriptInterface
    fun browserPrint(tabId: String) {
        if (!validTabId(tabId)) return
        onBrowser { it.printTab(tabId) }
    }

    /** Clears cookies, storage, cache, form data, geolocation grants and per-site permissions. */
    @JavascriptInterface
    fun clearBrowsingData() {
        if (!armedOnly()) return
        onBrowser { it.clearBrowsingData() }
        emitToUi("__salviaNativeBrowserEvent", JSONObject().put("type", "browsing-data-cleared"))
    }

    /**
     * Opens [url] in the user's real browser (Chrome / Firefox / ...). Used for flows that
     * refuse embedded WebViews, notably Google sign-in.
     */
    @JavascriptInterface
    fun openInExternalBrowser(url: String): Boolean {
        if (!armedOnly()) return false
        if (!isHttpUrl(url)) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            true
        } catch (ignored: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ storage location

    /** Opens the system folder picker for the download location (SAF tree). */
    @JavascriptInterface
    fun pickDownloadFolder() {
        if (!armedOnly()) return
        val main = activity as? MainActivity ?: return
        main.runOnUiThread { main.pickDownloadFolder() }
    }

    /** Display name of the chosen download folder ("" = app default). */
    @JavascriptInterface
    fun getDownloadFolderName(): String {
        val main = activity as? MainActivity ?: return ""
        return main.downloadFolderName()
    }

    /** Clears the custom download folder, returning to the app default location. */
    @JavascriptInterface
    fun resetDownloadFolder() {
        if (!armedOnly()) return
        val main = activity as? MainActivity ?: return
        main.clearDownloadFolder()
        emitToUi(
            "__salviaNativeBrowserEvent",
            JSONObject().put("type", "download-folder-changed").put("uri", "")
        )
    }

    @JavascriptInterface
    fun minimizeApp() {
        if (!armedOnly()) return
        activity.runOnUiThread {
            activity.moveTaskToBack(true)
        }
    }

    // ------------------------------------------------------------------ diagnostics & self-update

    /** Number of native crash reports stored on this device (see [CrashReporter]). */
    @JavascriptInterface
    fun getCrashReportCount(): Int =
        com.salvia.salviabrowxer.core.CrashReporter.reportCount(activity.applicationContext)

    /** Shares the stored native crash reports as a single zip via the system share sheet. */
    @JavascriptInterface
    fun shareCrashReports(): Boolean {
        if (!armedOnly()) return false
        return try {
            val ctx = activity.applicationContext
            val zip = com.salvia.salviabrowxer.core.CrashReporter.packageReports(ctx) ?: return false
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", zip
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SalviaBrowxer crash reports")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(
                Intent.createChooser(intent, "SalviaBrowxer — crash reports")
            )
            true
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * Starts the system package installer for an APK this app downloaded (the self-update flow).
     *
     * On Android 8+ the user must first allow "install unknown apps" for SalviaBrowxer; when
     * that is missing the corresponding settings page is opened and `false` is returned so the
     * UI can tell the user to tap the file again after granting.
     */
    @JavascriptInterface
    fun installApk(uriString: String): Boolean {
        if (!armedOnly()) return false
        val uri = parseOwnedContentUri(uriString) ?: return false
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                activity.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
                false
            } else {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(intent)
                true
            }
        } catch (ignored: Exception) {
            false
        }
    }

    private fun onBrowser(block: (BrowserHost) -> Unit) {
        activity.runOnUiThread {
            val host = browserProvider()
            if (host != null) block(host)
        }
    }

    private fun validTabId(tabId: String): Boolean = tabId.isNotBlank() && tabId.length <= 128

    private fun isBlankOrHttp(raw: String): Boolean = raw.isBlank() || isHttpUrl(raw)

    private fun isHttpUrl(raw: String): Boolean {
        return try {
            val scheme = Uri.parse(raw.trim()).scheme?.lowercase()
            scheme == "http" || scheme == "https"
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * Only content:// URIs this app itself produced may be opened or shared: its own FileProvider
     * and the MediaStore rows written by MediaPublisher. Anything else (file://, other apps'
     * providers) is refused so a page script cannot use the bridge to hand arbitrary URIs to
     * other apps with a read grant.
     */
    private fun parseOwnedContentUri(raw: String): Uri? {
        return try {
            val uri = Uri.parse(raw.trim())
            if (uri.scheme != "content") return null
            val authority = uri.authority ?: return null
            if (authority == "${activity.packageName}.fileprovider" || authority == "media") uri else null
        } catch (ignored: Exception) {
            null
        }
    }
}
