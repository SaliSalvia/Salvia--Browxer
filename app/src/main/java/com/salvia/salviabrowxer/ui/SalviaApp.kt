package com.salvia.salviabrowxer.ui

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salvia.salviabrowxer.core.PastedLinkClassifier
import com.salvia.salviabrowxer.data.ClipboardLinkKind
import com.salvia.salviabrowxer.data.UiRoute
import com.salvia.salviabrowxer.data.UpdateCheckState
import com.salvia.salviabrowxer.ui.components.AppSplashOverlay
import com.salvia.salviabrowxer.ui.components.BrowserNavRail
import com.salvia.salviabrowxer.ui.components.MediaQualitySelectionSheet
import com.salvia.salviabrowxer.ui.components.MediaTraySheet
import com.salvia.salviabrowxer.ui.components.TabSwitcherSheet
import com.salvia.salviabrowxer.ui.screens.BookmarksScreen
import com.salvia.salviabrowxer.ui.screens.BrowserScreen
import com.salvia.salviabrowxer.ui.screens.DownloadsScreen
import com.salvia.salviabrowxer.ui.screens.HistoryScreen
import com.salvia.salviabrowxer.ui.screens.HomeScreen
import com.salvia.salviabrowxer.ui.screens.MediaPlayerScreen
import com.salvia.salviabrowxer.ui.screens.SettingsScreen
import com.salvia.salviabrowxer.ui.theme.SalviaBlack
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSurface
import com.salvia.salviabrowxer.ui.theme.SalviaTheme

