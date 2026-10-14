package com.feldman.scholix.ui.components

import androidx.compose.ui.graphics.Color

/** Stable tonal pairs that identify each settings category in light and dark themes. */
enum class SettingsCategoryColor(
    private val darkContainer: Long,
    private val darkContent: Long,
    private val lightContainer: Long,
    private val lightContent: Long
) {
    APPEARANCE(0xFF7D5260, 0xFFFFD8E4, 0xFFFFD8E4, 0xFF631835),
    NAVIGATION(0xFF5B3F72, 0xFFEBD7FF, 0xFFEBD7FF, 0xFF4D2670),
    PLATFORMS(0xFF004A77, 0xFFC9E6FF, 0xFFC9E6FF, 0xFF001E2F),
    PORTIONS(0xFF005047, 0xFFA4F2E1, 0xFFA4F2E1, 0xFF00201C),
    SYSTEM(0xFF4A4458, 0xFFE8DEF8, 0xFFE8DEF8, 0xFF4A4458);

    fun container(isDark: Boolean): Color = Color(if (isDark) darkContainer else lightContainer)
    fun content(isDark: Boolean): Color = Color(if (isDark) darkContent else lightContent)
}

/** Fixed Material tonal pairs used to give each platform account a stable visual identity. */
enum class AccountIconColor(
    private val darkContainer: Long,
    private val darkContent: Long,
    private val lightContainer: Long,
    private val lightContent: Long
) {
    ROSE(0xFF7D5260, 0xFFFFD8E4, 0xFFFFD8E4, 0xFF631835),
    AMBER(0xFF6C4E00, 0xFFFFDF9E, 0xFFFFDF9E, 0xFF412D00),
    GREEN(0xFF324F34, 0xFFCBEFD0, 0xFFCBEFD0, 0xFF042106),
    BLUE(0xFF004A77, 0xFFC9E6FF, 0xFFC9E6FF, 0xFF001E2F),
    PURPLE(0xFF4F378B, 0xFFEADDFF, 0xFFEADDFF, 0xFF21005D),
    TEAL(0xFF005047, 0xFFA4F2E1, 0xFFA4F2E1, 0xFF00201C),
    ORANGE(0xFF723600, 0xFFFFDBC8, 0xFFFFDBC8, 0xFF2F1500),
    CYAN(0xFF004E5B, 0xFFA1EEFF, 0xFFA1EEFF, 0xFF001F26);

    fun container(isDark: Boolean): Color = Color(if (isDark) darkContainer else lightContainer)
    fun content(isDark: Boolean): Color = Color(if (isDark) darkContent else lightContent)
}
