package com.salvia.salviabrowxer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.salvia.salviabrowxer.MainActivity
import com.salvia.salviabrowxer.R
import com.salvia.salviabrowxer.SalviaApplication
import com.salvia.salviabrowxer.core.NativeProxyClient
import com.salvia.salviabrowxer.core.RangeChunkPlanner
import com.salvia.salviabrowxer.media.DashManifestParser
import com.salvia.salviabrowxer.media.DashRepresentation
import com.salvia.salviabrowxer.media.HlsKeyParser
import com.salvia.salviabrowxer.media.NativeMediaMuxer
import com.salvia.salviabrowxer.media.SidxParser
import com.salvia.salviabrowxer.storage.MediaPublisher
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object NativeDownloadBus {
    @Volatile
    var listener: ((JSONObject) -> Unit)? = null

    fun emit(payload: JSONObject) {
        listener?.invoke(payload)
    }
}

/** A non-2xx HTTP answer. Only some codes are worth retrying (see NativeDownloadService.isRetryable). */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Thrown when the device runs out of storage; surfaced with a dedicated error code. */
class InsufficientSpaceException(needed: Long, available: Long) :
    IOException("Insufficient storage: need ~${needed / 1_000_000} MB, only ${available / 1_000_000} MB free")

/**
 * Foreground download engine.
 *
 * * **True resume**: HLS / DASH segment files live in `filesDir/downloads/seg_<id>/` and survive
 *   pause, failure and app kills; resuming skips every segment already on disk instead of
 *   restarting from zero.
 * * **Persistent queue**: every job's spec is saved in [DownloadQueueStore]; jobs that were
 *   running when the app died (or the device rebooted) are picked up again on app start.
 * * **Headers**: the page's own Authorization / Origin / User-Agent headers are replayed, and
 *   the OkHttp cookie jar mirrors the WebView cookies, so token-signed CDNs keep working.
 * * **Wi-Fi only**: per-job; when the network becomes metered the job pauses with reason "wifi"
 *   and resumes automatically when unmetered again.
 * * **Android 14+**: uses the `userInitiatedDataTransfer` foreground type (downloads are started
 *   by the user), which is not subject to the 6-hour `dataSync` limit.
 */
class NativeDownloadService : Service() {

    private data class JobState(
        val id: String,
        val filename: String,
        val referer: String?,
        val headers: Map<String, String>,
        val wifiOnly: Boolean,
        val notifId: Int,
        val paused: AtomicBoolean = AtomicBoolean(false),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        /** Set when the pause came from the network guard, so the stored status stays PAUSED_WIFI
         *  (and auto-resumes) instead of being overwritten with the user-paused status. */
        val pausedForWifi: AtomicBoolean = AtomicBoolean(false),
        val activeCalls: MutableList<okhttp3.Call> = java.util.Collections.synchronizedList(mutableListOf())
    )

