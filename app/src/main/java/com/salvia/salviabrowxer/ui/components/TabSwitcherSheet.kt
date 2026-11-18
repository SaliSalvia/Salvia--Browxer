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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.data.BrowserTab
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaIrisContainer
import com.salvia.salviabrowxer.ui.theme.SalviaIrisLight
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaPink
import com.salvia.salviabrowxer.ui.theme.SalviaSilverDeep
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcherSheet(
    tabs: List<BrowserTab>,
    currentTabId: String,
    t: (String) -> String,
    onDismiss: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onDuplicateTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val normalTabs = tabs.filter { !it.isPrivate }
    val privateTabs = tabs.filter { it.isPrivate }

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
                Text(
                    text = t("tab_overview"),
                    style = MaterialTheme.typography.titleLarge,
                    color = SalviaPearl
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = t("action_dismiss"), tint = SalviaSilverMid)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onNewTab,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("switcher_new_tab"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(t("new_tab"), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onNewPrivateTab,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("switcher_new_private_tab"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SalviaPink.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SalviaPink)
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(t("new_private_tab"), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            HorizontalDivider(color = SalviaBorder.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(normalTabs, key = { it.id }) { tab ->
                    TabCardRow(
                        tab = tab,
                        isSelected = tab.id == currentTabId,
                        t = t,
                        onSelect = { onSelectTab(tab.id) },
                        onDuplicate = { onDuplicateTab(tab.id) },
                        onClose = { onCloseTab(tab.id) }
                    )
                }

                if (privateTabs.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = t("private_browsing"),
                                style = MaterialTheme.typography.titleSmall,
                                color = SalviaPink
                            )
                            Text(
                                text = t("private_tabs_disclosure"),
                                style = MaterialTheme.typography.bodySmall,
                                color = SalviaSilverMid
                            )
                        }
                    }
                    items(privateTabs, key = { it.id }) { tab ->
                        TabCardRow(
                            tab = tab,
                            isSelected = tab.id == currentTabId,
                            t = t,
                            onSelect = { onSelectTab(tab.id) },
                            onDuplicate = { onDuplicateTab(tab.id) },
                            onClose = { onCloseTab(tab.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCardRow(
    tab: BrowserTab,
    isSelected: Boolean,
    t: (String) -> String,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) SalviaIrisContainer else SalviaElevated,
        border = BorderStroke(
            1.dp,
            when {
                isSelected -> SalviaIris
                tab.isPrivate -> SalviaPink.copy(alpha = 0.45f)
                else -> SalviaBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("tab_item_${tab.id}")
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (tab.isPrivate) Icons.Filled.Security else Icons.Filled.Language,
                contentDescription = null,
                tint = when {
                    tab.isPrivate -> SalviaPink
                    isSelected -> SalviaCyan
                    else -> SalviaIrisLight
                },
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = tab.title.ifBlank { tab.url },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = SalviaPearl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (tab.isSleeping) {
                        Surface(shape = RoundedCornerShape(6.dp), color = SalviaGold.copy(alpha = 0.16f)) {
                            Text(
                                text = t("tab_hibernated"),
                                fontSize = 10.sp,
                                color = SalviaGold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = tab.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = SalviaSilverDeep,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDuplicate, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = t("duplicate_tab"), tint = SalviaSilverMid, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = t("close_tab"), tint = SalviaSilverMid, modifier = Modifier.size(18.dp))
            }
        }
    }
}
