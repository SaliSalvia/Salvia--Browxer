package com.salvia.salviabrowxer.data

enum class UiRoute {
    Home,
    Browser,
    Downloads,
    Bookmarks,
    History,
    Settings,
    MediaPlayer
}

enum class DownloadState {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class CandidateRole {
    MAIN,
    SUGGESTED,
    AD
}

enum class UnsupportedMedia {
    DASH,
    LIVE,
    BLOB_STREAM,
    DRM
}

enum class SearchEngineKey(val label: String, val template: String) {
    Google("Google", "https://www.google.com/search?q=%s"),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    Bing("Bing", "https://www.bing.com/search?q=%s"),
    Yahoo("Yahoo", "https://search.yahoo.com/search?p=%s"),
    Ecosia("Ecosia", "https://www.ecosia.org/search?q=%s")
}

enum class LanguageMode(val code: String) {
    SYSTEM("system"),
    EN("en"),
    FA("fa");

    companion object {
        fun fromCode(code: String?): LanguageMode =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
    }
}

enum class PlaybackOrder {
    SEQUENTIAL,
    LOOP_ALL,
    REVERSE,
    SHUFFLE
}

enum class ConnectionKind {
    WIFI,
    MOBILE,
    OTHER,
    OFFLINE
}

enum class ClipboardLinkKind {
    MEDIA_FILE,
    WEB_PAGE
}

data class ClipboardPrompt(
    val url: String,
    val kind: ClipboardLinkKind
)

data class MediaCandidate(
    val id: String,
    val pageUrl: String,
    val mediaUrl: String,
    val title: String? = null,
    val mimeType: String? = null,
    val extension: String? = null,
    val source: String = "DOM", // NETWORK | DOM | JS | FETCH
    val confidence: Double = 0.75,
    val duration: Long? = null,
    val estimatedSize: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val muted: Boolean? = null,
    val hasControls: Boolean? = null,
    val isLive: Boolean = false,
    val isAd: Boolean = false,
    val isSuggested: Boolean = false,
    val isMediaSource: Boolean = false,
    val isBlobFile: Boolean = false,
    val thumbnail: String? = null,
    val visible: Boolean = false,
    val label: String? = null,
    val kind: String? = null
)

data class ClassifiedCandidate(
    val candidate: MediaCandidate,
    val role: CandidateRole,
    val score: Int
)

data class MediaFormat(
    val id: String,
    val format: String,
    val url: String,
    val mimeType: String,
    val extension: String,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val bitrate: Int? = null,
    val isVideo: Boolean = true,
    val isAudio: Boolean = true,
    val isHls: Boolean = false,
    val isDash: Boolean = false,
    val audioTrackUrl: String? = null
)

data class MediaInfo(
    val title: String,
    val thumbnail: String? = null,
    val duration: Long? = null,
    val formats: List<MediaFormat> = emptyList(),
    val audioFormats: List<MediaFormat> = emptyList(),
    val videoFormats: List<MediaFormat> = emptyList(),
    val combinedFormats: List<MediaFormat> = emptyList(),
    val source: String? = null,
    val webpageUrl: String? = null
)

data class QualitySheetState(
    val candidate: MediaCandidate,
    val mediaInfo: MediaInfo,
    val isLoading: Boolean,
    val unsupported: UnsupportedMedia? = null
)

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String,
    val favicon: String? = null,
    val isPrivate: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val desktopMode: Boolean = false,
    val blockedCount: Int = 0,
    val errorText: String? = null,
    val cleartextPromptUrl: String? = null,
    val isSleeping: Boolean = false,
    val backStack: List<String> = emptyList(),
    val forwardStack: List<String> = emptyList(),
    val lastAccessedAt: Long = System.currentTimeMillis()
)

data class BookmarkItem(
    val id: String,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class HistoryItem(
    val id: String,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

data class AppSettings(
    val searchEngine: SearchEngineKey = SearchEngineKey.Google,
    val homepage: String = "https://www.google.com",
    val isJavaScriptEnabled: Boolean = true,
    val areCookiesEnabled: Boolean = true,
    val isDarkPages: Boolean = false,
    val isDesktopSite: Boolean = false,
    val isCleartextAllowed: Boolean = false,
    val isAdBlockEnabled: Boolean = false,
    val adBlockDisabledSites: Set<String> = emptySet(),
    val adBlockBlockedRequests: Int = 0,
    val maxSimultaneousDownloads: Int = 3,
    val parallelConnections: Int = 4,
    val isWifiOnly: Boolean = false,
    val isExportToGallery: Boolean = true,
    val isFloatingButtonAlwaysVisible: Boolean = true,
    val floatingButtonSize: Int = 54,
    val floatingButtonX: Float = -1f, // -1 = unset (snap right edge)
    val floatingButtonY: Float = -1f,
    val chosenFolderLabel: String? = null,
    val language: LanguageMode = LanguageMode.SYSTEM,
    val textZoom: Int = 100
)

data class DownloadItemRecord(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val originalPageUrl: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val errorMessage: String? = null,
    val filePath: String? = null,
    val exportedUri: String? = null,
    val mimeType: String = "video/mp4",
    val isHls: Boolean = false,
    val isDash: Boolean = false,
    val audioTrackUrl: String? = null,
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class DiagnosticsEntry(
    val time: Long,
    val kind: String,
    val detail: String
)

data class FindState(
    val isOpen: Boolean = false,
    val query: String = "",
    val matches: Int = 0,
    val active: Int = 0
)

data class BrowserContextMenu(
    val tabId: String,
    val linkUrl: String? = null,
    val imageUrl: String? = null,
    val title: String? = null
)

data class UpdateReleaseInfo(
    val version: String,
    val url: String,
    val apkUrl: String? = null,
    val apkSize: Long? = null,
    val notes: String? = null
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Available(val release: UpdateReleaseInfo) : UpdateCheckState
    data class Failed(val reason: String) : UpdateCheckState
}

data class ShowcaseItem(
    val mediaUrl: String,
    val title: String,
    val mimeType: String,
    val extension: String,
    val durationMs: Long? = null,
    val estimatedSize: Long? = null
)

data class ShowcasePage(
    val url: String,
    val title: String,
    val description: String,
    val media: List<ShowcaseItem>
)
