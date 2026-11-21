package com.salvia.salviabrowxer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.core.MediaFormatters
import com.salvia.salviabrowxer.core.MediaOfferability
import com.salvia.salviabrowxer.data.CandidateRole
import com.salvia.salviabrowxer.data.ClassifiedCandidate
import com.salvia.salviabrowxer.data.MediaCandidate
import com.salvia.salviabrowxer.data.UnsupportedMedia
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
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTraySheet(
    rankedCandidates: List<ClassifiedCandidate>,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onDismiss: () -> Unit,
    onOpenQuality: (MediaCandidate) -> Unit,
    onDownloadBatch: (List<MediaCandidate>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedUrls = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(rankedCandidates) {
        selectedUrls.clear()
        rankedCandidates.forEach { item ->
            val unsupported = MediaOfferability.unsupportedReasonFor(
                url = item.candidate.mediaUrl,
                mimeType = item.candidate.mimeType,
                extension = item.candidate.extension,
                isLive = item.candidate.isLive,
                isMediaSource = item.candidate.isMediaSource,
                isBlobFile = item.candidate.isBlobFile
            )
            if (unsupported == null && item.role == CandidateRole.MAIN) {
                selectedUrls[item.candidate.mediaUrl] = true
            }
        }
    }

    val selectable = rankedCandidates.filter {
        MediaOfferability.unsupportedReasonFor(
            url = it.candidate.mediaUrl,
            mimeType = it.candidate.mimeType,
            extension = it.candidate.extension,
            isLive = it.candidate.isLive,
            isMediaSource = it.candidate.isMediaSource,
            isBlobFile = it.candidate.isBlobFile
        ) == null
    }
    val selectedList = selectable.filter { selectedUrls[it.candidate.mediaUrl] == true }.map { it.candidate }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SalviaSurface,
        contentColor = SalviaPearl
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = t("media_tray_title", emptyArray()),
                        style = MaterialTheme.typography.titleLarge,
                        color = SalviaPearl
                    )
                    Text(
                        text = t("media_tray_count", arrayOf("count" to rankedCandidates.size)),
                        style = MaterialTheme.typography.bodySmall,
                        color = SalviaSilverMid
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectable.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val allSelected = selectedList.size == selectable.size
                                selectable.forEach { selectedUrls[it.candidate.mediaUrl] = !allSelected }
                            }
                        ) {
                            Text(t("media_select_all", emptyArray()), color = SalviaCyan)
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = t("action_dismiss", emptyArray()), tint = SalviaSilverMid)
                    }
                }
            }

            HorizontalDivider(color = SalviaBorder.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 8.dp))

            if (rankedCandidates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = t("media_tray_empty", emptyArray()),
                        color = SalviaSilverMid,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rankedCandidates, key = { it.candidate.mediaUrl }) { entry ->
                        val c = entry.candidate
                        val unsupported = MediaOfferability.unsupportedReasonFor(
                            url = c.mediaUrl,
                            mimeType = c.mimeType,
                            extension = c.extension,
                            isLive = c.isLive,
                            isMediaSource = c.isMediaSource,
                            isBlobFile = c.isBlobFile
                        )
                        val isChecked = selectedUrls[c.mediaUrl] == true
                        val kind = MediaOfferability.kindOfMedia(c.mimeType, c.extension)

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SalviaElevated,
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isChecked -> SalviaIris
                                    entry.role == CandidateRole.MAIN -> SalviaCyan.copy(alpha = 0.6f)
                                    else -> SalviaBorder
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = unsupported == null) {
                                    selectedUrls[c.mediaUrl] = !isChecked
                                }
                                .testTag("media_tray_item")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (unsupported == null) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked -> selectedUrls[c.mediaUrl] = checked },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = SalviaIris,
                                            uncheckedColor = SalviaSilverMid
                                        )
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(SalviaError.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = null, tint = SalviaError, modifier = Modifier.size(18.dp))
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    val roleLabel = when (entry.role) {
                                        CandidateRole.MAIN -> t("media_role_main", emptyArray())
                                        CandidateRole.SUGGESTED -> t("media_role_suggested", emptyArray())
                                        CandidateRole.AD -> t("media_role_ad", emptyArray())
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = when (entry.role) {
                                                CandidateRole.MAIN -> SalviaCyanContainer
                                                CandidateRole.SUGGESTED -> SalviaIrisContainer
                                                CandidateRole.AD -> SalviaError.copy(alpha = 0.16f)
                                            }
                                        ) {
                                            Text(
                                                text = roleLabel,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = when (entry.role) {
                                                    CandidateRole.MAIN -> SalviaCyan
                                                    CandidateRole.SUGGESTED -> SalviaIrisLight
                                                    CandidateRole.AD -> SalviaError
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        val kindLabel = when (kind) {
                                            MediaOfferability.Kind.AUDIO -> t("media_kind_audio", emptyArray())
                                            MediaOfferability.Kind.PLAYLIST -> t("media_kind_playlist", emptyArray())
                                            else -> t("media_kind_video", emptyArray())
                                        }
                                        Text(kindLabel, fontSize = 11.sp, color = SalviaGold)
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = c.title?.ifBlank { null } ?: c.mediaUrl.substringAfterLast('/').substringBefore('?'),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = SalviaPearl,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sub = buildString {
                                        c.extension?.let { append(it.uppercase()) }
                                        c.estimatedSize?.let { append(" · ").append(MediaFormatters.formatFileSize(it)) }
                                        c.duration?.let { append(" · ").append(MediaFormatters.formatDurationMs(it)) }
                                    }
                                    if (sub.isNotBlank()) {
                                        Text(sub, style = MaterialTheme.typography.bodySmall, color = SalviaSilverMid)
                                    }
                                    if (unsupported != null) {
                                        val msgKey = when (unsupported) {
                                            UnsupportedMedia.LIVE -> "error_live_unsupported"
                                            UnsupportedMedia.BLOB_STREAM -> "error_blob_stream_unsupported"
                                            UnsupportedMedia.DASH -> "error_dash_unsupported"
                                            UnsupportedMedia.DRM -> "error_drm_protected"
                                        }
                                        Text(t(msgKey, emptyArray()), style = MaterialTheme.typography.bodySmall, color = SalviaError)
                                    }
                                }

                                if (unsupported == null) {
                                    IconButton(
                                        onClick = { onOpenQuality(c) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = when (kind) {
                                                MediaOfferability.Kind.AUDIO -> Icons.Filled.AudioFile
                                                MediaOfferability.Kind.PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistPlay
                                                else -> Icons.Filled.Tune
                                            },
                                            contentDescription = t("media_open_quality", emptyArray()),
                                            tint = SalviaCyan
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { onDownloadBatch(selectedList) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("media_tray_batch_download"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = t("confirm_download_selected", arrayOf("count" to selectedList.size)),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
