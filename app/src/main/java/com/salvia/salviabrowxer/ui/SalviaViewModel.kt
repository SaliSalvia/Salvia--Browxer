package com.salvia.salviabrowxer.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.salvia.salviabrowxer.browser.BrowserHost
import com.salvia.salviabrowxer.core.AdBlockRules
import com.salvia.salviabrowxer.core.AddressBarResolver
import com.salvia.salviabrowxer.core.CrashReporter
import com.salvia.salviabrowxer.core.DownloadFileResolver
import com.salvia.salviabrowxer.core.LockoutPolicy
import com.salvia.salviabrowxer.core.MainContentSelector
import com.salvia.salviabrowxer.core.MediaFormatters
import com.salvia.salviabrowxer.core.MediaOfferability
import com.salvia.salviabrowxer.core.MediaProbeAndExtract
import com.salvia.salviabrowxer.core.MediaUrlRules
import com.salvia.salviabrowxer.core.NativeProxyClient
import com.salvia.salviabrowxer.core.PastedLinkClassifier
import com.salvia.salviabrowxer.core.SalviaConstants
import com.salvia.salviabrowxer.core.SecurityManager
import com.salvia.salviabrowxer.data.AppSettings
import com.salvia.salviabrowxer.data.AppStateRepository
import com.salvia.salviabrowxer.data.AppStrings
import com.salvia.salviabrowxer.data.BookmarkItem
import com.salvia.salviabrowxer.data.BrowserContextMenu
import com.salvia.salviabrowxer.data.BrowserTab
import com.salvia.salviabrowxer.data.CandidateRole
import com.salvia.salviabrowxer.data.ClassifiedCandidate
import com.salvia.salviabrowxer.data.ClipboardPrompt
import com.salvia.salviabrowxer.data.ConnectionKind
import com.salvia.salviabrowxer.data.DiagnosticsEntry
import com.salvia.salviabrowxer.data.DownloadItemRecord
import com.salvia.salviabrowxer.data.DownloadState
import com.salvia.salviabrowxer.data.FindState
import com.salvia.salviabrowxer.data.HistoryItem
import com.salvia.salviabrowxer.data.LanguageMode
import com.salvia.salviabrowxer.data.MediaCandidate
import com.salvia.salviabrowxer.data.MediaFormat
import com.salvia.salviabrowxer.data.PlaybackOrder
import com.salvia.salviabrowxer.data.QualitySheetState
import com.salvia.salviabrowxer.data.SearchEngineKey
import com.salvia.salviabrowxer.data.UiRoute
import com.salvia.salviabrowxer.data.UnsupportedMedia
import com.salvia.salviabrowxer.data.UpdateCheckState
import com.salvia.salviabrowxer.service.DownloadQueueStore
import com.salvia.salviabrowxer.service.NativeDownloadBus
import com.salvia.salviabrowxer.service.NativeDownloadService
import com.salvia.salviabrowxer.storage.MediaPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SalviaUiState(
    val route: UiRoute = UiRoute.Home,
    val routeBackStack: List<UiRoute> = emptyList(),
    val showSplash: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val locale: String = "en",
    val isRtl: Boolean = false,
    val connectionKind: ConnectionKind = ConnectionKind.WIFI,
    val tabs: List<BrowserTab> = emptyList(),
    val currentTabId: String = "",
    val isTabSwitcherVisible: Boolean = false,
    val bookmarks: List<BookmarkItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val downloads: List<DownloadItemRecord> = emptyList(),
    val diagnostics: List<DiagnosticsEntry> = emptyList(),
    val tabCandidates: Map<String, List<MediaCandidate>> = emptyMap(),
    val visibleMediaByTab: Map<String, String> = emptyMap(),
    val isMediaTrayVisible: Boolean = false,
    val qualitySheet: QualitySheetState? = null,
    val isExtractingHome: Boolean = false,
    val clipboardPrompt: ClipboardPrompt? = null,
    val findState: FindState = FindState(),
    val contextMenu: BrowserContextMenu? = null,
    val playerDownloadId: String? = null,
    val playbackOrder: PlaybackOrder = PlaybackOrder.SEQUENTIAL,
    val isLockedFolderConfigured: Boolean = false,
    val isLockedFolderUnlocked: Boolean = false,
    val lockoutRemainingSeconds: Int = 0,
    val updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    val showUpdateDialog: Boolean = false,
    val crashReportCount: Int = 0,
    val toastMessage: String? = null
) {
    val activeTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == currentTabId } ?: tabs.firstOrNull()

    val currentTabCandidates: List<MediaCandidate>
        get() {
            val tab = activeTab ?: return emptyList()
            val showcase = SalviaConstants.BUILTIN_SHOWCASE_PAGES.firstOrNull { it.url == tab.url }
            if (showcase != null) {
                return showcase.media.mapIndexed { idx, item ->
                    MediaCandidate(
                        id = "showcase-$idx-${item.mediaUrl}",
                        pageUrl = tab.url,
                        mediaUrl = item.mediaUrl,
                        title = item.title,
                        mimeType = item.mimeType,
                        extension = item.extension,
                        source = "DOM",
                        confidence = 0.96,
                        duration = item.durationMs,
                        estimatedSize = item.estimatedSize,
                        visible = idx == 0
                    )
                }
            }
            return tabCandidates[tab.id].orEmpty()
        }

    val rankedTabCandidates: List<ClassifiedCandidate>
        get() {
            val tab = activeTab ?: return emptyList()
            val visibleUrl = visibleMediaByTab[tab.id]
            return MainContentSelector.rankAndClassifyCandidates(currentTabCandidates, visibleUrl, tab.url).second
        }

    val currentMainCandidate: MediaCandidate?
        get() {
            val tab = activeTab ?: return null
            val visibleUrl = visibleMediaByTab[tab.id]
            return MainContentSelector.selectMainCandidate(currentTabCandidates, visibleUrl, tab.url)
        }

    val activeDownloadsCount: Int
        get() = downloads.count {
            it.state == DownloadState.QUEUED || it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING
        }

    val completedDownloadsCount: Int
        get() = downloads.count { it.state == DownloadState.COMPLETED && (!it.isLocked || isLockedFolderUnlocked) }

    val lockedDownloadsCount: Int
        get() = downloads.count { it.state == DownloadState.COMPLETED && it.isLocked }
}

class SalviaViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = AppStateRepository(appContext)
    private val lockoutPolicy = LockoutPolicy()
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _uiState = MutableStateFlow(SalviaUiState())
    val uiState: StateFlow<SalviaUiState> = _uiState.asStateFlow()

    var browserHost: BrowserHost? = null
        private set

    private var lastCheckedClipboard: String? = null
    private var lastUpdateCheckAt: Long = 0L
    private var toastJob: Job? = null
    private var lockoutTimerJob: Job? = null
    private var extractJob: Job? = null
    private var probeJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshConnectionKind()
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = refreshConnectionKind()
        override fun onLost(network: Network) = refreshConnectionKind()
    }

    init {
        val persisted = repository.load()
        lastUpdateCheckAt = persisted.lastUpdateCheckAt
        val locale = AppStrings.resolveLocale(persisted.settings.language)
        val mergedDownloads = mergeWithPersistedNativeJobs(persisted.downloads, locale)
        _uiState.value = SalviaUiState(
            settings = persisted.settings,
            locale = locale,
            isRtl = locale == "fa",
            connectionKind = queryConnectionKind(),
            tabs = persisted.tabs,
            currentTabId = persisted.currentTabId,
            bookmarks = persisted.bookmarks,
            history = persisted.history,
            downloads = mergedDownloads,
            diagnostics = persisted.diagnostics,
            isLockedFolderConfigured = SecurityManager.isPasswordConfigured(appContext),
            crashReportCount = CrashReporter.reportCount(appContext)
        )

        NativeDownloadBus.listener = { payload ->
            viewModelScope.launch {
                handleNativeDownloadEvent(payload)
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e)
        }

        silentStartupUpdateCheckIfNeeded()
        pumpDownloadQueue()
    }

    private fun mergeWithPersistedNativeJobs(
        existing: List<DownloadItemRecord>,
        locale: String
    ): List<DownloadItemRecord> {
        val nativeJobs = DownloadQueueStore.allJobs(appContext)
        if (nativeJobs.isEmpty()) return existing
        val map = existing.associateBy { it.id }.toMutableMap()
        val waitingWifi = AppStrings.t(locale, "download_waiting_wifi")
        for (spec in nativeJobs) {
            val cur = map[spec.id]
            if (cur == null) {
                val mappedState = when (spec.status) {
                    DownloadQueueStore.STATUS_RUNNING -> DownloadState.CONNECTING
                    DownloadQueueStore.STATUS_PAUSED, DownloadQueueStore.STATUS_PAUSED_WIFI -> DownloadState.PAUSED
                    DownloadQueueStore.STATUS_COMPLETE -> DownloadState.COMPLETED
                    DownloadQueueStore.STATUS_CANCELLED -> DownloadState.CANCELLED
                    DownloadQueueStore.STATUS_FAILED -> DownloadState.FAILED
                    else -> DownloadState.PAUSED
                }
                map[spec.id] = DownloadItemRecord(
                    id = spec.id,
                    title = spec.filename,
                    fileName = spec.filename,
                    url = spec.url,
                    originalPageUrl = spec.referer,
                    state = mappedState,
                    errorMessage = if (spec.status == DownloadQueueStore.STATUS_PAUSED_WIFI) waitingWifi else null,
                    mimeType = spec.mimeType,
                    isHls = spec.isHls,
                    audioTrackUrl = spec.audioTrackUrl,
                    createdAt = spec.createdAt
                )
            } else if (cur.state != DownloadState.COMPLETED && cur.state != DownloadState.CANCELLED) {
                when (spec.status) {
                    DownloadQueueStore.STATUS_RUNNING ->
                        map[spec.id] = cur.copy(state = DownloadState.CONNECTING, errorMessage = null)
                    DownloadQueueStore.STATUS_PAUSED_WIFI ->
                        map[spec.id] = cur.copy(state = DownloadState.PAUSED, errorMessage = waitingWifi)
                }
            }
        }
        return map.values.sortedByDescending { it.createdAt }
    }

    fun attachBrowserHost(host: BrowserHost) {
        browserHost = host
        val s = _uiState.value.settings
        host.applySettings(s.isJavaScriptEnabled, s.areCookiesEnabled, s.textZoom, s.isDarkPages, s.isCleartextAllowed)
        syncNativeBrowserToCurrentRoute()
    }

    fun detachBrowserHost() {
        browserHost = null
    }

    fun t(key: String, vararg vars: Pair<String, Any?>): String =
        AppStrings.t(_uiState.value.locale, key, *vars)

    fun dismissSplash() {
        _uiState.update { it.copy(showSplash = false) }
    }

    fun showToast(message: String) {
        toastJob?.cancel()
        _uiState.update { it.copy(toastMessage = message) }
        toastJob = viewModelScope.launch {
            delay(3000)
            _uiState.update { it.copy(toastMessage = null) }
        }
    }

    fun dismissToast() {
        toastJob?.cancel()
        _uiState.update { it.copy(toastMessage = null) }
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(route: UiRoute) {
        _uiState.update { state ->
            if (state.route == route) return@update state
            val newStack = if (route == UiRoute.Home) {
                emptyList()
            } else {
                (state.routeBackStack + state.route).takeLast(12)
            }
            state.copy(
                route = route,
                routeBackStack = newStack,
                isTabSwitcherVisible = false,
                isMediaTrayVisible = false,
                contextMenu = null,
                findState = FindState()
            )
        }
        syncNativeBrowserToCurrentRoute()
    }

    fun navigateBack(): Boolean {
        val st = _uiState.value
        if (st.qualitySheet != null) {
            closeQualitySheet()
            return true
        }
        if (st.contextMenu != null) {
            dismissContextMenu()
            return true
        }
        if (st.isMediaTrayVisible) {
            closeMediaTray()
            return true
        }
        if (st.isTabSwitcherVisible) {
            closeTabSwitcher()
            return true
        }
        if (st.findState.isOpen) {
            closeFindInPage()
            return true
        }
        if (st.route == UiRoute.Browser) {
            val tab = st.activeTab
            if (tab != null && !isShowcaseUrl(tab.url) && browserHost?.goBack(tab.id) == true) {
                return true
            }
            if (goBackInActiveTab()) return true
        }
        if (st.route != UiRoute.Home) {
            val prev = st.routeBackStack.lastOrNull() ?: UiRoute.Home
            val nextStack = if (st.routeBackStack.isNotEmpty()) st.routeBackStack.dropLast(1) else emptyList()
            _uiState.update { it.copy(route = prev, routeBackStack = nextStack) }
            syncNativeBrowserToCurrentRoute()
            return true
        }
        return false
    }

    private fun syncNativeBrowserToCurrentRoute() {
        val host = browserHost ?: return
        val st = _uiState.value
        val tab = st.activeTab
        val showNative = st.route == UiRoute.Browser &&
            tab != null &&
            !isShowcaseUrl(tab.url) &&
            tab.errorText == null &&
            tab.cleartextPromptUrl == null
        // `showNative` already implies `tab != null`, so the compiler smart-casts it here.
        if (showNative) {
            val adBlock = effectiveAdBlockForUrl(tab.url, st.settings)
            host.show(tab.id, tab.url, tab.desktopMode, adBlock, tab.isPrivate)
        } else {
            host.hide()
        }
    }

    // ------------------------------------------------------------------ clipboard

    fun inspectClipboardIfChanged() {
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            if (!cm.hasPrimaryClip()) return
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()?.trim() ?: return
            if (text == lastCheckedClipboard) return
            lastCheckedClipboard = text
            val extracted = AddressBarResolver.extractFirstSharedUrl(text) ?: return
            val kind = PastedLinkClassifier.classifyPastedLink(extracted) ?: return
            _uiState.update { it.copy(clipboardPrompt = ClipboardPrompt(extracted, kind)) }
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e)
        }
    }

    fun dismissClipboardPrompt() {
        _uiState.update { it.copy(clipboardPrompt = null) }
    }

    fun pasteFromClipboard(): String? {
        return try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()?.trim()
            if (text.isNullOrBlank()) {
                showToast(t("home_empty_clipboard"))
                null
            } else {
                lastCheckedClipboard = text
                text
            }
        } catch (_: Exception) {
            showToast(t("home_empty_clipboard"))
            null
        }
    }

    fun handleIncomingExternalUrl(url: String) {
        val kind = PastedLinkClassifier.classifyPastedLink(url)
        if (kind == com.salvia.salviabrowxer.data.ClipboardLinkKind.MEDIA_FILE) {
            val candidate = PastedLinkClassifier.pastedMediaCandidate(url)
            if (candidate != null) {
                openQualitySheetForCandidate(candidate)
                return
            }
        }
        openUrlInBrowser(url)
    }

    // ------------------------------------------------------------------ links & extraction

    fun openUrlInBrowser(rawInput: String, newTab: Boolean = false, isPrivate: Boolean = false) {
        val resolved = AddressBarResolver.resolveAddressBarInput(rawInput, _uiState.value.settings.searchEngine)
            ?: return
        if (newTab || isPrivate) {
            createTab(url = resolved, isPrivate = isPrivate, switchToBrowser = true)
        } else {
            val active = _uiState.value.activeTab
            if (active == null) {
                createTab(url = resolved, isPrivate = false, switchToBrowser = true)
            } else {
                navigateTabToUrl(active.id, resolved)
                navigate(UiRoute.Browser)
            }
        }
    }

    fun extractVideoFromLink(rawInput: String) {
        val url = AddressBarResolver.normalizeLinkInput(rawInput)
        if (url == null) {
            showToast(t("home_invalid_link"))
            return
        }
        val pastedCandidate = PastedLinkClassifier.pastedMediaCandidate(url)
        if (pastedCandidate != null) {
            openQualitySheetForCandidate(pastedCandidate)
            return
        }
        if (url.startsWith("http://") && !_uiState.value.settings.isCleartextAllowed) {
            openUrlInBrowser(url)
            return
        }
        if (url == SalviaConstants.SHOWCASE_URL) {
            openUrlInBrowser(url)
            return
        }
        extractJob?.cancel()
        _uiState.update { it.copy(isExtractingHome = true) }
        extractJob = viewModelScope.launch {
            val result = MediaProbeAndExtract.extractMediaFromPageUrl(url)
            _uiState.update { it.copy(isExtractingHome = false) }
            if (result.ok && result.candidates.isNotEmpty()) {
                val activeId = _uiState.value.activeTab?.id ?: "tab-1"
                _uiState.update { st ->
                    val existing = st.tabCandidates[activeId].orEmpty()
                    val merged = (result.candidates + existing).distinctBy { it.mediaUrl }
                    st.copy(
                        tabCandidates = st.tabCandidates + (activeId to merged),
                        isMediaTrayVisible = true
                    )
                }
            } else {
                showToast(t("extract_none_found"))
                openUrlInBrowser(url)
            }
        }
    }

    // ------------------------------------------------------------------ tabs & browsing

    fun isShowcaseUrl(url: String): Boolean =
        SalviaConstants.BUILTIN_SHOWCASE_PAGES.any { it.url == url }

    private fun effectiveAdBlockForUrl(url: String, settings: AppSettings): Boolean {
        if (!settings.isAdBlockEnabled) return false
        val host = AdBlockRules.hostOfUrl(url) ?: return true
        return host !in settings.adBlockDisabledSites
    }

    fun isAdBlockDisabledForHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return host in _uiState.value.settings.adBlockDisabledSites
    }

    fun toggleSiteAdBlock(host: String) {
        val clean = host.lowercase().trim()
        if (clean.isEmpty()) return
        val wasDisabled = clean in _uiState.value.settings.adBlockDisabledSites
        _uiState.update { st ->
            val nextSet = if (wasDisabled) {
                st.settings.adBlockDisabledSites - clean
            } else {
                st.settings.adBlockDisabledSites + clean
            }
            st.copy(settings = st.settings.copy(adBlockDisabledSites = nextSet))
        }
        persist()
        val tab = _uiState.value.activeTab
        if (tab != null) {
            val effective = effectiveAdBlockForUrl(tab.url, _uiState.value.settings)
            browserHost?.setAdBlock(tab.id, effective)
            browserHost?.reload(tab.id)
        }
        showToast(t(if (wasDisabled) "ad_block_on_here" else "ad_block_off_here", "host" to clean))
    }

    fun createTab(url: String = _uiState.value.settings.homepage, isPrivate: Boolean = false, switchToBrowser: Boolean = true) {
        val id = "tab-${UUID.randomUUID().toString().take(8)}"
        val title = if (isPrivate) t("private_browsing") else (AdBlockRules.hostOfUrl(url) ?: url)
        val newTab = BrowserTab(
            id = id,
            url = url,
            title = title,
            isPrivate = isPrivate,
            desktopMode = _uiState.value.settings.isDesktopSite
        )
        _uiState.update { st ->
            val nextTabs = hibernateIfNeeded(st.tabs + newTab, id)
            st.copy(tabs = nextTabs, currentTabId = id, isTabSwitcherVisible = false)
        }
        persist()
        navigateTabToUrl(id, url, recordHistoryEntry = false)
        if (switchToBrowser) navigate(UiRoute.Browser) else syncNativeBrowserToCurrentRoute()
    }

    fun duplicateTab(tabId: String) {
        val source = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        createTab(url = source.url, isPrivate = source.isPrivate, switchToBrowser = true)
    }

    fun switchToTab(tabId: String) {
        val target = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        _uiState.update { st ->
            val updated = st.tabs.map {
                if (it.id == tabId) it.copy(isSleeping = false, lastAccessedAt = System.currentTimeMillis()) else it
            }
            st.copy(
                tabs = hibernateIfNeeded(updated, tabId),
                currentTabId = tabId,
                isTabSwitcherVisible = false,
                contextMenu = null
            )
        }
        persist()
        if (!isShowcaseUrl(target.url)) {
            val st = _uiState.value
            val adBlock = effectiveAdBlockForUrl(target.url, st.settings)
            browserHost?.show(target.id, target.url, target.desktopMode, adBlock, target.isPrivate)
        }
        syncNativeBrowserToCurrentRoute()
    }

    fun closeTab(tabId: String) {
        val current = _uiState.value.tabs
        browserHost?.destroy(tabId)
        val remaining = current.filterNot { it.id == tabId }
        val nextTabs = if (remaining.isEmpty()) {
            listOf(
                BrowserTab(
                    id = "tab-${UUID.randomUUID().toString().take(8)}",
                    url = _uiState.value.settings.homepage,
                    title = "Google",
                    desktopMode = _uiState.value.settings.isDesktopSite
                )
            )
        } else {
            remaining
        }
        val nextCurrent = if (_uiState.value.currentTabId == tabId || nextTabs.none { it.id == _uiState.value.currentTabId }) {
            nextTabs.last().id
        } else {
            _uiState.value.currentTabId
        }
        _uiState.update { st ->
            st.copy(
                tabs = nextTabs,
                currentTabId = nextCurrent,
                tabCandidates = st.tabCandidates - tabId,
                visibleMediaByTab = st.visibleMediaByTab - tabId,
                contextMenu = null
            )
        }
        persist()
        syncNativeBrowserToCurrentRoute()
    }

    fun closeOtherTabs(keepTabId: String) {
        val keep = _uiState.value.tabs.firstOrNull { it.id == keepTabId } ?: return
        _uiState.value.tabs.filter { it.id != keepTabId }.forEach { browserHost?.destroy(it.id) }
        _uiState.update { st ->
            st.copy(
                tabs = listOf(keep),
                currentTabId = keep.id,
                isTabSwitcherVisible = false,
                contextMenu = null
            )
        }
        persist()
        syncNativeBrowserToCurrentRoute()
    }

    private fun hibernateIfNeeded(tabs: List<BrowserTab>, activeId: String): List<BrowserTab> {
        val live = tabs.filter { !it.isSleeping }
        if (live.size <= SalviaConstants.MAX_LIVE_TABS) return tabs
        val toSleep = live.filter { it.id != activeId }
            .sortedBy { it.lastAccessedAt }
            .take(live.size - SalviaConstants.MAX_LIVE_TABS)
            .map { it.id }
            .toSet()
        toSleep.forEach { browserHost?.destroy(it) }
        return tabs.map { if (it.id in toSleep) it.copy(isSleeping = true) else it }
    }

    fun navigateTabToUrl(tabId: String, rawUrl: String, recordHistoryEntry: Boolean = true) {
        val resolved = AddressBarResolver.resolveAddressBarInput(rawUrl, _uiState.value.settings.searchEngine)
            ?: return
        val tab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (resolved.startsWith("http://") && !_uiState.value.settings.isCleartextAllowed) {
            _uiState.update { st ->
                st.copy(tabs = st.tabs.map {
                    if (it.id == tabId) it.copy(cleartextPromptUrl = resolved, url = resolved, isLoading = false, errorText = null) else it
                })
            }
            syncNativeBrowserToCurrentRoute()
            return
        }

        val showcase = SalviaConstants.BUILTIN_SHOWCASE_PAGES.firstOrNull { it.url == resolved }
        _uiState.update { st ->
            val updatedTabs = st.tabs.map { t ->
                if (t.id == tabId) {
                    val pushBack = if (recordHistoryEntry && t.url != resolved) (t.backStack + t.url).takeLast(30) else t.backStack
                    t.copy(
                        url = resolved,
                        title = showcase?.title ?: (AdBlockRules.hostOfUrl(resolved) ?: resolved),
                        isLoading = showcase == null,
                        progress = if (showcase == null) 10 else 100,
                        errorText = null,
                        cleartextPromptUrl = null,
                        isSleeping = false,
                        backStack = pushBack,
                        forwardStack = emptyList(),
                        canGoBack = pushBack.isNotEmpty(),
                        canGoForward = false,
                        lastAccessedAt = System.currentTimeMillis()
                    )
                } else t
            }
            st.copy(
                tabs = updatedTabs,
                tabCandidates = st.tabCandidates - tabId,
                visibleMediaByTab = st.visibleMediaByTab - tabId,
                contextMenu = null
            )
        }
        persist()

        if (showcase != null) {
            if (!tab.isPrivate) addHistoryEntry(showcase.title, showcase.url)
            browserHost?.hide()
        } else {
            val st = _uiState.value
            val adBlock = effectiveAdBlockForUrl(resolved, st.settings)
            val host = browserHost
            if (st.route == UiRoute.Browser && st.currentTabId == tabId) {
                if (host != null && host.hasTab(tabId)) {
                    host.show(tabId, resolved, tab.desktopMode, adBlock, tab.isPrivate)
                    host.load(tabId, resolved, tab.desktopMode, adBlock, tab.isPrivate)
                } else {
                    host?.show(tabId, resolved, tab.desktopMode, adBlock, tab.isPrivate)
                }
            } else {
                host?.load(tabId, resolved, tab.desktopMode, adBlock, tab.isPrivate)
            }
        }
    }

    fun allowCleartextPrompt(tabId: String) {
        val url = _uiState.value.tabs.firstOrNull { it.id == tabId }?.cleartextPromptUrl ?: return
        updateSettings { it.copy(isCleartextAllowed = true) }
        navigateTabToUrl(tabId, url, recordHistoryEntry = false)
    }

    fun dismissCleartextPrompt(tabId: String) {
        _uiState.update { st ->
            st.copy(tabs = st.tabs.map {
                if (it.id == tabId) it.copy(cleartextPromptUrl = null) else it
            })
        }
        syncNativeBrowserToCurrentRoute()
    }

    fun reloadActiveTab() {
        val tab = _uiState.value.activeTab ?: return
        if (isShowcaseUrl(tab.url)) return
        _uiState.update { st ->
            st.copy(tabs = st.tabs.map { if (it.id == tab.id) it.copy(errorText = null, isLoading = true, progress = 10) else it })
        }
        syncNativeBrowserToCurrentRoute()
        browserHost?.reload(tab.id)
    }

    fun stopActiveTab() {
        val tab = _uiState.value.activeTab ?: return
        browserHost?.stop(tab.id)
        _uiState.update { st ->
            st.copy(tabs = st.tabs.map { if (it.id == tab.id) it.copy(isLoading = false, progress = 100) else it })
        }
    }

    fun goBackInActiveTab(): Boolean {
        val tab = _uiState.value.activeTab ?: return false
        if (!isShowcaseUrl(tab.url) && browserHost?.goBack(tab.id) == true) {
            return true
        }
        if (tab.backStack.isEmpty()) return false
        val prev = tab.backStack.last()
        _uiState.update { st ->
            st.copy(tabs = st.tabs.map {
                if (it.id == tab.id) it.copy(
                    url = prev,
                    backStack = it.backStack.dropLast(1),
                    forwardStack = (it.forwardStack + it.url).takeLast(30),
                    canGoBack = it.backStack.size > 1,
                    canGoForward = true
                ) else it
            })
        }
        navigateTabToUrl(tab.id, prev, recordHistoryEntry = false)
        return true
    }

    fun goForwardInActiveTab() {
        val tab = _uiState.value.activeTab ?: return
        if (!isShowcaseUrl(tab.url) && browserHost?.goForward(tab.id) == true) return
        if (tab.forwardStack.isEmpty()) return
        val next = tab.forwardStack.last()
        _uiState.update { st ->
            st.copy(tabs = st.tabs.map {
                if (it.id == tab.id) it.copy(
                    url = next,
                    forwardStack = it.forwardStack.dropLast(1),
                    backStack = (it.backStack + it.url).takeLast(30),
                    canGoBack = true,
                    canGoForward = it.forwardStack.size > 1
                ) else it
            })
        }
        navigateTabToUrl(tab.id, next, recordHistoryEntry = false)
    }

    fun toggleTabDesktopMode() {
        val tab = _uiState.value.activeTab ?: return
        val nextDesktop = !tab.desktopMode
        _uiState.update { st ->
            st.copy(tabs = st.tabs.map { if (it.id == tab.id) it.copy(desktopMode = nextDesktop) else it })
        }
        browserHost?.setDesktop(tab.id, nextDesktop)
    }

    fun openTabSwitcher() = _uiState.update { it.copy(isTabSwitcherVisible = true) }
    fun closeTabSwitcher() = _uiState.update { it.copy(isTabSwitcherVisible = false) }

    fun openFindInPage() = _uiState.update { it.copy(findState = FindState(isOpen = true)) }
    fun closeFindInPage() {
        val tabId = _uiState.value.activeTab?.id
        if (tabId != null) browserHost?.find(tabId, "", false)
        _uiState.update { it.copy(findState = FindState(isOpen = false)) }
    }

    fun findInPage(query: String, backwards: Boolean = false) {
        val tabId = _uiState.value.activeTab?.id ?: return
        _uiState.update { it.copy(findState = it.findState.copy(query = query)) }
        browserHost?.find(tabId, query, backwards)
    }

    fun printActiveTab() {
        val tab = _uiState.value.activeTab ?: return
        if (!isShowcaseUrl(tab.url)) {
            browserHost?.printTab(tab.id)
        }
    }

    fun openInExternalBrowser(url: String): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(clean)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ native browser events

    fun handleNativeBrowserEvent(payload: JSONObject) {
        val type = payload.optString("type")
        val tabId = payload.optString("tabId")
        when (type) {
            "loading" -> {
                val url = payload.optString("url")
                val progress = payload.optInt("progress", 0)
                val started = payload.optBoolean("started", false)
                _uiState.update { st ->
                    val nextCands = if (started) st.tabCandidates - tabId else st.tabCandidates
                    val nextVis = if (started) st.visibleMediaByTab - tabId else st.visibleMediaByTab
                    st.copy(
                        tabCandidates = nextCands,
                        visibleMediaByTab = nextVis,
                        tabs = st.tabs.map { t ->
                            if (t.id == tabId) {
                                t.copy(
                                    url = url.ifBlank { t.url },
                                    isLoading = progress < 100,
                                    progress = progress,
                                    errorText = if (started) null else t.errorText
                                )
                            } else t
                        }
                    )
                }
            }
            "finished" -> {
                val url = payload.optString("url")
                val title = payload.optString("title")
                val blocked = payload.optInt("blocked", 0)
                val canGoBack = payload.optBoolean("canGoBack", false)
                val canGoForward = payload.optBoolean("canGoForward", false)
                val tab = _uiState.value.tabs.firstOrNull { it.id == tabId }
                _uiState.update { st ->
                    st.copy(
                        settings = st.settings.copy(
                            adBlockBlockedRequests = st.settings.adBlockBlockedRequests + blocked
                        ),
                        tabs = st.tabs.map { t ->
                            if (t.id == tabId) {
                                t.copy(
                                    url = url.ifBlank { t.url },
                                    title = title.ifBlank { t.title },
                                    isLoading = false,
                                    progress = 100,
                                    canGoBack = canGoBack || t.backStack.isNotEmpty(),
                                    canGoForward = canGoForward || t.forwardStack.isNotEmpty(),
                                    blockedCount = t.blockedCount + blocked
                                )
                            } else t
                        }
                    )
                }
                if (tab != null && !tab.isPrivate && url.startsWith("http") && !isShowcaseUrl(url)) {
                    addHistoryEntry(title.ifBlank { url }, url)
                }
                persist()
            }
            "url" -> {
                val url = payload.optString("url")
                val canGoBack = payload.optBoolean("canGoBack", false)
                val canGoForward = payload.optBoolean("canGoForward", false)
                _uiState.update { st ->
                    st.copy(tabs = st.tabs.map { t ->
                        if (t.id == tabId) {
                            t.copy(
                                url = url.ifBlank { t.url },
                                canGoBack = canGoBack || t.backStack.isNotEmpty(),
                                canGoForward = canGoForward || t.forwardStack.isNotEmpty()
                            )
                        } else t
                    })
                }
            }
            "title" -> {
                val title = payload.optString("title")
                if (title.isNotBlank()) {
                    _uiState.update { st ->
                        st.copy(tabs = st.tabs.map { if (it.id == tabId) it.copy(title = title) else it })
                    }
                }
            }
            "error" -> {
                val message = payload.optString("message").ifBlank { t("error_page_load_failed") }
                _uiState.update { st ->
                    st.copy(tabs = st.tabs.map {
                        if (it.id == tabId) it.copy(isLoading = false, progress = 100, errorText = message) else it
                    })
                }
                syncNativeBrowserToCurrentRoute()
            }
            "new-tab" -> {
                val url = payload.optString("url")
                val currentPrivate = _uiState.value.activeTab?.isPrivate == true
                if (url.startsWith("http")) {
                    createTab(url = url, isPrivate = currentPrivate, switchToBrowser = true)
                }
            }
            "media" -> {
                val pageUrl = payload.optString("pageUrl")
                val arr = payload.optJSONArray("items") ?: return
                val incoming = ArrayList<MediaCandidate>(arr.length())
                val tabTitle = _uiState.value.tabs.firstOrNull { it.id == tabId }?.title
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val mUrl = o.optString("url")
                    if (!mUrl.startsWith("http")) continue
                    if (MediaUrlRules.looksLikeNonMedia(mUrl) || MediaUrlRules.looksLikeSegment(mUrl)) continue
                    val mime = o.optString("type").takeIf { it.isNotBlank() }
                    val ext = MediaUrlRules.pathExtension(mUrl)
                        ?: MediaUrlRules.extensionForMime(mime)
                        ?: if (MediaUrlRules.isPlaylistUrl(mUrl, mime)) "m3u8" else "mp4"
                    incoming.add(
                        MediaCandidate(
                            id = "nat-${mUrl.hashCode()}",
                            pageUrl = pageUrl.ifBlank { _uiState.value.tabs.firstOrNull { it.id == tabId }?.url.orEmpty() },
                            mediaUrl = mUrl,
                            title = o.optString("label").takeIf { it.isNotBlank() } ?: tabTitle,
                            mimeType = mime,
                            extension = ext,
                            source = "NETWORK",
                            confidence = if (o.optBoolean("visible", false)) 0.92 else 0.80,
                            duration = o.optLong("durationMs", -1L).takeIf { it > 0L },
                            width = o.optInt("width", -1).takeIf { it > 0 },
                            height = o.optInt("height", -1).takeIf { it > 0 },
                            muted = if (o.has("muted")) o.optBoolean("muted") else null,
                            hasControls = if (o.has("controls")) o.optBoolean("controls") else null,
                            isLive = o.optBoolean("isLive", false),
                            isAd = o.optBoolean("isAd", false),
                            isSuggested = o.optBoolean("isSuggested", false),
                            visible = o.optBoolean("visible", false),
                            label = o.optString("label").takeIf { it.isNotBlank() },
                            kind = o.optString("kind").takeIf { it.isNotBlank() }
                        )
                    )
                }
                if (incoming.isNotEmpty()) {
                    _uiState.update { st ->
                        val existing = st.tabCandidates[tabId].orEmpty()
                        val merged = (existing + incoming).distinctBy { it.mediaUrl }
                        val firstVisible = incoming.firstOrNull { it.visible }?.mediaUrl
                        val nextVis = if (firstVisible != null) st.visibleMediaByTab + (tabId to firstVisible) else st.visibleMediaByTab
                        st.copy(
                            tabCandidates = st.tabCandidates + (tabId to merged),
                            visibleMediaByTab = nextVis
                        )
                    }
                    logDiag("media-detected", "${incoming.size} candidate(s) on ${AdBlockRules.hostOfUrl(pageUrl) ?: "page"}")
                }
            }
            "visible-media" -> {
                val mediaUrl = payload.optString("mediaUrl")
                if (mediaUrl.startsWith("http")) {
                    _uiState.update { st -> st.copy(visibleMediaByTab = st.visibleMediaByTab + (tabId to mediaUrl)) }
                }
            }
            "download-request" -> {
                val url = payload.optString("url")
                if (url.startsWith("http")) {
                    val mime = payload.optString("mimeType").ifBlank { "application/octet-stream" }
                    val filename = payload.optString("filename").ifBlank { URLUtil.guessFileName(url, null, mime) }
                    val pageUrl = _uiState.value.tabs.firstOrNull { it.id == tabId }?.url
                    enqueueDirectFileDownload(url, filename, mime, pageUrl)
                }
            }
            "find-result" -> {
                _uiState.update { st ->
                    st.copy(
                        findState = st.findState.copy(
                            matches = payload.optInt("matches", 0),
                            active = payload.optInt("active", 0)
                        )
                    )
                }
            }
            "cleartext-blocked" -> {
                val url = payload.optString("url")
                if (url.startsWith("http://")) {
                    _uiState.update { st ->
                        st.copy(tabs = st.tabs.map {
                            if (it.id == tabId) it.copy(cleartextPromptUrl = url, url = url, isLoading = false, errorText = null) else it
                        })
                    }
                    syncNativeBrowserToCurrentRoute()
                }
            }
            "context-menu" -> {
                val linkUrl = payload.optString("linkUrl").takeIf { it.startsWith("http") }
                val imageUrl = payload.optString("imageUrl").takeIf { it.startsWith("http") }
                val title = payload.optString("title").takeIf { it.isNotBlank() }
                if (linkUrl != null || imageUrl != null) {
                    _uiState.update {
                        it.copy(
                            contextMenu = BrowserContextMenu(
                                tabId = tabId,
                                linkUrl = linkUrl,
                                imageUrl = imageUrl,
                                title = title
                            )
                        )
                    }
                }
            }
            "download-folder-changed" -> {
                refreshDownloadFolderLabel()
            }
        }
    }

    fun dismissContextMenu() {
        _uiState.update { it.copy(contextMenu = null) }
    }

    fun downloadContextUrl(url: String, mimeHint: String? = null) {
        val candidate = PastedLinkClassifier.pastedMediaCandidate(url)
        if (candidate != null) {
            openQualitySheetForCandidate(candidate)
            return
        }
        val mime = mimeHint ?: if (url.contains(".png", ignoreCase = true)) "image/png"
        else if (url.contains(".webp", ignoreCase = true)) "image/webp"
        else if (url.contains(".gif", ignoreCase = true)) "image/gif"
        else if (url.contains(".jpg", ignoreCase = true) || url.contains(".jpeg", ignoreCase = true)) "image/jpeg"
        else "application/octet-stream"
        val filename = URLUtil.guessFileName(url, null, mime)
        val pageUrl = _uiState.value.activeTab?.url
        enqueueDirectFileDownload(url, filename, mime, pageUrl)
    }

    // ------------------------------------------------------------------ media tray & quality sheet

    fun openMediaTray() = _uiState.update { it.copy(isMediaTrayVisible = true) }
    fun closeMediaTray() = _uiState.update { it.copy(isMediaTrayVisible = false) }

    fun downloadMainMedia() {
        val st = _uiState.value
        val main = st.currentMainCandidate
        if (main != null) {
            openQualitySheetForCandidate(main)
            return
        }
        if (st.rankedTabCandidates.isNotEmpty()) {
            openMediaTray()
            return
        }
        showToast(t("no_media_detected"))
    }

    fun openQualitySheetForCandidate(candidate: MediaCandidate) {
        val reason = MediaOfferability.unsupportedReasonFor(
            url = candidate.mediaUrl,
            mimeType = candidate.mimeType,
            extension = candidate.extension,
            isLive = candidate.isLive,
            isMediaSource = candidate.isMediaSource,
            isBlobFile = candidate.isBlobFile,
            dashIsSupported = true
        )
        val initial = MediaProbeAndExtract.initialMediaInfoForCandidate(candidate)
        _uiState.update {
            it.copy(
                isMediaTrayVisible = false,
                qualitySheet = QualitySheetState(
                    candidate = candidate,
                    mediaInfo = initial,
                    isLoading = reason == null,
                    unsupported = reason
                )
            )
        }
        if (reason != null) return

        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val res = MediaProbeAndExtract.probeMediaCandidate(candidate)
            _uiState.update { st ->
                val cur = st.qualitySheet ?: return@update st
                if (cur.candidate.mediaUrl != candidate.mediaUrl) return@update st
                st.copy(
                    qualitySheet = cur.copy(
                        mediaInfo = res.mediaInfo,
                        isLoading = false,
                        unsupported = res.unsupported
                    )
                )
            }
        }
    }

    fun closeQualitySheet() {
        probeJob?.cancel()
        _uiState.update { it.copy(qualitySheet = null) }
    }

    fun startDownloadFromFormat(format: MediaFormat, customFilename: String?) {
        val sheet = _uiState.value.qualitySheet
        val title = sheet?.mediaInfo?.title ?: format.format
        val pageUrl = sheet?.candidate?.pageUrl ?: _uiState.value.activeTab?.url
        val base = MediaFormatters.sanitizeFilename(customFilename?.ifBlank { null } ?: title)
        val ext = format.extension.ifBlank { "mp4" }
        val fileName = if (base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
        closeQualitySheet()
        enqueueDownload(
            title = title,
            fileName = fileName,
            url = format.url,
            originalPageUrl = pageUrl,
            mimeType = format.mimeType,
            isHls = format.isHls,
            isDash = format.isDash,
            audioTrackUrl = format.audioTrackUrl,
            totalBytes = format.size
        )
    }

    fun startBatchDownloadFromCandidates(candidates: List<MediaCandidate>) {
        closeMediaTray()
        if (candidates.isEmpty()) return
        for (c in candidates) {
            val info = MediaProbeAndExtract.initialMediaInfoForCandidate(c)
            val f = info.formats.first()
            val fileName = if (info.title.endsWith(".${f.extension}", ignoreCase = true)) {
                info.title
            } else {
                "${MediaFormatters.sanitizeFilename(info.title)}.${f.extension}"
            }
            enqueueDownload(
                title = info.title,
                fileName = fileName,
                url = f.url,
                originalPageUrl = c.pageUrl,
                mimeType = f.mimeType,
                isHls = f.isHls,
                isDash = f.isDash,
                totalBytes = f.size
            )
        }
    }

    private fun enqueueDirectFileDownload(url: String, fileName: String, mimeType: String, pageUrl: String?) {
        enqueueDownload(
            title = fileName,
            fileName = MediaFormatters.sanitizeFilename(fileName),
            url = url,
            originalPageUrl = pageUrl,
            mimeType = mimeType,
            isHls = url.contains(".m3u8", ignoreCase = true),
            isDash = url.contains(".mpd", ignoreCase = true)
        )
    }

    private fun enqueueDownload(
        title: String,
        fileName: String,
        url: String,
        originalPageUrl: String?,
        mimeType: String,
        isHls: Boolean,
        isDash: Boolean = false,
        audioTrackUrl: String? = null,
        totalBytes: Long? = null
    ) {
        val dup = _uiState.value.downloads.firstOrNull {
            it.url == url && (it.state == DownloadState.QUEUED || it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING)
        }
        if (dup != null) {
            showToast(t("download_already_downloading", "name" to dup.fileName))
            return
        }
        val now = System.currentTimeMillis()
        val item = DownloadItemRecord(
            id = "dl-${UUID.randomUUID().toString().take(10)}",
            title = title,
            fileName = fileName,
            url = url,
            originalPageUrl = originalPageUrl,
            state = DownloadState.QUEUED,
            totalBytes = totalBytes,
            mimeType = mimeType,
            isHls = isHls,
            isDash = isDash,
            audioTrackUrl = audioTrackUrl,
            createdAt = now,
            updatedAt = now
        )
        _uiState.update { st -> st.copy(downloads = listOf(item) + st.downloads) }
        persist()
        showToast(t("download_enqueued"))
        logDiag("download-queued", fileName)
        pumpDownloadQueue()
    }

    private fun pumpDownloadQueue() {
        val st = _uiState.value
        val activeCount = st.downloads.count {
            it.state == DownloadState.CONNECTING || it.state == DownloadState.DOWNLOADING
        }
        val availableSlots = (st.settings.maxSimultaneousDownloads - activeCount).coerceAtLeast(0)
        if (availableSlots == 0) return

        val toStart = st.downloads.filter { it.state == DownloadState.QUEUED }.take(availableSlots)
        if (toStart.isEmpty()) return

        _uiState.update { cur ->
            val startIds = toStart.map { it.id }.toSet()
            cur.copy(
                downloads = cur.downloads.map { d ->
                    if (d.id in startIds) d.copy(state = DownloadState.CONNECTING, errorMessage = null) else d
                }
            )
        }
        persist()

        for (d in toStart) {
            if (!d.originalPageUrl.isNullOrBlank()) {
                NativeDownloadService.setReferer(d.id, d.originalPageUrl)
            }
            NativeDownloadService.start(
                context = appContext,
                id = d.id,
                url = d.url,
                filename = d.fileName,
                mimeType = d.mimeType,
                isHls = d.isHls,
                connections = st.settings.parallelConnections,
                autoSave = st.settings.isExportToGallery,
                audioTrackUrl = d.audioTrackUrl,
                wifiOnly = st.settings.isWifiOnly,
                referer = d.originalPageUrl
            )
        }
    }

    private fun handleNativeDownloadEvent(payload: JSONObject) {
        val type = payload.optString("type")
        val id = payload.optString("id")
        if (id.isBlank()) return

        when (type) {
            "restored" -> {
                _uiState.update { st ->
                    st.copy(downloads = st.downloads.map { d ->
                        if (d.id == id) d.copy(
                            state = DownloadState.CONNECTING,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        ) else d
                    })
                }
            }
            "progress" -> {
                val dl = payload.optLong("downloadedBytes", 0L)
                val total = payload.optLong("totalBytes", -1L).takeIf { it > 0L }
                val speed = payload.optLong("bytesPerSecond", 0L)
                val eta = payload.optLong("etaSeconds", -1L).takeIf { it >= 0L }
                _uiState.update { st ->
                    st.copy(downloads = st.downloads.map { d ->
                        if (d.id == id) d.copy(
                            state = DownloadState.DOWNLOADING,
                            downloadedBytes = dl,
                            totalBytes = total ?: d.totalBytes,
                            speedBytesPerSec = speed,
                            etaSeconds = eta,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        ) else d
                    })
                }
            }
            "complete" -> {
                val blobUrl = payload.optString("blobUrl").takeIf { it.isNotBlank() }
                val finalSize = payload.optLong("finalSize", 0L)
                val mime = payload.optString("mimeType").ifBlank { "video/mp4" }
                val nativeUri = payload.optString("nativeUri").takeIf { it.isNotBlank() }
                val resolvedPath = payload.optString("filePath").takeIf { it.isNotBlank() }
                    ?: DownloadFileResolver.findInternalDownloadFile(downloadsDir(), id)?.absolutePath
                var finishedName = ""
                _uiState.update { st ->
                    st.copy(downloads = st.downloads.map { d ->
                        if (d.id == id) {
                            finishedName = d.fileName
                            d.copy(
                                state = DownloadState.COMPLETED,
                                downloadedBytes = finalSize,
                                totalBytes = finalSize,
                                speedBytesPerSec = 0L,
                                etaSeconds = null,
                                filePath = resolvedPath ?: d.filePath,
                                exportedUri = nativeUri ?: blobUrl,
                                mimeType = mime,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        } else d
                    })
                }
                persist()
                if (finishedName.isNotEmpty()) {
                    showToast(t("download_finished_named", "name" to finishedName))
                    logDiag("download-complete", finishedName)
                }
                pumpDownloadQueue()
            }
            "paused" -> {
                val isWifiWait = payload.optString("reason") == "wifi"
                val pauseMsg = if (isWifiWait) t("download_waiting_wifi") else null
                _uiState.update { st ->
                    st.copy(downloads = st.downloads.map { d ->
                        if (d.id == id) d.copy(
                            state = DownloadState.PAUSED,
                            errorMessage = pauseMsg,
                            speedBytesPerSec = 0L,
                            updatedAt = System.currentTimeMillis()
                        ) else d
                    })
                }
                persist()
                pumpDownloadQueue()
            }
            "cancelled" -> {
                _uiState.update { st ->
                    st.copy(downloads = st.downloads.map { d ->
                        if (d.id == id) d.copy(state = DownloadState.CANCELLED, speedBytesPerSec = 0L, updatedAt = System.currentTimeMillis()) else d
                    })
                }
                persist()
                pumpDownloadQueue()
            }
            "error" -> {
                val rawMsg = payload.optString("message").ifBlank { "Download failed" }
                val code = payload.optString("code")
                val isWifiWait = rawMsg == "__SALVIA_WIFI_HOLD__" || code == "WIFI_HOLD"
                val isNoSpace = code == "INSUFFICIENT_SPACE" || rawMsg == "__SALVIA_NO_SPACE__"
                val localized = when {
                    isWifiWait -> t("download_waiting_wifi")
                    isNoSpace -> t("download_insufficient_space")
                    else -> rawMsg
                }
                _uiState.update { st ->
                    st.copy(downloads = st.downloads.map { d ->
                        if (d.id == id) d.copy(
                            state = if (isWifiWait) DownloadState.PAUSED else DownloadState.FAILED,
                            errorMessage = localized,
                            speedBytesPerSec = 0L,
                            updatedAt = System.currentTimeMillis()
                        ) else d
                    })
                }
                persist()
                pumpDownloadQueue()
            }
        }
    }

    fun pauseDownload(id: String) {
        NativeDownloadService.pause(appContext, id)
        _uiState.update { st ->
            st.copy(downloads = st.downloads.map {
                if (it.id == id) it.copy(state = DownloadState.PAUSED, speedBytesPerSec = 0L) else it
            })
        }
        persist()
        pumpDownloadQueue()
    }

    fun resumeDownload(id: String) {
        val item = _uiState.value.downloads.firstOrNull { it.id == id } ?: return
        if (DownloadQueueStore.getJob(appContext, id) != null) {
            _uiState.update { st ->
                st.copy(downloads = st.downloads.map {
                    if (it.id == id) it.copy(state = DownloadState.CONNECTING, errorMessage = null) else it
                })
            }
            NativeDownloadService.resume(appContext, id)
        } else {
            _uiState.update { st ->
                st.copy(downloads = st.downloads.map {
                    if (it.id == id) it.copy(state = DownloadState.QUEUED, errorMessage = null) else it
                })
            }
            pumpDownloadQueue()
        }
        persist()
    }

    fun cancelDownload(id: String) {
        NativeDownloadService.cancel(appContext, id)
        _uiState.update { st ->
            st.copy(downloads = st.downloads.map {
                if (it.id == id) it.copy(state = DownloadState.CANCELLED, speedBytesPerSec = 0L) else it
            })
        }
        persist()
        pumpDownloadQueue()
    }

    fun deleteDownload(id: String) {
        val existing = _uiState.value.downloads.firstOrNull { it.id == id }
        NativeDownloadService.cancel(appContext, id)
        DownloadQueueStore.removeJob(appContext, id)
        try {
            DownloadFileResolver.collectArtifactsForDeletion(downloadsDir(), id, existing?.filePath)
                .forEach { file ->
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e)
        }
        _uiState.update { st ->
            val nextPlayer = if (st.playerDownloadId == id) null else st.playerDownloadId
            val nextRoute = if (st.playerDownloadId == id && st.route == UiRoute.MediaPlayer) UiRoute.Downloads else st.route
            st.copy(
                downloads = st.downloads.filterNot { it.id == id },
                playerDownloadId = nextPlayer,
                route = nextRoute
            )
        }
        persist()
    }

    fun clearDownloadBucket(bucket: String) {
        val predicate: (DownloadItemRecord) -> Boolean = when (bucket) {
            "QUEUED" -> { d -> d.state == DownloadState.QUEUED }
            "COMPLETED" -> { d -> d.state == DownloadState.COMPLETED && (!d.isLocked || _uiState.value.isLockedFolderUnlocked) }
            "FAILED" -> { d -> d.state == DownloadState.FAILED || d.state == DownloadState.CANCELLED }
            else -> { _ -> false }
        }
        val toRemove = _uiState.value.downloads.filter(predicate)
        toRemove.forEach { d ->
            DownloadQueueStore.removeJob(appContext, d.id)
            try {
                DownloadFileResolver.collectArtifactsForDeletion(downloadsDir(), d.id, d.filePath)
                    .forEach { file ->
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }
            } catch (e: Exception) { com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e) }
        }
        _uiState.update { st -> st.copy(downloads = st.downloads.filterNot(predicate)) }
        persist()
    }

    fun exportDownloadToDevice(id: String) {
        val item = _uiState.value.downloads.firstOrNull { it.id == id } ?: return
        val file = resolvePlayableFile(item)
        if (file == null || !file.exists()) {
            showToast(t("player_file_missing"))
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val published = MediaPublisher.publishToDevice(appContext, file, item.fileName, item.mimeType)
            if (published != null) {
                _uiState.update { st ->
                    st.copy(downloads = st.downloads.map { if (it.id == id) it.copy(exportedUri = published) else it })
                }
                persist()
                showToast(t("download_exported"))
            }
        }
    }

    fun openDownloadInPlayer(id: String) {
        val item = _uiState.value.downloads.firstOrNull { it.id == id } ?: return
        if (item.fileName.endsWith(".apk", ignoreCase = true) ||
            item.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)
        ) {
            installApk(item)
            return
        }
        _uiState.update { it.copy(playerDownloadId = id) }
        navigate(UiRoute.MediaPlayer)
    }

    fun setPlayerDownloadId(id: String) {
        _uiState.update { it.copy(playerDownloadId = id) }
    }

    fun cyclePlaybackOrder() {
        val values = PlaybackOrder.entries
        val next = values[(_uiState.value.playbackOrder.ordinal + 1) % values.size]
        _uiState.update { it.copy(playbackOrder = next) }
    }

    fun shareDownloadedFile(item: DownloadItemRecord) {
        val uri = uriForDownloadItem(item) ?: return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.ifBlank { "video/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(Intent.createChooser(intent, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e)
        }
    }

    fun installApk(item: DownloadItemRecord) {
        val uri = uriForDownloadItem(item) ?: return
        try {
            if (!appContext.packageManager.canRequestPackageInstalls()) {
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                showToast(t("update_install_permission_hint"))
                return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e)
        }
    }

    fun resolvePlayableFile(item: DownloadItemRecord): File? {
        return DownloadFileResolver.findInternalDownloadFile(downloadsDir(), item.id, item.filePath)
    }

    fun resolvePlayableUri(item: DownloadItemRecord): Uri? {
        resolvePlayableFile(item)?.let { return Uri.fromFile(it) }
        item.exportedUri?.takeIf { it.startsWith("content://") }?.let { return Uri.parse(it) }
        return null
    }

    private fun uriForDownloadItem(item: DownloadItemRecord): Uri? {
        resolvePlayableFile(item)?.let {
            return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", it)
        }
        return item.exportedUri?.takeIf { it.startsWith("content://") }?.let { Uri.parse(it) }
    }

    private fun downloadsDir(): File = File(appContext.filesDir, "downloads")

    // ------------------------------------------------------------------ locked folder

    suspend fun setLockedFolderPassword(password: String): Boolean {
        val ok = withContext(Dispatchers.Default) {
            SecurityManager.setPassword(appContext, password)
        }
        if (ok) {
            lockoutPolicy.onSuccess()
            _uiState.update { it.copy(isLockedFolderConfigured = true, lockoutRemainingSeconds = 0) }
            showToast(t("locked_folder_set"))
        }
        return ok
    }

    suspend fun verifyCurrentLockedFolderPassword(password: String): Boolean {
        if (lockoutPolicy.isLockedOut(System.currentTimeMillis())) {
            startLockoutCountdown()
            return false
        }
        val ok = withContext(Dispatchers.Default) {
            SecurityManager.verifyPassword(appContext, password)
        }
        if (ok) {
            lockoutPolicy.onSuccess()
            _uiState.update { it.copy(lockoutRemainingSeconds = 0) }
        } else {
            lockoutPolicy.onFailure(System.currentTimeMillis())
            startLockoutCountdown()
        }
        return ok
    }

    suspend fun unlockLockedFolder(password: String): Boolean {
        if (lockoutPolicy.isLockedOut(System.currentTimeMillis())) {
            startLockoutCountdown()
            return false
        }
        val ok = withContext(Dispatchers.Default) {
            SecurityManager.verifyPassword(appContext, password)
        }
        if (ok) {
            lockoutPolicy.onSuccess()
            lockoutTimerJob?.cancel()
            _uiState.update { it.copy(isLockedFolderUnlocked = true, lockoutRemainingSeconds = 0) }
        } else {
            lockoutPolicy.onFailure(System.currentTimeMillis())
            startLockoutCountdown()
        }
        return ok
    }

    fun lockLockedFolderAgain() {
        _uiState.update { it.copy(isLockedFolderUnlocked = false) }
    }

    fun removeLockedFolderPassword() {
        SecurityManager.removePassword(appContext)
        lockoutPolicy.onSuccess()
        _uiState.update { st ->
            st.copy(
                isLockedFolderConfigured = false,
                isLockedFolderUnlocked = false,
                lockoutRemainingSeconds = 0,
                downloads = st.downloads.map { it.copy(isLocked = false) }
            )
        }
        persist()
        showToast(t("locked_folder_removed"))
    }

    fun setDownloadLocked(id: String, locked: Boolean) {
        if (locked && !_uiState.value.isLockedFolderConfigured) {
            showToast(t("locked_folder_not_configured"))
            return
        }
        _uiState.update { st ->
            st.copy(downloads = st.downloads.map { if (it.id == id) it.copy(isLocked = locked) else it })
        }
        persist()
        showToast(t(if (locked) "download_locked" else "download_unlocked"))
    }

    private fun startLockoutCountdown() {
        lockoutTimerJob?.cancel()
        lockoutTimerJob = viewModelScope.launch {
            while (true) {
                val remMs = lockoutPolicy.remainingMillis(System.currentTimeMillis())
                val sec = ((remMs + 999L) / 1000L).toInt()
                _uiState.update { it.copy(lockoutRemainingSeconds = sec) }
                if (sec <= 0) break
                delay(500)
            }
        }
    }

    // ------------------------------------------------------------------ bookmarks & history

    fun isUrlBookmarked(url: String?): Boolean =
        !url.isNullOrBlank() && _uiState.value.bookmarks.any { it.url == url }

    fun toggleBookmark(title: String, url: String) {
        if (url.isBlank()) return
        val exists = _uiState.value.bookmarks.any { it.url == url }
        _uiState.update { st ->
            val next = if (exists) {
                st.bookmarks.filterNot { it.url == url }
            } else {
                listOf(BookmarkItem(id = "bm-${UUID.randomUUID().toString().take(8)}", title = title.ifBlank { url }, url = url)) + st.bookmarks
            }
            st.copy(bookmarks = next)
        }
        persist()
        showToast(t(if (exists) "bookmark_removed" else "bookmark_added"))
    }

    fun removeBookmark(id: String) {
        _uiState.update { st -> st.copy(bookmarks = st.bookmarks.filterNot { it.id == id }) }
        persist()
    }

    private fun addHistoryEntry(title: String, url: String) {
        _uiState.update { st ->
            val dedup = st.history.filterNot { it.url == url }
            val entry = HistoryItem(
                id = "h-${UUID.randomUUID().toString().take(8)}",
                title = title.ifBlank { url },
                url = url,
                visitedAt = System.currentTimeMillis()
            )
            st.copy(history = (listOf(entry) + dedup).take(300))
        }
        persist()
    }

    fun removeHistoryItem(id: String) {
        _uiState.update { st -> st.copy(history = st.history.filterNot { it.id == id }) }
        persist()
    }

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
        persist()
        showToast(t("settings_history_cleared"))
    }

    fun clearSiteStorage() {
        browserHost?.clearBrowsingData()
        showToast(t("settings_cookies_cleared"))
    }

    fun clearAllBrowsingData() {
        browserHost?.clearBrowsingData()
        _uiState.update { it.copy(history = emptyList()) }
        persist()
        showToast(t("settings_browsing_data_cleared"))
    }

    // ------------------------------------------------------------------ settings & updates

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _uiState.update { st ->
            val next = transform(st.settings)
            val locale = AppStrings.resolveLocale(next.language)
            st.copy(
                settings = next,
                locale = locale,
                isRtl = locale == "fa"
            )
        }
        val st = _uiState.value
        val s = st.settings
        browserHost?.applySettings(
            s.isJavaScriptEnabled,
            s.areCookiesEnabled,
            s.textZoom,
            s.isDarkPages,
            s.isCleartextAllowed
        )
        for (tab in st.tabs) {
            browserHost?.setAdBlock(tab.id, effectiveAdBlockForUrl(tab.url, s))
        }
        persist()
    }

    fun refreshDownloadFolderLabel() {
        val label = repository.loadDownloadFolderName()
        updateSettings { it.copy(chosenFolderLabel = label) }
        showToast(t("settings_download_folder_saved"))
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (_uiState.value.updateCheckState is UpdateCheckState.Checking) return
        _uiState.update { it.copy(updateCheckState = UpdateCheckState.Checking) }
        viewModelScope.launch {
            try {
                lastUpdateCheckAt = System.currentTimeMillis()
                persist()
                val release = MediaProbeAndExtract.fetchLatestRelease(SalviaConstants.APP_VERSION)
                if (release != null) {
                    _uiState.update {
                        it.copy(
                            updateCheckState = UpdateCheckState.Available(release),
                            showUpdateDialog = true
                        )
                    }
                } else {
                    _uiState.update { it.copy(updateCheckState = UpdateCheckState.UpToDate) }
                    if (manual) showToast(t("update_up_to_date"))
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(updateCheckState = UpdateCheckState.Failed(e.message ?: "Error")) }
                if (manual) showToast(t("update_check_failed"))
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun startUpdateApkDownload(release: com.salvia.salviabrowxer.data.UpdateReleaseInfo) {
        val apkUrl = release.apkUrl ?: run {
            openInExternalBrowser(release.url)
            return
        }
        dismissUpdateDialog()
        enqueueDownload(
            title = "SalviaBrowxer v${release.version}.apk",
            fileName = "SalviaBrowxer-${release.version}.apk",
            url = apkUrl,
            originalPageUrl = release.url,
            mimeType = "application/vnd.android.package-archive",
            isHls = false,
            totalBytes = release.apkSize
        )
        showToast(t("update_downloading"))
    }

    private fun silentStartupUpdateCheckIfNeeded() {
        val dayMs = 24L * 60 * 60 * 1000
        if (System.currentTimeMillis() - lastUpdateCheckAt >= dayMs) {
            checkForUpdates(manual = false)
        }
    }

    fun exportDiagnostics() {
        val st = _uiState.value
        val obj = JSONObject()
            .put("version", SalviaConstants.APP_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("tabCount", st.tabs.size)
            .put("adBlockEnabled", st.settings.isAdBlockEnabled)
            .put("adBlockBlockedRequests", st.settings.adBlockBlockedRequests)
            .put("downloads", DownloadQueueStore.summarize(appContext))
            .put("events", JSONArray().apply {
                for (e in st.diagnostics) {
                    put(JSONObject().put("time", e.time).put("kind", e.kind).put("detail", e.detail))
                }
            })
        try {
            val file = File(appContext.cacheDir, "salvia-diagnostics.json")
            file.writeText(obj.toString(2))
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SalviaBrowxer Diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(Intent.createChooser(intent, t("settings_share_diagnostics")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e)
        }
    }

    fun shareCrashReports() {
        val zip = CrashReporter.packageReports(appContext)
        if (zip == null) {
            showToast(t("settings_crash_reports_none"))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SalviaBrowxer crash reports")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(Intent.createChooser(intent, t("settings_crash_reports")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e)
        }
    }

    private fun logDiag(kind: String, detail: String) {
        _uiState.update { st ->
            st.copy(diagnostics = (st.diagnostics + DiagnosticsEntry(System.currentTimeMillis(), kind, detail)).takeLast(120))
        }
    }

    // ------------------------------------------------------------------ connectivity & persistence

    private fun queryConnectionKind(): ConnectionKind {
        return try {
            val network = connectivityManager.activeNetwork ?: return ConnectionKind.OFFLINE
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return ConnectionKind.OFFLINE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return ConnectionKind.OFFLINE
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionKind.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionKind.MOBILE
                else -> ConnectionKind.OTHER
            }
        } catch (_: Exception) {
            ConnectionKind.OTHER
        }
    }

    private fun refreshConnectionKind() {
        _uiState.update { it.copy(connectionKind = queryConnectionKind()) }
    }

    private fun persist() {
        val st = _uiState.value
        repository.saveAsync(
            AppStateRepository.PersistedState(
                settings = st.settings,
                bookmarks = st.bookmarks,
                history = st.history,
                tabs = st.tabs,
                currentTabId = st.currentTabId,
                downloads = st.downloads,
                diagnostics = st.diagnostics,
                lastUpdateCheckAt = lastUpdateCheckAt
            )
        )
    }

    override fun onCleared() {
        NativeDownloadBus.listener = null
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("SalviaViewModel", e)
        }
        super.onCleared()
    }
}
