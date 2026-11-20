package com.salvia.salviabrowxer.core

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

object NativeProxyClient {
    /**
     * User-Agent sent by this app's own network stack. Initialized from the device's real WebView
     * UA (minus the `; wv` marker) in [initFromWebView] so the Chrome/Android versions always
     * match the engine actually rendering pages — a stale or mismatched UA is how sites detect
     * (and block) embedded browsers. The hardcoded values are only a pre-init fallback.
     */
    @Volatile
    var MOBILE_UA: String =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    @Volatile
    var DESKTOP_UA: String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Derives both UAs from the real system WebView UA. Call once at app start. */
    fun initFromWebView(context: Context) {
        try {
            val raw = android.webkit.WebSettings.getDefaultUserAgent(context).trim()
            if (raw.length < 20 || !raw.startsWith("Mozilla/")) return
            val mobile = raw.replace("; wv", "").replace("  ", " ").trim()
            if (mobile.startsWith("Mozilla/")) MOBILE_UA = mobile
            val desktop = mobile
                .replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile Safari", " Safari")
            if (desktop.startsWith("Mozilla/") && desktop != mobile) DESKTOP_UA = desktop
        } catch (ignored: Exception) {
        com.salvia.salviabrowxer.core.SalviaLog.w("NativeProxyClient", ignored)
        }
    }

    /**
     * URL of the page currently open in the native browser. Probing and fetching media that the
     * page referenced is done with this Referer, because many CDNs refuse requests without it.
     */
    @Volatile
    var refererHint: String? = null

    private fun withReferer(builder: Request.Builder): Request.Builder {
        val ref = refererHint
        if (!ref.isNullOrBlank() && (ref.startsWith("http://") || ref.startsWith("https://"))) {
            builder.header("Referer", ref)
        }
        return builder
    }

