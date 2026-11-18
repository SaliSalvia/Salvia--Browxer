package com.salvia.salviabrowxer.core

import android.content.Context
import android.util.Base64
import com.salvia.salviabrowxer.data.CandidateRole
import com.salvia.salviabrowxer.data.ClassifiedCandidate
import com.salvia.salviabrowxer.data.ClipboardLinkKind
import com.salvia.salviabrowxer.data.MediaCandidate
import com.salvia.salviabrowxer.data.SearchEngineKey
import com.salvia.salviabrowxer.data.ShowcaseItem
import com.salvia.salviabrowxer.data.ShowcasePage
import com.salvia.salviabrowxer.data.UnsupportedMedia
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object SalviaConstants {
    const val APP_NAME = "SalviaBrowxer"
    const val APP_VERSION = "1.0.0"
    const val MAX_LIVE_TABS = 8
    const val DEFAULT_HOMEPAGE = "https://www.google.com"
    const val SOURCE_CODE_URL = "https://github.com/SaliSalvia/Salvia--Browxer"
    const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.txt"
    const val PRIVACY_POLICY_URL =
        "https://github.com/SaliSalvia/Salvia--Browxer/blob/main/docs/privacy-policy.md"
    const val RELEASES_API_URL =
        "https://api.github.com/repos/SaliSalvia/Salvia--Browxer/releases/latest"
    const val SHOWCASE_URL = "https://salvia.showcase/blender-open-movies"

    val BUILTIN_SHOWCASE_PAGES = listOf(
        ShowcasePage(
            url = SHOWCASE_URL,
            title = "Blender Open Movies — Sample Media Gallery",
            description = "Public-domain sample videos and adaptive HLS streams for testing SalviaBrowxer media detection, quality selection, and in-app playback.",
            media = listOf(
                ShowcaseItem(
                    mediaUrl = "https://media.w3.org/2010/05/sintel/trailer.mp4",
                    title = "Sintel Trailer — Blender Open Movie (MP4)",
                    mimeType = "video/mp4",
                    extension = "mp4",
                    durationMs = 52_000L,
                    estimatedSize = 4_372_373L
                ),
                ShowcaseItem(
                    mediaUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    title = "Big Buck Bunny Adaptive Stream (HLS Master)",
                    mimeType = "application/vnd.apple.mpegurl",
                    extension = "m3u8",
                    durationMs = 596_000L
                ),
                ShowcaseItem(
                    mediaUrl = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
                    title = "Blooming Flower CC0 Clip (MP4)",
                    mimeType = "video/mp4",
                    extension = "mp4",
                    durationMs = 5_000L,
                    estimatedSize = 1_128_375L
                )
            )
        )
    )
}

