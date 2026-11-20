package com.salvia.salviabrowxer.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent download queue: every job's full specification is written to a JSON file when it
 * starts and updated as its status changes. This is what lets downloads survive an app kill
 * (and, on Android versions that allow it, a reboot) and resume without user interaction.
 */
object DownloadQueueStore {

    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_PAUSED = "PAUSED"
    const val STATUS_PAUSED_WIFI = "PAUSED_WIFI"
    const val STATUS_COMPLETE = "COMPLETE"
    const val STATUS_CANCELLED = "CANCELLED"
    const val STATUS_FAILED = "FAILED"

    data class JobSpec(
        val id: String,
        val url: String,
        val filename: String,
        val mimeType: String,
        val isHls: Boolean,
        val connections: Int,
        val autoSave: Boolean,
        val audioTrackUrl: String?,
        val headersJson: String?,
        val wifiOnly: Boolean,
        val referer: String?,
        val createdAt: Long,
        val status: String
    )

    private val lock = Any()

    private fun file(context: Context): File = File(context.filesDir, "download_queue.json")

    private fun readAll(context: Context): JSONObject {
        return try {
            val raw = file(context).readText()
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (ignored: Exception) {
            JSONObject()
        }
    }

    private fun writeAll(context: Context, root: JSONObject) {
        try {
            val tmp = File(context.filesDir, "download_queue.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file(context))) {
                file(context).writeText(root.toString())
                tmp.delete()
            }
        } catch (ignored: Exception) {
        com.salvia.salviabrowxer.core.SalviaLog.w("DownloadQueueStore", ignored)
        }
    }

    fun saveJob(context: Context, spec: JobSpec) {
        synchronized(lock) {
            val root = readAll(context)
            root.put(
                spec.id,
                JSONObject()
                    .put("id", spec.id)
                    .put("url", spec.url)
                    .put("filename", spec.filename)
                    .put("mime", spec.mimeType)
                    .put("isHls", spec.isHls)
                    .put("connections", spec.connections)
                    .put("autoSave", spec.autoSave)
                    .put("audioTrackUrl", spec.audioTrackUrl ?: JSONObject.NULL)
                    .put("headers", spec.headersJson ?: JSONObject.NULL)
                    .put("wifiOnly", spec.wifiOnly)
                    .put("referer", spec.referer ?: JSONObject.NULL)
                    .put("createdAt", spec.createdAt)
                    .put("status", spec.status)
            )
            writeAll(context, root)
        }
    }

    fun updateStatus(context: Context, id: String, status: String) {
        synchronized(lock) {
            val root = readAll(context)
            val entry = root.optJSONObject(id) ?: return
            entry.put("status", status)
            writeAll(context, root)
        }
    }

    fun removeJob(context: Context, id: String) {
        synchronized(lock) {
            val root = readAll(context)
            root.remove(id)
            writeAll(context, root)
        }
    }

    fun getJob(context: Context, id: String): JobSpec? {
        val entry = readAll(context).optJSONObject(id) ?: return null
        return specFromJson(entry)
    }

    fun allJobs(context: Context): List<JobSpec> {
        val root = readAll(context)
        val out = ArrayList<JobSpec>()
        for (key in root.keys()) {
            root.optJSONObject(key)?.let { specFromJson(it) }?.let { out.add(it) }
        }
        return out
    }

    fun jobsIn(context: Context, vararg statuses: String): List<JobSpec> {
        return allJobs(context).filter { it.status in statuses }
    }

    private fun specFromJson(entry: JSONObject): JobSpec? {
        val id = entry.optString("id")
        val url = entry.optString("url")
        if (id.isBlank() || url.isBlank()) return null
        return JobSpec(
            id = id,
            url = url,
            filename = entry.optString("filename", "media.mp4").ifBlank { "media.mp4" },
            mimeType = entry.optString("mime", "video/mp4").ifBlank { "video/mp4" },
            isHls = entry.optBoolean("isHls", false),
            connections = entry.optInt("connections", 4),
            autoSave = entry.optBoolean("autoSave", true),
            audioTrackUrl = entry.optString("audioTrackUrl").takeIf { it.isNotBlank() },
            headersJson = entry.optString("headers").takeIf { it.isNotBlank() },
            wifiOnly = entry.optBoolean("wifiOnly", false),
            referer = entry.optString("referer").takeIf { it.isNotBlank() },
            createdAt = entry.optLong("createdAt", 0L),
            status = entry.optString("status", STATUS_RUNNING)
        )
    }

    /** Debug helper for the diagnostics screen. */
    fun summarize(context: Context): JSONArray {
        val arr = JSONArray()
        for (job in allJobs(context)) {
            arr.put(JSONObject().put("id", job.id).put("status", job.status).put("filename", job.filename))
        }
        return arr
    }
}