    private val webViewCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            try {
                val cm = CookieManager.getInstance()
                val urlStr = url.toString()
                for (cookie in cookies) {
                    cm.setCookie(urlStr, cookie.toString())
                }
            } catch (ignored: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("NativeProxyClient", ignored)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return try {
                val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
                raw.split(";")
                    .mapNotNull { part ->
                        val idx = part.indexOf('=')
                        if (idx <= 0) return@mapNotNull null
                        val name = part.substring(0, idx).trim()
                        val value = part.substring(idx + 1).trim()
                        if (name.isEmpty()) null else Cookie.Builder().name(name).value(value).domain(url.host).build()
                    }
            } catch (ignored: Exception) {
                emptyList()
            }
        }
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(webViewCookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun handleApiRequest(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        val path = uri.path ?: return null
        if (!path.startsWith("/api/")) return null

        return when (path) {
            "/api/browse" -> handleBrowse(uri)
            "/api/media/probe" -> handleProbe(uri)
            "/api/media/extract" -> handleExtract(uri)
            "/api/media/fetch" -> handleFetch(uri, request)
            "/api/local-file" -> handleLocalFile(context, uri, request)
            else -> jsonResponse(404, JSONObject().put("error", "Not found"))
        }
    }

    private fun handleBrowse(uri: Uri): WebResourceResponse {
        val target = validateRemoteUrl(uri.getQueryParameter("url"))
            ?: return jsonResponse(400, JSONObject().put("error", "Invalid or restricted URL"))
        val desktop = uri.getQueryParameter("desktop") == "1"
        val adBlock = uri.getQueryParameter("adblock") == "1"

        return try {
            val req = Request.Builder()
                .url(target)
                .header("User-Agent", if (desktop) DESKTOP_UA else MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            httpClient.newCall(req).execute().use { res ->
                val contentType = res.header("Content-Type").orEmpty()
                val finalUrl = res.request.url.toString()
                if (!res.isSuccessful) {
                    return jsonResponse(
                        200,
                        JSONObject()
                            .put("ok", false)
                            .put("status", res.code)
                            .put("statusText", res.message)
                            .put("finalUrl", finalUrl)
                    )
                }
                if (contentType.contains("text/html") ||
                    contentType.contains("application/xhtml+xml") ||
                    contentType.isEmpty()
                ) {
                    // Guard against unbounded bodies (a huge page would OOM the app process).
                    val rawHtml = readBodyCapped(res, MAX_TEXT_BODY_BYTES)
                        ?: return jsonResponse(
                            200,
                            JSONObject()
                                .put("ok", false)
                                .put("error", "Page too large to process")
                                .put("finalUrl", finalUrl)
                        )
                    val injected = BrowserBridgeInjector.inject(rawHtml, finalUrl, adBlock)
                    val title = injected.title ?: URI(finalUrl).host ?: target
                    jsonResponse(
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("finalUrl", finalUrl)
                            .put("title", title)
                            .put("contentType", contentType)
                            .put("blockedCount", injected.blockedCount)
                            .put("html", injected.html)
                    )
                } else {
                    val isDirect = contentType.startsWith("video/") ||
                        contentType.startsWith("audio/") ||
                        contentType.contains("mpegurl") ||
                        contentType.contains("dash+xml")
                    val cl = res.header("Content-Length")?.toLongOrNull()
                    jsonResponse(
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("finalUrl", finalUrl)
                            .put("title", finalUrl.substringAfterLast('/').ifEmpty { URI(finalUrl).host ?: target })
                            .put("contentType", contentType)
                            .put("blockedCount", 0)
                            .put("isDirectMedia", isDirect)
                            .put("contentLength", cl ?: JSONObject.NULL)
                    )
                }
            }
        } catch (e: Exception) {
            jsonResponse(
                200,
                JSONObject()
                    .put("ok", false)
                    .put("error", e.message ?: "Network error")
                    .put("finalUrl", target)
            )
        }
    }

    private fun handleProbe(uri: Uri): WebResourceResponse {
        val target = validateRemoteUrl(uri.getQueryParameter("url"))
            ?: return jsonResponse(400, JSONObject().put("error", "Invalid or restricted URL"))

        return try {
            var res = httpClient.newCall(
                withReferer(
                    Request.Builder()
                        .url(target)
                        .head()
                        .header("User-Agent", MOBILE_UA)
                ).build()
            ).execute()

            if (!res.isSuccessful || res.code == 405) {
                res.close()
                res = httpClient.newCall(
                    withReferer(
                        Request.Builder()
                            .url(target)
                            .get()
                            .header("User-Agent", MOBILE_UA)
                            .header("Range", "bytes=0-0")
                    ).build()
                ).execute()
            }

            res.use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                val contentDisposition = response.header("Content-Disposition").orEmpty()
                val contentRange = response.header("Content-Range").orEmpty()
                var size = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                if (contentRange.contains("/")) {
                    val total = contentRange.substringAfter("/").toLongOrNull()
                    if (total != null && total > 0L) size = total
                }
                val acceptRanges = response.header("Accept-Ranges").equals("bytes", ignoreCase = true) ||
                    response.code == 206
                val finalUrl = response.request.url.toString()

                var manifestText: String? = null
                val lowerUrl = target.lowercase()
                if (lowerUrl.contains(".m3u8") ||
                    lowerUrl.contains(".mpd") ||
                    contentType.contains("mpegurl") ||
                    contentType.contains("dash+xml")
                ) {
                    httpClient.newCall(
                        withReferer(Request.Builder().url(target).header("User-Agent", MOBILE_UA)).build()
                    ).execute().use { mRes ->
                        if (mRes.isSuccessful) {
                            manifestText = mRes.body?.string()
                        }
                    }
                }

                jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("finalUrl", finalUrl)
                        .put("contentType", contentType)
                        .put("contentDisposition", contentDisposition)
                        .put("size", size ?: JSONObject.NULL)
                        .put("acceptRanges", acceptRanges)
                        .put("manifestText", manifestText ?: JSONObject.NULL)
                )
            }
        } catch (e: Exception) {
            jsonResponse(
                200,
                JSONObject()
                    .put("ok", false)
                    .put("error", e.message ?: "Probe failed")
            )
        }
    }

    private fun handleExtract(uri: Uri): WebResourceResponse {
        val target = validateRemoteUrl(uri.getQueryParameter("url"))
            ?: return jsonResponse(400, JSONObject().put("error", "Invalid or restricted URL"))

        return try {
            val req = Request.Builder()
                .url(target)
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return jsonResponse(
                        200,
                        JSONObject()
                            .put("ok", false)
                            .put("status", res.code)
                            .put("error", "HTTP ${res.code}")
                    )
                }
                val ct = res.header("Content-Type").orEmpty()
                val finalUrl = res.request.url.toString()
                if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("mpegurl") || ct.contains("dash+xml")) {
                    val arr = JSONArray().put(
                        JSONObject()
                            .put("url", finalUrl)
                            .put("mimeType", ct)
                            .put("source", "FETCH")
                    )
                    return jsonResponse(
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("finalUrl", finalUrl)
                            .put("title", finalUrl.substringAfterLast('/'))
                            .put("items", arr)
                    )
                }

