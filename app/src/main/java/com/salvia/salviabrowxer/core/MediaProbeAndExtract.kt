package com.salvia.salviabrowxer.core

import com.salvia.salviabrowxer.data.MediaCandidate
import com.salvia.salviabrowxer.data.MediaFormat
import com.salvia.salviabrowxer.data.MediaInfo
import com.salvia.salviabrowxer.data.UnsupportedMedia
import com.salvia.salviabrowxer.data.UpdateReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

object MediaFormatters {
    fun formatFileSize(bytes: Long?): String {
        if (bytes == null || bytes < 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) {
            value /= 1024.0
            idx++
        }
        return if (value >= 100) {
            "${value.roundToInt()} ${units[idx]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[idx])
        }
    }

    fun formatDurationSeconds(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val rem = s % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, rem)
        } else {
            String.format(Locale.US, "%d:%02d", m, rem)
        }
    }

    fun formatDurationMs(ms: Long): String = formatDurationSeconds(ms / 1000L)

    fun sanitizeFilename(raw: String?): String {
        val cleaned = (raw ?: "media")
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.take(110).ifEmpty { "media" }
    }
}

object MediaProbeAndExtract {

    data class ManifestParseResult(
        val formats: List<MediaFormat>,
        val audioFormats: List<MediaFormat> = emptyList(),
        val unsupported: UnsupportedMedia? = null
    )

    data class ProbeResult(
        val mediaInfo: MediaInfo,
        val unsupported: UnsupportedMedia? = null
    )

    data class ExtractResult(
        val ok: Boolean,
        val finalUrl: String,
        val title: String,
        val thumbnail: String? = null,
        val candidates: List<MediaCandidate> = emptyList()
    )

    private fun resolveRelativeUrl(base: String, maybeRelative: String): String? {
        return try {
            URI(base).resolve(maybeRelative.trim()).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAttributes(line: String): Map<String, String> {
        val attrs = HashMap<String, String>()
        val regex = Regex("([A-Z0-9-]+)=(\"([^\"]*)\"|([^,]*))")
        for (m in regex.findAll(line)) {
            val key = m.groupValues[1]
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }
            attrs[key] = value
        }
        return attrs
    }

    fun parseHlsPlaylist(manifestText: String, baseUrl: String, title: String): ManifestParseResult {
        val lines = manifestText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (manifestText.contains("METHOD=SAMPLE-AES")) {
            return ManifestParseResult(emptyList(), unsupported = UnsupportedMedia.DRM)
        }

        val audioGroups = LinkedHashMap<String, String>()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                val attrs = parseAttributes(line.removePrefix("#EXT-X-MEDIA:"))
                val type = attrs["TYPE"]
                val groupId = attrs["GROUP-ID"]
                val uri = attrs["URI"]
                if (type == "AUDIO" && !groupId.isNullOrEmpty() && !uri.isNullOrEmpty()) {
                    val resolved = resolveRelativeUrl(baseUrl, uri)
                    if (resolved != null && !audioGroups.containsKey(groupId)) {
                        audioGroups[groupId] = resolved
                    }
                }
            }
        }