object MediaUrlRules {
    val VIDEO_EXTENSIONS = setOf(
        "mp4", "webm", "mov", "avi", "3gp", "3g2", "m4v", "mkv", "flv",
        "ogv", "mpg", "mpeg", "wmv", "f4v", "m2ts", "mts", "vob", "asf"
    )
    val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "wav", "flac", "ogg", "oga", "opus",
        "mka", "wma", "aif", "aiff", "amr", "ac3"
    )
    val PLAYLIST_EXTENSIONS = setOf("m3u8", "ts")
    val MEDIA_EXTENSIONS = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS + PLAYLIST_EXTENSIONS
    const val DASH_EXTENSION = "mpd"
    const val DASH_MIME_TYPE = "application/dash+xml"

    val PLAYLIST_MIME_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl"
    )
    val SEGMENT_MIME_TYPES = setOf("video/mp2t")
    val SEGMENT_EXTENSIONS = setOf("ts", "m4s", "cmfv", "cmfa", "cmft", "mp4a")
    val NON_MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif", "heic",
        "css", "js", "mjs", "map", "json", "webmanifest", "xml", "txt", "html", "htm",
        "woff", "woff2", "ttf", "otf", "eot", "pdf", "zip", "gz", "vtt", "srt",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "csv"
    )

    fun normalizeMime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.substringBefore(';').trim().trim('"', '\'').lowercase()
        return cleaned.ifEmpty { null }
    }

    fun pathExtension(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = url.substringBefore('#').substringBefore('?')
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        if (dot <= slash || dot == path.length - 1) return null
        val ext = path.substring(dot + 1).lowercase()
        return if (ext.length in 1..5 && ext.all { it in 'a'..'z' || it in '0'..'9' }) ext else null
    }

    fun hasMediaExtension(url: String): Boolean {
        val ext = pathExtension(url)
        return ext != null && ext in MEDIA_EXTENSIONS
    }

    fun isVideoMime(mimeType: String?) = normalizeMime(mimeType)?.startsWith("video/") == true
    fun isAudioMime(mimeType: String?) = normalizeMime(mimeType)?.startsWith("audio/") == true
    fun isPlaylistMime(mimeType: String?) = normalizeMime(mimeType) in PLAYLIST_MIME_TYPES
    fun isDashMime(mimeType: String?) = normalizeMime(mimeType) == DASH_MIME_TYPE
    fun isSegmentMime(mimeType: String?) = normalizeMime(mimeType) in SEGMENT_MIME_TYPES

    fun isMediaMime(mimeType: String?): Boolean {
        val norm = normalizeMime(mimeType) ?: return false
        if (isSegmentMime(norm)) return false
        return norm.startsWith("video/") || norm.startsWith("audio/") || norm in PLAYLIST_MIME_TYPES
    }

    fun isDashUrl(url: String, mimeType: String? = null): Boolean =
        pathExtension(url) == DASH_EXTENSION || isDashMime(mimeType)

    fun isPlaylistUrl(url: String, mimeType: String? = null): Boolean {
        if (isPlaylistMime(mimeType)) return true
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val name = path.substringAfterLast('/')
        if (name.isEmpty()) return false
        if (name.endsWith(".json") || name.endsWith(".webmanifest") || name.endsWith(".xml")) return false
        if (name.endsWith(".m3u8")) return true
        return name == "manifest" || name.startsWith("manifest.")
    }

    fun looksLikeNonMedia(url: String): Boolean {
        val ext = pathExtension(url)
        if (ext != null && ext in NON_MEDIA_EXTENSIONS) return true
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return path.endsWith(".webmanifest")
    }

    fun looksLikeSegment(url: String): Boolean {
        val ext = pathExtension(url)
        if (ext != null && ext in SEGMENT_EXTENSIONS) return true
        val qIdx = url.indexOf('?')
        if (qIdx == -1) return false
        val query = url.substring(qIdx + 1).lowercase()
        return query.contains("bytestart=") || query.contains("byteend=")
    }

    fun extensionForMime(mimeType: String?): String? {
        val mime = normalizeMime(mimeType) ?: return null
        if (mime in PLAYLIST_MIME_TYPES) return "m3u8"
        if (mime == DASH_MIME_TYPE) return "mpd"
        return when {
            mime.startsWith("video/mp4") -> "mp4"
            mime.startsWith("video/webm") -> "webm"
            mime.startsWith("video/quicktime") -> "mov"
            mime.startsWith("video/x-matroska") -> "mkv"
            mime.startsWith("video/x-flv") -> "flv"
            mime.startsWith("video/3gpp2") -> "3g2"
            mime.startsWith("video/3gpp") -> "3gp"
            mime.startsWith("video/ogg") -> "ogv"
            mime.startsWith("video/x-msvideo") || mime.startsWith("video/avi") -> "avi"
            mime.startsWith("video/x-ms-wmv") || mime.startsWith("video/x-ms-asf") -> "wmv"
            mime.startsWith("video/mpeg") -> "mpg"
            mime.startsWith("audio/mpeg") -> "mp3"
            mime.startsWith("audio/mp4") || mime.startsWith("audio/x-m4a") -> "m4a"
            mime.startsWith("audio/aac") -> "aac"
            mime.startsWith("audio/wav") || mime.startsWith("audio/x-wav") -> "wav"
            mime.startsWith("audio/flac") || mime.startsWith("audio/x-flac") -> "flac"
            mime.startsWith("audio/ogg") -> "ogg"
            mime.startsWith("audio/opus") -> "opus"
            mime.startsWith("audio/x-matroska") -> "mka"
            mime.startsWith("audio/x-ms-wma") -> "wma"
            mime.startsWith("audio/aiff") || mime.startsWith("audio/x-aiff") -> "aiff"
            mime.startsWith("audio/amr") -> "amr"
            mime.startsWith("audio/ac3") -> "ac3"
            else -> null
        }
    }
}

