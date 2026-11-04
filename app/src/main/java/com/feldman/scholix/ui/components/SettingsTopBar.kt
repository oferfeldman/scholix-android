package com.feldman.scholix.ui.components

import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.feldman.motion.MotionBackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    chromeColor: Color = colorScheme.surfaceContainer,
    chromeContentColor: Color = colorScheme.onSurface,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                com.feldman.scholix.LocalScopeControl.current?.label?.takeIf { title != "Home" && it != title }?.let { course ->
                    Text(course, style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                MotionBackButton(
                    onClick = onBack,
                    containerColor = colorScheme.surface,
                    contentColor = colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = chromeColor, titleContentColor = chromeContentColor),
        actions = {
            com.feldman.scholix.PlatformSwitcherButton()
            actions()
        }
    )
}
