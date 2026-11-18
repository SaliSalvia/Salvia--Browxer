package com.salvia.salviabrowxer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.R
import com.salvia.salviabrowxer.core.AdBlockRules
import com.salvia.salviabrowxer.core.AddressBarResolver
import com.salvia.salviabrowxer.core.PastedLinkClassifier
import com.salvia.salviabrowxer.core.SalviaConstants
import com.salvia.salviabrowxer.data.ClipboardLinkKind
import com.salvia.salviabrowxer.data.DownloadState
import com.salvia.salviabrowxer.data.SearchEngineKey
import com.salvia.salviabrowxer.data.UiRoute
import com.salvia.salviabrowxer.ui.SalviaUiState
import com.salvia.salviabrowxer.ui.components.DownloadItemRow
import com.salvia.salviabrowxer.ui.components.OrbitalBrandMark
import com.salvia.salviabrowxer.ui.theme.SalviaBlack
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaCyanContainer
import com.salvia.salviabrowxer.ui.theme.SalviaDeepCosmic
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaIrisContainer
import com.salvia.salviabrowxer.ui.theme.SalviaIrisLight
import com.salvia.salviabrowxer.ui.theme.SalviaMonoFontFamily
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaPink
import com.salvia.salviabrowxer.ui.theme.SalviaSilverDeep
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSurface

@Composable
fun HomeScreen(
    state: SalviaUiState,
    showMobileTopBar: Boolean,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onNavigate: (UiRoute) -> Unit,
    onOpenUrl: (String, Boolean) -> Unit, // url, isPrivate
    onExtractVideo: (String) -> Unit,
    onPasteFromClipboard: () -> String?,
    onUseClipboardPrompt: (String, ClipboardLinkKind) -> Unit,
    onDismissClipboardPrompt: () -> Unit,
    onSelectSearchEngine: (SearchEngineKey) -> Unit,
    onOpenMediaCandidate: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenDownload: (String) -> Unit,
    onExportDownload: (String) -> Unit,
    onShareDownload: (com.salvia.salviabrowxer.data.DownloadItemRecord) -> Unit,
    onToggleLockDownload: (String, Boolean) -> Unit,
    onDeleteDownload: (String) -> Unit
) {
    var linkInput by rememberSaveable { mutableStateOf("") }
    var showEngineMenu by remember { mutableStateOf(false) }

    val classifiedInput = remember(linkInput) {
        val norm = AddressBarResolver.normalizeLinkInput(linkInput)
        if (norm != null) PastedLinkClassifier.classifyPastedLink(norm) else null
    }
    val activeDownloads = remember(state.downloads) {
        state.downloads.filter {
            it.state == DownloadState.DOWNLOADING ||
                it.state == DownloadState.CONNECTING ||
                it.state == DownloadState.QUEUED
        }
    }

    val suggestions = remember(linkInput, state.bookmarks, state.history) {
        val q = linkInput.trim().lowercase()
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SalviaBlack)
    ) {
        // Subtle cosmic radial halo & watermark logo
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            SalviaIris.copy(alpha = 0.16f),
                            SalviaCyan.copy(alpha = 0.06f),
                            Color.Transparent
                        )
                    )
                )
        )
        Image(
            painter = painterResource(id = R.drawable.salvia_logo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.Center)
                .size(320.dp)
                .alpha(0.06f)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showMobileTopBar) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 640.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onNavigate(UiRoute.Settings) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = t("settings", emptyArray()),
                                tint = SalviaSilverMid
                            )
                        }
                        Surface(
                            onClick = { onNavigate(UiRoute.Browser) },
                            shape = CircleShape,
                            color = SalviaElevated,
                            border = BorderStroke(1.dp, SalviaBorder),
                            modifier = Modifier
                                .height(44.dp)
                                .testTag("home_browser_cta")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.Language, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(18.dp))
                                Text(t("home_open_browser", emptyArray()), color = SalviaPearl, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                if (state.tabs.isNotEmpty()) {
                                    Surface(shape = CircleShape, color = SalviaIrisContainer) {
                                        Text(
                                            text = state.tabs.size.toString(),
                                            fontSize = 11.sp,
                                            fontFamily = SalviaMonoFontFamily,
                                            color = SalviaIrisLight,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Hero Header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OrbitalBrandMark(
                        size = 76.dp,
                        showRing = true,
                        animateRing = true,
                        contentDescription = t("app_name", emptyArray())
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = t("home_hero_title", emptyArray()),
                        style = MaterialTheme.typography.headlineMedium,
                        color = SalviaPearl,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = t("home_hero_subtitle", emptyArray()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SalviaSilverMid,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Clipboard Prompt Banner
            state.clipboardPrompt?.let { prompt ->
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 640.dp)
                            .testTag("home_clipboard_banner"),
                        shape = RoundedCornerShape(18.dp),
                        color = SalviaCyanContainer,
                        border = BorderStroke(1.dp, SalviaCyan.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.Link, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(18.dp))
                                Text(
                                    text = t(
                                        if (prompt.kind == ClipboardLinkKind.MEDIA_FILE) "home_clipboard_media" else "home_clipboard_page",
                                        emptyArray()
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = SalviaPearl
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = prompt.url,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = SalviaMonoFontFamily),
                                color = SalviaCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onUseClipboardPrompt(prompt.url, prompt.kind) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SalviaCyan, contentColor = SalviaDeepCosmic),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(t("home_clipboard_use", emptyArray()), fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(onClick = onDismissClipboardPrompt) {
                                    Text(t("home_clipboard_dismiss", emptyArray()), color = SalviaSilverMid)
                                }
                            }
                        }
                    }
                }
            }

            // Smart Omnibox Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = SalviaSurface,
                    border = BorderStroke(1.dp, SalviaBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("home_paste_title", emptyArray()), style = MaterialTheme.typography.titleMedium, color = SalviaPearl)
                                Text(t("home_paste_support", emptyArray()), style = MaterialTheme.typography.bodySmall, color = SalviaSilverMid)
                            }
                            Box {
                                Surface(
                                    onClick = { showEngineMenu = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = SalviaElevated,
                                    border = BorderStroke(1.dp, SalviaBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Filled.Search, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(14.dp))
                                        Text(state.settings.searchEngine.label, fontSize = 12.sp, color = SalviaPearl)
                                    }
                                }
                                DropdownMenu(
                                    expanded = showEngineMenu,
                                    onDismissRequest = { showEngineMenu = false },
                                    containerColor = SalviaElevated
                                ) {
                                    SearchEngineKey.entries.forEach { engine ->
                                        DropdownMenuItem(
                                            text = { Text(engine.label, color = SalviaPearl) },
                                            onClick = {
                                                showEngineMenu = false
                                                onSelectSearchEngine(engine)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = linkInput,
                            onValueChange = { linkInput = it },
                            placeholder = { Text(t("home_paste_hint", emptyArray()), color = SalviaSilverDeep) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    if (linkInput.isNotBlank()) {
                                        if (classifiedInput == ClipboardLinkKind.MEDIA_FILE) {
                                            onOpenMediaCandidate(AddressBarResolver.normalizeLinkInput(linkInput) ?: linkInput)
                                        } else {
                                            onOpenUrl(linkInput, false)
                                        }
                                    }
                                }
                            ),
                            trailingIcon = {
                                Row {
                                    if (linkInput.isNotEmpty()) {
                                        IconButton(onClick = { linkInput = "" }) {
                                            Icon(Icons.Filled.Clear, contentDescription = t("home_clear_input", emptyArray()), tint = SalviaSilverMid)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val clip = onPasteFromClipboard()
                                            if (clip != null) linkInput = clip
                                        },
                                        modifier = Modifier.testTag("home_paste_button")
                                    ) {
                                        Icon(Icons.Filled.ContentPaste, contentDescription = t("home_paste_from_clipboard", emptyArray()), tint = SalviaCyan)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("home_link_input"),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SalviaIris,
                                unfocusedBorderColor = SalviaBorder,
                                focusedTextColor = SalviaPearl,
                                unfocusedTextColor = SalviaPearl,
                                focusedContainerColor = SalviaDeepCosmic,
                                unfocusedContainerColor = SalviaDeepCosmic
                            )
                        )

                        if (suggestions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                suggestions.forEach { (title, url, isBookmark) ->
                                    Surface(
                                        onClick = {
                                            linkInput = url
                                            onOpenUrl(url, false)
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
                                                imageVector = if (isBookmark) Icons.Filled.Bookmark else Icons.Filled.History,
                                                contentDescription = null,
                                                tint = if (isBookmark) SalviaGold else SalviaCyan,
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

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (linkInput.isNotBlank()) {
                                        if (classifiedInput == ClipboardLinkKind.MEDIA_FILE) {
                                            onOpenMediaCandidate(AddressBarResolver.normalizeLinkInput(linkInput) ?: linkInput)
                                        } else {
                                            onOpenUrl(linkInput, false)
                                        }
                                    }
                                },
                                enabled = linkInput.isNotBlank(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("home_open_link_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                            ) {
                                Icon(
                                    imageVector = if (classifiedInput == ClipboardLinkKind.MEDIA_FILE) Icons.Filled.Download else Icons.Filled.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = t(if (classifiedInput == ClipboardLinkKind.MEDIA_FILE) "home_download_file" else "home_open_link", emptyArray()),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = { if (linkInput.isNotBlank()) onExtractVideo(linkInput) },
                                enabled = linkInput.isNotBlank() && !state.isExtractingHome,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("home_extract_video_button"),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, SalviaCyan.copy(alpha = 0.65f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SalviaCyan)
                            ) {
                                if (state.isExtractingHome) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SalviaCyan)
                                } else {
                                    Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = t(if (state.isExtractingHome) "extracting_media" else "home_extract_video", emptyArray()),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Quick Access Speed Dials
            item {
                Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                    Text(
                        text = t("speed_dial_title", emptyArray()),
                        style = MaterialTheme.typography.titleSmall,
                        color = SalviaSilverMid,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SpeedDialTile(
                            label = t("speed_dial_clips", emptyArray()),
                            icon = Icons.Filled.PlayCircleOutline,
                            tint = SalviaGold,
                            onClick = { onOpenUrl(SalviaConstants.SHOWCASE_URL, false) },
                            modifier = Modifier.weight(1f).testTag("speed_dial_showcase")
                        )
                        SpeedDialTile(
                            label = t("browser_title", emptyArray()),
                            icon = Icons.Filled.Language,
                            tint = SalviaCyan,
                            onClick = { onNavigate(UiRoute.Browser) },
                            modifier = Modifier.weight(1f).testTag("speed_dial_browser")
                        )
                        SpeedDialTile(
                            label = t("private_browsing", emptyArray()),
                            icon = Icons.Filled.Security,
                            tint = SalviaPink,
                            onClick = { onOpenUrl(state.settings.homepage, true) },
                            modifier = Modifier.weight(1f).testTag("speed_dial_private")
                        )
                        SpeedDialTile(
                            label = t("downloads", emptyArray()),
                            icon = Icons.Filled.VideoLibrary,
                            tint = SalviaIrisLight,
                            onClick = { onNavigate(UiRoute.Downloads) },
                            modifier = Modifier.weight(1f).testTag("speed_dial_downloads")
                        )
                    }
                }
            }

            // Bookmarks preview strip
            if (state.bookmarks.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(t("bookmarks", emptyArray()), style = MaterialTheme.typography.titleSmall, color = SalviaSilverMid)
                            TextButton(onClick = { onNavigate(UiRoute.Bookmarks) }) {
                                Text(t("home_active_see_all", emptyArray()), color = SalviaCyan, fontSize = 12.sp)
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.bookmarks.take(8), key = { it.id }) { bm ->
                                Surface(
                                    onClick = { onOpenUrl(bm.url, false) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = SalviaSurface,
                                    border = BorderStroke(1.dp, SalviaBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Filled.Bookmark, contentDescription = null, tint = SalviaGold, modifier = Modifier.size(14.dp))
                                        Text(
                                            text = bm.title.ifBlank { AdBlockRules.hostOfUrl(bm.url) ?: bm.url },
                                            color = SalviaPearl,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 150.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recent History preview strip
            if (state.history.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(t("recent_history_title", emptyArray()), style = MaterialTheme.typography.titleSmall, color = SalviaSilverMid)
                            TextButton(onClick = { onNavigate(UiRoute.History) }) {
                                Text(t("home_active_see_all", emptyArray()), color = SalviaCyan, fontSize = 12.sp)
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.history.take(8), key = { it.id }) { hist ->
                                Surface(
                                    onClick = { onOpenUrl(hist.url, false) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = SalviaSurface,
                                    border = BorderStroke(1.dp, SalviaBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Filled.History, contentDescription = null, tint = SalviaSilverMid, modifier = Modifier.size(14.dp))
                                        Text(
                                            text = hist.title.ifBlank { AdBlockRules.hostOfUrl(hist.url) ?: hist.url },
                                            color = SalviaPearl,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 150.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Active Downloads strip
            item {
                Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t("home_active_title", emptyArray()), style = MaterialTheme.typography.titleSmall, color = SalviaSilverMid)
                        TextButton(onClick = { onNavigate(UiRoute.Downloads) }) {
                            Text(t("home_active_see_all", emptyArray()), color = SalviaCyan, fontSize = 12.sp)
                        }
                    }
                    if (activeDownloads.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = SalviaSurface,
                            border = BorderStroke(1.dp, SalviaBorder)
                        ) {
                            Text(
                                text = t("home_active_empty", emptyArray()),
                                style = MaterialTheme.typography.bodySmall,
                                color = SalviaSilverMid,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            activeDownloads.take(3).forEach { dl ->
                                DownloadItemRow(
                                    item = dl,
                                    isLockedFolderConfigured = state.isLockedFolderConfigured,
                                    t = t,
                                    onPause = { onPauseDownload(dl.id) },
                                    onResume = { onResumeDownload(dl.id) },
                                    onCancel = { onCancelDownload(dl.id) },
                                    onOpen = { onOpenDownload(dl.id) },
                                    onExport = { onExportDownload(dl.id) },
                                    onShare = { onShareDownload(dl) },
                                    onToggleLock = { onToggleLockDownload(dl.id, !dl.isLocked) },
                                    onDelete = { onDeleteDownload(dl.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedDialTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = SalviaSurface,
        border = BorderStroke(1.dp, SalviaBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(SalviaElevated, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                color = SalviaPearl,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