object AdBlockRules {
    val DEFAULT_BLOCKED_HOSTS = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "googletagmanager.com",
        "google-analytics.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "adnxs.com", "adsrvr.org", "amazon-adsystem.com", "casalemedia.com", "criteo.com",
        "criteo.net", "dotomi.com", "openx.net", "pubmatic.com", "rubiconproject.com",
        "smartadserver.com", "sovrn.com", "yieldmo.com", "33across.com", "adform.net",
        "bidswitch.net", "districtm.io", "mediageneral.com", "sharethrough.com", "teads.tv",
        "gumgum.com", "improvedigital.com", "inmobi.com", "ironsrc.com", "lifestreet.com",
        "mopub.com", "smaato.net", "startappservice.com", "supersonicads.com",
        "unityads.unity3d.com", "vungle.com", "chartboost.com", "applovin.com", "adcolony.com",
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "zergnet.com",
        "aniview.com", "spotxchange.com", "springserve.com", "tremorhub.com",
        "scorecardresearch.com", "quantserve.com", "imrworldwide.com", "moatads.com",
        "admaster.com.cn", "hotjar.com", "mixpanel.com", "amplitude.com", "segment.com",
        "segment.io", "branch.io", "adjust.com", "appsflyer.com", "newrelic.com",
        "bugsnag.com", "sentry.io"
    )

    fun hostOfUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val parsed = URI(url.trim())
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            parsed.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun matchesDomain(host: String, domain: String): Boolean {
        val target = domain.lowercase().trim().trimEnd('.')
        if (target.isEmpty()) return false
        return host == target || host.endsWith(".$target")
    }
}

object AddressBarResolver {
    private val BLOCKED_PREFIXES = listOf(
        "javascript:", "data:", "file:", "ftp://", "ws://", "wss://", "blob:", "content://", "about:"
    )
    private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    fun hasExplicitScheme(input: String): Boolean {
        val lower = input.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    fun looksLikeHost(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.contains(' ') || trimmed.contains('"') || trimmed.contains('<')) {
            return false
        }
        if (trimmed.contains(':') && !trimmed.contains('.') && !trimmed.startsWith("localhost")) {
            return false
        }
        return trimmed.contains('.') || trimmed.startsWith("localhost") || trimmed.contains(':')
    }

    fun buildSearchUrl(query: String, engine: SearchEngineKey = SearchEngineKey.Google): String {
        return engine.template.replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))
    }

    fun resolveAddressBarInput(input: String, engine: SearchEngineKey = SearchEngineKey.Google): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (BLOCKED_PREFIXES.any { lower.startsWith(it) }) {
            return buildSearchUrl(trimmed, engine)
        }
        if (hasExplicitScheme(trimmed)) return trimmed
        return if (looksLikeHost(trimmed)) "https://$trimmed" else buildSearchUrl(trimmed, engine)
    }

    fun extractFirstSharedUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = URL_REGEX.find(text)?.value ?: return null
        val cleaned = match.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '»', '"', '\'', '،')
        return if (cleaned.length > "https://".length) cleaned else null
    }

    fun normalizeLinkInput(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val extracted = extractFirstSharedUrl(trimmed)
        if (extracted != null) return extracted
        return if (looksLikeHost(trimmed)) "https://$trimmed" else null
    }
}

object PastedLinkClassifier {
    private val PAGE_HOSTS = setOf(
        "instagram.com", "tiktok.com", "youtube.com", "youtu.be", "facebook.com", "x.com"
    )

    private fun parseWebUri(url: String): URI? {
        return try {
            val parsed = URI(url.trim())
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (parsed.host.isNullOrBlank() || !parsed.userInfo.isNullOrEmpty()) return null
            parsed
        } catch (_: Exception) {
            null
        }
    }

