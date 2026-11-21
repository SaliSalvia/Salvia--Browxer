package com.salvia.salviabrowxer.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Best-effort crash capture for field testing.
 *
 * The process is about to die anyway; writing a small text file is safe and fast. Reports are
 * stored under `filesDir/crash/` (private storage) and the user can share them from
 * Settings → About ("Share crash reports"). Only the newest [MAX_REPORTS] files are kept.
 *
 * This is deliberately local-only: nothing leaves the device unless the user explicitly taps
 * "Share", so there is no privacy policy implication.
 */
object CrashReporter {
    private const val TAG = "SalviaCrash"
    private const val DIR_NAME = "crash"
    private const val MAX_REPORTS = 10

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(app, thread, throwable)
            } catch (ignored: Throwable) {
                // Never let reporting change how the original crash propagates.
                Log.e(TAG, "failed to persist crash report", ignored)
            }
            // Hand over to the platform handler (system dialog, ANR bookkeeping, process death).
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun reportsDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    fun reportCount(context: Context): Int =
        reportsDir(context).listFiles { f -> f.isFile }?.size ?: 0

    /**
     * Bundles every stored report plus a device/build header into a single zip in the cache
     * dir (covered by the FileProvider `cache-path` entry, so it can be shared). Returns null
     * when there is nothing to share or zipping fails.
     */
    fun packageReports(context: Context): File? {
        val app = context.applicationContext
        val reports = reportsDir(app).listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
        if (reports.isEmpty()) return null
        return try {
            val zip = File(app.cacheDir, "salvia-crash-reports.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("device.txt"))
                out.write(deviceHeader(app).toByteArray(Charsets.UTF_8))
                out.closeEntry()
                for (report in reports) {
                    out.putNextEntry(ZipEntry(report.name))
                    report.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            zip
        } catch (ignored: Throwable) {
            Log.e(TAG, "failed to zip crash reports", ignored)
            null
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(reportsDir(context), "crash-$stamp.txt")
        file.writeText(buildReport(context, thread, throwable))
        trim(reportsDir(context))
    }

    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("SalviaBrowxer crash report")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("thread: ${thread.name}")
            appendLine(deviceHeader(context))
            appendLine()
            append(stack.take(64_000)) // hard cap; stack traces can be megabytes on OOM/StackOverflow
            appendLine()
        }
    }

    // `versionCode` is the only field available below API 28; longVersionCode covers the rest.
    @Suppress("DEPRECATION")
    private fun deviceHeader(context: Context): String = buildString {
        try {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            appendLine("app: ${context.packageName} ${pm.versionName} (code ${if (Build.VERSION.SDK_INT >= 28) pm.longVersionCode else pm.versionCode.toLong()})")
        } catch (ignored: Exception) {
            appendLine("app: ${context.packageName}")
        }
        appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile } ?: return
        if (files.size <= MAX_REPORTS) return
        // Report names start with a sortable timestamp, so name order == time order.
        files.sortedByDescending { it.name }.drop(MAX_REPORTS).forEach { it.delete() }
    }
}
