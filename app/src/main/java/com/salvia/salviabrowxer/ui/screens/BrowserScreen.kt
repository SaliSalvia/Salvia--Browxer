package com.salvia.salviabrowxer.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.salvia.salviabrowxer.core.AdBlockRules
import com.salvia.salviabrowxer.core.MediaFormatters
import com.salvia.salviabrowxer.core.SalviaConstants
import com.salvia.salviabrowxer.data.BrowserContextMenu
import com.salvia.salviabrowxer.data.MediaCandidate
import com.salvia.salviabrowxer.data.UiRoute
import com.salvia.salviabrowxer.ui.SalviaUiState
import com.salvia.salviabrowxer.ui.components.FloatingDownloadButton
import com.salvia.salviabrowxer.ui.theme.SalviaBlack
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
import com.salvia.salviabrowxer.ui.theme.SalviaPink
import com.salvia.salviabrowxer.ui.theme.SalviaSilverDeep
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSuccess
import com.salvia.salviabrowxer.ui.theme.SalviaSurface
import com.salvia.salviabrowxer.ui.theme.SalviaWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    state: SalviaUiState,
    browserContainer: FrameLayout,
    isWideLayout: Boolean,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onNavigate: (UiRoute) -> Unit,
    onNavigateToUrl: (String, String) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onDuplicateTab: (String) -> Unit,
    onCloseOtherTabs: (String) -> Unit,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onToggleBookmark: (String, String) -> Unit,
    onToggleDesktop: () -> Unit,
    onToggleSiteAdBlock: (String) -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onOpenMediaTray: () -> Unit,
    onDownloadMainMedia: () -> Unit,
    onOpenQualityForCandidate: (MediaCandidate) -> Unit,
    onSaveFloatingPosition: (Float, Float) -> Unit,
    onOpenFindInPage: () -> Unit,
    onCloseFindInPage: () -> Unit,
    onFindQuery: (String, Boolean) -> Unit,
    onPrintPage: () -> Unit,
    onSharePage: (String, String) -> Unit,
    onCopyLink: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onAllowCleartext: (String) -> Unit,
    onDismissCleartext: (String) -> Unit,
    onChangeTextZoom: (Int) -> Unit,
    onClearSiteData: () -> Unit,
    onDismissContextMenu: () -> Unit,
    onOpenLinkInTab: (String, Boolean) -> Unit,
    onDownloadContextUrl: (String, String?) -> Unit
) {
    val tab = state.activeTab ?: return
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var addressText by remember(tab.id) { mutableStateOf(tab.url) }
    var isAddressFocused by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSiteInfoSheet by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(tab.url, isAddressFocused) {
        if (!isAddressFocused) {
            addressText = tab.url
        }
    }

    val isBookmarked = remember(tab.url, state.bookmarks) {
        state.bookmarks.any { it.url == tab.url }
    }
    val currentHost = remember(tab.url) { AdBlockRules.hostOfUrl(tab.url) }
    val isSiteAdBlockExempt = currentHost != null && currentHost in state.settings.adBlockDisabledSites
    val showcase = remember(tab.url) { SalviaConstants.BUILTIN_SHOWCASE_PAGES.firstOrNull { it.url == tab.url } }
    val rankedCount = state.rankedTabCandidates.size
    val hasMain = state.currentMainCandidate != null

    val suggestions = remember(addressText, isAddressFocused, state.bookmarks, state.history) {
        if (!isAddressFocused) emptyList()
        else {
            val q = addressText.trim().lowercase()
            if (q.length < 2) emptyList()
            else {
                val bm = state.bookmarks.filter {
                    it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
                }.map { Triple(it.title, it.url, true) }
                val hist = state.history.filter { h ->
                    (h.title.lowercase().contains(q) || h.url.lowercase().contains(q)) &&
                        bm.none { it.second == h.url }
                }.map { Triple(it.title, it.url, false) }
                (bm + hist).take(5)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SalviaBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Wide screen tab strip
        if (isWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SalviaDeepCosmic)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.tabs.forEach { itemTab ->
                    val active = itemTab.id == tab.id
                    Surface(
                        onClick = { onSelectTab(itemTab.id) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) SalviaIrisContainer else SalviaElevated,
                        border = BorderStroke(
                            1.dp,
                            when {
                                active -> SalviaIris
                                itemTab.isPrivate -> SalviaPink.copy(alpha = 0.5f)
                                else -> SalviaBorder
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (itemTab.isPrivate) Icons.Filled.Security else Icons.Filled.Language,
                                contentDescription = null,
                                tint = if (itemTab.isPrivate) SalviaPink else if (active) SalviaCyan else SalviaSilverMid,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = itemTab.title.ifBlank { itemTab.url },
                                fontSize = 12.sp,
                                color = SalviaPearl,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                            IconButton(
                                onClick = { onCloseTab(itemTab.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = t("close_tab", emptyArray()), tint = SalviaSilverMid, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                IconButton(onClick = { onNewTab(false) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = t("new_tab", emptyArray()), tint = SalviaCyan)
                }
            }
        }

        // Top Omnibox Toolbar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SalviaDeepCosmic)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isWideLayout) {
                    IconButton(
                        onClick = onGoBack,
                        enabled = tab.canGoBack,
                        modifier = Modifier.size(44.dp).testTag("browser_wide_back")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = t("go_back", emptyArray()),
                            tint = if (tab.canGoBack) SalviaPearl else SalviaSilverDeep
                        )
                    }
                    IconButton(
                        onClick = onGoForward,
                        enabled = tab.canGoForward,
                        modifier = Modifier.size(44.dp).testTag("browser_wide_forward")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = t("go_forward", emptyArray()),
                            tint = if (tab.canGoForward) SalviaPearl else SalviaSilverDeep
                        )
                    }
                }

                // Address bar pill
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = SalviaElevated,
                    border = BorderStroke(
                        1.dp,
                        when {
                            isAddressFocused -> SalviaIris
                            tab.isPrivate -> SalviaPink.copy(alpha = 0.55f)
                            else -> SalviaBorder
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isHttps = tab.url.startsWith("https://", ignoreCase = true)
                        IconButton(
                            onClick = { showSiteInfoSheet = true },
                            modifier = Modifier.size(34.dp).testTag("browser_site_info_button")
                        ) {
                            Icon(
                                imageVector = when {
                                    tab.isPrivate -> Icons.Filled.Security
                                    isHttps -> Icons.Filled.Lock
                                    else -> Icons.Filled.WarningAmber
                                },
                                contentDescription = t("site_info_title", emptyArray()),
                                tint = when {
                                    tab.isPrivate -> SalviaPink
                                    isHttps -> SalviaSuccess
                                    else -> SalviaWarning
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        BasicTextField(
                            value = addressText,
                            onValueChange = { addressText = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = SalviaPearl,
                                fontSize = 13.sp,
                                fontFamily = MaterialTheme.typography.bodyMedium.fontFamily
                            ),
                            cursorBrush = SolidColor(SalviaCyan),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    if (addressText.isNotBlank()) {
                                        onNavigateToUrl(tab.id, addressText)
                                    }
                                }
                            ),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (addressText.isEmpty()) {
                                        Text(
                                            text = t("search_or_type_url", emptyArray()),
                                            color = SalviaSilverDeep,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { isAddressFocused = it.isFocused }
                                .testTag("browser_address_input")
                        )

                        if (tab.desktopMode && !isAddressFocused) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SalviaCyanContainer,
                                modifier = Modifier.clickable { onToggleDesktop() }
                            ) {
                                Text(
                                    text = t("desktop_badge", emptyArray()),
                                    color = SalviaCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (isAddressFocused && addressText.isNotEmpty()) {
                            IconButton(
                                onClick = { addressText = "" },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = t("address_clear", emptyArray()), tint = SalviaSilverMid, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            IconButton(
                                onClick = { onToggleBookmark(tab.title, tab.url) },
                                modifier = Modifier.size(32.dp).testTag("browser_bookmark_button")
                            ) {
                                Icon(
                                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    contentDescription = t(if (isBookmarked) "bookmark_remove" else "bookmark_add", emptyArray()),
                                    tint = if (isBookmarked) SalviaGold else SalviaSilverMid,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Detected Media Pill Badge
                if (rankedCount > 0) {
                    Surface(
                        onClick = onOpenMediaTray,
                        shape = RoundedCornerShape(12.dp),
                        color = SalviaCyanContainer,
                        border = BorderStroke(1.dp, SalviaCyan.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("browser_media_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Movie, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(16.dp))
                            Text(
                                text = rankedCount.toString(),
                                color = SalviaCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = SalviaMonoFontFamily
                            )
                        }
                    }
                }

                // Reload / Stop button
                IconButton(
                    onClick = { if (tab.isLoading) onStop() else onReload() },
                    modifier = Modifier.size(44.dp).testTag("browser_reload_stop")
                ) {
                    Icon(
                        imageVector = if (tab.isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = t(if (tab.isLoading) "stop" else "reload", emptyArray()),
                        tint = SalviaPearl
                    )
                }

                if (isWideLayout) {
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.size(44.dp).testTag("browser_wide_menu")
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = t("menu", emptyArray()), tint = SalviaPearl)
                        }
                        BrowserOverflowMenu(
                            expanded = showOverflowMenu,
                            onDismiss = { showOverflowMenu = false },
                            state = state,
                            currentHost = currentHost,
                            isSiteAdBlockExempt = isSiteAdBlockExempt,
                            t = t,
                            onNewTab = onNewTab,
                            onDuplicateTab = { onDuplicateTab(tab.id) },
                            onCloseOtherTabs = { onCloseOtherTabs(tab.id) },
                            onOpenMediaTray = onOpenMediaTray,
                            onToggleDesktop = onToggleDesktop,
                            onToggleSiteAdBlock = { currentHost?.let(onToggleSiteAdBlock) },
                            onOpenFindInPage = onOpenFindInPage,
                            onPrintPage = onPrintPage,
                            onOpenExternal = { onOpenExternal(tab.url) },
                            onSharePage = { onSharePage(tab.title, tab.url) },
                            onCopyLink = { onCopyLink(tab.url) },
                            onNavigate = onNavigate,
                            onChangeTextZoom = onChangeTextZoom
                        )
                    }
                }
            }

            // Linear Gradient Loading Bar
            if (tab.isLoading && tab.progress in 1..99) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(SalviaSurface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (tab.progress / 100f).coerceIn(0.05f, 1f))
                            .height(3.dp)
                            .background(Brush.horizontalGradient(listOf(SalviaIris, SalviaCyan, SalviaGold)))
                    )
                }
            } else {
                HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f), thickness = 1.dp)
            }
        }

        // Autocomplete suggestions dropdown when typing
        if (suggestions.isNotEmpty()) {
            Surface(
                color = SalviaSurface,
                border = BorderStroke(1.dp, SalviaBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestions.forEach { (title, url, isBm) ->
                        Surface(
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                addressText = url
                                onNavigateToUrl(tab.id, url)
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = SalviaElevated
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isBm) Icons.Filled.Bookmark else Icons.Filled.History,
                                    contentDescription = null,
                                    tint = if (isBm) SalviaGold else SalviaCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(title, color = SalviaPearl, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(url, color = SalviaSilverDeep, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Find-in-page bar
        if (state.findState.isOpen) {
            Surface(
                color = SalviaSurface,
                border = BorderStroke(1.dp, SalviaBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.FindInPage, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(18.dp))
                    BasicTextField(
                        value = state.findState.query,
                        onValueChange = { onFindQuery(it, false) },
                        singleLine = true,
                        textStyle = TextStyle(color = SalviaPearl, fontSize = 14.sp),
                        cursorBrush = SolidColor(SalviaCyan),
                        decorationBox = { inner ->
                            Box {
                                if (state.findState.query.isEmpty()) {
                                    Text(t("find_in_page_hint", emptyArray()), color = SalviaSilverDeep, fontSize = 14.sp)
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("find_in_page_input")
                    )
                    val matchText = if (state.findState.query.isEmpty()) ""
                    else if (state.findState.matches == 0) t("find_no_matches", emptyArray())
                    else "${state.findState.active}/${state.findState.matches}"
                    if (matchText.isNotEmpty()) {
                        Text(matchText, color = SalviaSilverMid, fontSize = 12.sp, fontFamily = SalviaMonoFontFamily)
                    }
                    IconButton(onClick = { onFindQuery(state.findState.query, true) }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = t("find_previous", emptyArray()), tint = SalviaPearl)
                    }
                    IconButton(onClick = { onFindQuery(state.findState.query, false) }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = t("find_next", emptyArray()), tint = SalviaPearl)
                    }
                    IconButton(onClick = onCloseFindInPage, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = t("find_close", emptyArray()), tint = SalviaSilverMid)
                    }
                }
            }
        }

        // Main Browser Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportSize = it }
        ) {
            when {
                tab.cleartextPromptUrl != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SalviaBlack)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = SalviaSurface,
                            border = BorderStroke(1.dp, SalviaWarning.copy(alpha = 0.6f)),
                            modifier = Modifier.widthIn(max = 480.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = SalviaWarning, modifier = Modifier.size(26.dp))
                                    Text(t("connection_not_secure", emptyArray()), style = MaterialTheme.typography.titleMedium, color = SalviaPearl)
                                }
                                Text(tab.cleartextPromptUrl, color = SalviaWarning, fontSize = 12.sp, fontFamily = SalviaMonoFontFamily)
                                Text(t("error_cleartext_blocked", emptyArray()), color = SalviaSilverMid, style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { onAllowCleartext(tab.id) },
                                        colors = ButtonDefaults.buttonColors(containerColor = SalviaWarning, contentColor = SalviaDeepCosmic),
                                        modifier = Modifier.testTag("cleartext_allow_button")
                                    ) {
                                        Text(t("action_allow", emptyArray()), fontWeight = FontWeight.SemiBold)
                                    }
                                    OutlinedButton(onClick = { onDismissCleartext(tab.id) }) {
                                        Text(t("action_dismiss", emptyArray()), color = SalviaSilverMid)
                                    }
                                }
                            }
                        }
                    }
                }

                tab.errorText != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SalviaBlack)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = SalviaSurface,
                            border = BorderStroke(1.dp, SalviaError.copy(alpha = 0.55f)),
                            modifier = Modifier.widthIn(max = 480.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = SalviaError, modifier = Modifier.size(34.dp))
                                Text(t("error_page_load_failed", emptyArray()), style = MaterialTheme.typography.titleMedium, color = SalviaPearl)
                                Text(tab.errorText, color = SalviaSilverMid, style = MaterialTheme.typography.bodySmall)
                                Button(
                                    onClick = onReload,
                                    colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl),
                                    modifier = Modifier.testTag("error_reload_button")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(t("reload", emptyArray()))
                                }
                            }
                        }
                    }
                }

                showcase != null -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SalviaBlack),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = SalviaSurface,
                                border = BorderStroke(1.dp, SalviaIris.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(showcase.title, style = MaterialTheme.typography.titleLarge, color = SalviaPearl)
                                    Text(showcase.description, style = MaterialTheme.typography.bodySmall, color = SalviaSilverMid)
                                }
                            }
                        }
                        items(showcase.media) { item ->
                            val cand = MediaCandidate(
                                id = "showcase-${item.mediaUrl}",
                                pageUrl = showcase.url,
                                mediaUrl = item.mediaUrl,
                                title = item.title,
                                mimeType = item.mimeType,
                                extension = item.extension,
                                source = "DOM",
                                confidence = 0.96,
                                duration = item.durationMs,
                                estimatedSize = item.estimatedSize,
                                visible = true
                            )
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = SalviaElevated,
                                border = BorderStroke(1.dp, SalviaBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SalviaIrisContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Movie, contentDescription = null, tint = SalviaCyan)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.titleSmall, color = SalviaPearl)
                                        val meta = buildString {
                                            append(item.extension.uppercase())
                                            item.durationMs?.let { append(" · ").append(MediaFormatters.formatDurationMs(it)) }
                                            item.estimatedSize?.let { append(" · ").append(MediaFormatters.formatFileSize(it)) }
                                        }
                                        Text(meta, fontSize = 12.sp, fontFamily = SalviaMonoFontFamily, color = SalviaGold)
                                    }
                                    Button(
                                        onClick = { onOpenQualityForCandidate(cand) },
                                        colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.size(6.dp))
                                        Text(t("media_open_quality", emptyArray()), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    AndroidView(
                        factory = {
                            (browserContainer.parent as? ViewGroup)?.removeView(browserContainer)
                            browserContainer
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Draggable Floating Download Button
            val fabDesc = if (hasMain || rankedCount > 0) {
                t("media_floating_ready", arrayOf("count" to rankedCount))
            } else {
                t("media_floating_idle", emptyArray())
            }
            FloatingDownloadButton(
                containerWidthPx = viewportSize.width.toFloat(),
                containerHeightPx = viewportSize.height.toFloat(),
                buttonSizeDp = state.settings.floatingButtonSize,
                savedX = state.settings.floatingButtonX,
                savedY = state.settings.floatingButtonY,
                hasMainMedia = hasMain,
                mediaCount = rankedCount,
                alwaysVisible = state.settings.isFloatingButtonAlwaysVisible,
                contentDescriptionText = fabDesc,
                onPositionSaved = onSaveFloatingPosition,
                onTap = onDownloadMainMedia,
                onLongPress = onOpenMediaTray
            )
        }

        // Mobile Bottom Toolbar
        if (!isWideLayout) {
            HorizontalDivider(color = SalviaBorder.copy(alpha = 0.6f), thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(SalviaDeepCosmic)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                IconButton(
                    onClick = onGoBack,
                    enabled = tab.canGoBack,
                    modifier = Modifier.size(48.dp).testTag("browser_nav_back")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = t("go_back", emptyArray()),
                        tint = if (tab.canGoBack) SalviaPearl else SalviaSilverDeep
                    )
                }

                IconButton(
                    onClick = onGoForward,
                    enabled = tab.canGoForward,
                    modifier = Modifier.size(48.dp).testTag("browser_nav_forward")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = t("go_forward", emptyArray()),
                        tint = if (tab.canGoForward) SalviaPearl else SalviaSilverDeep
                    )
                }

                // Gradient Home Pill
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(SalviaIris, SalviaCyan)))
                        .clickable { onNavigate(UiRoute.Home) }
                        .testTag("browser_nav_home"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Home, contentDescription = t("home", emptyArray()), tint = SalviaDeepCosmic, modifier = Modifier.size(22.dp))
                }

                // Tab count button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onOpenTabSwitcher)
                        .testTag("browser_nav_tabs"),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.8.dp, if (tab.isPrivate) SalviaPink else SalviaPearl),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = state.tabs.size.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = SalviaMonoFontFamily,
                                color = if (tab.isPrivate) SalviaPink else SalviaPearl
                            )
                        }
                    }
                }

                // Overflow menu button
                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier.size(48.dp).testTag("browser_nav_menu")
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = t("menu", emptyArray()), tint = SalviaPearl)
                    }
                    BrowserOverflowMenu(
                        expanded = showOverflowMenu,
                        onDismiss = { showOverflowMenu = false },
                        state = state,
                        currentHost = currentHost,
                        isSiteAdBlockExempt = isSiteAdBlockExempt,
                        t = t,
                        onNewTab = onNewTab,
                        onDuplicateTab = { onDuplicateTab(tab.id) },
                        onCloseOtherTabs = { onCloseOtherTabs(tab.id) },
                        onOpenMediaTray = onOpenMediaTray,
                        onToggleDesktop = onToggleDesktop,
                        onToggleSiteAdBlock = { currentHost?.let(onToggleSiteAdBlock) },
                        onOpenFindInPage = onOpenFindInPage,
                        onPrintPage = onPrintPage,
                        onOpenExternal = { onOpenExternal(tab.url) },
                        onSharePage = { onSharePage(tab.title, tab.url) },
                        onCopyLink = { onCopyLink(tab.url) },
                        onNavigate = onNavigate,
                        onChangeTextZoom = onChangeTextZoom
                    )
                }
            }
        }
    }

    if (showSiteInfoSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSiteInfoSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SalviaSurface,
            contentColor = SalviaPearl
        ) {
            val isHttps = tab.url.startsWith("https://", ignoreCase = true)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        imageVector = when {
                            tab.isPrivate -> Icons.Filled.Security
                            isHttps -> Icons.Filled.Lock
                            else -> Icons.Filled.WarningAmber
                        },
                        contentDescription = null,
                        tint = when {
                            tab.isPrivate -> SalviaPink
                            isHttps -> SalviaSuccess
                            else -> SalviaWarning
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentHost ?: tab.title.ifBlank { t("site_info_title", emptyArray()) },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = SalviaPearl,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                tab.isPrivate -> t("site_info_private_tab", emptyArray())
                                isHttps -> t("site_info_https_desc", emptyArray())
                                else -> t("site_info_http_desc", emptyArray())
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = SalviaSilverMid
                        )
                    }
                }

                Text(
                    text = tab.url,
                    fontSize = 11.sp,
                    fontFamily = SalviaMonoFontFamily,
                    color = SalviaSilverDeep,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (tab.blockedCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SalviaElevated,
                        border = BorderStroke(1.dp, SalviaBorder)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Shield, contentDescription = null, tint = SalviaSuccess, modifier = Modifier.size(18.dp))
                            Text(
                                text = t("site_info_blocked_tab", arrayOf("count" to tab.blockedCount)),
                                color = SalviaPearl,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                HorizontalDivider(color = SalviaBorder.copy(alpha = 0.6f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.DesktopWindows, contentDescription = null, tint = SalviaCyan)
                        Text(t("desktop_site", emptyArray()), color = SalviaPearl)
                    }
                    Switch(
                        checked = tab.desktopMode,
                        onCheckedChange = {
                            onToggleDesktop()
                            showSiteInfoSheet = false
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = SalviaDeepCosmic, checkedTrackColor = SalviaCyan)
                    )
                }

                if (state.settings.isAdBlockEnabled && currentHost != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Shield, contentDescription = null, tint = if (isSiteAdBlockExempt) SalviaWarning else SalviaSuccess)
                            Text(t("settings_ad_block", emptyArray()), color = SalviaPearl)
                        }
                        Switch(
                            checked = !isSiteAdBlockExempt,
                            onCheckedChange = {
                                onToggleSiteAdBlock(currentHost)
                                showSiteInfoSheet = false
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = SalviaDeepCosmic, checkedTrackColor = SalviaSuccess)
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            onCopyLink(tab.url)
                            showSiteInfoSheet = false
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(t("copy_link", emptyArray()), color = SalviaPearl, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            onClearSiteData()
                            showSiteInfoSheet = false
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(t("site_info_clear_data", emptyArray()), color = SalviaWarning, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    state.contextMenu?.let { ctx ->
        BrowserContextMenuSheet(
            menu = ctx,
            t = t,
            onDismiss = onDismissContextMenu,
            onOpenNewTab = { url, isPriv ->
                onDismissContextMenu()
                onOpenLinkInTab(url, isPriv)
            },
            onCopy = { url ->
                onDismissContextMenu()
                onCopyLink(url)
            },
            onShare = { url ->
                onDismissContextMenu()
                onSharePage(ctx.title.orEmpty(), url)
            },
            onDownload = { url, mime ->
                onDismissContextMenu()
                onDownloadContextUrl(url, mime)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserContextMenuSheet(
    menu: BrowserContextMenu,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onDismiss: () -> Unit,
    onOpenNewTab: (String, Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDownload: (String, String?) -> Unit
) {
    val primaryUrl = menu.linkUrl ?: menu.imageUrl ?: return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SalviaSurface,
        contentColor = SalviaPearl
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!menu.title.isNullOrBlank()) {
                Text(
                    text = menu.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = SalviaPearl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            Text(
                text = primaryUrl,
                fontSize = 11.sp,
                fontFamily = SalviaMonoFontFamily,
                color = SalviaSilverMid,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            HorizontalDivider(color = SalviaBorder.copy(alpha = 0.6f))

            menu.linkUrl?.let { link ->
                ContextSheetActionRow(
                    icon = Icons.Filled.Add,
                    tint = SalviaCyan,
                    label = t("ctx_open_new_tab", emptyArray()),
                    onClick = { onOpenNewTab(link, false) }
                )
                ContextSheetActionRow(
                    icon = Icons.Filled.Security,
                    tint = SalviaPink,
                    label = t("ctx_open_private_tab", emptyArray()),
                    onClick = { onOpenNewTab(link, true) }
                )
                ContextSheetActionRow(
                    icon = Icons.Filled.ContentCopy,
                    tint = SalviaPearl,
                    label = t("ctx_copy_link", emptyArray()),
                    onClick = { onCopy(link) }
                )
                ContextSheetActionRow(
                    icon = Icons.Filled.Share,
                    tint = SalviaPearl,
                    label = t("ctx_share_link", emptyArray()),
                    onClick = { onShare(link) }
                )
                ContextSheetActionRow(
                    icon = Icons.Filled.Download,
                    tint = SalviaGold,
                    label = t("ctx_download_link", emptyArray()),
                    onClick = { onDownload(link, null) }
                )
            }

            menu.imageUrl?.let { img ->
                ContextSheetActionRow(
                    icon = Icons.Filled.Download,
                    tint = SalviaIrisLight,
                    label = t("ctx_download_image", emptyArray()),
                    onClick = { onDownload(img, "image/*") }
                )
                if (menu.linkUrl == null) {
                    ContextSheetActionRow(
                        icon = Icons.Filled.ContentCopy,
                        tint = SalviaPearl,
                        label = t("ctx_copy_link", emptyArray()),
                        onClick = { onCopy(img) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ContextSheetActionRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = SalviaPearl, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BrowserOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: SalviaUiState,
    currentHost: String?,
    isSiteAdBlockExempt: Boolean,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onNewTab: (Boolean) -> Unit,
    onDuplicateTab: () -> Unit,
    onCloseOtherTabs: () -> Unit,
    onOpenMediaTray: () -> Unit,
    onToggleDesktop: () -> Unit,
    onToggleSiteAdBlock: () -> Unit,
    onOpenFindInPage: () -> Unit,
    onPrintPage: () -> Unit,
    onOpenExternal: () -> Unit,
    onSharePage: () -> Unit,
    onCopyLink: () -> Unit,
    onNavigate: (UiRoute) -> Unit,
    onChangeTextZoom: (Int) -> Unit
) {
    val tab = state.activeTab ?: return
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = SalviaElevated
    ) {
        // Zoom controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { onChangeTextZoom((state.settings.textZoom - 10).coerceAtLeast(50)) }) {
                Text("-", color = SalviaCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { onChangeTextZoom(100) }) {
                Text("${state.settings.textZoom}%", color = SalviaPearl, fontFamily = SalviaMonoFontFamily, fontSize = 13.sp)
            }
            TextButton(onClick = { onChangeTextZoom((state.settings.textZoom + 10).coerceAtMost(200)) }) {
                Text("+", color = SalviaCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        HorizontalDivider(color = SalviaBorder.copy(alpha = 0.6f))

        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null, tint = SalviaCyan) },
            text = {
                val count = if (state.activeDownloadsCount > 0) state.activeDownloadsCount else state.completedDownloadsCount
                val suffix = if (count > 0) " ($count)" else ""
                Text("${t("downloads", emptyArray())}$suffix", color = SalviaPearl)
            },
            onClick = { onDismiss(); onNavigate(UiRoute.Downloads) }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, tint = SalviaIrisLight) },
            text = { Text("${t("media_tray_title", emptyArray())} (${state.rankedTabCandidates.size})", color = SalviaPearl) },
            onClick = { onDismiss(); onOpenMediaTray() }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = SalviaPearl) },
            text = { Text(t("new_tab", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onNewTab(false) }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Security, contentDescription = null, tint = SalviaPink) },
            text = { Text(t("new_private_tab", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onNewTab(true) }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = SalviaSilverMid) },
            text = { Text(t("duplicate_tab", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onDuplicateTab() }
        )
        if (state.tabs.size > 1) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = SalviaSilverMid) },
                text = { Text(t("close_other_tabs", emptyArray()), color = SalviaPearl) },
                onClick = { onDismiss(); onCloseOtherTabs() }
            )
        }
        HorizontalDivider(color = SalviaBorder.copy(alpha = 0.6f))
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.DesktopWindows, contentDescription = null, tint = if (tab.desktopMode) SalviaCyan else SalviaSilverMid) },
            trailingIcon = { Checkbox(checked = tab.desktopMode, onCheckedChange = null) },
            text = { Text(t("desktop_site", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onToggleDesktop() }
        )
        if (state.settings.isAdBlockEnabled && currentHost != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Shield, contentDescription = null, tint = if (isSiteAdBlockExempt) SalviaWarning else SalviaSuccess) },
                text = {
                    Text(
                        t(if (isSiteAdBlockExempt) "ad_block_enable_here" else "ad_block_disable_here", emptyArray()),
                        color = SalviaPearl
                    )
                },
                onClick = { onDismiss(); onToggleSiteAdBlock() }
            )
        }
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.FindInPage, contentDescription = null, tint = SalviaSilverMid) },
            text = { Text(t("find_in_page", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onOpenFindInPage() }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Print, contentDescription = null, tint = SalviaSilverMid) },
            text = { Text(t("print_page", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onPrintPage() }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.OpenInBrowser, contentDescription = null, tint = SalviaSilverMid) },
            text = { Text(t("open_in_external_browser", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onOpenExternal() }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null, tint = SalviaSilverMid) },
            text = { Text(t("share_page", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onSharePage() }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = SalviaSilverMid) },
            text = { Text(t("copy_link", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onCopyLink() }
        )
        HorizontalDivider(color = SalviaBorder.copy(alpha = 0.6f))
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null, tint = SalviaGold) },
            text = { Text(t("bookmarks", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onNavigate(UiRoute.Bookmarks) }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, tint = SalviaCyan) },
            text = { Text(t("history", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onNavigate(UiRoute.History) }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = SalviaSilverMid) },
            text = { Text(t("settings", emptyArray()), color = SalviaPearl) },
            onClick = { onDismiss(); onNavigate(UiRoute.Settings) }
        )
    }
}