    private val jobs = ConcurrentHashMap<String, JobState>()
    private val pool = Executors.newCachedThreadPool()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerNetworkWatch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID)
                val url = intent.getStringExtra(EXTRA_URL)
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "media.mp4"

                // startForegroundService() obliges the service to call startForeground() within a
                // few seconds, even when the request turns out to be unusable. Promote first and
                // bail out afterwards, otherwise a bad request crashes the whole app.
                promoteForeground(filename, 0)
                if (id.isNullOrBlank() || url.isNullOrBlank()) {
                    if (jobs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                    return START_NOT_STICKY
                }
                if (jobs.containsKey(id)) return START_NOT_STICKY

                val mimeType = intent.getStringExtra(EXTRA_MIME) ?: "video/mp4"
                val isHls = intent.getBooleanExtra(EXTRA_IS_HLS, false)
                val connections = intent.getIntExtra(EXTRA_CONNECTIONS, 4).coerceIn(1, 8)
                val autoSave = intent.getBooleanExtra(EXTRA_AUTO_SAVE, true)
                val audioTrackUrl = intent.getStringExtra(EXTRA_AUDIO_TRACK_URL)?.takeIf { it.isNotBlank() }
                val headers = parseHeadersExtra(intent.getStringExtra(EXTRA_HEADERS))
                val wifiOnly = intent.getBooleanExtra(EXTRA_WIFI_ONLY, false)
                val referer = pendingReferers.remove(id) ?: intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }

                val job = JobState(
                    id = id,
                    filename = filename,
                    referer = referer,
                    headers = headers,
                    wifiOnly = wifiOnly,
                    notifId = notifIdFor(id)
                )
                jobs[id] = job
                // Move the foreground association onto this job's own notification.
                startForegroundInternal(job.notifId, buildProgressNotification(filename, 0, id))

                DownloadQueueStore.saveJob(
                    this,
                    DownloadQueueStore.JobSpec(
                        id = id,
                        url = url,
                        filename = filename,
                        mimeType = mimeType,
                        isHls = isHls,
                        connections = connections,
                        autoSave = autoSave,
                        audioTrackUrl = audioTrackUrl,
                        headersJson = if (headers.isEmpty()) null else JSONObject(headers).toString(),
                        wifiOnly = wifiOnly,
                        referer = referer,
                        createdAt = System.currentTimeMillis(),
                        status = DownloadQueueStore.STATUS_RUNNING
                    )
                )

                pool.execute {
                    runDownload(job, url, mimeType, isHls, connections, autoSave, audioTrackUrl, restored = false)
                }
            }
            ACTION_RESUME -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id != null) {
                    promoteForeground(id, 0)
                    resumeSpec(id, restored = false)
                }
                if (jobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_RESUME_ALL -> {
                val ids = DownloadQueueStore.jobsIn(this, DownloadQueueStore.STATUS_RUNNING).map { it.id }
                if (ids.isNotEmpty()) promoteForeground("SalviaBrowxer", 0)
                for (id in ids) resumeSpec(id, restored = true)
                if (jobs.isEmpty() && ids.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_PAUSE -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id != null) {
                    jobs[id]?.let { job ->
                        job.paused.set(true)
                        synchronized(job.activeCalls) {
                            job.activeCalls.toList().forEach { it.cancel() }
                        }
                    }
                }
                if (jobs.isEmpty()) stopSelf(startId)
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id != null) {
                    jobs[id]?.let { job ->
                        job.cancelled.set(true)
                        synchronized(job.activeCalls) {
                            job.activeCalls.toList().forEach { it.cancel() }
                        }
                    } ?: run {
                        // Not running (service was restarted just for this): clean up directly.
                        cleanupJobFiles(id)
                        DownloadQueueStore.removeJob(this@NativeDownloadService, id)
                        NativeDownloadBus.emit(JSONObject().put("type", "cancelled").put("id", id))
                        NotificationManagerCompat(this).cancel(notifIdFor(id))
                    }
                }
                if (jobs.isEmpty()) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** Re-runs a persisted job (pause / failure / app-kill recovery). */
    private fun resumeSpec(id: String, restored: Boolean) {
        if (jobs.containsKey(id)) return
        val spec = DownloadQueueStore.getJob(this, id)
        if (spec == null) {
            if (jobs.isEmpty()) stopSelf()
            return
        }
        val job = JobState(
            id = spec.id,
            filename = spec.filename,
            referer = spec.referer,
            headers = parseHeadersExtra(spec.headersJson),
            wifiOnly = spec.wifiOnly,
            notifId = notifIdFor(spec.id)
        )
        jobs[spec.id] = job
        startForegroundInternal(job.notifId, buildProgressNotification(spec.filename, 0, spec.id))
        DownloadQueueStore.updateStatus(this, spec.id, DownloadQueueStore.STATUS_RUNNING)
        if (restored) {
            NativeDownloadBus.emit(JSONObject().put("type", "restored").put("id", spec.id))
        }
        pool.execute {
            runDownload(
                job,
                spec.url,
                spec.mimeType,
                spec.isHls,
                spec.connections,
                spec.autoSave,
                spec.audioTrackUrl,
                restored = restored
            )
        }
    }

    override fun onDestroy() {
        unregisterNetworkWatch()
        jobs.values.forEach { job ->
            job.cancelled.set(true)
            synchronized(job.activeCalls) {
                job.activeCalls.forEach { it.cancel() }
            }
        }
        pool.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Wi-Fi / network guard

    private fun isCurrentlyMetered(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun registerNetworkWatch() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    if (metered) {
                        // Pause every Wi-Fi-only job.
                        for (job in jobs.values) {
                            if (job.wifiOnly && !job.paused.get()) {
                                job.paused.set(true)
                                job.pausedForWifi.set(true)
                                synchronized(job.activeCalls) {
                                    job.activeCalls.toList().forEach { it.cancel() }
                                }
                                DownloadQueueStore.updateStatus(
                                    this@NativeDownloadService, job.id, DownloadQueueStore.STATUS_PAUSED_WIFI
                                )
                                NativeDownloadBus.emit(
                                    JSONObject().put("type", "paused").put("id", job.id).put("reason", "wifi")
                                )
                            }
                        }
                    } else {
                        // Back on an unmetered network: bring Wi-Fi-held jobs back.
                        val held = DownloadQueueStore.jobsIn(
                            this@NativeDownloadService, DownloadQueueStore.STATUS_PAUSED_WIFI
                        )
                        for (spec in held) {
                            if (!jobs.containsKey(spec.id)) {
                                pool.execute { resumeSpec(spec.id, restored = false) }
                            }
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (ignored: Exception) {
        }
    }

    private fun unregisterNetworkWatch() {
        try {
            networkCallback?.let {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            }
        } catch (ignored: Exception) {
        }
        networkCallback = null
    }

    // ------------------------------------------------------------------ job runner

    private fun runDownload(
        job: JobState,
        url: String,
        mimeType: String,
        isHls: Boolean,
        connections: Int,
        autoSave: Boolean,
        audioTrackUrl: String?,
        restored: Boolean
    ) {
        val downloadsDir = File(filesDir, "downloads").apply { mkdirs() }
        val safeStorageId = job.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val ext = job.filename.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "mp4"
        val fileKey = "$safeStorageId.$ext"
        val outFile = File(downloadsDir, fileKey)
        // Persistent segment/parts storage: survives pause and app kills so resume is real.
        val partsDir = File(downloadsDir, "parts_$safeStorageId")
        val segDir = File(downloadsDir, "seg_$safeStorageId")
        val startedAt = System.currentTimeMillis()
        var lastReportAt = 0L
        var lastProgressDownloaded = 0L

        fun reportProgress(downloaded: Long, total: Long?, force: Boolean = false) {
            val now = System.currentTimeMillis()
            lastProgressDownloaded = downloaded
            if (!force && now - lastReportAt < 250L) return
            lastReportAt = now
            val elapsedSec = maxOf(0.1, (now - startedAt) / 1000.0)
            val speed = (downloaded / elapsedSec).toLong()
            val eta = if (total != null && total > downloaded && speed > 0L) {
                ((total - downloaded) + speed - 1L) / speed
            } else {
                null
            }
            val pct = if (total != null && total > 0L) {
                ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
            } else {
                -1
            }
            updateNotification(job, pct)
            NativeDownloadBus.emit(
                JSONObject()
                    .put("type", "progress")
                    .put("id", job.id)
                    .put("downloadedBytes", downloaded)
                    .put("totalBytes", total ?: JSONObject.NULL)
                    .put("bytesPerSecond", speed)
                    .put("etaSeconds", eta ?: JSONObject.NULL)
            )
        }

        try {
            // Wi-Fi guard before touching the network.
            if (job.wifiOnly && isCurrentlyMetered()) {
                DownloadQueueStore.updateStatus(this, job.id, DownloadQueueStore.STATUS_PAUSED_WIFI)
                NativeDownloadBus.emit(
                    JSONObject().put("type", "paused").put("id", job.id).put("reason", "wifi")
                )
                return
            }

            // Basic free-space guard before anything expensive (10 MB floor is enough for small
            // files; larger downloads re-check once their size is known).
            checkDiskSpace(minimumBytes = 10L * 1024 * 1024)

            val outInfo: OutputInfo = if (isHls) {
                downloadHls(job, url, audioTrackUrl, outFile, segDir) { dl, est -> reportProgress(dl, est) }
            } else if (isDashUrl(url)) {
                downloadDash(job, url, audioTrackUrl, downloadsDir, safeStorageId, segDir) { dl, est -> reportProgress(dl, est) }
            } else if (!audioTrackUrl.isNullOrBlank()) {
                segDir.mkdirs()
                val videoTmp = File(segDir, "video.bin")
                val audioTmp = File(segDir, "audio.bin")
                try {
                    downloadDirect(job, url, videoTmp, partsDir, connections) { dl, total -> reportProgress(dl, total) }
                    var audioReady = false
                    if (!job.paused.get() && !job.cancelled.get()) {
                        try {
                            downloadSingleStream(job, audioTrackUrl, audioTmp)
                            audioReady = audioTmp.exists() && audioTmp.length() > 0L
                        } catch (audioError: Throwable) {
                            // Ship the finished video without its extra audio track instead of
                            // failing the whole job (same behaviour as yt-dlp).
                            audioReady = false
                        }
                    }
                    if (!job.paused.get() && !job.cancelled.get()) {
                        val muxed = muxOrCopy(videoTmp, if (audioReady) audioTmp else null, outFile)
                        OutputInfo(muxed, detectMime(muxed, url))
                    } else {
                        OutputInfo(outFile, "video/mp4")
                    }
                } finally {
                    videoTmp.delete()
                    audioTmp.delete()
                }
            } else {
                downloadDirect(job, url, outFile, partsDir, connections) { dl, total -> reportProgress(dl, total) }
                val finalMime = detectMime(outFile, url)
                OutputInfo(outFile, finalMime)
            }

            if (job.paused.get()) {
                // Keep the wifi-specific status when the network guard paused this job, otherwise
                // coming back online could never find it for auto-resume.
                val pausedEvt = JSONObject().put("type", "paused").put("id", job.id)
                if (job.pausedForWifi.get()) {
                    DownloadQueueStore.updateStatus(this, job.id, DownloadQueueStore.STATUS_PAUSED_WIFI)
                    pausedEvt.put("reason", "wifi")
                } else {
                    DownloadQueueStore.updateStatus(this, job.id, DownloadQueueStore.STATUS_PAUSED)
                }
                NativeDownloadBus.emit(pausedEvt)
                return
            }
            if (job.cancelled.get()) {
                outFile.delete()
                cleanupJobFiles(job.id)
                DownloadQueueStore.removeJob(this, job.id)
                NativeDownloadBus.emit(JSONObject().put("type", "cancelled").put("id", job.id))
                return
            }

            val finalFile = outInfo.file
            val finalMime = outInfo.mimeType
            val finalSize = finalFile.length()
            reportProgress(finalSize, finalSize, force = true)

            val publishedUri = if (autoSave) {
                MediaPublisher.publishToDevice(this, finalFile, job.filename, finalMime)
            } else {
                null
            }

            cleanupJobFiles(job.id)
            DownloadQueueStore.removeJob(this, job.id)

            val localPlaybackUrl = "https://appassets.androidplatform.net/api/local-file?id=${
                java.net.URLEncoder.encode(finalFile.name, "UTF-8")
            }&mime=${
                java.net.URLEncoder.encode(finalMime, "UTF-8")
            }"

            NativeDownloadBus.emit(
                JSONObject()
                    .put("type", "complete")
                    .put("id", job.id)
                    .put("blobUrl", localPlaybackUrl)
                    .put("finalSize", finalSize)
                    .put("mimeType", finalMime)
                    .put("filePath", finalFile.absolutePath)
                    .put("nativeUri", publishedUri ?: MediaPublisher.providerUriForInternalFile(this, finalFile).toString())
            )
            val openUri = publishedUri?.let { Uri.parse(it) }
                ?: MediaPublisher.providerUriForInternalFile(this, finalFile)
            showCompletedNotification(job, openUri, finalMime)
        } catch (e: Throwable) {
            runDownloadCaught(job, e, outFile)
        } finally {
            jobs.remove(job.id)
            NotificationManagerCompat(this).cancel(job.notifId)
            if (jobs.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private data class OutputInfo(val file: File, val mimeType: String)

    /** Failure handling shared by every exit path of [runDownload]. */
    private fun runDownloadCaught(job: JobState, e: Throwable, outFile: File) {
        when {
            job.paused.get() -> {
                // Keep the wifi-specific status when the network guard paused this job, otherwise
                // coming back online could never find it for auto-resume.
                val pausedEvt = JSONObject().put("type", "paused").put("id", job.id)
                if (job.pausedForWifi.get()) {
                    DownloadQueueStore.updateStatus(this, job.id, DownloadQueueStore.STATUS_PAUSED_WIFI)
                    pausedEvt.put("reason", "wifi")
                } else {
                    DownloadQueueStore.updateStatus(this, job.id, DownloadQueueStore.STATUS_PAUSED)
                }
                NativeDownloadBus.emit(pausedEvt)
            }
            job.cancelled.get() -> {
                outFile.delete()
                cleanupJobFiles(job.id)
                DownloadQueueStore.removeJob(this, job.id)
                NativeDownloadBus.emit(JSONObject().put("type", "cancelled").put("id", job.id))
            }
            else -> {
                val code = if (e is InsufficientSpaceException) "INSUFFICIENT_SPACE" else null
                DownloadQueueStore.updateStatus(this, job.id, DownloadQueueStore.STATUS_FAILED)
                NativeDownloadBus.emit(
                    JSONObject()
                        .put("type", "error")
                        .put("id", job.id)
                        .put("message", e.message ?: "Download failed")
                        .put("code", code ?: JSONObject.NULL)
                )
                showFailedNotification(job, e.message ?: "Download failed")
            }
        }
    }

    /** Deletes the segment / part folders of a job (after success or cancel, never on pause). */
    private fun cleanupJobFiles(id: String) {
        val safe = id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val downloads = File(filesDir, "downloads")
        File(downloads, "seg_$safe").deleteRecursively()
        File(downloads, "parts_$safe").deleteRecursively()
        try {
            File(cacheDir, "hls_$safe").deleteRecursively()
            File(cacheDir, "dash_$safe").deleteRecursively()
        } catch (ignored: Exception) {
        }
    }

    private fun checkDiskSpace(minimumBytes: Long, estimatedTotal: Long? = null) {
        val available = try {
            StatFs(filesDir.absolutePath).availableBytes
        } catch (ignored: Exception) {
            return
        }
        val needed = maxOf(minimumBytes, ((estimatedTotal ?: 0L) * 115L) / 100L)
        if (available < needed) {
            throw InsufficientSpaceException(needed, available)
        }
    }

    private fun detectMime(file: File, url: String): String {
        // Trust the URL/extension first, then sniff the container via MediaExtractor.
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val byExt = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ts" -> "video/mp2t"
            else -> null
        }
        if (byExt != null) return byExt
        return try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val mime = extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME) ?: "application/octet-stream"
            extractor.release()
            mime
        } catch (ignored: Exception) {
            if (url.contains(".mp3", ignoreCase = true)) "audio/mpeg" else "application/octet-stream"
        }
    }

    // ------------------------------------------------------------------ direct downloads

    private fun downloadDirect(
        job: JobState,
        url: String,
        outFile: File,
        partsDir: File,
        connections: Int,
        onProgress: (Long, Long?) -> Unit
    ): String {
        val headCall = NativeProxyClient.httpClient.newCall(
            newRequest(job, url).head().build()
        )
        job.activeCalls.add(headCall)
        var totalLength: Long? = null
        var acceptRanges = false
        var detectedMime = "video/mp4"
        try {
            headCall.execute().use { res ->
                if (res.isSuccessful) {
                    totalLength = res.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                    acceptRanges = res.header("Accept-Ranges").equals("bytes", ignoreCase = true)
                    detectedMime = res.header("Content-Type")?.substringBefore(';')?.trim() ?: detectedMime
                }
            }
        } catch (ignored: Exception) {
            // Try tiny Range probe below
        } finally {
            job.activeCalls.remove(headCall)
        }

        if (!job.paused.get() && !job.cancelled.get() && (!acceptRanges || totalLength == null)) {
            val probeCall = NativeProxyClient.httpClient.newCall(
                newRequest(job, url).get().header("Range", "bytes=0-0").build()
            )
            job.activeCalls.add(probeCall)
            try {
                probeCall.execute().use { res ->
                    if (res.code == 206) {
                        acceptRanges = true
                        val cr = res.header("Content-Range").orEmpty()
                        if (cr.contains("/")) {
                            cr.substringAfter("/").toLongOrNull()?.takeIf { it > 0L }?.let { totalLength = it }
                        }
                        detectedMime = res.header("Content-Type")?.substringBefore(';')?.trim() ?: detectedMime
                    }
                }
            } catch (ignored: Exception) {
                // Fall through to single-stream GET
            } finally {
                job.activeCalls.remove(probeCall)
            }
        }

        if (job.paused.get() || job.cancelled.get()) return detectedMime

        val total = totalLength
        if (total != null && total > 0L) {
            checkDiskSpace(minimumBytes = 50L * 1024 * 1024, estimatedTotal = total)
        }

        if (acceptRanges && total != null && total >= 262_144L && connections > 1) {
            val ranges = RangeChunkPlanner.computeRanges(total, connections)
            partsDir.mkdirs()

            val initialDownloaded = ranges.sumOf { range ->
                val partFile = File(partsDir, "chunk_${range.index}.part")
                val expectedLen = range.end - range.start + 1L
                if (partFile.exists()) partFile.length().coerceAtMost(expectedLen) else 0L
            }
            val downloaded = AtomicLong(initialDownloaded)
            val failure = AtomicReference<Throwable?>(null)
            val latch = CountDownLatch(ranges.size)

            for (range in ranges) {
                pool.execute {
                    val partFile = File(partsDir, "chunk_${range.index}.part")
                    val expectedLen = range.end - range.start + 1L
                    try {
                        // Each attempt resumes from whatever is already in the part file, so a
                        // dropped connection costs only the bytes since the last write.
                        retrying(job, 4) {
                            val existing = if (partFile.exists()) partFile.length().coerceAtMost(expectedLen) else 0L
                            if (existing < expectedLen) {
                                val resumeStart = range.start + existing
                                val call = NativeProxyClient.httpClient.newCall(
                                    newRequest(job, url).header("Range", "bytes=$resumeStart-${range.end}").build()
                                )
                                job.activeCalls.add(call)
                                try {
                                    call.execute().use { res ->
                                        if (!res.isSuccessful) throw HttpStatusException(res.code)
                                        if (res.code != 206) {
                                            throw IllegalStateException("Range unsupported (HTTP ${res.code})")
                                        }
                                        val input = res.body?.byteStream() ?: throw IllegalStateException("Empty body")
                                        FileOutputStream(partFile, existing > 0L).use { out ->
                                            val buf = ByteArray(32 * 1024)
                                            while (!job.paused.get() && !job.cancelled.get() && failure.get() == null) {
                                                val n = input.read(buf)
                                                if (n < 0) break
                                                out.write(buf, 0, n)
                                                val totalNow = downloaded.addAndGet(n.toLong())
                                                onProgress(totalNow, total)
                                            }
                                        }
                                    }
                                } finally {
                                    job.activeCalls.remove(call)
                                }
                            }
                            Unit
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            failure.get()?.let { throw it }
            if (job.paused.get() || job.cancelled.get()) return detectedMime

            outFile.outputStream().use { finalOut ->
                for (range in ranges) {
                    val partFile = File(partsDir, "chunk_${range.index}.part")
                    partFile.inputStream().use { it.copyTo(finalOut, 64 * 1024) }
                }
            }
            partsDir.deleteRecursively()
            return detectedMime
        }

        // Single-stream with byte-offset resume support when Accept-Ranges is available
        partsDir.mkdirs()
        val partFile = File(partsDir, "single.part")
        var attemptOffset = if (acceptRanges && partFile.exists()) partFile.length() else 0L
        var completedCleanly = false

        for (attempt in 0..3) {
            if (completedCleanly || job.paused.get() || job.cancelled.get()) break
            val reqBuilder = newRequest(job, url)
            if (attemptOffset > 0L) {
                reqBuilder.header("Range", "bytes=$attemptOffset-")
            }
            val getCall = NativeProxyClient.httpClient.newCall(reqBuilder.build())
            job.activeCalls.add(getCall)
            try {
                getCall.execute().use { res ->
                    if (res.code == 416 && attemptOffset > 0L && attempt == 0) {
                        partFile.delete()
                        attemptOffset = 0L
                        return@use
                    }
                    if (!res.isSuccessful) throw HttpStatusException(res.code)
                    val isAppending = attemptOffset > 0L && res.code == 206
                    detectedMime = res.header("Content-Type")?.substringBefore(';')?.trim() ?: detectedMime
                    val remainingLen = res.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
                    val effectiveTotal = totalLength ?: remainingLen?.let { if (isAppending) attemptOffset + it else it }
                    val input = res.body?.byteStream() ?: throw IllegalStateException("Empty body")
                    var dl = if (isAppending) attemptOffset else 0L
                    FileOutputStream(partFile, isAppending).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (!job.paused.get() && !job.cancelled.get()) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            dl += n
                            onProgress(dl, effectiveTotal)
                        }
                    }
                    completedCleanly = true
                }
            } catch (e: IOException) {
                if (!isRetryable(e) || job.paused.get() || job.cancelled.get() || attempt >= 3) throw e
                attemptOffset = if (acceptRanges && partFile.exists()) partFile.length() else 0L
                sleepBackoff(attempt + 1)
            } finally {
                job.activeCalls.remove(getCall)
            }
        }

        if (!job.paused.get() && !job.cancelled.get() && partFile.exists() && completedCleanly) {
            if (outFile.exists()) outFile.delete()
            if (!partFile.renameTo(outFile)) {
                partFile.copyTo(outFile, overwrite = true)
                partFile.delete()
            }
            partsDir.deleteRecursively()
        }
        return detectedMime
    }

    // ------------------------------------------------------------------ HLS

    private fun downloadHls(
        job: JobState,
        playlistUrl: String,
        audioTrackUrl: String?,
        outFile: File,
        segDir: File,
        onProgress: (Long, Long?) -> Unit
    ): OutputInfo {
        val rawManifestText = fetchText(job, playlistUrl)
        val variantUrl = RangeChunkPlanner.selectBestMasterVariantUrl(rawManifestText, playlistUrl)
        val effectiveUrl = variantUrl ?: playlistUrl
        val manifestText = if (variantUrl != null) fetchText(job, variantUrl) else rawManifestText

        val entries = HlsKeyParser.parsePlaylistEntries(manifestText, effectiveUrl)
        if (entries.isEmpty()) throw IllegalStateException("Empty playlist")
        if (manifestText.contains("#EXT-X-ENDLIST", ignoreCase = true).not() &&
            manifestText.contains("#EXT-X-PLAYLIST-TYPE:VOD", ignoreCase = true).not() &&
            manifestText.contains("#EXT-X-PLAYLIST-TYPE:EVENT", ignoreCase = true).not()
        ) {
            // Live without an end-list: refuse instead of downloading a partial forever-stream.
            throw IllegalStateException("Live stream (no EXT-X-ENDLIST); live recording is not supported")
        }

        // Persistent per-segment storage under filesDir so pause/resume reuses everything.
        segDir.mkdirs()

        fun segFileFor(idx: Int, init: Boolean): File =
            File(segDir, if (init) "init_$idx.ts" else "seg_$idx.ts")

        val initialDownloaded = entries.indices.sumOf { idx ->
            val f = segFileFor(idx, entries[idx].isInitSegment)
            if (f.exists()) f.length() else 0L
        }
        // Rough free-space estimate from what we already know (first resume: 0).
        if (initialDownloaded > 0L) {
            val existingCount = entries.indices.count { idx -> segFileFor(idx, entries[idx].isInitSegment).exists() }
            val avg = initialDownloaded.toDouble() / existingCount.coerceAtLeast(1)
            try {
                checkDiskSpace(50L * 1024 * 1024, (avg * entries.size).toLong())
            } catch (e: InsufficientSpaceException) {
                // Resuming an in-flight job: let it continue while space lasts.
            }
        } else {
            checkDiskSpace(50L * 1024 * 1024)
        }

        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val downloaded = AtomicLong(initialDownloaded)
        val failure = AtomicReference<Throwable?>(null)
        val nextIndex = AtomicInteger(0)
        val workers = minOf(4, entries.size)
        val latch = CountDownLatch(workers)

        repeat(workers) {
            pool.execute {
                try {
                    while (!job.paused.get() && !job.cancelled.get() && failure.get() == null) {
                        val idx = nextIndex.getAndIncrement()
                        if (idx >= entries.size) break
                        val entry = entries[idx]
                        val segFile = segFileFor(idx, entry.isInitSegment)

                        if (segFile.exists() && segFile.length() > 0L) continue // resume: already there

                        val rawBytes = fetchBytes(job, entry.url, byteRangeOf(entry))

                        val finalBytes = if (entry.keySpec != null && entry.keySpec.method == "AES-128") {
                            val keyUri = entry.keySpec.keyUri
                                ?: throw IllegalStateException("Missing AES-128 key URI")
                            val keyBytes = keyCache.getOrPut(keyUri) { fetchKeyBytes(job, keyUri) }
                            val iv = entry.keySpec.explicitIv ?: HlsKeyParser.sequenceIv(entry.sequenceNumber)
                            HlsKeyParser.decryptAes128Cbc(rawBytes, keyBytes, iv)
                        } else {
                            rawBytes
                        }

                        // Write-then-rename keeps half-written segments from being seen as done.
                        val tmp = File(segDir, "w_${idx}.tmp")
                        tmp.writeBytes(finalBytes)
                        if (!tmp.renameTo(segFile)) {
                            segFile.writeBytes(finalBytes)
                            tmp.delete()
                        }

                        val nowDl = downloaded.addAndGet(finalBytes.size.toLong())
                        val estTotal = ((nowDl.toDouble() / (idx + 1)) * entries.size).toLong()
                        onProgress(nowDl, estTotal)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        failure.get()?.let { throw it }

        if (job.paused.get() || job.cancelled.get()) return OutputInfo(outFile, "video/mp4")

        val rawMergedTs = File(segDir, "merged.ts")
        rawMergedTs.outputStream().use { out ->
            for (idx in entries.indices) {
                val f = segFileFor(idx, entries[idx].isInitSegment)
                f.inputStream().use { it.copyTo(out, 32 * 1024) }
            }
        }

        var audioFile: File? = null
        if (!audioTrackUrl.isNullOrBlank()) {
            audioFile = File(segDir, "audio_track.bin")
            if (!audioFile.exists() || audioFile.length() == 0L) {
                try {
                    if (audioTrackUrl.contains(".m3u8", ignoreCase = true)) {
                        downloadHlsRaw(job, audioTrackUrl, audioFile)
                    } else {
                        downloadSingleStream(job, audioTrackUrl, audioFile)
                    }
                } catch (audioError: Throwable) {
                    // The video itself is fully downloaded; ship it without the extra audio
                    // track instead of failing the whole job (same behaviour as yt-dlp).
                    audioFile = null
                }
            }
        }

        val audioReady = audioFile != null && audioFile.exists() && audioFile.length() > 0L && !job.paused.get() && !job.cancelled.get()
        val result = muxOrCopy(rawMergedTs, if (audioReady) audioFile else null, outFile)
        val mime = detectMime(result, "")
        // Raw TS keeps its extension when muxing was impossible.
        if (mime == "video/mp2t" && result.extension != "ts") {
            val tsFile = File(result.parentFile, result.nameWithoutExtension + ".ts")
            if (result.renameTo(tsFile)) return OutputInfo(tsFile, mime)
        }
        return OutputInfo(result, if (mime == "video/mp2t") "video/mp2t" else mime)
    }

    private fun byteRangeOf(entry: com.salvia.salviabrowxer.media.HlsSegmentEntry): LongRange? {
        val offset = entry.byteRangeOffset ?: return null
        val length = entry.byteRangeLength ?: return null
        return LongRange(offset, offset + length - 1)
    }

    private fun downloadHlsRaw(job: JobState, playlistUrl: String, outFile: File) {
        val text = fetchText(job, playlistUrl)
        val entries = HlsKeyParser.parsePlaylistEntries(text, playlistUrl)
        if (entries.isEmpty()) return
        val keyCache = HashMap<String, ByteArray>()
        outFile.outputStream().use { out ->
            for (entry in entries) {
                if (job.paused.get() || job.cancelled.get()) break
                val raw = fetchBytes(job, entry.url, byteRangeOf(entry))
                val bytes = if (entry.keySpec != null && entry.keySpec.method == "AES-128") {
                    val uri = entry.keySpec.keyUri ?: throw IllegalStateException("Missing audio key URI")
                    val key = keyCache.getOrPut(uri) { fetchKeyBytes(job, uri) }
                    val iv = entry.keySpec.explicitIv ?: HlsKeyParser.sequenceIv(entry.sequenceNumber)
                    HlsKeyParser.decryptAes128Cbc(raw, key, iv)
                } else {
                    raw
                }
                out.write(bytes)
            }
        }
    }

    // ------------------------------------------------------------------ DASH

    private fun isDashUrl(url: String): Boolean {
        if (url.contains("salvia-dash=")) return true
        val path = url.substringBefore('#').substringBefore('?')
        return path.endsWith(".mpd", ignoreCase = true)
    }

    private fun parseFragmentParams(fragment: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (part in fragment.split('&')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val raw = part.substring(idx + 1)
            val value = try {
                java.net.URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                raw
            }
            map[part.substring(0, idx)] = value
        }
        return map
    }

    private fun downloadDash(
        job: JobState,
        rawUrl: String,
        fallbackAudioUrl: String?,
        downloadsDir: File,
        storageId: String,
        segDir: File,
        onProgress: (Long, Long?) -> Unit
    ): OutputInfo {
        val mpdUrl = rawUrl.substringBefore('#')
        val params = parseFragmentParams(rawUrl.substringAfter('#', ""))
        val manifest = DashManifestParser.parse(fetchText(job, mpdUrl), mpdUrl)
        if (manifest.unsupported != null) {
            throw IllegalStateException("DASH stream not supported (${manifest.unsupported})")
        }
        val video = manifest.pickVideo(params["salvia-dash"])
            ?: throw IllegalStateException("No video stream in manifest")
        val audio = manifest.pickAudio(params["audio"])

        // Expand SegmentBase (sidx) representations into concrete byte ranges.
        val videoRep = expandSidx(job, video)
        val audioRep = audio?.let { expandSidx(job, it) }

        segDir.mkdirs()
        val videoFile = File(segDir, "video.mp4")
        val audioFile = File(segDir, "audio.m4a")
        val counter = AtomicLong(0L)
        val chosen = listOfNotNull(videoRep, audioRep)
        val totalSegments = chosen.sumOf { it.urls.size }
        val doneSegments = AtomicInteger(0)

        fun report(downloaded: Long) {
            val done = doneSegments.get()
            val estimate: Long? = if (done > 0) {
                (downloaded.toDouble() / done * totalSegments).toLong()
            } else {
                null
            }
            onProgress(downloaded, estimate)
        }

        downloadRepresentation(job, videoRep, videoFile, segDir, "v", counter, doneSegments) { report(it) }
        if (job.paused.get() || job.cancelled.get()) return OutputInfo(File(downloadsDir, "${storageId}.mp4"), "video/mp4")
        var hasAudio = false
        if (audioRep != null) {
            downloadRepresentation(job, audioRep, audioFile, segDir, "a", counter, doneSegments) { report(it) }
            hasAudio = true
        } else if (!fallbackAudioUrl.isNullOrBlank()) {
            try {
                downloadSingleStream(job, fallbackAudioUrl, audioFile)
                hasAudio = true
            } catch (audioError: Throwable) {
                // Video is complete; do not fail the whole job over the extra audio track.
                hasAudio = false
            }
        }
        if (job.paused.get() || job.cancelled.get()) return OutputInfo(File(downloadsDir, "${storageId}.mp4"), "video/mp4")

        val result = muxOrCopy(videoFile, if (hasAudio && audioFile.exists() && audioFile.length() > 0L) audioFile else null, File(downloadsDir, "${storageId}.mp4"))
        val mime = detectMime(result, "")
        if (mime == "video/webm" && result.extension != "webm") {
            val webmFile = File(result.parentFile, result.nameWithoutExtension + ".webm")
            if (result.renameTo(webmFile)) return OutputInfo(webmFile, mime)
        }
        if (mime == "video/mp2t" && result.extension != "ts") {
            val tsFile = File(result.parentFile, result.nameWithoutExtension + ".ts")
            if (result.renameTo(tsFile)) return OutputInfo(tsFile, mime)
        }
        return OutputInfo(result, mime)
    }

    /** Resolves a SegmentBase representation into init + media byte ranges using its sidx box. */
    private fun expandSidx(job: JobState, rep: DashRepresentation): DashRepresentation {
        val sidxUrl = rep.sidxUrl ?: return rep
        val sidxRange = rep.sidxRange ?: return rep
        val indexBytes = fetchBytes(job, sidxUrl, sidxRange)
        val sidx = SidxParser.parse(indexBytes)
            ?: throw IllegalStateException("Invalid sidx index for ${rep.id}")
        val anchor = sidxRange.last + 1L + sidx.firstOffset
        val urls = ArrayList<String>()
        val ranges = ArrayList<LongRange?>()
        urls.add(sidxUrl)
        ranges.add(rep.initRange ?: LongRange(0L, sidxRange.first - 1L))
        var cursor = anchor
        for (entry in sidx.entries) {
            if (entry.isSubsegment) continue
            urls.add(sidxUrl)
            ranges.add(LongRange(cursor, cursor + entry.size - 1L))
            cursor += entry.size
        }
        return rep.copy(
            urls = urls,
            isSingleFile = false,
            byteRanges = ranges,
            sidxUrl = null,
            sidxRange = null,
            initRange = null
        )
    }

    private fun downloadRepresentation(
        job: JobState,
        rep: DashRepresentation,
        outFile: File,
        workDir: File,
        tag: String,
        counter: AtomicLong,
        doneSegments: AtomicInteger,
        report: (Long) -> Unit
    ) {
        if (rep.isSingleFile && rep.byteRanges.isEmpty()) {
            val before = counter.get()
            downloadDirect(job, rep.urls[0], outFile, File(workDir, "parts_$tag"), 4) { dl, _ ->
                report(before + dl)
            }
            counter.addAndGet(outFile.length())
            doneSegments.incrementAndGet()
            return
        }

        val urls = rep.urls
        val ranges = rep.byteRanges.ifEmpty { List(urls.size) { null } }
        val segDir = File(workDir, "segs_$tag").apply { mkdirs() }
        val nextIndex = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val workers = minOf(4, urls.size)
        val latch = CountDownLatch(workers)

        fun segFileFor(idx: Int): File = File(segDir, "s_$idx")

        repeat(workers) {
            pool.execute {
                try {
                    while (!job.paused.get() && !job.cancelled.get() && failure.get() == null) {
                        val idx = nextIndex.getAndIncrement()
                        if (idx >= urls.size) break
                        val segFile = segFileFor(idx)
                        if (!(segFile.exists() && segFile.length() > 0L)) {
                            val bytes = fetchBytes(job, urls[idx], ranges.getOrNull(idx))
                            // Write-then-rename so a crash mid-write can never leave a partial
                            // segment that a later resume would mistake for a complete one.
                            val tmp = File(segDir, "w_${tag}_$idx.tmp")
                            tmp.writeBytes(bytes)
                            if (!tmp.renameTo(segFile)) {
                                segFile.writeBytes(bytes)
                                tmp.delete()
                            }
                        }
                        doneSegments.incrementAndGet()
                        report(counter.addAndGet(segFile.length()))
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        failure.get()?.let { throw it }
        if (job.paused.get() || job.cancelled.get()) return

        outFile.outputStream().use { out ->
            for (i in urls.indices) {
                segFileFor(i).inputStream().use { it.copyTo(out, 64 * 1024) }
            }
        }
        segDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ output

    /**
     * Muxes video+audio into MP4 (or WebM for VP8/VP9/Opus), remuxes a single stream, or falls
     * back to a raw copy. Returns the produced file (which may have a different name/extension
     * than [target] when the container forces it).
     */
    private fun muxOrCopy(videoFile: File, audioFile: File?, target: File): File {
        val out = NativeMediaMuxer.smartMux(videoFile, audioFile, target)
        if (out != null && out.length() > 0L) return out
        // Give up on muxing: ship the raw stream with an honest extension.
        val raw = videoFile
        val ext = when {
            raw.name.endsWith(".ts") -> "ts"
            raw.name.endsWith(".webm") -> "webm"
            raw.name.endsWith(".m4a") -> "m4a"
            else -> "mp4"
        }
        val rawTarget = File(target.parentFile, target.nameWithoutExtension + ".$ext")
        if (target.exists()) target.delete()
        raw.copyTo(rawTarget, overwrite = true)
        return rawTarget
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private fun downloadSingleStream(job: JobState, url: String, outFile: File) {
        val call = NativeProxyClient.httpClient.newCall(newRequest(job, url).build())
        job.activeCalls.add(call)
        try {
            call.execute().use { res ->
                if (!res.isSuccessful) throw HttpStatusException(res.code)
                val input = res.body?.byteStream() ?: throw IllegalStateException("Empty audio stream")
                val tmp = File(outFile.parentFile, outFile.name + ".tmp")
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (!job.paused.get() && !job.cancelled.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                if (!tmp.renameTo(outFile)) {
                    if (outFile.exists()) outFile.delete()
                    tmp.renameTo(outFile)
                }
            }
        } finally {
            job.activeCalls.remove(call)
        }
    }

    private fun newRequest(job: JobState, url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("User-Agent", NativeProxyClient.MOBILE_UA)
        val ref = job.referer
        if (!ref.isNullOrBlank()) builder.header("Referer", ref)
        // Headers the page itself sent for this URL (Authorization / Origin / custom UA),
        // captured during browsing; explicit job headers from the UI take precedence.
        try {
            for ((key, value) in com.salvia.salviabrowxer.core.RequestHeaderStore.headersFor(url)) {
                builder.header(key, value)
            }
        } catch (ignored: Exception) {
        }
        for ((key, value) in job.headers) {
            builder.header(key, value)
        }
        return builder
    }

    private fun isRetryable(t: Throwable): Boolean {
        if (t is HttpStatusException) return t.code == 408 || t.code == 429 || t.code >= 500
        return t is IOException && t !is InsufficientSpaceException
    }

    private fun sleepBackoff(attempt: Int) {
        try {
            Thread.sleep(600L * attempt * attempt)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Runs [block], retrying transient failures (network errors, HTTP 408/429/5xx) with a growing
     * pause. Never retries once the job was paused or cancelled, nor on permanent errors (403/404).
     */
    private fun <T> retrying(job: JobState, attempts: Int, block: () -> T): T {
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                return block()
            } catch (t: Throwable) {
                val stopped = job.paused.get() || job.cancelled.get()
                if (stopped || attempt >= attempts || !isRetryable(t)) throw t
                sleepBackoff(attempt)
            }
        }
    }

    private fun fetchBytes(job: JobState, url: String, range: LongRange? = null): ByteArray {
        return retrying(job, 4) {
            val builder = newRequest(job, url)
            if (range != null) {
                builder.header("Range", "bytes=${range.first}-${range.last}")
            }
            val call = NativeProxyClient.httpClient.newCall(builder.build())
            job.activeCalls.add(call)
            try {
                call.execute().use { res ->
                    if (!res.isSuccessful) throw HttpStatusException(res.code)
                    val body = res.body?.bytes() ?: throw IllegalStateException("Empty response")
                    if (range != null && res.code == 200 && body.size > range.last + 1) {
                        // Server ignored the Range header; slice manually.
                        body.copyOfRange(range.first.toInt(), (range.last + 1).toInt())
                    } else {
                        body
                    }
                }
            } finally {
                job.activeCalls.remove(call)
            }
        }
    }

    private fun fetchText(job: JobState, url: String): String {
        return String(fetchBytes(job, url), Charsets.UTF_8)
    }

    private fun fetchKeyBytes(job: JobState, keyUrl: String): ByteArray {
        return fetchBytes(job, keyUrl)
    }

    // ------------------------------------------------------------------ notifications

    private fun NotificationManagerCompat(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun promoteForeground(filename: String, progressPct: Int) {
        // Take any job id to find a notification id we can show immediately.
        val notifId = jobs.values.firstOrNull()?.notifId ?: BASE_NOTIF_ID
        val notif = buildProgressNotification(filename, progressPct, null)
        startForegroundInternal(notifId, notif)
    }

    private fun startForegroundInternal(notifId: Int, notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Downloads are started by the user, and dataSync is capped at 6 hours on Android
            // 14+; the specialUse type (declared with a subtype property in the manifest) has
            // no such cap and matches a user-initiated download manager.
            startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifId, notif)
        }
    }

    private fun actionIntent(jobId: String, action: String): PendingIntent {
        return PendingIntent.getService(
            this,
            (jobId.hashCode() and 0xFFFF) + action.hashCode().mod(1000),
            Intent(this, NativeDownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, jobId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(job: JobState, progressPct: Int) {
        val nm = NotificationManagerCompat(this)
        nm.notify(job.notifId, buildProgressNotification(job.filename, progressPct, job.id))
    }

    private fun buildProgressNotification(filename: String, progressPct: Int, jobId: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, SalviaApplication.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(filename)
            .setContentText(if (progressPct >= 0) "$progressPct%" else getString(R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPct.coerceIn(0, 100), progressPct < 0)
            .setContentIntent(openIntent)
        if (jobId != null) {
            builder.addAction(0, getString(R.string.notification_action_pause), actionIntent(jobId, ACTION_PAUSE))
            builder.addAction(0, getString(R.string.notification_action_cancel), actionIntent(jobId, ACTION_CANCEL))
        }
        return builder.build()
    }

    private fun showCompletedNotification(job: JobState, uri: Uri, mime: String) {
        try {
            val open = PendingIntent.getActivity(
                this,
                (job.id.hashCode() and 0xFFFF),
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nm = NotificationManagerCompat(this)
            nm.notify(
                job.notifId + 1,
                NotificationCompat.Builder(this, SalviaApplication.CHANNEL_DOWNLOADS)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(job.filename)
                    .setContentText(getString(R.string.download_complete))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            )
        } catch (ignored: Exception) {
        }
    }

    private fun showFailedNotification(job: JobState, message: String) {
        try {
            val nm = NotificationManagerCompat(this)
            nm.notify(
                job.notifId + 1,
                NotificationCompat.Builder(this, SalviaApplication.CHANNEL_DOWNLOADS)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(job.filename)
                    .setContentText(message)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private val pendingReferers = ConcurrentHashMap<String, String>()
        private const val BASE_NOTIF_ID = 4101

        const val ACTION_START = "com.salvia.salviabrowxer.action.START"
        const val ACTION_PAUSE = "com.salvia.salviabrowxer.action.PAUSE"
        const val ACTION_CANCEL = "com.salvia.salviabrowxer.action.CANCEL"
        const val ACTION_RESUME = "com.salvia.salviabrowxer.action.RESUME"
        const val ACTION_RESUME_ALL = "com.salvia.salviabrowxer.action.RESUME_ALL"

        const val EXTRA_ID = "id"
        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MIME = "mime"
        const val EXTRA_IS_HLS = "is_hls"
        const val EXTRA_CONNECTIONS = "connections"
        const val EXTRA_AUTO_SAVE = "auto_save"
        const val EXTRA_AUDIO_TRACK_URL = "audio_track_url"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_WIFI_ONLY = "wifi_only"
        const val EXTRA_REFERER = "referer"

        private fun notifIdFor(id: String): Int = BASE_NOTIF_ID + 2 + (id.hashCode().mod(2000))

        private fun parseHeadersExtra(headersJson: String?): Map<String, String> {
            if (headersJson.isNullOrBlank() || headersJson.length > 4000) return emptyMap()
            return try {
                val obj = org.json.JSONObject(headersJson)
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

        fun start(
            context: Context,
            id: String,
            url: String,
            filename: String,
            mimeType: String,
            isHls: Boolean,
            connections: Int,
            autoSave: Boolean,
            audioTrackUrl: String? = null,
            headers: Map<String, String> = emptyMap(),
            wifiOnly: Boolean = false,
            referer: String? = null
        ) {
            MediaPublisher.downloadTreeUri = currentDownloadTree(context)
            val intent = Intent(context, NativeDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILENAME, filename)
                putExtra(EXTRA_MIME, mimeType)
                putExtra(EXTRA_IS_HLS, isHls)
                putExtra(EXTRA_CONNECTIONS, connections)
                putExtra(EXTRA_AUTO_SAVE, autoSave)
                if (!audioTrackUrl.isNullOrBlank()) {
                    putExtra(EXTRA_AUDIO_TRACK_URL, audioTrackUrl)
                }
                if (headers.isNotEmpty()) {
                    putExtra(EXTRA_HEADERS, org.json.JSONObject(headers).toString())
                }
                putExtra(EXTRA_WIFI_ONLY, wifiOnly)
                if (!referer.isNullOrBlank()) {
                    putExtra(EXTRA_REFERER, referer)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context, id: String) {
            context.startService(
                Intent(context, NativeDownloadService::class.java).apply {
                    action = ACTION_PAUSE
                    putExtra(EXTRA_ID, id)
                }
            )
        }

        fun cancel(context: Context, id: String) {
            context.startService(
                Intent(context, NativeDownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_ID, id)
                }
            )
        }

        /** Resumes a paused / failed download by re-running its persisted specification. */
        fun resume(context: Context, id: String) {
            MediaPublisher.downloadTreeUri = currentDownloadTree(context)
            val intent = Intent(context, NativeDownloadService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_ID, id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Restores downloads that were running when the app died or the device rebooted. */
        fun resumeRunningJobs(context: Context) {
            try {
                val running = DownloadQueueStore.jobsIn(context, DownloadQueueStore.STATUS_RUNNING)
                if (running.isEmpty()) return
                // Hand the SAF tree to the publisher so restored downloads still land in the
                // user-chosen folder.
                MediaPublisher.downloadTreeUri = currentDownloadTree(context)
                val intent = Intent(context, NativeDownloadService::class.java).apply {
                    action = ACTION_RESUME_ALL
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (ignored: Exception) {
            }
        }

        private fun currentDownloadTree(context: Context): Uri? {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val raw = prefs.getString("download_tree_uri", null) ?: return null
                Uri.parse(raw)
            } catch (ignored: Exception) {
                null
            }
        }

        const val PREFS_NAME = "salvia_prefs"

        /** Registers the page URL to send as Referer for the next start of download [id]. */
        fun setReferer(id: String, referer: String) {
            if (id.isNotBlank() && referer.isNotBlank()) pendingReferers[id] = referer
        }
    }
}
