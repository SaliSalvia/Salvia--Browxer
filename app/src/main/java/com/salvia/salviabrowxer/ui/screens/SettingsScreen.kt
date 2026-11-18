package com.salvia.salviabrowxer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.core.SalviaConstants
import com.salvia.salviabrowxer.core.SecurityManager
import com.salvia.salviabrowxer.data.AppSettings
import com.salvia.salviabrowxer.data.ConnectionKind
import com.salvia.salviabrowxer.data.LanguageMode
import com.salvia.salviabrowxer.data.SearchEngineKey
import com.salvia.salviabrowxer.data.UpdateCheckState
import com.salvia.salviabrowxer.ui.SalviaUiState
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
import com.salvia.salviabrowxer.ui.theme.SalviaMonoFontFamily
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverDeep
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSuccess
import com.salvia.salviabrowxer.ui.theme.SalviaSurface
import com.salvia.salviabrowxer.ui.theme.SalviaWarning
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class SettingsDialogKind {
    NONE,
    SEARCH_ENGINE,
    HOMEPAGE,
    LANGUAGE,
    CLEAR_BROWSING,
    CLEAR_HISTORY,
    CLEAR_COOKIES,
    SET_LOCK_PASSWORD,
    CHANGE_LOCK_PASSWORD,
    REMOVE_LOCK_PASSWORD
}

