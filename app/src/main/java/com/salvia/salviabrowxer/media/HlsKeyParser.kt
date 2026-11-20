package com.salvia.salviabrowxer.media

import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class HlsKeySpec(
    val method: String,
    val keyUri: String?,
    val explicitIv: ByteArray?
)

data class HlsSegmentEntry(
    val url: String,
    val sequenceNumber: Long,
    val keySpec: HlsKeySpec?,
    val isInitSegment: Boolean = false,
    /** When set, only [byteRangeLength] bytes starting at [byteRangeOffset] of [url] are the segment. */
    val byteRangeOffset: Long? = null,
    val byteRangeLength: Long? = null,
    /** True when the stream switched codecs/timestamps here (EXT-X-DISCONTINUITY). */
    val isDiscontinuity: Boolean = false
)

object HlsKeyParser {
    private val attrRegex = Regex("([A-Z0-9-]+)=(\"([^\"]*)\"|([^,]*))")

    fun parsePlaylistEntries(manifestText: String, baseUrl: String): List<HlsSegmentEntry> {
        val base = try {
            URI(baseUrl)
        } catch (ignored: Exception) {
            return emptyList()
        }

        val entries = ArrayList<HlsSegmentEntry>()
        var sequence = 0L
        var activeKey: HlsKeySpec? = null
        var discontinuity = false

        // EXT-X-BYTERANGE state: when a byterange omits its offset, it continues right after the
        // previous byterange on the same resource (RFC 8216 §4.3.2.2).
        var byterangeResourceUrl: String? = null
        var byterangeNextOffset = 0L
        var pendingByteRangeLength: Long? = null
        var pendingByteRangeOffset: Long? = null

        fun resolve(raw: String): String = try {
            base.resolve(raw).toString()
        } catch (ignored: Exception) {
            raw
        }

        fun applyByteRange(url: String): Triple<Long?, Long?, String?> {
            var length = pendingByteRangeLength
            var offset = pendingByteRangeOffset
            if (length != null) {
                if (offset == null) {
                    if (byterangeResourceUrl == url) {
                        offset = byterangeNextOffset
                    } else {
                        offset = 0L
                    }
                }
                byterangeResourceUrl = url
                byterangeNextOffset = offset + length
            }
            pendingByteRangeLength = null
            pendingByteRangeOffset = null
            return Triple(length, offset, null)
        }

        for (raw in manifestText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true)) {
                sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                continue
            }

            if (line.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true)) {
                // Marks a codec / timestamp break; the remuxer tolerates it, but we remember it.
                discontinuity = true
                continue
            }

            if (line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true)) {
                val spec = line.substringAfter(':').trim()
                val parts = spec.split('@')
                pendingByteRangeLength = parts.getOrNull(0)?.trim()?.toLongOrNull()
                pendingByteRangeOffset = parts.getOrNull(1)?.trim()?.toLongOrNull()
                if (pendingByteRangeLength == null) pendingByteRangeOffset = null
                continue
            }

            if (line.startsWith("#EXT-X-KEY:", ignoreCase = true)) {
                val attrs = parseAttributes(line.substringAfter(':'))
                val method = attrs["METHOD"]?.uppercase() ?: "NONE"
                activeKey = if (method == "NONE") {
                    null
                } else {
                    val uri = attrs["URI"]?.let { rawUri -> resolve(rawUri) }
                    val ivBytes = attrs["IV"]?.let { parseHexIv(it) }
                    HlsKeySpec(method = method, keyUri = uri, explicitIv = ivBytes)
                }
                continue
            }

            if (line.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
                val attrs = parseAttributes(line.substringAfter(':'))
                val rawUri = attrs["URI"]
                if (!rawUri.isNullOrBlank()) {
                    val url = resolve(rawUri)
                    // MAP may itself be a byte range of a shared resource.
                    val range = attrs["BYTERANGE"]
                    var offset: Long? = null
                    var length: Long? = null
                    if (!range.isNullOrBlank()) {
                        val parts = range.split('@')
                        length = parts.getOrNull(0)?.trim()?.toLongOrNull()
                        offset = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                        byterangeResourceUrl = url
                        byterangeNextOffset = (offset ?: 0L) + (length ?: 0L)
                    }
                    entries.add(
                        HlsSegmentEntry(
                            url = url,
                            sequenceNumber = sequence,
                            keySpec = activeKey,
                            isInitSegment = true,
                            byteRangeOffset = offset,
                            byteRangeLength = length,
                            isDiscontinuity = discontinuity
                        )
                    )
                    discontinuity = false
                }
                continue
            }

            if (line.startsWith("#")) continue

            try {
                val url = resolve(line)
                val (length, offset, _) = applyByteRange(url)
                entries.add(
                    HlsSegmentEntry(
                        url = url,
                        sequenceNumber = sequence,
                        keySpec = activeKey,
                        byteRangeOffset = offset,
                        byteRangeLength = length,
                        isDiscontinuity = discontinuity
                    )
                )
                discontinuity = false
                sequence++
            } catch (ignored: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("HlsKeyParser", ignored)
            }
        }
        return entries
    }

    fun sequenceIv(sequenceNumber: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequenceNumber
        for (i in 15 downTo 8) {
            iv[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return iv
    }

    fun parseHexIv(raw: String): ByteArray? {
        val clean = raw.trim().removePrefix("0x").removePrefix("0X")
        if (clean.isEmpty() || clean.length > 32) return null
        val padded = clean.padStart(32, '0')
        return try {
            ByteArray(16) { idx ->
                padded.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        } catch (ignored: Exception) {
            null
        }
    }

    fun decryptAes128Cbc(encrypted: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        if (keyBytes.size != 16 || ivBytes.size != 16) {
            throw IllegalArgumentException("AES-128 requires 16-byte key and IV")
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return cipher.doFinal(encrypted)
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (match in attrRegex.findAll(raw)) {
            val k = match.groupValues[1].uppercase()
            val v = match.groupValues[3].ifEmpty { match.groupValues[4] }
            map[k] = v
        }
        return map
    }
}
