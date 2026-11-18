package com.salvia.salviabrowxer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restarts downloads that were running when the device was rebooted. On Android 14+ starting a
 * dataSync foreground service from BOOT_COMPLETED is restricted, so on those versions downloads
 * resume the next time the app itself is opened; on older versions they continue immediately.
 */
class DownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val running = DownloadQueueStore.jobsIn(context, DownloadQueueStore.STATUS_RUNNING)
        if (running.isEmpty()) return
        try {
            val service = Intent(context, NativeDownloadService::class.java).apply {
                action = NativeDownloadService.ACTION_RESUME_ALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (ignored: Exception) {
            // Expected on Android 14+ when BOOT_COMPLETED restrictions apply; the app start
            // path (MainActivity) will pick these jobs up instead.
        }
    }
}
