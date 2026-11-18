package com.salvia.salviabrowxer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.core.MediaFormatters
import com.salvia.salviabrowxer.data.MediaFormat
import com.salvia.salviabrowxer.data.QualitySheetState
import com.salvia.salviabrowxer.data.UnsupportedMedia
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaError
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaIrisContainer
import com.salvia.salviabrowxer.ui.theme.SalviaMonoFontFamily
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaQualitySelectionSheet(
    state: QualitySheetState,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onDismiss: () -> Unit,
    onConfirmDownload: (MediaFormat, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allFormats = remember(state.mediaInfo) {
        (state.mediaInfo.formats + state.mediaInfo.audioFormats).distinctBy { it.id }
    }
    var selectedFormatId by remember(allFormats) {
        mutableStateOf(allFormats.firstOrNull()?.id.orEmpty())
    }
    var fileName by remember(state.mediaInfo.title) {
        mutableStateOf(MediaFormatters.sanitizeFilename(state.mediaInfo.title))
    }

    LaunchedEffect(allFormats) {
        if (allFormats.none { it.id == selectedFormatId }) {
            selectedFormatId = allFormats.firstOrNull()?.id.orEmpty()
        }
    }

    val selectedFormat = allFormats.firstOrNull { it.id == selectedFormatId } ?: allFormats.firstOrNull()

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = t("select_quality", emptyArray()),
                        style = MaterialTheme.typography.titleLarge,
                        color = SalviaPearl
                    )
                    Text(
                        text = state.mediaInfo.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = SalviaSilverMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = t("action_dismiss", emptyArray()), tint = SalviaSilverMid)
                }
            }

            HorizontalDivider(color = SalviaBorder.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 8.dp))

            if (state.isLoading) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SalviaCyan)
                    Text(t("quality_checking", emptyArray()), color = SalviaCyan, fontSize = 13.sp)
                }
            }

            if (state.unsupported != null) {
                val msgKey = when (state.unsupported) {
                    UnsupportedMedia.LIVE -> "error_live_unsupported"
                    UnsupportedMedia.DASH -> "error_dash_unsupported"
                    UnsupportedMedia.DRM -> "error_drm_protected"
                    UnsupportedMedia.BLOB_STREAM -> "error_blob_stream_unsupported"
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SalviaError.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, SalviaError.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = SalviaError)
                        Text(t(msgKey, emptyArray()), color = SalviaPearl, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(t("file_name_label", emptyArray())) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quality_filename_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SalviaIris,
                        unfocusedBorderColor = SalviaBorder,
                        focusedTextColor = SalviaPearl,
                        unfocusedTextColor = SalviaPearl,
                        focusedLabelColor = SalviaCyan,
                        unfocusedLabelColor = SalviaSilverMid
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = t("quality_options", arrayOf("count" to allFormats.size)),
                    style = MaterialTheme.typography.labelLarge,
                    color = SalviaSilverMid
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allFormats, key = { it.id }) { fmt ->
                        val selected = fmt.id == selectedFormat?.id
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) SalviaIrisContainer else SalviaElevated,
                            border = BorderStroke(1.dp, if (selected) SalviaIris else SalviaBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFormatId = fmt.id }
                                .testTag("quality_option_${fmt.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (fmt.isVideo) Icons.Filled.Movie else Icons.Filled.AudioFile,
                                    contentDescription = null,
                                    tint = if (selected) SalviaCyan else SalviaSilverMid
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fmt.format,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = SalviaPearl
                                    )
                                    val meta = buildString {
                                        append(fmt.extension.uppercase())
                                        if (fmt.width != null && fmt.height != null) {
                                            append(" · ${fmt.width}x${fmt.height}")
                                        }
                                        if (fmt.bitrate != null && fmt.bitrate > 0) {
                                            append(" · ${fmt.bitrate} kbps")
                                        }
                                        if (fmt.size != null && fmt.size > 0L) {
                                            append(" · ${MediaFormatters.formatFileSize(fmt.size)}")
                                        }
                                    }
                                    Text(
                                        text = meta,
                                        fontSize = 12.sp,
                                        fontFamily = SalviaMonoFontFamily,
                                        color = if (selected) SalviaGold else SalviaSilverMid
                                    )
                                }
                                if (selected) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SalviaCyan)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        selectedFormat?.let { fmt -> onConfirmDownload(fmt, fileName) }
                    },
                    enabled = selectedFormat != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("quality_confirm_download"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(t("confirm_download_cta", emptyArray()), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