@Composable
fun SalviaApp(
    viewModel: SalviaViewModel,
    browserContainer: FrameLayout,
    onExitWebFullscreen: () -> Boolean,
    onPickDownloadFolder: () -> Unit,
    onResetDownloadFolder: () -> Unit,
    onEnterPip: () -> Unit,
    onCopyText: (String) -> Unit,
    onShareLink: (String, String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val t: (String, Array<out Pair<String, Any?>>) -> String = { key, vars -> viewModel.t(key, *vars) }
    val tSimple: (String) -> String = { key -> viewModel.t(key) }

    val hasBackAction = state.route != UiRoute.Home ||
        state.isTabSwitcherVisible ||
        state.isMediaTrayVisible ||
        state.qualitySheet != null ||
        state.contextMenu != null ||
        state.findState.isOpen

    BackHandler(enabled = hasBackAction) {
        if (!onExitWebFullscreen()) {
            viewModel.navigateBack()
        }
    }

    SalviaTheme(isRtl = state.isRtl) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(SalviaBlack)
        ) {
            val isWideLayout = maxWidth >= 600.dp
            val showRail = isWideLayout && state.route != UiRoute.MediaPlayer

            Row(modifier = Modifier.fillMaxSize()) {
                if (showRail) {
                    BrowserNavRail(
                        currentRoute = state.route,
                        tabsCount = state.tabs.size,
                        activeDownloadsCount = state.activeDownloadsCount,
                        completedDownloadsCount = state.completedDownloadsCount,
                        t = tSimple,
                        onNavigate = { viewModel.navigate(it) }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    when (state.route) {
                        UiRoute.Home -> HomeScreen(
                            state = state,
                            showMobileTopBar = !isWideLayout,
                            t = t,
                            onNavigate = { viewModel.navigate(it) },
                            onOpenUrl = { url, isPrivate ->
                                viewModel.openUrlInBrowser(url, newTab = isPrivate, isPrivate = isPrivate)
                            },
                            onExtractVideo = { url -> viewModel.extractVideoFromLink(url) },
                            onPasteFromClipboard = { viewModel.pasteFromClipboard() },
                            onUseClipboardPrompt = { url, kind ->
                                viewModel.dismissClipboardPrompt()
                                if (kind == ClipboardLinkKind.MEDIA_FILE) {
                                    PastedLinkClassifier.pastedMediaCandidate(url)?.let {
                                        viewModel.openQualitySheetForCandidate(it)
                                    } ?: viewModel.openUrlInBrowser(url)
                                } else {
                                    viewModel.openUrlInBrowser(url)
                                }
                            },
                            onDismissClipboardPrompt = { viewModel.dismissClipboardPrompt() },
                            onSelectSearchEngine = { engine ->
                                viewModel.updateSettings { it.copy(searchEngine = engine) }
                            },
                            onOpenMediaCandidate = { url ->
                                PastedLinkClassifier.pastedMediaCandidate(url)?.let {
                                    viewModel.openQualitySheetForCandidate(it)
                                } ?: viewModel.openUrlInBrowser(url)
                            },
                            onPauseDownload = { viewModel.pauseDownload(it) },
                            onResumeDownload = { viewModel.resumeDownload(it) },
                            onCancelDownload = { viewModel.cancelDownload(it) },
                            onOpenDownload = { viewModel.openDownloadInPlayer(it) },
                            onExportDownload = { viewModel.exportDownloadToDevice(it) },
                            onShareDownload = { viewModel.shareDownloadedFile(it) },
                            onToggleLockDownload = { id, locked -> viewModel.setDownloadLocked(id, locked) },
                            onDeleteDownload = { viewModel.deleteDownload(it) }
                        )

                        UiRoute.Browser -> BrowserScreen(
                            state = state,
                            browserContainer = browserContainer,
                            isWideLayout = isWideLayout,
                            t = t,
                            onNavigate = { viewModel.navigate(it) },
                            onNavigateToUrl = { tabId, url -> viewModel.navigateTabToUrl(tabId, url) },
                            onNewTab = { isPrivate -> viewModel.createTab(isPrivate = isPrivate) },
                            onSelectTab = { viewModel.switchToTab(it) },
                            onCloseTab = { viewModel.closeTab(it) },
                            onDuplicateTab = { viewModel.duplicateTab(it) },
                            onCloseOtherTabs = { viewModel.closeOtherTabs(it) },
                            onGoBack = { viewModel.goBackInActiveTab() },
                            onGoForward = { viewModel.goForwardInActiveTab() },
                            onReload = { viewModel.reloadActiveTab() },
                            onStop = { viewModel.stopActiveTab() },
                            onToggleBookmark = { title, url -> viewModel.toggleBookmark(title, url) },
                            onToggleDesktop = { viewModel.toggleTabDesktopMode() },
                            onToggleSiteAdBlock = { host -> viewModel.toggleSiteAdBlock(host) },
                            onOpenTabSwitcher = { viewModel.openTabSwitcher() },
                            onOpenMediaTray = { viewModel.openMediaTray() },
                            onDownloadMainMedia = { viewModel.downloadMainMedia() },
                            onOpenQualityForCandidate = { viewModel.openQualitySheetForCandidate(it) },
                            onSaveFloatingPosition = { x, y ->
                                viewModel.updateSettings { it.copy(floatingButtonX = x, floatingButtonY = y) }
                            },
                            onOpenFindInPage = { viewModel.openFindInPage() },
                            onCloseFindInPage = { viewModel.closeFindInPage() },
                            onFindQuery = { q, back -> viewModel.findInPage(q, back) },
                            onPrintPage = { viewModel.printActiveTab() },
                            onSharePage = onShareLink,
                            onCopyLink = { url ->
                                onCopyText(url)
                                viewModel.showToast(viewModel.t("link_copied"))
                            },
                            onOpenExternal = { url -> viewModel.openInExternalBrowser(url) },
                            onAllowCleartext = { tabId -> viewModel.allowCleartextPrompt(tabId) },
                            onDismissCleartext = { tabId -> viewModel.dismissCleartextPrompt(tabId) },
                            onChangeTextZoom = { zoom ->
                                viewModel.updateSettings { it.copy(textZoom = zoom) }
                            },
                            onClearSiteData = { viewModel.clearSiteStorage() },
                            onDismissContextMenu = { viewModel.dismissContextMenu() },
                            onOpenLinkInTab = { url, isPriv ->
                                viewModel.openUrlInBrowser(url, newTab = true, isPrivate = isPriv)
                            },
                            onDownloadContextUrl = { url, mime ->
                                viewModel.downloadContextUrl(url, mime)
                            }
                        )

                        UiRoute.Downloads -> DownloadsScreen(
                            state = state,
                            showBackButton = !isWideLayout,
                            t = t,
                            onBack = { viewModel.navigateBack() },
                            onPause = { viewModel.pauseDownload(it) },
                            onResume = { viewModel.resumeDownload(it) },
                            onCancel = { viewModel.cancelDownload(it) },
                            onOpen = { viewModel.openDownloadInPlayer(it) },
                            onExport = { viewModel.exportDownloadToDevice(it) },
                            onShare = { viewModel.shareDownloadedFile(it) },
                            onToggleLock = { id, locked -> viewModel.setDownloadLocked(id, locked) },
                            onDelete = { viewModel.deleteDownload(it) },
                            onClearBucket = { viewModel.clearDownloadBucket(it) },
                            onUnlockFolder = { pw -> viewModel.unlockLockedFolder(pw) },
                            onLockFolderAgain = { viewModel.lockLockedFolderAgain() }
                        )

                        UiRoute.Bookmarks -> BookmarksScreen(
                            bookmarks = state.bookmarks,
                            showBackButton = !isWideLayout,
                            t = tSimple,
                            onBack = { viewModel.navigateBack() },
                            onOpenUrl = { url -> viewModel.openUrlInBrowser(url) },
                            onRemoveBookmark = { viewModel.removeBookmark(it) }
                        )

                        UiRoute.History -> HistoryScreen(
                            history = state.history,
                            showBackButton = !isWideLayout,
                            t = tSimple,
                            onBack = { viewModel.navigateBack() },
                            onOpenUrl = { url -> viewModel.openUrlInBrowser(url) },
                            onRemoveItem = { viewModel.removeHistoryItem(it) },
                            onClearAll = { viewModel.clearHistory() }
                        )

                        UiRoute.Settings -> SettingsScreen(
                            state = state,
                            showBackButton = !isWideLayout,
                            t = t,
                            onBack = { viewModel.navigateBack() },
                            onUpdateSettings = { viewModel.updateSettings(it) },
                            onPickDownloadFolder = onPickDownloadFolder,
                            onResetDownloadFolder = onResetDownloadFolder,
                            onClearHistory = { viewModel.clearHistory() },
                            onClearSiteStorage = { viewModel.clearSiteStorage() },
                            onClearBrowsingData = { viewModel.clearAllBrowsingData() },
                            onSetLockPassword = { pw -> viewModel.setLockedFolderPassword(pw) },
                            onVerifyCurrentLockPassword = { pw -> viewModel.verifyCurrentLockedFolderPassword(pw) },
                            onRemoveLockPassword = { viewModel.removeLockedFolderPassword() },
                            onCheckUpdates = { viewModel.checkForUpdates(manual = true) },
                            onExportDiagnostics = { viewModel.exportDiagnostics() },
                            onShareCrashReports = { viewModel.shareCrashReports() },
                            onOpenUrl = { url -> viewModel.openUrlInBrowser(url) },
                            onShowToast = { msg -> viewModel.showToast(msg) }
                        )

                        UiRoute.MediaPlayer -> MediaPlayerScreen(
                            state = state,
                            t = t,
                            resolvePlayableUri = { item -> viewModel.resolvePlayableUri(item) },
                            onBack = { viewModel.navigateBack() },
                            onSelectItem = { id -> viewModel.setPlayerDownloadId(id) },
                            onCyclePlaybackOrder = { viewModel.cyclePlaybackOrder() },
                            onDeleteItem = { id -> viewModel.deleteDownload(id) },
                            onEnterPip = onEnterPip
                        )
                    }

                    // Bottom Sheets
                    if (state.isTabSwitcherVisible) {
                        TabSwitcherSheet(
                            tabs = state.tabs,
                            currentTabId = state.currentTabId,
                            t = tSimple,
                            onDismiss = { viewModel.closeTabSwitcher() },
                            onSelectTab = { viewModel.switchToTab(it) },
                            onCloseTab = { viewModel.closeTab(it) },
                            onDuplicateTab = { viewModel.duplicateTab(it) },
                            onNewTab = { viewModel.createTab(isPrivate = false, switchToBrowser = true) },
                            onNewPrivateTab = { viewModel.createTab(isPrivate = true, switchToBrowser = true) }
                        )
                    }

                    if (state.isMediaTrayVisible) {
                        MediaTraySheet(
                            rankedCandidates = state.rankedTabCandidates,
                            t = t,
                            onDismiss = { viewModel.closeMediaTray() },
                            onOpenQuality = { viewModel.openQualitySheetForCandidate(it) },
                            onDownloadBatch = { viewModel.startBatchDownloadFromCandidates(it) }
                        )
                    }

                    state.qualitySheet?.let { sheet ->
                        MediaQualitySelectionSheet(
                            state = sheet,
                            t = t,
                            onDismiss = { viewModel.closeQualitySheet() },
                            onConfirmDownload = { fmt, name -> viewModel.startDownloadFromFormat(fmt, name) }
                        )
                    }

                    // Update Available Dialog
                    val updateAvail = state.updateCheckState as? UpdateCheckState.Available
                    if (state.showUpdateDialog && updateAvail != null) {
                        val release = updateAvail.release
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissUpdateDialog() },
                            containerColor = SalviaSurface,
                            titleContentColor = SalviaPearl,
                            title = {
                                Text(viewModel.t("update_available_title", "version" to release.version))
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    release.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                                        Text(notes.take(400), color = SalviaSilverMid, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.dismissUpdateDialog()
                                                viewModel.openInExternalBrowser(release.url)
                                            }
                                        ) {
                                            Text(viewModel.t("update_view_release"), color = SalviaCyan)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = { viewModel.startUpdateApkDownload(release) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                                ) {
                                    Text(viewModel.t("update_download_install"), fontWeight = FontWeight.SemiBold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                                    Text(viewModel.t("action_dismiss"), color = SalviaSilverMid)
                                }
                            }
                        )
                    }

                    // Toast Pill Banner
                    ToastOverlayBanner(
                        message = state.toastMessage,
                        onDismiss = { viewModel.dismissToast() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 72.dp)
                    )

                    // Startup Splash Overlay
                    if (state.showSplash) {
                        AppSplashOverlay(onFinished = { viewModel.dismissSplash() })
                    }
                }
            }
        }
    }
}

@Composable
private fun ToastOverlayBanner(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SalviaElevated,
                border = BorderStroke(1.dp, SalviaBorder),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .testTag("toast_banner")
            ) {
                Text(
                    text = msg,
                    color = SalviaPearl,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}