    fun classifyPastedLink(url: String): ClipboardLinkKind? {
        val parsed = parseWebUri(url) ?: return null
        return if (isPastedMediaFile(parsed.toString())) ClipboardLinkKind.MEDIA_FILE else ClipboardLinkKind.WEB_PAGE
    }

    fun isPastedMediaFile(url: String): Boolean {
        val parsed = parseWebUri(url) ?: return false
        val host = parsed.host?.lowercase()?.trimEnd('.') ?: return false
        if (PAGE_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        val normalized = parsed.toString()
        return MediaUrlRules.hasMediaExtension(normalized) ||
            MediaUrlRules.isDashUrl(normalized) ||
            MediaUrlRules.isPlaylistUrl(normalized)
    }

    private fun titleFromUrl(url: String): String? {
        val clean = url.substringBefore('#').substringBefore('?')
        val last = clean.substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        val base = if (dot > 0) last.substring(0, dot) else last
        return base.trim().takeIf { it.length > 2 }
    }

    fun pastedMediaCandidate(url: String): MediaCandidate? {
        if (classifyPastedLink(url) != ClipboardLinkKind.MEDIA_FILE) return null
        val rawExt = MediaUrlRules.pathExtension(url)
        val extension = when {
            rawExt != null && (rawExt in MediaUrlRules.MEDIA_EXTENSIONS || rawExt == MediaUrlRules.DASH_EXTENSION) -> rawExt
            MediaUrlRules.isPlaylistUrl(url) -> "m3u8"
            else -> return null
        }
        val mimeType = when {
            extension == MediaUrlRules.DASH_EXTENSION -> MediaUrlRules.DASH_MIME_TYPE
            extension == "m3u8" || extension == "ts" -> "application/vnd.apple.mpegurl"
            extension in MediaUrlRules.AUDIO_EXTENSIONS -> "audio/$extension"
            else -> "video/$extension"
        }
        return MediaCandidate(
            id = "pasted-$url",
            pageUrl = url,
            mediaUrl = url,
            title = titleFromUrl(url),
            mimeType = mimeType,
            extension = extension,
            source = "JS",
            confidence = 0.8
        )
    }
}

object MediaOfferability {
    enum class Kind { VIDEO, AUDIO, PLAYLIST }

    fun kindOfMedia(mimeType: String?, extension: String?): Kind? {
        val mime = MediaUrlRules.normalizeMime(mimeType)
        val ext = extension?.lowercase()
        if ((ext != null && ext in MediaUrlRules.PLAYLIST_EXTENSIONS) || MediaUrlRules.isPlaylistMime(mime)) {
            return Kind.PLAYLIST
        }
        if (MediaUrlRules.isVideoMime(mime) || (ext != null && ext in MediaUrlRules.VIDEO_EXTENSIONS)) {
            return Kind.VIDEO
        }
        if (MediaUrlRules.isAudioMime(mime) || (ext != null && ext in MediaUrlRules.AUDIO_EXTENSIONS)) {
            return Kind.AUDIO
        }
        return null
    }

    fun unsupportedReasonFor(
        url: String,
        mimeType: String? = null,
        extension: String? = null,
        isLive: Boolean = false,
        isMediaSource: Boolean = false,
        isBlobFile: Boolean = false,
        dashIsSupported: Boolean = true
    ): UnsupportedMedia? {
        if (extension?.lowercase() == MediaUrlRules.DASH_EXTENSION || MediaUrlRules.isDashUrl(url, mimeType)) {
            return if (dashIsSupported) null else UnsupportedMedia.DASH
        }
        if (isLive) return UnsupportedMedia.LIVE
        if (url.startsWith("blob:") && (isMediaSource || !isBlobFile)) {
            return UnsupportedMedia.BLOB_STREAM
        }
        return null
    }
}

object MainContentSelector {
    const val MINIMUM_MAIN_SCORE = 10
    private const val SHORT_CONTENT_MS = 16_000L
    private const val LONG_CONTENT_MS = 45_000L
    private const val SUBSTANTIAL_BYTES = 5L * 1024 * 1024

