package com.salvia.salviabrowxer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salvia.salviabrowxer.data.UiRoute
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaDeepCosmic
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid

@Composable
fun BrowserNavRail(
    currentRoute: UiRoute,
    tabsCount: Int,
    activeDownloadsCount: Int,
    completedDownloadsCount: Int,
    t: (String) -> String,
    onNavigate: (UiRoute) -> Unit
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .background(SalviaDeepCosmic)
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = SalviaDeepCosmic,
        header = {
            OrbitalBrandMark(
                size = 36.dp,
                showRing = true,
                contentDescription = t("app_name"),
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
            )
        }
    ) {
        val itemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = SalviaPearl,
            selectedTextColor = SalviaPearl,
            indicatorColor = SalviaElevated,
            unselectedIconColor = SalviaSilverMid,
            unselectedTextColor = SalviaSilverMid
        )

        NavigationRailItem(
            selected = currentRoute == UiRoute.Home,
            onClick = { onNavigate(UiRoute.Home) },
            icon = { Icon(Icons.Outlined.Home, contentDescription = t("home")) },
            label = { Text(t("home"), fontSize = 10.sp) },
            colors = itemColors,
            modifier = Modifier.testTag("rail_home")
        )
        NavigationRailItem(
            selected = currentRoute == UiRoute.Browser,
            onClick = { onNavigate(UiRoute.Browser) },
            icon = {
                BadgedBox(
                    badge = {
                        if (tabsCount > 0) {
                            Badge(containerColor = SalviaIris, contentColor = SalviaPearl) {
                                Text(tabsCount.toString())
                            }
                        }
                    }
                ) {
                    Icon(Icons.Outlined.Language, contentDescription = t("browser_title"))
                }
            },
            label = { Text(t("browser_title"), fontSize = 10.sp) },
            colors = itemColors,
            modifier = Modifier.testTag("rail_browser")
        )
        NavigationRailItem(
            selected = currentRoute == UiRoute.Downloads || currentRoute == UiRoute.MediaPlayer,
            onClick = { onNavigate(UiRoute.Downloads) },
            icon = {
                val badgeCount = if (activeDownloadsCount > 0) activeDownloadsCount else completedDownloadsCount
                BadgedBox(
                    badge = {
                        if (badgeCount > 0) {
                            Badge(
                                containerColor = if (activeDownloadsCount > 0) SalviaCyan else SalviaIris,
                                contentColor = if (activeDownloadsCount > 0) SalviaDeepCosmic else SalviaPearl
                            ) {
                                Text(badgeCount.toString())
                            }
                        }
                    }
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = t("downloads"))
                }
            },
            label = { Text(t("downloads"), fontSize = 10.sp) },
            colors = itemColors,
            modifier = Modifier.testTag("rail_downloads")
        )
        NavigationRailItem(
            selected = currentRoute == UiRoute.Bookmarks,
            onClick = { onNavigate(UiRoute.Bookmarks) },
            icon = { Icon(Icons.Outlined.Bookmarks, contentDescription = t("bookmarks")) },
            label = { Text(t("bookmarks"), fontSize = 10.sp) },
            colors = itemColors,
            modifier = Modifier.testTag("rail_bookmarks")
        )
        NavigationRailItem(
            selected = currentRoute == UiRoute.History,
            onClick = { onNavigate(UiRoute.History) },
            icon = { Icon(Icons.Outlined.History, contentDescription = t("history")) },
            label = { Text(t("history"), fontSize = 10.sp) },
            colors = itemColors,
            modifier = Modifier.testTag("rail_history")
        )
        Spacer(modifier = Modifier.weight(1f))
        NavigationRailItem(
            selected = currentRoute == UiRoute.Settings,
            onClick = { onNavigate(UiRoute.Settings) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = t("settings")) },
            label = { Text(t("settings"), fontSize = 10.sp) },
            colors = itemColors,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .testTag("rail_settings")
        )
    }
}
