package com.salvia.salviabrowxer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.data.DownloadItemRecord
import com.salvia.salviabrowxer.data.DownloadState
import com.salvia.salviabrowxer.ui.SalviaUiState
import com.salvia.salviabrowxer.ui.components.DownloadItemRow
import com.salvia.salviabrowxer.ui.components.ScreenHeader
import com.salvia.salviabrowxer.ui.theme.SalviaBlack
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaDeepCosmic
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaError
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaIrisContainer
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSurface
import kotlinx.coroutines.launch

private enum class DownloadsBucket(val key: String) {
    ACTIVE("ACTIVE"),
    QUEUED("QUEUED"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED")
}

@Composable
fun DownloadsScreen(
    state: SalviaUiState,
    showBackButton: Boolean,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onBack: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    onShare: (DownloadItemRecord) -> Unit,
    onToggleLock: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onClearBucket: (String) -> Unit,
    onUnlockFolder: suspend (String) -> Boolean,
    onLockFolderAgain: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(DownloadsBucket.ACTIVE) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var unlockPassword by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }

    val activeItems = remember(state.downloads) {
        state.downloads.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.CONNECTING }
    }
    val queuedItems = remember(state.downloads) {
        state.downloads.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED }
    }
    val completedItems = remember(state.downloads, state.isLockedFolderUnlocked) {
        state.downloads.filter {
            it.state == DownloadState.COMPLETED && (!it.isLocked || state.isLockedFolderUnlocked)
        }
    }
    val failedItems = remember(state.downloads) {
        state.downloads.filter { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED }
    }

    val currentItems = when (selectedTab) {
        DownloadsBucket.ACTIVE -> activeItems
        DownloadsBucket.QUEUED -> queuedItems
        DownloadsBucket.COMPLETED -> completedItems
        DownloadsBucket.FAILED -> failedItems
    }

    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isVerifying) {
                    showUnlockDialog = false
                    unlockPassword = ""
                    unlockError = null
                }
            },
            containerColor = SalviaSurface,
            titleContentColor = SalviaPearl,
            title = { Text(t("downloads_locked_folder", emptyArray())) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unlockPassword,
                        onValueChange = {
                            unlockPassword = it
                            unlockError = null
                        },
                        enabled = !isVerifying,
                        label = { Text(t("locked_folder_password_label", emptyArray())) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SalviaIris,
                            unfocusedBorderColor = SalviaBorder,
                            focusedTextColor = SalviaPearl,
                            unfocusedTextColor = SalviaPearl
                        )
                    )
                    if (isVerifying) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SalviaCyan)
                            Text(t("verifying", emptyArray()), color = SalviaSilverMid, fontSize = 12.sp)
                        }
                    } else if (state.lockoutRemainingSeconds > 0) {
                        Text(
                            text = t("locked_folder_try_later", arrayOf("seconds" to state.lockoutRemainingSeconds)),
                            color = SalviaError,
                            fontSize = 12.sp
                        )
                    } else if (unlockError != null) {
                        Text(unlockError!!, color = SalviaError, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isVerifying && state.lockoutRemainingSeconds == 0 && unlockPassword.isNotEmpty(),
                    onClick = {
                        isVerifying = true
                        unlockError = null
                        scope.launch {
                            val ok = onUnlockFolder(unlockPassword)
                            isVerifying = false
                            if (ok) {
                                showUnlockDialog = false
                                unlockPassword = ""
                                unlockError = null
                            } else {
                                unlockError = t("locked_folder_wrong_password", emptyArray())
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                ) {
                    Text(t("action_unlock_folder", emptyArray()))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isVerifying,
                    onClick = {
                        showUnlockDialog = false
                        unlockPassword = ""
                        unlockError = null
                    }
                ) {
                    Text(t("action_cancel", emptyArray()), color = SalviaSilverMid)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SalviaBlack)
            .navigationBarsPadding()
    ) {
        ScreenHeader(
            title = t("downloads_title", emptyArray()),
            backContentDescription = t("go_back", emptyArray()),
            onBack = onBack,
            showBackButton = showBackButton,
            actions = {
                if (selectedTab != DownloadsBucket.ACTIVE && currentItems.isNotEmpty()) {
                    IconButton(
                        onClick = { onClearBucket(selectedTab.key) },
                        modifier = Modifier.size(48.dp).testTag("downloads_clear_bucket")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = t("action_clear", emptyArray()), tint = SalviaSilverMid)
                    }
                }
            }
        )

        val tabsList = listOf(
            DownloadsBucket.ACTIVE to "${t("download_active", emptyArray())} (${activeItems.size})",
            DownloadsBucket.QUEUED to "${t("download_queue", emptyArray())} (${queuedItems.size})",
            DownloadsBucket.COMPLETED to "${t("download_completed", emptyArray())} (${completedItems.size})",
            DownloadsBucket.FAILED to "${t("download_failed", emptyArray())} (${failedItems.size})"
        )

        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = SalviaDeepCosmic,
            contentColor = SalviaPearl,
            edgePadding = 12.dp,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                    color = SalviaCyan
                )
            }
        ) {
            tabsList.forEach { (bucket, label) ->
                Tab(
                    selected = selectedTab == bucket,
                    onClick = { selectedTab = bucket },
                    text = {
                        Text(
                            text = label,
                            color = if (selectedTab == bucket) SalviaPearl else SalviaSilverMid,
                            fontWeight = if (selectedTab == bucket) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("downloads_tab_${bucket.key}")
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp)
        ) {
            if (selectedTab == DownloadsBucket.COMPLETED && state.isLockedFolderConfigured && (state.lockedDownloadsCount > 0 || state.isLockedFolderUnlocked)) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SalviaElevated,
                    border = BorderStroke(1.dp, SalviaGold.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isLockedFolderUnlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = SalviaGold
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t("downloads_locked_folder", emptyArray()), color = SalviaPearl, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                text = t("downloads_locked_count", arrayOf("count" to state.lockedDownloadsCount)),
                                color = SalviaSilverMid,
                                fontSize = 12.sp
                            )
                        }
                        if (state.isLockedFolderUnlocked) {
                            OutlinedButton(
                                onClick = onLockFolderAgain,
                                border = BorderStroke(1.dp, SalviaGold)
                            ) {
                                Text(t("action_lock_folder", emptyArray()), color = SalviaGold, fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                onClick = { showUnlockDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = SalviaGold, contentColor = SalviaDeepCosmic)
                            ) {
                                Text(t("action_unlock_folder", emptyArray()), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (currentItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val emptyKey = when (selectedTab) {
                        DownloadsBucket.ACTIVE -> "no_active_downloads"
                        DownloadsBucket.QUEUED -> "no_queued_downloads"
                        DownloadsBucket.COMPLETED -> "no_completed_downloads"
                        DownloadsBucket.FAILED -> "no_failed_downloads"
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(SalviaIrisContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(30.dp))
                        }
                        Text(t(emptyKey, emptyArray()), color = SalviaSilverMid, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    items(currentItems, key = { it.id }) { dl ->
                        DownloadItemRow(
                            item = dl,
                            isLockedFolderConfigured = state.isLockedFolderConfigured,
                            t = t,
                            onPause = { onPause(dl.id) },
                            onResume = { onResume(dl.id) },
                            onCancel = { onCancel(dl.id) },
                            onOpen = { onOpen(dl.id) },
                            onExport = { onExport(dl.id) },
                            onShare = { onShare(dl) },
                            onToggleLock = { onToggleLock(dl.id, !dl.isLocked) },
                            onDelete = { onDelete(dl.id) }
                        )
                    }
                }
            }
        }
    }
}