    private val AD_PATH_MARKERS = listOf(
        "/ads/", "/ad/", "adserver", "advertis", "preroll", "pre-roll", "midroll", "mid-roll",
        "/vast", "vmap", "doubleclick", "imasdk", "companion", "/banners/", "sponsor",
        "bumper", "/promo/", "promo_ad", "ad_break"
    )
    private val SUGGESTED_PATH_MARKERS = listOf(
        "suggest", "recommend", "related", "upnext", "up-next", "sidebar",
        "thumb_video", "preview_clip", "hover_preview"
    )
    private val RESOLUTION_REGEX = Regex("(\\d{3,4})p\\b")

    private fun isOfferableMain(c: MediaCandidate): Boolean {
        if (c.isLive) return false
        if (MediaOfferability.kindOfMedia(c.mimeType, c.extension) == null) return false
        return MediaOfferability.unsupportedReasonFor(
            url = c.mediaUrl,
            mimeType = c.mimeType,
            extension = c.extension,
            isLive = c.isLive,
            isMediaSource = c.isMediaSource,
            isBlobFile = c.isBlobFile,
            dashIsSupported = true
        ) == null
    }

    fun looksLikeAdCandidate(c: MediaCandidate): Boolean {
        if (c.isAd) return true
        val host = AdBlockRules.hostOfUrl(c.mediaUrl)
        if (host != null && AdBlockRules.DEFAULT_BLOCKED_HOSTS.any { AdBlockRules.matchesDomain(host, it) }) {
            return true
        }
        val lowered = c.mediaUrl.lowercase()
        if (AD_PATH_MARKERS.any { lowered.contains(it) }) return true
        if (c.duration != null && c.duration in 1..12_000 && c.muted == true && c.hasControls == false) {
            return true
        }
        return false
    }

    fun looksLikeSuggestedCandidate(c: MediaCandidate): Boolean {
        if (c.isSuggested) return true
        val lowered = c.mediaUrl.lowercase()
        if (SUGGESTED_PATH_MARKERS.any { lowered.contains(it) }) return true
        if (c.width != null && c.width in 1 until 250 && c.height != null && c.height in 1 until 150) {
            return true
        }
        return false
    }

    fun scoreMainCandidate(c: MediaCandidate, visibleUrl: String?, pageUrl: String?): Int {
        var score = 0
        val isAd = looksLikeAdCandidate(c)
        val isSuggested = looksLikeSuggestedCandidate(c)
        val isShortClip = c.duration != null && c.duration in 1 until SHORT_CONTENT_MS

        if (isAd) {
            val host = AdBlockRules.hostOfUrl(c.mediaUrl)
            if (host != null && AdBlockRules.DEFAULT_BLOCKED_HOSTS.any { AdBlockRules.matchesDomain(host, it) }) {
                return Int.MIN_VALUE
            }
            score -= 60
        }
        if (isSuggested) score -= 30

        if (visibleUrl != null && c.mediaUrl == visibleUrl && !isAd && !isSuggested && !isShortClip) {
            score += 50
        }
        if (pageUrl != null && c.pageUrl == pageUrl) score += 8

        when (MediaOfferability.kindOfMedia(c.mimeType, c.extension)) {
            MediaOfferability.Kind.VIDEO -> score += 24
            MediaOfferability.Kind.PLAYLIST -> score += 20
            MediaOfferability.Kind.AUDIO -> score += 6
            null -> return Int.MIN_VALUE
        }

        if (c.width != null && c.height != null && c.width > 0 && c.height > 0) {
            val area = c.width * c.height
            if (area >= 480 * 270) score += 14
            else if (area < 260 * 160) score -= 14
        }
        if (c.hasControls == true) score += 6
        if (c.duration != null && c.duration > 0) {
            when {
                isShortClip -> score -= 42
                c.duration >= LONG_CONTENT_MS -> score += 16
                else -> score += 6
            }
        }
        if (c.estimatedSize != null && c.estimatedSize >= SUBSTANTIAL_BYTES) score += 8

        val resMatch = RESOLUTION_REGEX.find(c.mediaUrl.lowercase())
        if (resMatch != null) {
            val h = resMatch.groupValues[1].toIntOrNull() ?: 0
            score += when {
                h >= 2160 -> 7
                h >= 1080 -> 5
                h >= 720 -> 3
                else -> 1
            }
        }
        score += floor(c.confidence.coerceIn(0.0, 1.0) * 8.0).toInt()
        return score
    }

