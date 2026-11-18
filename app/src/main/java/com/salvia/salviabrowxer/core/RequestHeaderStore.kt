package com.salvia.salviabrowxer.core

/**
 * Small LRU that remembers the interesting request headers the page itself sent for a media URL
 * (Origin, Authorization, custom User-Agent). When the downloader later fetches exactly that URL,
 * those headers are replayed — this is what makes signed / token-protected CDNs work.
 *
 * Cookies are deliberately NOT stored: the OkHttp client already mirrors the WebView cookie jar,
 * so cookies flow automatically and stay fresh.
 */
object RequestHeaderStore {

    private const val MAX_ENTRIES = 320

    private val interesting = setOf("origin", "authorization", "user-agent", "referer", "x-requested-with", "accept")

    private val lock = Any()
    private val store = object : LinkedHashMap<String, Map<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    /** Records the noteworthy headers seen for [url] during page traffic. Cheap; no I/O. */
    fun capture(url: String?, requestHeaders: Map<String, String>?) {
        if (url.isNullOrBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val headers = requestHeaders ?: return
        if (headers.isEmpty()) return
        val picked = LinkedHashMap<String, String>()
        for ((key, value) in headers) {
            val lower = key.lowercase()
            if (lower in interesting && value.isNotBlank()) {
                if (lower == "referer" || lower == "x-requested-with") continue // handled separately / noise
                picked[lower] = value.take(400)
            }
        }
        if (picked.isEmpty()) return
        synchronized(lock) {
            val existing = store[url]
            val merged = if (existing != null) existing + picked else picked
            store[url] = merged
        }
    }

    /** The captured headers for [url] (may be empty). */
    fun headersFor(url: String?): Map<String, String> {
        if (url.isNullOrBlank()) return emptyMap()
        synchronized(lock) {
            return store[url] ?: emptyMap()
        }
    }
}
