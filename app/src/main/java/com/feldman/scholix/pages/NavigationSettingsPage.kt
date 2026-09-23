package com.feldman.scholix.pages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.feldman.motion.MotionScaffold
import com.feldman.motion.isDarkTheme
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.storage.defaultNavbarPages
import com.feldman.scholix.storage.navbarPagesFlow
import com.feldman.scholix.storage.setNavbarPages
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.SettingsCategoryColor
import kotlinx.coroutines.launch

@Composable
fun NavigationSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by remember(context) { context.navbarPagesFlow() }.collectAsState(initial = defaultNavbarPages)
    val pages = LocalAppState.current.navigationPages
    val selected = saved.intersect(pages.map { it.label }.toSet())
    val isDark = isDarkTheme()
    val categoryColor = SettingsCategoryColor.NAVIGATION.container(isDark)
    val categoryContentColor = SettingsCategoryColor.NAVIGATION.content(isDark)

    MotionScaffold(
        scaffoldModifier = Modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                title = "Navigation",
                onBack = onBack,
                chromeColor = categoryColor,
                chromeContentColor = categoryContentColor
            )
        }
    ) {
        item { Text("Choose up to four pages for the navbar. Other pages stay in More.") }
        section {
            pages.forEach { page ->
                val checked = page.label in selected
                val isMaxSelected = selected.size >= 4 && !checked
                val itemEnabled = if (checked) selected.size > 1 else !isMaxSelected
                switchItem(
                    key = page.label,
                    title = page.label,
                    icon = painterResource(page.filledIcon),
                    checked = checked,
                    enabled = itemEnabled,
                    containerColor = if (isMaxSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f) else null,
                    backgroundColor = if (isMaxSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary,
                    iconColor = if (isMaxSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onPrimary,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            context.setNavbarPages(if (enabled) selected + page.label else selected - page.label)
                        }
                    }
                )
            }
        }
        section {
            pageItem(title = "Restore defaults", onClick = {
                scope.launch { context.setNavbarPages(defaultNavbarPages) }
            })
        }
    }
}