@Composable
fun SettingsScreen(
    state: SalviaUiState,
    showBackButton: Boolean,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    onBack: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onPickDownloadFolder: () -> Unit,
    onResetDownloadFolder: () -> Unit,
    onClearHistory: () -> Unit,
    onClearSiteStorage: () -> Unit,
    onClearBrowsingData: () -> Unit,
    onSetLockPassword: suspend (String) -> Boolean,
    onVerifyCurrentLockPassword: suspend (String) -> Boolean,
    onRemoveLockPassword: () -> Unit,
    onCheckUpdates: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onShareCrashReports: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onShowToast: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var activeDialog by remember { mutableStateOf(SettingsDialogKind.NONE) }
    var homepageDraft by remember(state.settings.homepage) { mutableStateOf(state.settings.homepage) }
    var curPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var isProcessingPassword by remember { mutableStateOf(false) }

    fun resetPasswordDialogState() {
        curPassword = ""
        newPassword = ""
        confirmPassword = ""
        passwordError = null
        isProcessingPassword = false
        activeDialog = SettingsDialogKind.NONE
    }

    // Dialogs
    when (activeDialog) {
        SettingsDialogKind.SEARCH_ENGINE -> {
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialogKind.NONE },
                containerColor = SalviaSurface,
                titleContentColor = SalviaPearl,
                title = { Text(t("settings_search_engine", emptyArray())) },
                text = {
                    Column {
                        SearchEngineKey.entries.forEach { engine ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onUpdateSettings { it.copy(searchEngine = engine) }
                                        activeDialog = SettingsDialogKind.NONE
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.settings.searchEngine == engine,
                                    onClick = {
                                        onUpdateSettings { it.copy(searchEngine = engine) }
                                        activeDialog = SettingsDialogKind.NONE
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = SalviaCyan, unselectedColor = SalviaSilverMid)
                                )
                                Text(engine.label, color = SalviaPearl, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = SettingsDialogKind.NONE }) {
                        Text(t("action_cancel", emptyArray()), color = SalviaSilverMid)
                    }
                }
            )
        }

        SettingsDialogKind.HOMEPAGE -> {
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialogKind.NONE },
                containerColor = SalviaSurface,
                titleContentColor = SalviaPearl,
                title = { Text(t("settings_homepage", emptyArray())) },
                text = {
                    OutlinedTextField(
                        value = homepageDraft,
                        onValueChange = { homepageDraft = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SalviaIris,
                            unfocusedBorderColor = SalviaBorder,
                            focusedTextColor = SalviaPearl,
                            unfocusedTextColor = SalviaPearl
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val clean = homepageDraft.trim().ifEmpty { SalviaConstants.DEFAULT_HOMEPAGE }
                            val fixed = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "https://$clean"
                            onUpdateSettings { it.copy(homepage = fixed) }
                            onShowToast(t("settings_homepage_updated", emptyArray()))
                            activeDialog = SettingsDialogKind.NONE
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                    ) {
                        Text(t("action_ok", emptyArray()))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = SettingsDialogKind.NONE }) {
                        Text(t("action_cancel", emptyArray()), color = SalviaSilverMid)
                    }
                }
            )
        }

        SettingsDialogKind.LANGUAGE -> {
            val langs = listOf(
                LanguageMode.SYSTEM to t("language_system", emptyArray()),
                LanguageMode.EN to t("language_en", emptyArray()),
                LanguageMode.FA to t("language_fa", emptyArray())
            )
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialogKind.NONE },
                containerColor = SalviaSurface,
                titleContentColor = SalviaPearl,
                title = { Text(t("settings_language", emptyArray())) },
                text = {
                    Column {
                        langs.forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onUpdateSettings { it.copy(language = mode) }
                                        activeDialog = SettingsDialogKind.NONE
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.settings.language == mode,
                                    onClick = {
                                        onUpdateSettings { it.copy(language = mode) }
                                        activeDialog = SettingsDialogKind.NONE
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = SalviaCyan, unselectedColor = SalviaSilverMid)
                                )
                                Text(label, color = SalviaPearl, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = SettingsDialogKind.NONE }) {
                        Text(t("action_cancel", emptyArray()), color = SalviaSilverMid)
                    }
                }
            )
        }

        SettingsDialogKind.CLEAR_BROWSING,
        SettingsDialogKind.CLEAR_HISTORY,
        SettingsDialogKind.CLEAR_COOKIES -> {
            val titleKey = when (activeDialog) {
                SettingsDialogKind.CLEAR_HISTORY -> "settings_clear_history"
                SettingsDialogKind.CLEAR_COOKIES -> "settings_clear_cookies"
                else -> "settings_clear_browsing_data"
            }
            val bodyKey = when (activeDialog) {
                SettingsDialogKind.CLEAR_HISTORY -> "clear_history_confirm"
                else -> "settings_clear_browsing_data_hint"
            }
            AlertDialog(
                onDismissRequest = { activeDialog = SettingsDialogKind.NONE },
                containerColor = SalviaSurface,
                titleContentColor = SalviaPearl,
                title = { Text(t(titleKey, emptyArray())) },
                text = { Text(t(bodyKey, emptyArray()), color = SalviaSilverMid) },
                confirmButton = {
                    Button(
                        onClick = {
                            when (activeDialog) {
                                SettingsDialogKind.CLEAR_HISTORY -> onClearHistory()
                                SettingsDialogKind.CLEAR_COOKIES -> onClearSiteStorage()
                                else -> onClearBrowsingData()
                            }
                            activeDialog = SettingsDialogKind.NONE
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SalviaError, contentColor = SalviaDeepCosmic)
                    ) {
                        Text(t("action_clear", emptyArray()), fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = SettingsDialogKind.NONE }) {
                        Text(t("action_cancel", emptyArray()), color = SalviaSilverMid)
                    }
                }
            )
        }

        SettingsDialogKind.SET_LOCK_PASSWORD,
        SettingsDialogKind.CHANGE_LOCK_PASSWORD -> {
            val isChange = activeDialog == SettingsDialogKind.CHANGE_LOCK_PASSWORD
            AlertDialog(
                onDismissRequest = { if (!isProcessingPassword) resetPasswordDialogState() },
                containerColor = SalviaSurface,
                titleContentColor = SalviaPearl,
                title = { Text(t(if (isChange) "action_change_password" else "action_set_password", emptyArray())) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isChange) {
                            OutlinedTextField(
                                value = curPassword,
                                onValueChange = { curPassword = it; passwordError = null },
                                enabled = !isProcessingPassword,
                                label = { Text(t("locked_folder_current_password_label", emptyArray())) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SalviaIris, unfocusedBorderColor = SalviaBorder,
                                    focusedTextColor = SalviaPearl, unfocusedTextColor = SalviaPearl
                                )
                            )
                        }
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it; passwordError = null },
                            enabled = !isProcessingPassword,
                            label = { Text(t("locked_folder_password_label", emptyArray())) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SalviaIris, unfocusedBorderColor = SalviaBorder,
                                focusedTextColor = SalviaPearl, unfocusedTextColor = SalviaPearl
                            )
                        )
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it; passwordError = null },
                            enabled = !isProcessingPassword,
                            label = { Text(t("locked_folder_confirm_label", emptyArray())) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SalviaIris, unfocusedBorderColor = SalviaBorder,
                                focusedTextColor = SalviaPearl, unfocusedTextColor = SalviaPearl
                            )
                        )
                        if (isProcessingPassword) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SalviaCyan)
                                Text(t("verifying", emptyArray()), color = SalviaSilverMid, fontSize = 12.sp)
                            }
                        } else if (state.lockoutRemainingSeconds > 0) {
                            Text(t("locked_folder_try_later", arrayOf("seconds" to state.lockoutRemainingSeconds)), color = SalviaError, fontSize = 12.sp)
                        } else if (passwordError != null) {
                            Text(passwordError!!, color = SalviaError, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !isProcessingPassword && state.lockoutRemainingSeconds == 0,
                        onClick = {
                            if (newPassword.length < SecurityManager.MIN_PASSWORD_CHARS) {
                                passwordError = t("locked_folder_password_short", arrayOf("min" to SecurityManager.MIN_PASSWORD_CHARS))
                                return@Button
                            }
                            if (newPassword != confirmPassword) {
                                passwordError = t("locked_folder_mismatch", emptyArray())
                                return@Button
                            }
                            isProcessingPassword = true
                            passwordError = null
                            scope.launch {
                                if (isChange && !onVerifyCurrentLockPassword(curPassword)) {
                                    isProcessingPassword = false
                                    passwordError = t("locked_folder_wrong_password", emptyArray())
                                    return@launch
                                }
                                val saved = onSetLockPassword(newPassword)
                                isProcessingPassword = false
                                if (saved) {
                                    resetPasswordDialogState()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                    ) {
                        Text(t("action_ok", emptyArray()))
                    }
                },
                dismissButton = {
                    TextButton(enabled = !isProcessingPassword, onClick = { resetPasswordDialogState() }) {
                        Text(t("action_cancel", emptyArray()), color = SalviaSilverMid)
                    }
                }
            )
        }

        SettingsDialogKind.REMOVE_LOCK_PASSWORD -> {
            AlertDialog(
                onDismissRequest = { if (!isProcessingPassword) resetPasswordDialogState() },
                containerColor = SalviaSurface,
                titleContentColor = SalviaPearl,
                title = { Text(t("action_remove_password", emptyArray())) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = curPassword,
                            onValueChange = { curPassword = it; passwordError = null },
                            enabled = !isProcessingPassword,
                            label = { Text(t("locked_folder_current_password_label", emptyArray())) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SalviaIris, unfocusedBorderColor = SalviaBorder,
                                focusedTextColor = SalviaPearl, unfocusedTextColor = SalviaPearl
                            )
                        )
                        if (isProcessingPassword) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SalviaCyan)
                                Text(t("verifying", emptyArray()), color = SalviaSilverMid, fontSize = 12.sp)
                            }
                        } else if (state.lockoutRemainingSeconds > 0) {
                            Text(t("locked_folder_try_later", arrayOf("seconds" to state.lockoutRemainingSeconds)), color = SalviaError, fontSize = 12.sp)
                        } else if (passwordError != null) {
                            Text(passwordError!!, color = SalviaError, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !isProcessingPassword && state.lockoutRemainingSeconds == 0,
                        onClick = {
                            isProcessingPassword = true
                            passwordError = null
                            scope.launch {
                                val verified = onVerifyCurrentLockPassword(curPassword)
                                isProcessingPassword = false
                                if (verified) {
                                    onRemoveLockPassword()
                                    resetPasswordDialogState()
                                } else {
                                    passwordError = t("locked_folder_wrong_password", emptyArray())
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SalviaError, contentColor = SalviaDeepCosmic)
                    ) {
                        Text(t("action_remove_password", emptyArray()), fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(enabled = !isProcessingPassword, onClick = { resetPasswordDialogState() }) {
                        Text(t("action_cancel", emptyArray()), color = SalviaSilverMid)
                    }
                }
            )
        }

        SettingsDialogKind.NONE -> Unit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SalviaBlack)
            .navigationBarsPadding()
    ) {
        ScreenHeader(
            title = t("settings", emptyArray()),
            backContentDescription = t("go_back", emptyArray()),
            onBack = onBack,
            showBackButton = showBackButton
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Browser section
            item {
                SettingsSectionCard(title = t("settings_browser", emptyArray())) {
                    SettingsActionRow(
                        title = t("settings_search_engine", emptyArray()),
                        subtitle = state.settings.searchEngine.label,
                        onClick = { activeDialog = SettingsDialogKind.SEARCH_ENGINE }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsActionRow(
                        title = t("settings_homepage", emptyArray()),
                        subtitle = state.settings.homepage,
                        onClick = {
                            homepageDraft = state.settings.homepage
                            activeDialog = SettingsDialogKind.HOMEPAGE
                        }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        title = t("settings_javascript", emptyArray()),
                        subtitle = t("settings_javascript_hint", emptyArray()),
                        checked = state.settings.isJavaScriptEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(isJavaScriptEnabled = v) } },
                        tag = "settings_switch_js"
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        title = t("settings_desktop_site", emptyArray()),
                        subtitle = t("settings_desktop_site_hint", emptyArray()),
                        checked = state.settings.isDesktopSite,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(isDesktopSite = v) } }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        title = t("settings_cookies", emptyArray()),
                        subtitle = t("settings_cookies_hint", emptyArray()),
                        checked = state.settings.areCookiesEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(areCookiesEnabled = v) } }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    val langLabel = when (state.settings.language) {
                        LanguageMode.SYSTEM -> t("language_system", emptyArray())
                        LanguageMode.EN -> t("language_en", emptyArray())
                        LanguageMode.FA -> t("language_fa", emptyArray())
                    }
                    SettingsActionRow(
                        title = t("settings_language", emptyArray()),
                        subtitle = langLabel,
                        onClick = { activeDialog = SettingsDialogKind.LANGUAGE },
                        tag = "settings_language_row"
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsActionRow(
                        title = t("settings_clear_browsing_data", emptyArray()),
                        subtitle = t("settings_clear_browsing_data_hint", emptyArray()),
                        titleColor = SalviaError,
                        icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = SalviaError) },
                        onClick = { activeDialog = SettingsDialogKind.CLEAR_BROWSING }
                    )
                }
            }

            // 2. Downloads section
            item {
                SettingsSectionCard(title = t("settings_downloads", emptyArray())) {
                    // Live network status
                    val (connKey, connTint, connIcon) = when (state.connectionKind) {
                        ConnectionKind.WIFI -> Triple("settings_connection_wifi", SalviaSuccess, Icons.Filled.Wifi)
                        ConnectionKind.MOBILE -> Triple("settings_connection_mobile", SalviaCyan, Icons.Filled.Wifi)
                        ConnectionKind.OTHER -> Triple("settings_connection_other", SalviaCyan, Icons.Filled.Wifi)
                        ConnectionKind.OFFLINE -> Triple("settings_connection_offline", SalviaWarning, Icons.Filled.WifiOff)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(connIcon, contentDescription = null, tint = connTint)
                        Column {
                            Text(t("settings_connection_label", emptyArray()), fontSize = 12.sp, color = SalviaSilverDeep)
                            Text(t(connKey, emptyArray()), fontSize = 13.sp, color = connTint, fontWeight = FontWeight.Medium)
                        }
                    }
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    // Simultaneous downloads slider (1..6)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t("settings_simultaneous_downloads", emptyArray()), color = SalviaPearl, fontWeight = FontWeight.Medium)
                            Text(
                                t("settings_downloads_at_a_time", arrayOf("count" to state.settings.maxSimultaneousDownloads)),
                                color = SalviaCyan,
                                fontFamily = SalviaMonoFontFamily,
                                fontSize = 13.sp
                            )
                        }
                        Slider(
                            value = state.settings.maxSimultaneousDownloads.toFloat(),
                            onValueChange = { v -> onUpdateSettings { it.copy(maxSimultaneousDownloads = v.roundToInt().coerceIn(1, 6)) } },
                            valueRange = 1f..6f,
                            steps = 4,
                            colors = SliderDefaults.colors(thumbColor = SalviaCyan, activeTrackColor = SalviaIris)
                        )
                    }
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    // Parallel connections slider (1..8)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t("settings_parallel_connections", emptyArray()), color = SalviaPearl, fontWeight = FontWeight.Medium)
                            Text(
                                state.settings.parallelConnections.toString(),
                                color = SalviaCyan,
                                fontFamily = SalviaMonoFontFamily,
                                fontSize = 13.sp
                            )
                        }
                        Text(t("settings_parallel_connections_hint", emptyArray()), color = SalviaSilverMid, fontSize = 12.sp)
                        Slider(
                            value = state.settings.parallelConnections.toFloat(),
                            onValueChange = { v -> onUpdateSettings { it.copy(parallelConnections = v.roundToInt().coerceIn(1, 8)) } },
                            valueRange = 1f..8f,
                            steps = 6,
                            colors = SliderDefaults.colors(thumbColor = SalviaCyan, activeTrackColor = SalviaIris)
                        )
                    }
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    SettingsSwitchRow(
                        title = t("settings_wifi_only", emptyArray()),
                        subtitle = t("settings_wifi_only_hint", emptyArray()),
                        checked = state.settings.isWifiOnly,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(isWifiOnly = v) } }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    SettingsSwitchRow(
                        title = t("settings_export_to_gallery", emptyArray()),
                        subtitle = t("settings_export_to_gallery_hint", emptyArray()),
                        checked = state.settings.isExportToGallery,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(isExportToGallery = v) } }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    // Download folder picker
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = SalviaGold)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("settings_download_folder", emptyArray()), color = SalviaPearl, fontWeight = FontWeight.Medium)
                                Text(
                                    state.settings.chosenFolderLabel ?: t("settings_download_folder_reset", emptyArray()),
                                    color = SalviaSilverMid,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onPickDownloadFolder,
                                colors = ButtonDefaults.buttonColors(containerColor = SalviaIrisContainer, contentColor = SalviaPearl),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(t("settings_download_folder_pick", emptyArray()), fontSize = 12.sp)
                            }
                            if (state.settings.chosenFolderLabel != null) {
                                OutlinedButton(onClick = onResetDownloadFolder, shape = RoundedCornerShape(10.dp)) {
                                    Text(t("settings_download_folder_reset", emptyArray()), color = SalviaSilverMid, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    // AdBlock
                    SettingsSwitchRow(
                        title = t("settings_ad_block", emptyArray()),
                        subtitle = "${t("settings_ad_block_hint", emptyArray())}\n${
                            t(
                                "settings_ad_block_stats",
                                arrayOf(
                                    "blocked" to state.settings.adBlockBlockedRequests,
                                    "exempt" to state.settings.adBlockDisabledSites.size
                                )
                            )
                        }",
                        checked = state.settings.isAdBlockEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(isAdBlockEnabled = v) } },
                        tag = "settings_switch_adblock"
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    // Locked folder
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = SalviaGold)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("settings_locked_folder", emptyArray()), color = SalviaPearl, fontWeight = FontWeight.Medium)
                                Text(t("settings_locked_folder_hint", emptyArray()), color = SalviaSilverMid, fontSize = 12.sp)
                                Text(
                                    text = if (state.isLockedFolderConfigured) t("settings_locked_folder_secured_note", emptyArray())
                                    else t("settings_locked_folder_not_set", emptyArray()),
                                    color = if (state.isLockedFolderConfigured) SalviaGold else SalviaSilverDeep,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.isLockedFolderConfigured) {
                                Button(
                                    onClick = { activeDialog = SettingsDialogKind.CHANGE_LOCK_PASSWORD },
                                    colors = ButtonDefaults.buttonColors(containerColor = SalviaIrisContainer, contentColor = SalviaPearl),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(t("action_change_password", emptyArray()), fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { activeDialog = SettingsDialogKind.REMOVE_LOCK_PASSWORD },
                                    border = BorderStroke(1.dp, SalviaError.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(t("action_remove_password", emptyArray()), color = SalviaError, fontSize = 12.sp)
                                }
                            } else {
                                Button(
                                    onClick = { activeDialog = SettingsDialogKind.SET_LOCK_PASSWORD },
                                    colors = ButtonDefaults.buttonColors(containerColor = SalviaGold, contentColor = SalviaDeepCosmic),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(t("action_set_password", emptyArray()), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // 3. Appearance section
            item {
                SettingsSectionCard(title = t("settings_appearance", emptyArray())) {
                    SettingsSwitchRow(
                        title = t("settings_dark_pages", emptyArray()),
                        subtitle = t("settings_dark_pages_hint", emptyArray()),
                        checked = state.settings.isDarkPages,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(isDarkPages = v) } }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsSwitchRow(
                        title = t("settings_floating_button_always", emptyArray()),
                        subtitle = t("settings_floating_button_always_hint", emptyArray()),
                        checked = state.settings.isFloatingButtonAlwaysVisible,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(isFloatingButtonAlwaysVisible = v) } }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t("settings_floating_button_size", emptyArray()), color = SalviaPearl, fontWeight = FontWeight.Medium)
                            Text(
                                t("settings_dp_value", arrayOf("size" to state.settings.floatingButtonSize)),
                                color = SalviaCyan,
                                fontFamily = SalviaMonoFontFamily,
                                fontSize = 13.sp
                            )
                        }
                        Slider(
                            value = state.settings.floatingButtonSize.toFloat(),
                            onValueChange = { v -> onUpdateSettings { it.copy(floatingButtonSize = v.roundToInt().coerceIn(44, 76)) } },
                            valueRange = 44f..76f,
                            colors = SliderDefaults.colors(thumbColor = SalviaCyan, activeTrackColor = SalviaIris)
                        )
                    }
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsActionRow(
                        title = t("settings_floating_button_position", emptyArray()),
                        subtitle = t("settings_floating_button_position_hint", emptyArray()),
                        onClick = { onUpdateSettings { it.copy(floatingButtonX = -1f, floatingButtonY = -1f) } }
                    )
                }
            }

            // 4. Privacy section
            item {
                SettingsSectionCard(title = t("settings_privacy", emptyArray())) {
                    SettingsSwitchRow(
                        title = t("settings_allow_cleartext", emptyArray()),
                        subtitle = t("settings_allow_cleartext_hint", emptyArray()),
                        checked = state.settings.isCleartextAllowed,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(isCleartextAllowed = v) } }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsActionRow(
                        title = t("settings_clear_history", emptyArray()),
                        onClick = { activeDialog = SettingsDialogKind.CLEAR_HISTORY }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsActionRow(
                        title = t("settings_clear_cookies", emptyArray()),
                        onClick = { activeDialog = SettingsDialogKind.CLEAR_COOKIES }
                    )
                }
            }

            // 5. About & Diagnostics section
            item {
                SettingsSectionCard(title = t("settings_about", emptyArray())) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = SalviaCyan)
                        Column {
                            Text(t("about_title", emptyArray()), color = SalviaPearl, fontWeight = FontWeight.SemiBold)
                            Text(
                                t("about_version", arrayOf("version" to SalviaConstants.APP_VERSION)),
                                color = SalviaSilverMid,
                                fontSize = 12.sp,
                                fontFamily = SalviaMonoFontFamily
                            )
                        }
                    }
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    val updateSub = when (val u = state.updateCheckState) {
                        UpdateCheckState.Idle -> t("settings_check_updates_hint", emptyArray())
                        UpdateCheckState.Checking -> t("update_checking", emptyArray())
                        UpdateCheckState.UpToDate -> t("update_up_to_date", emptyArray())
                        is UpdateCheckState.Available -> t("update_available_title", arrayOf("version" to u.release.version))
                        is UpdateCheckState.Failed -> "${t("update_check_failed", emptyArray())}: ${u.reason}"
                    }
                    SettingsActionRow(
                        title = t("settings_check_updates", emptyArray()),
                        subtitle = updateSub,
                        icon = {
                            if (state.updateCheckState is UpdateCheckState.Checking) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = SalviaCyan)
                            } else {
                                Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = SalviaIris)
                            }
                        },
                        onClick = onCheckUpdates,
                        tag = "settings_check_updates"
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    SettingsActionRow(
                        title = t("settings_share_diagnostics", emptyArray()),
                        subtitle = t("settings_share_diagnostics_hint", emptyArray()),
                        icon = { Icon(Icons.Filled.Share, contentDescription = null, tint = SalviaCyan) },
                        onClick = onExportDiagnostics
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    val crashSub = if (state.crashReportCount > 0) {
                        t("settings_crash_reports_count", arrayOf("count" to state.crashReportCount))
                    } else {
                        t("settings_crash_reports_none_hint", emptyArray())
                    }
                    SettingsActionRow(
                        title = t("settings_crash_reports", emptyArray()),
                        subtitle = crashSub,
                        icon = { Icon(Icons.Filled.BugReport, contentDescription = null, tint = SalviaGold) },
                        onClick = onShareCrashReports
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))

                    SettingsActionRow(
                        title = t("about_license", emptyArray()),
                        onClick = { onOpenUrl(SalviaConstants.LICENSE_URL) }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsActionRow(
                        title = t("about_privacy_policy", emptyArray()),
                        onClick = { onOpenUrl(SalviaConstants.PRIVACY_POLICY_URL) }
                    )
                    HorizontalDivider(color = SalviaBorder.copy(alpha = 0.5f))
                    SettingsActionRow(
                        title = t("about_source_code", emptyArray()),
                        onClick = { onOpenUrl(SalviaConstants.SOURCE_CODE_URL) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = SalviaCyan,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = SalviaSurface,
            border = BorderStroke(1.dp, SalviaBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SalviaPearl, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = SalviaSilverMid, fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SalviaDeepCosmic,
                checkedTrackColor = SalviaCyan,
                uncheckedThumbColor = SalviaSilverMid,
                uncheckedTrackColor = SalviaElevated
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    titleColor: androidx.compose.ui.graphics.Color = SalviaPearl,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    tag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = SalviaSilverMid, fontSize = 12.sp)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SalviaSilverDeep)
    }
}
