package com.feldman.scholix.ui.components

import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.feldman.motion.MotionBackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    chromeColor: Color = colorScheme.surfaceContainer,
    chromeContentColor: Color = colorScheme.onSurface
) {
    CenterAlignedTopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (onBack != null) {
                MotionBackButton(
                    onClick = onBack,
                    containerColor = colorScheme.surface,
                    contentColor = colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = chromeColor, titleContentColor = chromeContentColor)
    )
}
