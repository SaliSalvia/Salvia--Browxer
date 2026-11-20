package com.salvia.salviabrowxer.browser

import com.salvia.salviabrowxer.core.AdBlockHostRules
import java.net.URI

data class SniffedMedia(val url: String, val mimeType: String)

/** One #EXT-X-STREAM-INF entry of an HLS master playlist. */
data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val codecs: String?
)

/** One #EXT-X-MEDIA rendition (extra audio language or subtitle track). */
data class HlsRendition(
    val type: String,
    val uri: String?,
    val name: String?,
    val language: String?,
    val isDefault: Boolean
)

/**
 * Classifies a network request seen by the WebView as "probably a media file / playlist".
 *
 * URLs the page itself requested with a known media extension are offered directly; segment
 * pieces (.ts, .m4s, byte-ranges) are ignored and stand in for their playlist. Extension-less
 * URLs are handled by MediaProber (Content-Type based), not here.
 */
object MediaSniffer {

    private val segmentExtensions: Set<String> = setOf(
        "ts", "m4s", "cmfv", "cmfa", "cmft", "aac", "mp4a", "vtt", "srt", "webvtt"
    )

    private val mimeByExtension: Map<String, String> = mapOf(
        "m3u8" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "3gp" to "video/3gpp",
        "flv" to "video/x-flv",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "ogv" to "video/ogg",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "wav" to "audio/wav",
        "flac" to "audio/flac"
    )

    // File names that are pieces of a stream rather than a whole file.
    private val segmentName = Regex("(^|[-_.])(init|seg|segment|chunk|frag|fragment)([-_.]|[0-9]|$)")

    fun classify(rawUrl: String?): SniffedMedia? {
        if (rawUrl.isNullOrBlank()) return null
        if (!(rawUrl.startsWith("http://") || rawUrl.startsWith("https://"))) return null
        val uri: URI = try {
            URI(rawUrl)
        } catch (ignored: Exception) {
            return null
        }
        val host = uri.host ?: return null
        if (AdBlockHostRules.isBlockedHost(host)) return null

        val path = (uri.path ?: "").lowercase()
        val query = (uri.query ?: "").lowercase()
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        if (ext.isEmpty() || ext in segmentExtensions) return null
        val mime = mimeByExtension[ext] ?: return null

        val isManifest = ext == "m3u8" || ext == "mpd"
        if (!isManifest) {
            if (segmentName.containsMatchIn(name)) return null
            if (query.contains("bytestart=") || query.contains("byteend=") || query.contains("range=")) return null
        }
        return SniffedMedia(rawUrl, mime)
    }

    /** True when the URL path ends with one of the known media file extensions. */
    fun hasMediaExtension(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val path = try {
            URI(rawUrl).path?.lowercase() ?: return false
        } catch (ignored: Exception) {
            return false
        }
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "")
        return ext.isNotEmpty() && ext in mimeByExtension
    }

    // ------------------------------------------------------------------ HLS master playlist

    /**
     * Parses the variants of an HLS master playlist (`#EXT-X-STREAM-INF`). Returns an empty list
     * for media playlists, so callers can use this to detect which kind they are looking at.
     */
    fun parseMasterVariants(manifestText: String, baseUrl: String): List<HlsVariant> {
        if (!manifestText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return emptyList()
        val base = try {
            URI(baseUrl)
        } catch (ignored: Exception) {
            return emptyList()
        }
        val out = ArrayList<HlsVariant>()
        var pendingBandwidth = 0L
        var pendingWidth = 0
        var pendingHeight = 0
        var pendingCodecs: String? = null
        var expectingUri = false
        for (raw in manifestText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                pendingBandwidth = attrLong(line, "BANDWIDTH")
                val resolution = attrString(line, "RESOLUTION")
                if (resolution != null) {
                    val (w, h) = splitResolution(resolution)
                    pendingWidth = w
                    pendingHeight = h
                }
                pendingCodecs = attrString(line, "CODECS")
                expectingUri = true
            } else if (expectingUri && !line.startsWith("#")) {
                expectingUri = false
                try {
                    val resolved = base.resolve(line).toString()
                    out.add(
                        HlsVariant(
                            url = resolved,
                            bandwidth = pendingBandwidth,
                            width = pendingWidth.toInt(),
                            height = pendingHeight.toInt(),
                            codecs = pendingCodecs
                        )
                    )
                } catch (ignored: Exception) {
                com.salvia.salviabrowxer.core.SalviaLog.w("MediaSniffer", ignored)
                }
            }
        }
        return out
    }

    /** Parses `#EXT-X-MEDIA` renditions: extra audio languages and subtitle tracks. */
    fun parseRenditions(manifestText: String, baseUrl: String): List<HlsRendition> {
        val base = try {
            URI(baseUrl)
        } catch (ignored: Exception) {
            return emptyList()
        }
        val out = ArrayList<HlsRendition>()
        for (raw in manifestText.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) continue
            val type = attrString(line, "TYPE")?.uppercase() ?: continue
            if (type != "AUDIO" && type != "SUBTITLES" && type != "CLOSED-CAPTIONS") continue
            val rawUri = attrString(line, "URI")
            val uri = if (rawUri.isNullOrBlank()) {
                null
            } else {
                try {
                    base.resolve(rawUri).toString()
                } catch (ignored: Exception) {
                    rawUri
                }
            }
            out.add(
                HlsRendition(
                    type = type,
                    uri = uri,
                    name = attrString(line, "NAME"),
                    language = attrString(line, "LANGUAGE"),
                    isDefault = attrString(line, "DEFAULT")?.equals("YES", ignoreCase = true) == true
                )
            )
        }
        return out
    }

    private fun splitResolution(value: String): Pair<Int, Int> {
        val parts = value.split("x")
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return w to h
    }

    private fun attrLong(line: String, attr: String): Long {
        val match = Regex("""$attr=("[^"]*"|[^\s,]+)""", RegexOption.IGNORE_CASE).find(line) ?: return 0L
        return match.groupValues[1].trim('"').toLongOrNull() ?: 0L
    }

    private fun attrString(line: String, attr: String): String? {
        val match = Regex("""$attr=("[^"]*"|[^\s,]+)""", RegexOption.IGNORE_CASE).find(line) ?: return null
        val value = match.groupValues[1].trim('"')
        return value.ifEmpty { null }
    }
}