        val variants = ArrayList<MediaFormat>()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = parseAttributes(line.removePrefix("#EXT-X-STREAM-INF:"))
                val nextUrlLine = lines.drop(i + 1).firstOrNull { !it.startsWith("#") } ?: continue
                val resolvedUrl = resolveRelativeUrl(baseUrl, nextUrlLine) ?: continue
                val bw = (attrs["BANDWIDTH"] ?: attrs["AVERAGE-BANDWIDTH"])?.toLongOrNull() ?: 0L
                val kbps = if (bw > 0) (bw / 1000L).toInt() else null
                var width: Int? = null
                var height: Int? = null
                val res = attrs["RESOLUTION"]
                if (res != null && res.contains('x', ignoreCase = true)) {
                    val parts = res.lowercase().split('x')
                    width = parts.getOrNull(0)?.toIntOrNull()
                    height = parts.getOrNull(1)?.toIntOrNull()
                }
                val audioGroup = attrs["AUDIO"]?.let { audioGroups[it] }
                val qualityLabel = when {
                    height != null -> "${height}p${if (audioGroup != null) " + audio" else ""}"
                    kbps != null -> "$kbps kbps"
                    else -> "Variant ${variants.size + 1}"
                }
                variants.add(
                    MediaFormat(
                        id = "hls-${variants.size}-$resolvedUrl",
                        format = qualityLabel,
                        url = resolvedUrl,
                        mimeType = "application/vnd.apple.mpegurl",
                        extension = "mp4",
                        width = width,
                        height = height,
                        bitrate = kbps,
                        isVideo = true,
                        isAudio = audioGroup != null,
                        isHls = true,
                        audioTrackUrl = audioGroup
                    )
                )
            }
        }

        if (variants.isNotEmpty()) {
            variants.sortWith(compareByDescending<MediaFormat> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 })
            val audioFormats = audioGroups.entries.map { (groupId, audioUrl) ->
                MediaFormat(
                    id = "hls-audio-$groupId",
                    format = "Audio ($groupId)",
                    url = audioUrl,
                    mimeType = "audio/mp4",
                    extension = "m4a",
                    isVideo = false,
                    isAudio = true,
                    isHls = true
                )
            }
            return ManifestParseResult(variants, audioFormats, null)
        }

        val isVod = manifestText.contains("#EXT-X-ENDLIST") || manifestText.contains("#EXT-X-PLAYLIST-TYPE:VOD")
        if (!isVod && manifestText.contains("#EXT-X-TARGETDURATION")) {
            return ManifestParseResult(emptyList(), unsupported = UnsupportedMedia.LIVE)
        }

        return ManifestParseResult(
            formats = listOf(
                MediaFormat(
                    id = "hls-single-$baseUrl",
                    format = "$title (HLS)",
                    url = baseUrl,
                    mimeType = "application/vnd.apple.mpegurl",
                    extension = "mp4",
                    isVideo = true,
                    isHls = true
                )
            )
        )
    }

    private data class DashRep(
        val id: String,
        val kind: String,
        val bandwidth: Long,
        val width: Int?,
        val height: Int?,
        val fileUrl: String?,
        val segmented: Boolean
    )

    private fun elementChildren(parent: Element, localName: String): List<Element> {
        val list = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (n.localName == localName || n.tagName == localName)) {
                list.add(n)
            }
        }
        return list
    }

    private fun dashBaseUrl(parentBase: String, el: Element): String {
        val text = elementChildren(el, "BaseURL").firstOrNull()?.textContent?.trim()
        return if (!text.isNullOrEmpty()) resolveRelativeUrl(parentBase, text) ?: parentBase else parentBase
    }

    fun parseDashManifest(manifestText: String, baseUrl: String): ManifestParseResult {
        val lower = manifestText.lowercase()
        if (lower.contains("contentprotection")) {
            return ManifestParseResult(emptyList(), unsupported = UnsupportedMedia.DRM)
        }
        if (lower.contains("type=\"dynamic\"")) {
            return ManifestParseResult(emptyList(), unsupported = UnsupportedMedia.DASH)
        }
        val mpd = try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(manifestText.toByteArray(Charsets.UTF_8)))
            doc.documentElement
        } catch (_: Exception) {
            null
        } ?: return ManifestParseResult(emptyList(), unsupported = UnsupportedMedia.DASH)

        val period = elementChildren(mpd, "Period").firstOrNull()
            ?: return ManifestParseResult(emptyList(), unsupported = UnsupportedMedia.DASH)

        val manifestUrl = baseUrl.substringBefore('#')
        val periodBase = dashBaseUrl(dashBaseUrl(manifestUrl, mpd), period)
        val reps = ArrayList<DashRep>()

        for (set in elementChildren(period, "AdaptationSet")) {
            val setBase = dashBaseUrl(periodBase, set)
            val setMime = set.getAttribute("mimeType").lowercase()
            val setType = set.getAttribute("contentType").lowercase()
            val setSegmented = elementChildren(set, "SegmentTemplate").isNotEmpty() ||
                elementChildren(set, "SegmentList").isNotEmpty()
            val setHasBase = elementChildren(set, "BaseURL").isNotEmpty()

            elementChildren(set, "Representation").forEachIndexed { idx, rep ->
                val mime = rep.getAttribute("mimeType").ifEmpty { setMime }.lowercase()
                val width = rep.getAttribute("width").ifEmpty { set.getAttribute("width") }.toIntOrNull()?.takeIf { it > 0 }
                val height = rep.getAttribute("height").ifEmpty { set.getAttribute("height") }.toIntOrNull()?.takeIf { it > 0 }
                val kind = when {
                    mime.startsWith("video") || setType == "video" || (mime.isEmpty() && setType.isEmpty() && height != null) -> "video"
                    mime.startsWith("audio") || setType == "audio" -> "audio"
                    else -> return@forEachIndexed
                }
                val segmented = setSegmented ||
                    elementChildren(rep, "SegmentTemplate").isNotEmpty() ||
                    elementChildren(rep, "SegmentList").isNotEmpty()
                val hasBase = setHasBase || elementChildren(rep, "BaseURL").isNotEmpty()
                if (!segmented && !hasBase) return@forEachIndexed

                reps.add(
                    DashRep(
                        id = rep.getAttribute("id").ifEmpty { "rep$idx" },
                        kind = kind,
                        bandwidth = rep.getAttribute("bandwidth").toLongOrNull() ?: 0L,
                        width = width,
                        height = height,
                        fileUrl = if (!segmented) dashBaseUrl(setBase, rep) else null,
                        segmented = segmented
                    )
                )
            }
        }

        val bestAudio = reps.filter { it.kind == "audio" }.maxByOrNull { it.bandwidth }
        val formats = ArrayList<MediaFormat>()
        for (rep in reps) {
            if (rep.kind != "video") continue
            val hasAudio = bestAudio != null
            val url: String?
            var audioTrackUrl: String? = null
            if (rep.segmented) {
                val encVideo = java.net.URLEncoder.encode(rep.id, "UTF-8")
                val audioPart = if (bestAudio != null) "&audio=${java.net.URLEncoder.encode(bestAudio.id, "UTF-8")}" else ""
                url = "$manifestUrl#salvia-dash=$encVideo$audioPart"
            } else {
                url = rep.fileUrl
                audioTrackUrl = if (bestAudio != null && !bestAudio.segmented) bestAudio.fileUrl else null
            }
            if (url == null) continue
            val label = if (rep.height != null) {
                "${rep.height}p${if (hasAudio) " + audio" else ""}"
            } else {
                "${(rep.bandwidth / 1000L).toInt()} kbps"
            }
            formats.add(
                MediaFormat(
                    id = "dash-${formats.size}-${rep.id}",
                    format = label,
                    url = url,
                    mimeType = MediaUrlRules.DASH_MIME_TYPE,
                    extension = "mp4",
                    width = rep.width,
                    height = rep.height,
                    bitrate = if (rep.bandwidth > 0) (rep.bandwidth / 1000L).toInt() else null,
                    isVideo = true,
                    isAudio = hasAudio,
                    isDash = true,
                    audioTrackUrl = audioTrackUrl
                )
            )
        }
        if (formats.isEmpty()) {
            return ManifestParseResult(emptyList(), unsupported = UnsupportedMedia.DASH)
        }
        formats.sortWith(compareByDescending<MediaFormat> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 })
        return ManifestParseResult(formats)
    }

    fun initialMediaInfoForCandidate(candidate: MediaCandidate): MediaInfo {
        val ext = candidate.extension ?: MediaUrlRules.pathExtension(candidate.mediaUrl) ?: "mp4"
        val title = candidate.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: MediaFormatters.sanitizeFilename(candidate.mediaUrl.substringAfterLast('/').substringBefore('?'))
        val isPlaylist = ext == "m3u8" || MediaUrlRules.isPlaylistMime(candidate.mimeType)
        val isDash = ext == "mpd" || MediaUrlRules.isDashUrl(candidate.mediaUrl, candidate.mimeType)
        val isAudio = ext in MediaUrlRules.AUDIO_EXTENSIONS || (candidate.mimeType?.startsWith("audio/") == true)

        val fallbackFormat = MediaFormat(
            id = "direct-${candidate.mediaUrl}",
            format = ext.uppercase(Locale.US),
            url = candidate.mediaUrl,
            mimeType = candidate.mimeType ?: if (isAudio) "audio/$ext" else "video/$ext",
            extension = if (isPlaylist || isDash) "mp4" else if (ext in MediaUrlRules.MEDIA_EXTENSIONS) ext else "mp4",
            size = candidate.estimatedSize,
            isVideo = !isAudio,
            isAudio = isAudio,
            isHls = isPlaylist,
            isDash = isDash
        )
        return MediaInfo(
            title = title,
            duration = candidate.duration,
            formats = listOf(fallbackFormat),
            audioFormats = if (isAudio) listOf(fallbackFormat) else emptyList(),
            videoFormats = if (!isAudio) listOf(fallbackFormat) else emptyList(),
            combinedFormats = listOf(fallbackFormat),
            source = candidate.source,
            webpageUrl = candidate.pageUrl
        )
    }

    suspend fun probeMediaCandidate(candidate: MediaCandidate): ProbeResult = withContext(Dispatchers.IO) {
        val baseInfo = initialMediaInfoForCandidate(candidate)
        val target = candidate.mediaUrl
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return@withContext ProbeResult(baseInfo)
        }
        try {
            val ref = candidate.pageUrl.takeIf { it.startsWith("http") } ?: NativeProxyClient.refererHint
            fun buildReq(methodHead: Boolean, range: Boolean = false): Request {
                val b = Request.Builder().url(target).header("User-Agent", NativeProxyClient.MOBILE_UA)
                if (!ref.isNullOrBlank()) b.header("Referer", ref)
                if (methodHead) b.head() else b.get()
                if (range) b.header("Range", "bytes=0-0")
                RequestHeaderStore.headersFor(target).forEach { (k, v) -> b.header(k, v) }
                return b.build()
            }

            var res = NativeProxyClient.httpClient.newCall(buildReq(methodHead = true)).execute()
            if (!res.isSuccessful || res.code == 405) {
                res.close()
                res = NativeProxyClient.httpClient.newCall(buildReq(methodHead = false, range = true)).execute()
            }

            var contentType = ""
            var size: Long? = null
            var finalUrl = target
            res.use { response ->
                contentType = response.header("Content-Type").orEmpty()
                val contentRange = response.header("Content-Range").orEmpty()
                size = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                if (contentRange.contains("/")) {
                    val total = contentRange.substringAfter("/").toLongOrNull()
                    if (total != null && total > 0L) size = total
                }
                finalUrl = response.request.url.toString()
            }

            var manifestText: String? = null
            val lowerUrl = target.lowercase()
            if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") ||
                contentType.contains("mpegurl", ignoreCase = true) ||
                contentType.contains("dash+xml", ignoreCase = true)
            ) {
                val mReq = Request.Builder().url(target).header("User-Agent", NativeProxyClient.MOBILE_UA).apply {
                    if (!ref.isNullOrBlank()) header("Referer", ref)
                    RequestHeaderStore.headersFor(target).forEach { (k, v) -> header(k, v) }
                }.build()
                NativeProxyClient.httpClient.newCall(mReq).execute().use { mRes ->
                    if (mRes.isSuccessful) {
                        manifestText = mRes.body?.string()
                    }
                }
            }

            val effectiveMime = contentType.ifBlank { candidate.mimeType ?: baseInfo.formats.first().mimeType }
            if (manifestText != null && (MediaUrlRules.isPlaylistUrl(finalUrl, effectiveMime) || manifestText!!.contains("#EXTM3U"))) {
                val parsed = parseHlsPlaylist(manifestText!!, finalUrl, baseInfo.title)
                if (parsed.unsupported != null) {
                    return@withContext ProbeResult(baseInfo.copy(formats = emptyList(), combinedFormats = emptyList()), parsed.unsupported)
                }
                return@withContext ProbeResult(
                    baseInfo.copy(
                        formats = parsed.formats,
                        audioFormats = parsed.audioFormats.ifEmpty { baseInfo.audioFormats },
                        combinedFormats = parsed.formats
                    )
                )
            }

            if (manifestText != null && (MediaUrlRules.isDashUrl(finalUrl, effectiveMime) || manifestText!!.contains("<MPD"))) {
                val parsed = parseDashManifest(manifestText!!, finalUrl)
                if (parsed.unsupported != null) {
                    return@withContext ProbeResult(baseInfo.copy(formats = emptyList(), combinedFormats = emptyList()), parsed.unsupported)
                }
                return@withContext ProbeResult(
                    baseInfo.copy(
                        formats = parsed.formats,
                        combinedFormats = parsed.formats
                    )
                )
            }

            val updated = baseInfo.formats.first().copy(
                url = finalUrl,
                mimeType = effectiveMime,
                size = size ?: baseInfo.formats.first().size
            )
            ProbeResult(baseInfo.copy(formats = listOf(updated), combinedFormats = listOf(updated)))
        } catch (_: Exception) {
            ProbeResult(baseInfo)
        }
    }

    suspend fun extractMediaFromPageUrl(pageUrl: String): ExtractResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", NativeProxyClient.MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            NativeProxyClient.httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext ExtractResult(false, pageUrl, pageUrl)
                }
                val ct = res.header("Content-Type").orEmpty()
                val finalUrl = res.request.url.toString()
                if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("mpegurl") || ct.contains("dash+xml")) {
                    val c = candidateFromRaw(finalUrl, finalUrl, finalUrl.substringAfterLast('/'), ct, "FETCH", null)
                    return@withContext ExtractResult(
                        ok = true,
                        finalUrl = finalUrl,
                        title = finalUrl.substringAfterLast('/'),
                        candidates = listOfNotNull(c)
                    )
                }

                val html = res.body?.string().orEmpty()
                val title = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    .ifEmpty { URI(finalUrl).host ?: finalUrl }
                val thumbRaw = Regex(
                    "<meta[^>]+(?:property=[\"']og:image[\"']|name=[\"']twitter:image[\"'])[^>]+content=[\"']([^\"']+)[\"']",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.getOrNull(1)
                val thumbnail = thumbRaw?.let { resolveRelativeUrl(finalUrl, it) }

                val found = LinkedHashMap<String, String>()
                collectUrlsFromHtml(html, finalUrl, found)

                val iframeRegex = Regex("<iframe\\b[^>]+\\bsrc=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                val iframeUrls = iframeRegex.findAll(html)
                    .mapNotNull { m -> resolveRelativeUrl(finalUrl, m.groupValues[1]) }
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .distinct()
                    .take(2)
                    .toList()

                for (iframeUrl in iframeUrls) {
                    try {
                        NativeProxyClient.httpClient.newCall(
                            Request.Builder()
                                .url(iframeUrl)
                                .header("User-Agent", NativeProxyClient.MOBILE_UA)
                                .header("Referer", finalUrl)
                                .build()
                        ).execute().use { iRes ->
                            if (iRes.isSuccessful) {
                                collectUrlsFromHtml(iRes.body?.string().orEmpty(), iRes.request.url.toString(), found)
                            }
                        }
                    } catch (_: Exception) {
                    com.salvia.salviabrowxer.core.SalviaLog.w("MediaProbeAndExtract", _)
                    }
                }

                val candidates = found.entries.mapNotNull { (mediaUrl, src) ->
                    candidateFromRaw(mediaUrl, finalUrl, title, null, src, thumbnail)
                }
                ExtractResult(true, finalUrl, title, thumbnail, candidates)
            }
        } catch (_: Exception) {
            ExtractResult(false, pageUrl, pageUrl)
        }
    }

    private fun candidateFromRaw(
        mediaUrl: String,
        pageUrl: String,
        title: String,
        explicitMime: String?,
        source: String,
        thumbnail: String?
    ): MediaCandidate? {
        if (MediaUrlRules.looksLikeNonMedia(mediaUrl) || MediaUrlRules.looksLikeSegment(mediaUrl)) return null
        val ext = MediaUrlRules.pathExtension(mediaUrl)
            ?: MediaUrlRules.extensionForMime(explicitMime)
            ?: when {
                MediaUrlRules.isPlaylistUrl(mediaUrl, explicitMime) -> "m3u8"
                MediaUrlRules.isDashUrl(mediaUrl, explicitMime) -> "mpd"
                else -> "mp4"
            }
        val mime = explicitMime?.ifBlank { null } ?: when {
            ext == "m3u8" -> "application/vnd.apple.mpegurl"
            ext == "mpd" -> MediaUrlRules.DASH_MIME_TYPE
            ext in MediaUrlRules.AUDIO_EXTENSIONS -> "audio/$ext"
            else -> "video/$ext"
        }
        return MediaCandidate(
            id = "extract-$mediaUrl",
            pageUrl = pageUrl,
            mediaUrl = mediaUrl,
            title = title,
            mimeType = mime,
            extension = ext,
            source = source,
            confidence = if (source == "FETCH") 0.95 else 0.85,
            thumbnail = thumbnail
        )
    }

    private fun collectUrlsFromHtml(html: String, baseUrl: String, out: MutableMap<String, String>) {
        val unescaped = html
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val directRegex = Regex(
            "https?://[^\\s\"'<>\\\\]+\\.(?:mp4|webm|mov|m4v|m3u8|mpd|mp3|m4a)(?:\\?[^\\s\"'<>\\\\]*)?",
            RegexOption.IGNORE_CASE
        )
        for (match in directRegex.findAll(unescaped)) {
            if (!out.containsKey(match.value)) out[match.value] = "JS"
        }
        val keyRegex = Regex(
            "\"(?:contentUrl|playbackUrl|playable_url(?:_quality_hd)?|video_url|hd_src|sd_src|hls_url|file)\"\\s*:\\s*\"(https?://[^\"]+)\"",
            RegexOption.IGNORE_CASE
        )
        for (match in keyRegex.findAll(unescaped)) {
            val u = match.groupValues[1]
            if (!out.containsKey(u)) out[u] = "DOM"
        }
        val tagRegex = Regex("<(?:source|video|audio)\\b[^>]+\\bsrc=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        for (match in tagRegex.findAll(html)) {
            val abs = resolveRelativeUrl(baseUrl, match.groupValues[1]) ?: continue
            if (abs.startsWith("http") && !out.containsKey(abs)) out[abs] = "DOM"
        }
        val ogRegex = Regex(
            "<meta[^>]+(?:property=[\"']og:video(?::url)?[\"']|name=[\"']twitter:player:stream[\"'])[^>]+content=[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
        for (match in ogRegex.findAll(html)) {
            val abs = resolveRelativeUrl(baseUrl, match.groupValues[1]) ?: continue
            if (abs.startsWith("http") && !out.containsKey(abs)) out[abs] = "DOM"
        }
    }

    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val parse = { s: String ->
            s.trim().removePrefix("v").removePrefix("V")
                .split(".").take(3)
                .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        }
        val r = parse(remoteTag)
        val c = parse(currentVersion)
        for (i in 0 until 3) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    suspend fun fetchLatestRelease(currentVersion: String): UpdateReleaseInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(SalviaConstants.RELEASES_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", NativeProxyClient.MOBILE_UA)
            .build()
        NativeProxyClient.httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = res.body?.string() ?: error("Empty response")
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
            val htmlUrl = json.optString("html_url").ifBlank { SalviaConstants.SOURCE_CODE_URL }
            val notes = json.optString("body").takeIf { it.isNotBlank() }
            if (tag.isBlank() || !isNewerVersion(tag, currentVersion)) return@withContext null

            var apkUrl: String? = null
            var apkSize: Long? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                        apkSize = asset.optLong("size").takeIf { it > 0L }
                        break
                    }
                }
            }
            UpdateReleaseInfo(tag, htmlUrl, apkUrl, apkSize, notes)
        }
    }
}
