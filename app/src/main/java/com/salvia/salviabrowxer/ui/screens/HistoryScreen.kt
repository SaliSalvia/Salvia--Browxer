package com.salvia.salviabrowxer.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.salvia.salviabrowxer.data.HistoryItem
import com.salvia.salviabrowxer.ui.components.ScreenHeader
import com.salvia.salviabrowxer.ui.theme.SalviaBlack
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaCyanContainer
import com.salvia.salviabrowxer.ui.theme.SalviaDeepCosmic
import com.salvia.salviabrowxer.ui.theme.SalviaError
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverDeep
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSurface

@Composable
fun HistoryScreen(
    history: List<HistoryItem>,
    showBackButton: Boolean,
    t: (String) -> String,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val filtered = remember(history, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) history else history.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = SalviaSurface,
            titleContentColor = SalviaPearl,
            textContentColor = SalviaSilverMid,
            title = { Text(t("settings_clear_history")) },
            text = { Text(t("clear_history_confirm")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearAll()
                    }
                ) {
                    Text(t("action_clear"), color = SalviaError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(t("action_cancel"), color = SalviaSilverMid)
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
            title = t("history"),
            backContentDescription = t("go_back"),
            onBack = onBack,
            showBackButton = showBackButton,
            actions = {
                if (history.isNotEmpty()) {
                    IconButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.size(48.dp).testTag("history_clear_all")
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = t("settings_clear_history"), tint = SalviaError)
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(t("history_search_hint"), color = SalviaSilverDeep) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = SalviaSilverMid) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = t("address_clear"), tint = SalviaSilverMid)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("history_search_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SalviaIris,
                    unfocusedBorderColor = SalviaBorder,
                    focusedTextColor = SalviaPearl,
                    unfocusedTextColor = SalviaPearl,
                    focusedContainerColor = SalviaDeepCosmic,
                    unfocusedContainerColor = SalviaDeepCosmic
                )
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(SalviaCyanContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(30.dp))
                        }
                        Text(
                            text = t(if (history.isEmpty()) "no_history" else "no_history_match"),
                            color = SalviaSilverMid,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filtered, key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SalviaSurface,
                            border = BorderStroke(1.dp, SalviaBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUrl(item.url) }
                                .testTag("history_item_${item.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Filled.History, contentDescription = null, tint = SalviaCyan)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = SalviaPearl,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SalviaSilverMid,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveItem(item.id) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = t("action_delete"), tint = SalviaSilverDeep)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