    fun selectMainCandidate(
        candidates: List<MediaCandidate>,
        visibleUrl: String?,
        pageUrl: String?
    ): MediaCandidate? {
        var bestCandidate: MediaCandidate? = null
        var bestScore = Int.MIN_VALUE
        for (c in candidates) {
            if (!isOfferableMain(c)) continue
            val s = scoreMainCandidate(c, visibleUrl, pageUrl)
            if (s < MINIMUM_MAIN_SCORE) continue
            if (bestCandidate == null || s > bestScore || (s == bestScore && c.confidence > bestCandidate.confidence)) {
                bestCandidate = c
                bestScore = s
            }
        }
        return bestCandidate
    }

    fun rankAndClassifyCandidates(
        candidates: List<MediaCandidate>,
        visibleUrl: String?,
        pageUrl: String?
    ): Pair<MediaCandidate?, List<ClassifiedCandidate>> {
        val main = selectMainCandidate(candidates, visibleUrl, pageUrl)
        val hasLongerNonAd = main != null && candidates.any { c ->
            c.mediaUrl == main.mediaUrl ||
                (!looksLikeAdCandidate(c) && (c.duration == null || c.duration >= SHORT_CONTENT_MS))
        }
        val roleWeight = mapOf(CandidateRole.MAIN to 3, CandidateRole.SUGGESTED to 2, CandidateRole.AD to 1)
        val ranked = candidates.map { c ->
            val s = scoreMainCandidate(c, visibleUrl, pageUrl)
            val role = when {
                main != null && c.mediaUrl == main.mediaUrl -> CandidateRole.MAIN
                looksLikeAdCandidate(c) || (hasLongerNonAd && c.duration != null && c.duration in 1 until SHORT_CONTENT_MS && s < MINIMUM_MAIN_SCORE) ->
                    CandidateRole.AD
                looksLikeSuggestedCandidate(c) -> CandidateRole.SUGGESTED
                else -> CandidateRole.SUGGESTED
            }
            ClassifiedCandidate(c, role, s)
        }.sortedWith { a, b ->
            val rwDiff = (roleWeight[b.role] ?: 0) - (roleWeight[a.role] ?: 0)
            if (rwDiff != 0) rwDiff
            else if (b.score != a.score) b.score - a.score
            else b.candidate.confidence.compareTo(a.candidate.confidence)
        }
        return main to ranked
    }
}

object FloatingSnap {
    fun snapFloatingPosition(
        x: Float,
        y: Float,
        containerWidth: Float,
        containerHeight: Float,
        buttonSize: Float,
        pad: Float = 14f
    ): Pair<Float, Float> {
        val safeW = max(containerWidth, buttonSize + pad * 2f)
        val safeH = max(containerHeight, buttonSize + pad * 2f)
        val minEdge = pad
        val maxX = max(minEdge, safeW - buttonSize - pad)
        val maxY = max(minEdge, safeH - buttonSize - pad)
        val clampedX = min(maxX, max(minEdge, x))
        val clampedY = min(maxY, max(minEdge, y))
        val centerX = clampedX + buttonSize / 2f
        val snappedX = if (centerX < safeW / 2f) minEdge else maxX
        return snappedX to clampedY.roundToInt().toFloat()
    }
}

class LockoutPolicy(
    private val maxAttempts: Int = 3,
    private val baseDelayMillis: Long = 2000L,
    private val maxDelayMillis: Long = 60_000L
) {
    private var failures = 0
    private var lockedUntil = 0L

    fun isLockedOut(now: Long): Boolean = now < lockedUntil

    fun remainingMillis(now: Long): Long = max(0L, lockedUntil - now)

    fun onFailure(now: Long): Long {
        failures += 1
        if (failures < maxAttempts) return 0L
        val exponent = min(failures - maxAttempts, 12)
        val delay = min((baseDelayMillis * 2.0.pow(exponent.toDouble())).toLong(), maxDelayMillis)
        lockedUntil = now + delay
        return delay
    }

    fun onSuccess() {
        failures = 0
        lockedUntil = 0L
    }
}

object SecurityManager {
    const val MIN_PASSWORD_CHARS = 4
    private const val PREFS_NAME = "salvia_security_prefs"
    private const val KEY_VERIFIER = "locked_folder_verifier_v1"
    private const val PBKDF2_ITERATIONS = 120_000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPasswordConfigured(context: Context): Boolean =
        prefs(context).contains(KEY_VERIFIER)

