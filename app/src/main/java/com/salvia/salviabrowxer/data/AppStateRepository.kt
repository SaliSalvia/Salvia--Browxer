package com.salvia.salviabrowxer.data

import android.content.Context
import com.salvia.salviabrowxer.core.SalviaConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class AppStateRepository(private val context: Context) {

    private val lock = Any()
    private val saveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "salvia-state-io").apply { isDaemon = true }
    }

    private fun stateFile() = File(context.filesDir, "salvia_ui_state.json")

    data class PersistedState(
        val settings: AppSettings,
        val bookmarks: List<BookmarkItem>,
        val history: List<HistoryItem>,
        val tabs: List<BrowserTab>,
        val currentTabId: String,
        val downloads: List<DownloadItemRecord>,
        val diagnostics: List<DiagnosticsEntry>,
        val lastUpdateCheckAt: Long
    )

    fun load(): PersistedState = synchronized(lock) {
        val raw = try {
            stateFile().takeIf { it.exists() }?.readText()
        } catch (_: Exception) {
            null
        }
        if (raw.isNullOrBlank()) {
            val defaultTab = BrowserTab(
                id = "tab-1",
                url = SalviaConstants.DEFAULT_HOMEPAGE,
                title = "Google"
            )
            return@synchronized PersistedState(
                settings = AppSettings(chosenFolderLabel = loadDownloadFolderName()),
                bookmarks = emptyList(),
                history = emptyList(),
                tabs = listOf(defaultTab),
                currentTabId = defaultTab.id,
                downloads = emptyList(),
                diagnostics = emptyList(),
                lastUpdateCheckAt = 0L
            )
        }
        return@synchronized try {
            val root = JSONObject(raw)
            val settings = parseSettings(root.optJSONObject("settings"))
            val bookmarks = parseBookmarks(root.optJSONArray("bookmarks"))
            val history = parseHistory(root.optJSONArray("history"))
            val parsedTabs = parseTabs(root.optJSONArray("tabs")).filter { !it.isPrivate }
            val tabs = if (parsedTabs.isEmpty()) {
                listOf(BrowserTab(id = "tab-1", url = settings.homepage, title = "Google"))
            } else {
                parsedTabs
            }
            val savedCurrent = root.optString("currentTabId")
            val currentTabId = if (tabs.any { it.id == savedCurrent }) savedCurrent else tabs.first().id
            val downloads = parseDownloads(root.optJSONArray("downloads"))
            val diagnostics = parseDiagnostics(root.optJSONArray("diagnostics"))
            val lastUpdate = root.optLong("lastUpdateCheckAt", 0L)
            PersistedState(settings, bookmarks, history, tabs, currentTabId, downloads, diagnostics, lastUpdate)
        } catch (_: Exception) {
            val defaultTab = BrowserTab(
                id = "tab-1",
                url = SalviaConstants.DEFAULT_HOMEPAGE,
                title = "Google"
            )
            PersistedState(
                settings = AppSettings(chosenFolderLabel = loadDownloadFolderName()),
                bookmarks = emptyList(),
                history = emptyList(),
                tabs = listOf(defaultTab),
                currentTabId = defaultTab.id,
                downloads = emptyList(),
                diagnostics = emptyList(),
                lastUpdateCheckAt = 0L
            )
        }
    }

    fun save(state: PersistedState) = synchronized(lock) {
        try {
            val root = JSONObject()
            root.put("settings", serializeSettings(state.settings))
            root.put("bookmarks", serializeBookmarks(state.bookmarks))
            root.put("history", serializeHistory(state.history.take(300)))
            // Never persist private tabs
            val nonPrivateTabs = state.tabs.filter { !it.isPrivate }
            root.put("tabs", serializeTabs(nonPrivateTabs))
            val activeNonPrivate = if (nonPrivateTabs.any { it.id == state.currentTabId }) {
                state.currentTabId
            } else {
                nonPrivateTabs.firstOrNull()?.id ?: ""
            }
            root.put("currentTabId", activeNonPrivate)
            root.put("downloads", serializeDownloads(state.downloads))
            root.put("diagnostics", serializeDiagnostics(state.diagnostics.takeLast(120)))
            root.put("lastUpdateCheckAt", state.lastUpdateCheckAt)

            val tmp = File(context.filesDir, "salvia_ui_state.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(stateFile())) {
                stateFile().writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            com.salvia.salviabrowxer.core.SalviaLog.w("AppStateRepository", "state save failed")
        }
    }

    fun saveAsync(state: PersistedState) {
        try {
            saveExecutor.execute { save(state) }
        } catch (_: Exception) {
            save(state)
        }
    }

    fun loadDownloadFolderName(): String? {
        val uriString = context.getSharedPreferences("salvia_prefs", Context.MODE_PRIVATE)
            .getString("download_tree_uri", null) ?: return null
        return try {
            val docId = android.net.Uri.parse(uriString).lastPathSegment ?: return null
            docId.substringAfter(':').takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSettings(obj: JSONObject?): AppSettings {
        val folder = loadDownloadFolderName()
        if (obj == null) return AppSettings(chosenFolderLabel = folder)
        val disabledSites = HashSet<String>()
        val arr = obj.optJSONArray("adBlockDisabledSites")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val h = arr.optString(i)
                if (h.isNotBlank()) disabledSites.add(h)
            }
        }
        val engine = try {
            SearchEngineKey.valueOf(obj.optString("searchEngine", SearchEngineKey.Google.name))
        } catch (_: Exception) {
            SearchEngineKey.Google
        }
        return AppSettings(
            searchEngine = engine,
            homepage = obj.optString("homepage", SalviaConstants.DEFAULT_HOMEPAGE).ifBlank { SalviaConstants.DEFAULT_HOMEPAGE },
            isJavaScriptEnabled = obj.optBoolean("isJavaScriptEnabled", true),
            areCookiesEnabled = obj.optBoolean("areCookiesEnabled", true),
            isDarkPages = obj.optBoolean("isDarkPages", false),
            isDesktopSite = obj.optBoolean("isDesktopSite", false),
            isCleartextAllowed = obj.optBoolean("isCleartextAllowed", false),
            isAdBlockEnabled = obj.optBoolean("isAdBlockEnabled", false),
            adBlockDisabledSites = disabledSites,
            adBlockBlockedRequests = obj.optInt("adBlockBlockedRequests", 0),
            maxSimultaneousDownloads = obj.optInt("maxSimultaneousDownloads", 3).coerceIn(1, 6),
            parallelConnections = obj.optInt("parallelConnections", 4).coerceIn(1, 8),
            isWifiOnly = obj.optBoolean("isWifiOnly", false),
            isExportToGallery = obj.optBoolean("isExportToGallery", true),
            isFloatingButtonAlwaysVisible = obj.optBoolean("isFloatingButtonAlwaysVisible", true),
            floatingButtonSize = obj.optInt("floatingButtonSize", 54).coerceIn(44, 76),
            floatingButtonX = obj.optDouble("floatingButtonX", -1.0).toFloat(),
            floatingButtonY = obj.optDouble("floatingButtonY", -1.0).toFloat(),
            chosenFolderLabel = folder,
            language = LanguageMode.fromCode(obj.optString("language", "system")),
            textZoom = obj.optInt("textZoom", 100).coerceIn(50, 200)
        )
    }

    private fun serializeSettings(s: AppSettings): JSONObject = JSONObject().apply {
        put("searchEngine", s.searchEngine.name)
        put("homepage", s.homepage)
        put("isJavaScriptEnabled", s.isJavaScriptEnabled)
        put("areCookiesEnabled", s.areCookiesEnabled)
        put("isDarkPages", s.isDarkPages)
        put("isDesktopSite", s.isDesktopSite)
        put("isCleartextAllowed", s.isCleartextAllowed)
        put("isAdBlockEnabled", s.isAdBlockEnabled)
        put("adBlockDisabledSites", JSONArray(s.adBlockDisabledSites.toList()))
        put("adBlockBlockedRequests", s.adBlockBlockedRequests)
        put("maxSimultaneousDownloads", s.maxSimultaneousDownloads)
        put("parallelConnections", s.parallelConnections)
        put("isWifiOnly", s.isWifiOnly)
        put("isExportToGallery", s.isExportToGallery)
        put("isFloatingButtonAlwaysVisible", s.isFloatingButtonAlwaysVisible)
        put("floatingButtonSize", s.floatingButtonSize)
        put("floatingButtonX", s.floatingButtonX.toDouble())
        put("floatingButtonY", s.floatingButtonY.toDouble())
        put("language", s.language.code)
        put("textZoom", s.textZoom)
    }

    private fun parseBookmarks(arr: JSONArray?): List<BookmarkItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<BookmarkItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            list.add(
                BookmarkItem(
                    id = o.optString("id").ifBlank { "bm-$i" },
                    title = o.optString("title").ifBlank { url },
                    url = url,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    private fun serializeBookmarks(list: List<BookmarkItem>): JSONArray = JSONArray().apply {
        for (b in list) {
            put(JSONObject().put("id", b.id).put("title", b.title).put("url", b.url).put("createdAt", b.createdAt))
        }
    }

    private fun parseHistory(arr: JSONArray?): List<HistoryItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<HistoryItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            list.add(
                HistoryItem(
                    id = o.optString("id").ifBlank { "hist-$i" },
                    title = o.optString("title").ifBlank { url },
                    url = url,
                    visitedAt = o.optLong("visitedAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    private fun serializeHistory(list: List<HistoryItem>): JSONArray = JSONArray().apply {
        for (h in list) {
            put(JSONObject().put("id", h.id).put("title", h.title).put("url", h.url).put("visitedAt", h.visitedAt))
        }
    }

    private fun parseTabs(arr: JSONArray?): List<BrowserTab> {
        if (arr == null) return emptyList()
        val list = ArrayList<BrowserTab>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val url = o.optString("url")
            if (id.isBlank() || url.isBlank()) continue
            list.add(
                BrowserTab(
                    id = id,
                    url = url,
                    title = o.optString("title").ifBlank { url },
                    isPrivate = o.optBoolean("isPrivate", false),
                    desktopMode = o.optBoolean("desktopMode", false),
                    lastAccessedAt = o.optLong("lastAccessedAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    private fun serializeTabs(list: List<BrowserTab>): JSONArray = JSONArray().apply {
        for (t in list) {
            put(
                JSONObject()
                    .put("id", t.id)
                    .put("url", t.url)
                    .put("title", t.title)
                    .put("isPrivate", t.isPrivate)
                    .put("desktopMode", t.desktopMode)
                    .put("lastAccessedAt", t.lastAccessedAt)
            )
        }
    }

    private fun parseDownloads(arr: JSONArray?): List<DownloadItemRecord> {
        if (arr == null) return emptyList()
        val list = ArrayList<DownloadItemRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val url = o.optString("url")
            if (id.isBlank() || url.isBlank()) continue
            val rawState = try {
                DownloadState.valueOf(o.optString("state", DownloadState.PAUSED.name))
            } catch (_: Exception) {
                DownloadState.PAUSED
            }
            val safeState = if (rawState == DownloadState.DOWNLOADING || rawState == DownloadState.CONNECTING) {
                DownloadState.PAUSED
            } else {
                rawState
            }
            list.add(
                DownloadItemRecord(
                    id = id,
                    title = o.optString("title").ifBlank { o.optString("fileName") },
                    fileName = o.optString("fileName").ifBlank { "media.mp4" },
                    url = url,
                    originalPageUrl = o.optString("originalPageUrl").takeIf { it.isNotBlank() },
                    state = safeState,
                    downloadedBytes = o.optLong("downloadedBytes", 0L),
                    totalBytes = o.optLong("totalBytes", -1L).takeIf { it >= 0L },
                    errorMessage = o.optString("errorMessage").takeIf { it.isNotBlank() },
                    filePath = o.optString("filePath").takeIf { it.isNotBlank() },
                    exportedUri = o.optString("exportedUri").takeIf { it.isNotBlank() },
                    mimeType = o.optString("mimeType", "video/mp4"),
                    isHls = o.optBoolean("isHls", false),
                    isDash = o.optBoolean("isDash", false),
                    audioTrackUrl = o.optString("audioTrackUrl").takeIf { it.isNotBlank() },
                    isLocked = o.optBoolean("isLocked", false),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
        }
        return list
    }

    private fun serializeDownloads(list: List<DownloadItemRecord>): JSONArray = JSONArray().apply {
        for (d in list) {
            put(
                JSONObject()
                    .put("id", d.id)
                    .put("title", d.title)
                    .put("fileName", d.fileName)
                    .put("url", d.url)
                    .put("originalPageUrl", d.originalPageUrl ?: JSONObject.NULL)
                    .put("state", d.state.name)
                    .put("downloadedBytes", d.downloadedBytes)
                    .put("totalBytes", d.totalBytes ?: JSONObject.NULL)
                    .put("errorMessage", d.errorMessage ?: JSONObject.NULL)
                    .put("filePath", d.filePath ?: JSONObject.NULL)
                    .put("exportedUri", d.exportedUri ?: JSONObject.NULL)
                    .put("mimeType", d.mimeType)
                    .put("isHls", d.isHls)
                    .put("isDash", d.isDash)
                    .put("audioTrackUrl", d.audioTrackUrl ?: JSONObject.NULL)
                    .put("isLocked", d.isLocked)
                    .put("createdAt", d.createdAt)
                    .put("updatedAt", d.updatedAt)
            )
        }
    }

    private fun parseDiagnostics(arr: JSONArray?): List<DiagnosticsEntry> {
        if (arr == null) return emptyList()
        val list = ArrayList<DiagnosticsEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                DiagnosticsEntry(
                    time = o.optLong("time", 0L),
                    kind = o.optString("kind"),
                    detail = o.optString("detail")
                )
            )
        }
        return list
    }

    private fun serializeDiagnostics(list: List<DiagnosticsEntry>): JSONArray = JSONArray().apply {
        for (d in list) {
            put(JSONObject().put("time", d.time).put("kind", d.kind).put("detail", d.detail))
        }
    }
}
