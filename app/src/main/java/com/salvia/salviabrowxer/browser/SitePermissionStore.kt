package com.salvia.salviabrowxer.browser

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Per-site decisions for web permissions (camera, microphone, geolocation), persisted as a small
 * JSON file. This is what lets a real browser ask once and then behave like the user expects.
 *
 * Layout: { "origin": { "camera": true|false, "microphone": true|false, "location": true|false } }
 */
object SitePermissionStore {

    const val KIND_CAMERA = "camera"
    const val KIND_MICROPHONE = "microphone"
    const val KIND_LOCATION = "location"

    private val lock = Any()
    private var cache: JSONObject? = null

    private fun file(context: Context): File = File(context.filesDir, "site_permissions.json")

    private fun load(context: Context): JSONObject {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val parsed = try {
                val raw = file(context).readText()
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } catch (ignored: Exception) {
                JSONObject()
            }
            cache = parsed
            return parsed
        }
    }

    private fun persist(context: Context, data: JSONObject) {
        try {
            val tmp = File(context.filesDir, "site_permissions.json.tmp")
            tmp.writeText(data.toString())
            if (!tmp.renameTo(file(context))) {
                file(context).writeText(data.toString())
                tmp.delete()
            }
        } catch (ignored: Exception) {
        }
    }

    /** Origin (scheme://host[:port]) for a raw URL, or null when not http(s). */
    fun originOf(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            val port = uri.port
            if (port == -1 || port == (if (scheme == "https") 443 else 80)) {
                "$scheme://$host"
            } else {
                "$scheme://$host:$port"
            }
        } catch (ignored: Exception) {
            null
        }
    }

    /** The remembered decision for this origin, or null when the user has never decided. */
    fun get(context: Context, origin: String, kind: String): Boolean? {
        val value = load(context).optJSONObject(origin)?.opt(kind) ?: return null
        return value as? Boolean
    }

    fun put(context: Context, origin: String, kind: String, granted: Boolean) {
        synchronized(lock) {
            val data = load(context)
            val entry = data.optJSONObject(origin) ?: JSONObject().also { data.put(origin, it) }
            entry.put(kind, granted)
            cache = data
            persist(context, data)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            cache = JSONObject()
            file(context).delete()
        }
    }
}
