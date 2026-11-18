package com.salvia.salviabrowxer.core

import com.salvia.salviabrowxer.browser.MediaSniffer
import com.salvia.salviabrowxer.browser.SniffedMedia
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Light, budgeted Content-Type probing for URLs that have no recognizable media extension.
 *
 * `shouldInterceptRequest` never sees response headers, so a media file served from a URL like
 * `https://site.com/getvideo?id=123` is invisible to extension-based sniffing. For a small,
 * hint-filtered subset of such URLs we issue a HEAD (or 1-byte Range GET) with the page's cookies
 * and Referer — the same session the page itself uses — and classify by Content-Type.
 *
 * Strictly limited: every URL is probed at most once per app run, at most [MAX_PROBES_TOTAL]
 * probes happen per page load, and only URLs whose path/query carries a media hint are probed,
 * so ordinary XHR traffic is never touched.
 */
object MediaProber {

    private const val MAX_PROBES_PER_PAGE = 12
    private const val PROBE_TIMEOUT_MS = 8000L

    /** URL -> was media (a missing entry means "never probed"). */
    private val probed = ConcurrentHashMap<String, Boolean>()
    private val budget = AtomicInteger(MAX_PROBES_PER_PAGE)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "salvia-media-prober").apply { isDaemon = true }
    }

    private val mediaContentTypes = setOf(
        "video/mp4", "video/webm", "video/quicktime", "video/x-matroska", "video/3gpp",
        "video/x-flv", "video/x-msvideo", "video/x-ms-wmv", "video/mpeg", "video/ogg",
        "video/mp2t", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/flac",
        "audio/aac", "audio/x-m4a", "audio/webm", "application/ogg"
    )

    private val playlistContentTypes = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl",
        "audio/mpegurl", "audio/x-mpegurl", "application/dash+xml"
    )

    // Extensions that are obviously not a media file we care about.
    private val boringExtensions = setOf(
        "html", "htm", "php", "asp", "aspx", "jsp", "json", "xml", "css", "js", "mjs",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf", "otf",
        "eot", "txt", "pdf", "zip", "gz", "woff3", "map", "ts", "tsx", "jsx"
    )

    private val hintPattern = Regex(
        "(?i)(m3u8|mpegurl|mpd|dash|manifest|playlist|master|\\.mp4|\\.webm|\\.m4a|\\.mp3" +
            "|/video|videos?/|/media|medias?/|/stream|streams?/|/watch|/play|players?/|/clip" +
            "|/embed|progressive|/dl/|/download|/file|/hls|/audio|/quality|/source|/get_video" +
            "|videoplayback|/content|/vod)"
    )

    /** Cheap pre-filter: may this URL be worth a probe? Never blocks or performs I/O. */
    fun shouldProbe(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return false
        if (probed.containsKey(rawUrl)) return false
        if (budget.get() <= 0) return false
        if (AdBlockHostRules.isBlockedUrl(rawUrl)) return false
        if (MediaSniffer.hasMediaExtension(rawUrl)) return false
        return try {
            val uri = java.net.URI(rawUrl)
            val path = (uri.path ?: "").lowercase()
            val query = (uri.query ?: "").lowercase()
            val name = path.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "")
            if (ext.isNotEmpty() && ext in boringExtensions) return false
            // Path or query must carry a media hint; otherwise this is just some API call.
            hintPattern.containsMatchIn(path) || hintPattern.containsMatchIn(query) ||
                query.contains("format=mp4") || query.contains("mime=video")
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * Asynchronously probes [url]; when it turns out to be media, [onFound] is invoked with the
     * normalized mime type (on the prober thread — hop to the main thread yourself if needed).
     */
    fun probeAsync(url: String, referer: String?, onFound: (url: String, mimeType: String) -> Unit) {
        if (!shouldProbe(url)) return
        probed[url] = false
        budget.decrementAndGet()
        executor.execute {
            val found = probeBlocking(url, referer)
            if (found != null) {
                probed[url] = true
                onFound(url, found.mimeType)
            }
        }
    }

    fun probeBlocking(url: String, referer: String?): SniffedMedia? {
        return try {
            var mimeType: String? = null

            fun inspect(contentType: String?) {
                val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return
                if (ct.isEmpty()) return
                if (ct in mediaContentTypes || ct in playlistContentTypes) mimeType = ct
                // Many servers lazily answer "application/octet-stream" for media; only trust it
                // when the URL itself carries a strong media hint.
                if (ct == "application/octet-stream" && mimeType == null) {
                    val strongHint = Regex("(?i)(m3u8|mpd|\\.mp4|\\.webm|\\.mp3|\\.m4a|manifest|playlist|videoplayback|get_video)")
                    if (strongHint.containsMatchIn(url)) mimeType = "video/mp4"
                }
                if (ct == "application/x-mpegurl" || ct == "audio/x-mpegurl") mimeType = "application/vnd.apple.mpegurl"
            }

            val headBuilder = Request.Builder().url(url).head().header("User-Agent", NativeProxyClient.MOBILE_UA)
            if (!referer.isNullOrBlank()) headBuilder.header("Referer", referer)
            NativeProxyClient.httpClient.newCall(headBuilder.build()).execute().use { res ->
                inspect(res.header("Content-Type"))
            }

            if (mimeType == null) {
                // HEAD is often unsupported; a 1-byte range GET costs one packet.
                val getBuilder = Request.Builder().url(url)
                    .get()
                    .header("User-Agent", NativeProxyClient.MOBILE_UA)
                    .header("Range", "bytes=0-0")
                if (!referer.isNullOrBlank()) getBuilder.header("Referer", referer)
                NativeProxyClient.httpClient.newCall(getBuilder.build()).execute().use { res ->
                    inspect(res.header("Content-Type"))
                }
            }

            mimeType?.let { SniffedMedia(url, it) }
        } catch (ignored: Exception) {
            null
        }
    }

    /** Resets the per-page probe budget; called when a new page starts loading in a tab. */
    fun onPageStart() {
        budget.set(MAX_PROBES_PER_PAGE)
    }

    fun isKnownMedia(url: String): Boolean = probed[url] == true

    fun isKnownNotMedia(url: String): Boolean = probed.containsKey(url) && probed[url] == false
}
