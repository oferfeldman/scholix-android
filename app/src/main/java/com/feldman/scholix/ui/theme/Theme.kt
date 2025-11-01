package com.feldman.lockerapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
private val DarkColors = darkColorScheme(
    primary = Color(0xFF54B3FF),
    secondary = Color(0xFF47CB4C),
    tertiary = Color(0xFFEC991F),
    errorContainer = Color(0xFFDA3124),
    primaryContainer = Color(0xFF0D6CB7),
    surfaceVariant = Color(0xFF252525),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2196F3),
    secondary = Color(0xFF40B043),
    tertiary = Color(0xFFEC991F),
    errorContainer = Color(0xFFDA3124),
    primaryContainer = Color(0xFF0D6CB7),
)

@Composable
fun darkColors(): ColorScheme{
    return DarkColors
}

@Composable
fun AppTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme =
        if (useDarkTheme) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}