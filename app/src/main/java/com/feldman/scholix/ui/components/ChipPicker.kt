package com.feldman.scholix.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionDropdown
import com.feldman.motion.MotionDropdownDefaults
import com.feldman.motion.MotionDropdownDirection
import com.feldman.motion.MotionDropdownLabelPosition

@Composable
fun ChipPicker(
    label: String,
    options: List<String>,
    selected: String,
    onSelectedChange: (String) -> Unit,
    optionIcon: (@Composable (String) -> Painter?)? = null
) {
    MotionDropdown(
        modifier = Modifier,
        options = options,
        selected = selected,
        onSelected = onSelectedChange,
        label = label,
        labelPosition = MotionDropdownLabelPosition.Inside,
        direction = if (isMostlyRtl(selected)) MotionDropdownDirection.Rtl else MotionDropdownDirection.Ltr,
        colors = MotionDropdownDefaults.colors(
            expandedTextColor = MaterialTheme.colorScheme.onTertiaryContainer,
            expandedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            expandedTrailingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
            menuContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            menuItemTextColor = MaterialTheme.colorScheme.onTertiaryContainer,
            menuItemIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
            selectedMenuItemContainerColor = MaterialTheme.colorScheme.onTertiaryContainer,
            selectedMenuItemTextColor = MaterialTheme.colorScheme.tertiaryContainer,
            selectedMenuItemIconColor = MaterialTheme.colorScheme.tertiaryContainer,
            expandedBorderColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        items = MotionDropdownDefaults.items(icon = optionIcon),
        text = MotionDropdownDefaults.text(value = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), label = MaterialTheme.typography.labelMedium),
        menu = MotionDropdownDefaults.menu(showSelectedCheck = true)
    )
}

private fun isMostlyRtl(text: String): Boolean {
    var rtl = 0
    var ltr = 0
    text.forEach { char ->
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
        }
    }
    return rtl > ltr
}
