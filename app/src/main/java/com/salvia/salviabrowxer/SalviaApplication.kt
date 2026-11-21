package com.salvia.salviabrowxer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import com.salvia.salviabrowxer.core.CrashReporter
import com.salvia.salviabrowxer.storage.MediaPublisher

class SalviaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        restoreDownloadTreeUri()
        createDownloadChannel()
    }

    private fun restoreDownloadTreeUri() {
        try {
            val raw = getSharedPreferences("salvia_prefs", MODE_PRIVATE)
                .getString("download_tree_uri", null)
            MediaPublisher.downloadTreeUri = raw?.let { Uri.parse(it) }
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaApplication", e)
        }
    }

    // minSdk is 26, so NotificationChannel always exists here.
    private fun createDownloadChannel() {
        val channel = NotificationChannel(
            CHANNEL_DOWNLOADS,
            getString(R.string.channel_downloads_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_downloads_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "salvia_downloads"
    }
}
