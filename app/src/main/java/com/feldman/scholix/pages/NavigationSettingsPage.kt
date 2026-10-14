package com.feldman.scholix.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionSymbols
import com.feldman.motion.isMotionDarkTheme
import com.feldman.motion.rememberSymbolPainter
import com.feldman.scholix.AppDest
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.storage.defaultNavigationOrder
import com.feldman.scholix.storage.navigationOrderFlow
import com.feldman.scholix.storage.setNavigationOrder
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.SettingsCategoryColor
import kotlinx.coroutines.launch

@Composable
fun NavigationSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by remember(context) { context.navigationOrderFlow() }.collectAsState(initial = defaultNavigationOrder)
    val pages = LocalAppState.current.navigationPages
    var order by remember(saved) { mutableStateOf(saved) }
    val orderedPages = pages.filterNot { it == AppDest.Settings }.sortedBy { order.indexOf(it.label) } + pages.filter { it == AppDest.Settings }
    val isDark = isMotionDarkTheme()
    val categoryColor = SettingsCategoryColor.NAVIGATION.container(isDark)
    val categoryContentColor = SettingsCategoryColor.NAVIGATION.content(isDark)

    fun move(label: String, adjacentLabel: String) {
        val updated = order.toMutableList()
        val from = updated.indexOf(label)
        val to = updated.indexOf(adjacentLabel)
        updated[from] = adjacentLabel
        updated[to] = label
        order = updated
        scope.launch { context.setNavigationOrder(updated) }
    }

    MotionScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                title = "Navigation", onBack = onBack,
                chromeColor = categoryColor, chromeContentColor = categoryContentColor,
            )
        }
    ) {
        Item { Text("Set the page order. The selected page stays visible. Settings stays at the end; when the bar is full, an overflow page temporarily takes its slot. The rest remain in More.") }
        Section {
            orderedPages.forEachIndexed { index, page ->
                Item(key = page.label) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(page.filledIcon), null, Modifier.size(24.dp))
                        Text(page.label, Modifier.weight(1f))
                        IconButton(
                            enabled = index > 0 && page != AppDest.Settings,
                            onClick = { move(page.label, orderedPages[index - 1].label) },
                        ) {
                            Icon(rememberSymbolPainter(MotionSymbols.ic_arrow_upward), "Move ${page.label} up")
                        }
                        IconButton(
                            enabled = index < orderedPages.lastIndex - 1,
                            onClick = { move(page.label, orderedPages[index + 1].label) },
                        ) {
                            Icon(rememberSymbolPainter(MotionSymbols.ic_arrow_downward), "Move ${page.label} down")
                        }
                    }
                }
            }
        }
        Section {
            PageItem(title = "Restore defaults", onClick = {
                order = defaultNavigationOrder
                scope.launch { context.setNavigationOrder(defaultNavigationOrder) }
            })
        }
    }
}
