package com.salvia.salviabrowxer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.core.MediaFormatters
import com.salvia.salviabrowxer.data.DownloadItemRecord
import com.salvia.salviabrowxer.data.DownloadState
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaCyanContainer
import com.salvia.salviabrowxer.ui.theme.SalviaDeepCosmic
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaError
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaIrisContainer
import com.salvia.salviabrowxer.ui.theme.SalviaIrisLight
import com.salvia.salviabrowxer.ui.theme.SalviaMonoFontFamily
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverDeep
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSuccess
import com.salvia.salviabrowxer.ui.theme.SalviaSurface

@Composable
fun DownloadItemRow(
    item: DownloadItemRecord,
    isLockedFolderConfigured: Boolean,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isApk = item.fileName.endsWith(".apk", ignoreCase = true) ||
        item.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)
    val isAudio = item.mimeType.startsWith("audio/")
    val progress = if (item.totalBytes != null && item.totalBytes > 0L) {
        (item.downloadedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_item_${item.id}"),
        shape = RoundedCornerShape(18.dp),
        color = SalviaSurface,
        border = BorderStroke(1.dp, SalviaBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when (item.state) {
                                DownloadState.COMPLETED -> SalviaCyanContainer
                                DownloadState.FAILED -> SalviaError.copy(alpha = 0.16f)
                                else -> SalviaIrisContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            item.state == DownloadState.COMPLETED -> if (isApk) Icons.Filled.InstallMobile else if (isAudio) Icons.Filled.AudioFile else Icons.Filled.CheckCircle
                            item.state == DownloadState.FAILED -> Icons.Filled.ErrorOutline
                            isAudio -> Icons.Filled.AudioFile
                            else -> Icons.Filled.Movie
                        },
                        contentDescription = item.fileName,
                        tint = when (item.state) {
                            DownloadState.COMPLETED -> SalviaCyan
                            DownloadState.FAILED -> SalviaError
                            else -> SalviaIrisLight
                        }
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = SalviaPearl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    val statusLine = buildString {
                        when (item.state) {
                            DownloadState.QUEUED -> append(t("download_queue", emptyArray()))
                            DownloadState.CONNECTING -> append(t("quality_checking", emptyArray()))
                            DownloadState.DOWNLOADING -> {
                                append(MediaFormatters.formatFileSize(item.downloadedBytes))
                                if (item.totalBytes != null) {
                                    append(" / ").append(MediaFormatters.formatFileSize(item.totalBytes))
                                }
                                if (item.speedBytesPerSec > 0) {
                                    append(" · ").append(
                                        t("download_speed", arrayOf("speed" to MediaFormatters.formatFileSize(item.speedBytesPerSec)))
                                    )
                                }
                                if (item.etaSeconds != null) {
                                    append(" · ").append(
                                        t("download_eta_left", arrayOf("eta" to MediaFormatters.formatDurationSeconds(item.etaSeconds)))
                                    )
                                }
                            }
                            DownloadState.PAUSED -> {
                                append(item.errorMessage ?: t("download_pause", emptyArray()))
                                if (item.downloadedBytes > 0) {
                                    append(" · ").append(MediaFormatters.formatFileSize(item.downloadedBytes))
                                }
                            }
                            DownloadState.COMPLETED -> {
                                append(MediaFormatters.formatFileSize(item.totalBytes ?: item.downloadedBytes))
                                if (item.isLocked) append(" · 🔒")
                            }
                            DownloadState.FAILED -> append(item.errorMessage ?: t("download_failed", emptyArray()))
                            DownloadState.CANCELLED -> append(t("download_cancel", emptyArray()))
                        }
                    }
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.COMPLETED) {
                                SalviaMonoFontFamily
                            } else {
                                MaterialTheme.typography.bodySmall.fontFamily
                            }
                        ),
                        color = if (item.state == DownloadState.FAILED) SalviaError else SalviaSilverMid,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.CONNECTING || item.state == DownloadState.PAUSED) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(SalviaElevated)
                ) {
                    val fraction = if (item.state == DownloadState.CONNECTING) 0.15f else progress
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(listOf(SalviaIris, SalviaCyan, SalviaGold))
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item.state) {
                    DownloadState.DOWNLOADING, DownloadState.CONNECTING -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Pause, contentDescription = t("download_pause", emptyArray()), tint = SalviaCyan)
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = t("download_cancel", emptyArray()), tint = SalviaSilverMid)
                        }
                    }
                    DownloadState.QUEUED, DownloadState.PAUSED -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = t("download_resume", emptyArray()), tint = SalviaCyan)
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = t("download_delete", emptyArray()), tint = SalviaSilverDeep)
                        }
                    }
                    DownloadState.FAILED, DownloadState.CANCELLED -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = t("download_retry", emptyArray()), tint = SalviaGold)
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = t("download_delete", emptyArray()), tint = SalviaError)
                        }
                    }
                    DownloadState.COMPLETED -> {
                        Surface(
                            onClick = onOpen,
                            shape = RoundedCornerShape(12.dp),
                            color = SalviaIris,
                            modifier = Modifier.height(40.dp).testTag("download_play_${item.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isApk) Icons.Filled.InstallMobile else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = SalviaPearl,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (isApk) t("update_download_install", emptyArray()) else t("download_open", emptyArray()),
                                    color = SalviaPearl,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (item.exportedUri == null) {
                            IconButton(onClick = onExport, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.SaveAlt, contentDescription = t("download_export", emptyArray()), tint = SalviaSuccess)
                            }
                        }
                        IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Share, contentDescription = t("download_share", emptyArray()), tint = SalviaSilverMid)
                        }
                        if (isLockedFolderConfigured) {
                            IconButton(onClick = onToggleLock, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    imageVector = if (item.isLocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                                    contentDescription = t(if (item.isLocked) "action_unlock_download" else "action_lock_download", emptyArray()),
                                    tint = if (item.isLocked) SalviaGold else SalviaSilverMid
                                )
                            }
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = t("download_delete", emptyArray()), tint = SalviaSilverDeep)
                        }
                    }
                }
            }
        }
    }
}