    private fun derivePbkdf2(password: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun setPassword(context: Context, password: String): Boolean {
        if (password.length < MIN_PASSWORD_CHARS) return false
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = derivePbkdf2(password, salt, PBKDF2_ITERATIONS)
        val json = JSONObject()
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("hash", hash)
            .put("iterations", PBKDF2_ITERATIONS)
        prefs(context).edit().putString(KEY_VERIFIER, json.toString()).apply()
        return true
    }

    fun verifyPassword(context: Context, password: String): Boolean {
        val raw = prefs(context).getString(KEY_VERIFIER, null) ?: return false
        return try {
            val obj = JSONObject(raw)
            val salt = Base64.decode(obj.getString("salt"), Base64.NO_WRAP)
            val iterations = obj.optInt("iterations", PBKDF2_ITERATIONS)
            val expectedHash = obj.getString("hash")
            val derived = derivePbkdf2(password, salt, iterations)
            java.security.MessageDigest.isEqual(
                derived.toByteArray(Charsets.UTF_8),
                expectedHash.toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            false
        }
    }

    fun removePassword(context: Context) {
        prefs(context).edit().remove(KEY_VERIFIER).apply()
    }
}

object DownloadFileResolver {
    fun sanitizeStorageId(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    fun legacySanitizeStorageId(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9._-]"), "")

    fun findInternalDownloadFile(
        downloadsDir: java.io.File,
        id: String,
        recordedFilePath: String? = null
    ): java.io.File? {
        if (!recordedFilePath.isNullOrBlank()) {
            val recorded = java.io.File(recordedFilePath)
            if (recorded.isFile && recorded.length() > 0L) return recorded
        }
        if (!downloadsDir.isDirectory) return null
        val safe = sanitizeStorageId(id)
        val legacy = legacySanitizeStorageId(id)
        val exactCandidates = listOfNotNull(
            java.io.File(downloadsDir, safe),
            legacy.takeIf { it != safe }?.let { java.io.File(downloadsDir, it) }
        )
        for (c in exactCandidates) {
            if (c.isFile && c.length() > 0L) return c
        }
        val matching = downloadsDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.length() > 0L && (
                file.name == safe ||
                    file.name.startsWith("$safe.") ||
                    (legacy.isNotEmpty() && (file.name == legacy || file.name.startsWith("$legacy.")))
                )
        }
        return matching
    }

    fun collectArtifactsForDeletion(
        downloadsDir: java.io.File,
        id: String,
        recordedFilePath: String? = null
    ): List<java.io.File> {
        val out = LinkedHashSet<java.io.File>()
        if (!recordedFilePath.isNullOrBlank()) {
            val recorded = java.io.File(recordedFilePath)
            if (recorded.exists()) out.add(recorded)
        }
        if (downloadsDir.isDirectory) {
            val safe = sanitizeStorageId(id)
            val legacy = legacySanitizeStorageId(id)
            downloadsDir.listFiles()?.forEach { file ->
                val name = file.name
                val matchesSafe = name == safe || name.startsWith("$safe.") ||
                    name == "seg_$safe" || name == "parts_$safe"
                val matchesLegacy = legacy.isNotEmpty() && (
                    name == legacy || name.startsWith("$legacy.") ||
                        name == "seg_$legacy" || name == "parts_$legacy"
                    )
                if (matchesSafe || matchesLegacy) {
                    out.add(file)
                }
            }
        }
        return out.toList()
    }
}