                val html = res.body?.string().orEmpty()
                val titleMatch = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)
                val title = titleMatch?.groupValues?.getOrNull(1)?.trim().orEmpty().ifEmpty { URI(finalUrl).host ?: finalUrl }
                val thumbMatch = Regex(
                    "<meta[^>]+(?:property=[\"']og:image[\"']|name=[\"']twitter:image[\"'])[^>]+content=[\"']([^\"']+)[\"']",
                    RegexOption.IGNORE_CASE
                ).find(html)
                val thumbnail = thumbMatch?.groupValues?.getOrNull(1)?.let { raw ->
                    try {
                        URI(finalUrl).resolve(raw).toString()
                    } catch (ignored: Exception) {
                        null
                    }
                }

                val found = LinkedHashMap<String, String>()
                collectUrlsFromHtml(html, finalUrl, found)

                // Inspect up to 2 embedded iframes one hop deep
                val iframeRegex = Regex("<iframe\\b[^>]+\\bsrc=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val iframeUrls = iframeRegex.findAll(html)
                    .mapNotNull { m ->
                        try {
                            validateRemoteUrl(URI(finalUrl).resolve(m.groupValues[1]).toString())
                        } catch (ignored: Exception) {
                            null
                        }
                    }
                    .distinct()
                    .take(2)
                    .toList()

                for (iframeUrl in iframeUrls) {
                    try {
                        httpClient.newCall(
                            Request.Builder()
                                .url(iframeUrl)
                                .header("User-Agent", MOBILE_UA)
                                .header("Referer", finalUrl)
                                .build()
                        ).execute().use { iRes ->
                            if (iRes.isSuccessful) {
                                collectUrlsFromHtml(iRes.body?.string().orEmpty(), iRes.request.url.toString(), found)
                            }
                        }
                    } catch (ignored: Exception) {
                    com.salvia.salviabrowxer.core.SalviaLog.w("NativeProxyClient", ignored)
                    }
                }

                val arr = JSONArray()
                for ((mediaUrl, sourceLabel) in found) {
                    arr.put(JSONObject().put("url", mediaUrl).put("source", sourceLabel))
                }
                jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("finalUrl", finalUrl)
                        .put("title", title)
                        .put("thumbnail", thumbnail ?: JSONObject.NULL)
                        .put("items", arr)
                )
            }
        } catch (e: Exception) {
            jsonResponse(200, JSONObject().put("ok", false).put("error", e.message ?: "Extract failed"))
        }
    }

    private fun collectUrlsFromHtml(html: String, baseUrl: String, out: MutableMap<String, String>) {
        val unescaped = html
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val directRegex = Regex(
            "https?://[^\\s\"'<>\\\\]+\\.(?:mp4|webm|mov|m4v|m3u8|mpd|mp3|m4a)(?:\\?[^\\s\"'<>\\\\]*)?",
            RegexOption.IGNORE_CASE
        )
        for (match in directRegex.findAll(unescaped)) {
            val u = match.value
            if (!out.containsKey(u)) out[u] = "JS"
        }
        val keyRegex = Regex(
            "\"(?:contentUrl|playbackUrl|playable_url(?:_quality_hd)?|video_url|hd_src|sd_src|hls_url|file)\"\\s*:\\s*\"(https?://[^\"]+)\"",
            RegexOption.IGNORE_CASE
        )
        for (match in keyRegex.findAll(unescaped)) {
            val u = match.groupValues[1]
            if (!out.containsKey(u)) out[u] = "DOM"
        }
        val base = try {
            URI(baseUrl)
        } catch (ignored: Exception) {
            return
        }
        val tagRegex = Regex("<(?:source|video|audio)\\b[^>]+\\bsrc=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        for (match in tagRegex.findAll(html)) {
            try {
                val abs = base.resolve(match.groupValues[1]).toString()
                if (abs.startsWith("http") && !out.containsKey(abs)) {
                    out[abs] = "DOM"
                }
            } catch (ignored: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("NativeProxyClient", ignored)
            }
        }
        val ogVideoRegex = Regex(
            "<meta[^>]+(?:property=[\"']og:video(?::url)?[\"']|name=[\"']twitter:player:stream[\"'])[^>]+content=[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        for (match in ogVideoRegex.findAll(html)) {
            try {
                val abs = base.resolve(match.groupValues[1]).toString()
                if (abs.startsWith("http") && !out.containsKey(abs)) {
                    out[abs] = "DOM"
                }
            } catch (ignored: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("NativeProxyClient", ignored)
            }
        }
    }

    private fun handleFetch(uri: Uri, webRequest: WebResourceRequest): WebResourceResponse {
        val target = validateRemoteUrl(uri.getQueryParameter("url"))
            ?: return jsonResponse(400, JSONObject().put("error", "Invalid or restricted URL"))

        return try {
            val reqBuilder = withReferer(
                Request.Builder()
                    .url(target)
                    .header("User-Agent", MOBILE_UA)
            )
            val rangeHeader = webRequest.requestHeaders?.entries
                ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
                ?.value
            if (!rangeHeader.isNullOrBlank()) {
                reqBuilder.header("Range", rangeHeader)
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val body = response.body
            if (body == null) {
                val status = response.code
                val msg = response.message.ifEmpty { "OK" }
                response.close()
                return WebResourceResponse("application/octet-stream", null, status, msg, corsHeaders(), ByteArrayInputStream(ByteArray(0)))
            }

            val ct = response.header("Content-Type")?.substringBefore(';')?.trim() ?: "application/octet-stream"

            // HLS playlists served through the proxy get their relative URIs rewritten to
            // absolute ones, so players that resolve segment URLs against the proxy URL
            // (hls.js in the UI) can fetch every segment through /api/media/fetch as well.
            if (ct.contains("mpegurl", ignoreCase = true) && isPlaylistSized(response)) {
                val playlistText = readBodyCapped(response, MAX_TEXT_BODY_BYTES)
                response.close()
                if (playlistText != null) {
                    val rewritten = rewriteHlsPlaylist(playlistText, response.request.url.toString())
                    val bytes = rewritten.toByteArray(Charsets.UTF_8)
                    val headers = corsHeaders().toMutableMap()
                    headers["Content-Length"] = bytes.size.toString()
                    headers["Accept-Ranges"] = "none"
                    return WebResourceResponse(ct, null, 200, "OK", headers, ByteArrayInputStream(bytes))
                }
                return jsonResponse(502, JSONObject().put("error", "Playlist too large"))
            }

            val headers = corsHeaders().toMutableMap()
            response.header("Content-Length")?.let { headers["Content-Length"] = it }
            response.header("Content-Range")?.let { headers["Content-Range"] = it }
            response.header("Accept-Ranges")?.let { headers["Accept-Ranges"] = it }

            val stream: InputStream = object : FilterInputStream(body.byteStream()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        response.close()
                    }
                }
            }

            WebResourceResponse(
                ct,
                null,
                response.code,
                response.message.ifEmpty { "OK" },
                headers,
                stream
            )
        } catch (e: Exception) {
            jsonResponse(502, JSONObject().put("error", e.message ?: "Fetch proxy failed"))
        }
    }

    private fun handleLocalFile(context: Context, uri: Uri, webRequest: WebResourceRequest): WebResourceResponse {
        val id = uri.getQueryParameter("id")?.replace(Regex("[^a-zA-Z0-9._-]"), "")
            ?: return jsonResponse(400, JSONObject().put("error", "Missing file id"))
        val mime = uri.getQueryParameter("mime")?.takeIf { it.isNotBlank() } ?: "video/mp4"
        val downloadsDir = File(context.filesDir, "downloads")
        val targetFile = File(downloadsDir, id)
        if (!targetFile.canonicalPath.startsWith(downloadsDir.canonicalPath) || !targetFile.exists()) {
            return jsonResponse(404, JSONObject().put("error", "Local file not found"))
        }

        val totalLen = targetFile.length()
        val rangeHeader = webRequest.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value

        val headers = corsHeaders().toMutableMap()
        headers["Accept-Ranges"] = "bytes"

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split("-", limit = 2)
            val start = spec.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = spec.getOrNull(1)?.toLongOrNull()?.coerceAtMost(totalLen - 1L) ?: (totalLen - 1L)
            if (start in 0 until totalLen && end >= start) {
                val length = end - start + 1L
                headers["Content-Range"] = "bytes $start-$end/$totalLen"
                headers["Content-Length"] = length.toString()
                val raf = RandomAccessFile(targetFile, "r")
                raf.seek(start)
                val boundedStream = object : InputStream() {
                    var remaining = length
                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        val b = raf.read()
                        if (b >= 0) remaining--
                        return b
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0) return -1
                        val toRead = minOf(len.toLong(), remaining).toInt()
                        val read = raf.read(b, off, toRead)
                        if (read > 0) remaining -= read
                        return read
                    }

                    override fun close() {
                        raf.close()
                    }
                }
                return WebResourceResponse(mime, null, 206, "Partial Content", headers, boundedStream)
            }
        }

        headers["Content-Length"] = totalLen.toString()
        return WebResourceResponse(mime, null, 200, "OK", headers, targetFile.inputStream())
    }

    private fun validateRemoteUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return try {
            val parsed = URI(rawUrl.trim())
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (!parsed.userInfo.isNullOrEmpty()) return null
            val host = parsed.host?.lowercase()?.trim('[', ']') ?: return null
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0" || host.endsWith(".local")) {
                return null
            }
            if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) {
                return null
            }
            if (host.startsWith("172.")) {
                val second = host.split(".").getOrNull(1)?.toIntOrNull()
                if (second != null && second in 16..31) return null
            }
            try {
                val addr = InetAddress.getByName(host)
                if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) {
                    return null
                }
            } catch (ignored: Exception) {
                // Allow DNS resolution during actual OkHttp call if transient
            }
            parsed.toString()
        } catch (ignored: Exception) {
            null
        }
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Headers" to "Range, Content-Type",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges"
    )

    private const val MAX_TEXT_BODY_BYTES = 8_000_000L

    /** True when the declared body size is small enough to be a playlist we can rewrite. */
    private fun isPlaylistSized(response: okhttp3.Response): Boolean {
        val len = response.header("Content-Length")?.toLongOrNull() ?: return true
        return len in 1..MAX_TEXT_BODY_BYTES
    }

    /** Reads a text body fully, or null when it exceeds [limit] bytes. */
    private fun readBodyCapped(response: okhttp3.Response, limit: Long): String? {
        return try {
            val input = response.body?.byteStream() ?: return null
            val out = java.io.ByteArrayOutputStream(16 * 1024)
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) return null
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        } catch (ignored: Exception) {
            null
        }
    }

    /** Rewrites relative segment/key/media URIs of an HLS playlist to absolute URLs. */
    private fun rewriteHlsPlaylist(text: String, baseUrl: String): String {
        if (!text.contains("#EXTM3U")) return text
        return try {
            val base = URI(baseUrl)
            text.lineSequence().joinToString("\n") { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() -> raw
                    line.startsWith("#") ->
                        if (line.startsWith("#EXT-X-KEY", true) ||
                            line.startsWith("#EXT-X-MAP", true) ||
                            line.startsWith("#EXT-X-MEDIA", true) ||
                            line.startsWith("#EXT-X-I-FRAME-STREAM-INF", true) ||
                            line.startsWith("#EXT-X-SESSION-KEY", true)
                        ) {
                            quotedUriRegex.replace(line) { m ->
                                val abs = try {
                                    base.resolve(m.groupValues[1]).toString()
                                } catch (ignored: Exception) {
                                    m.groupValues[1]
                                }
                                "URI=\"$abs\""
                            }
                        } else {
                            raw
                        }
                    else -> try {
                        base.resolve(line).toString()
                    } catch (ignored: Exception) {
                        raw
                    }
                }
            }
        } catch (ignored: Exception) {
            text
        }
    }

    private val quotedUriRegex = Regex("URI=\"([^\"]+)\"")

    private fun jsonResponse(status: Int, body: JSONObject): WebResourceResponse {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val headers = corsHeaders().toMutableMap()
        headers["Content-Length"] = bytes.size.toString()
        return WebResourceResponse(
            "application/json",
            "UTF-8",
            status,
            if (status < 400) "OK" else "Error",
            headers,
            ByteArrayInputStream(bytes)
        )
    }
}
