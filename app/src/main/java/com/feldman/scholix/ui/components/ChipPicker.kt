package com.feldman.scholix.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionDropdownTextFit
import com.feldman.motion.MotionDropdownMenuAlignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import com.feldman.motion.MotionDropdown
import com.feldman.motion.MotionDropdownAnimation
import com.feldman.motion.MotionDropdownDefaults
import com.feldman.motion.MotionDropdownDirection
import com.feldman.motion.MotionDropdownLabelPosition
import com.feldman.motion.MotionSymbols
import com.feldman.motion.rememberSymbolPainter

@Composable
fun ChipPicker(
    label: String,
    options: List<String>,
    selected: String,
    onSelectedChange: (String) -> Unit,
    optionIcon: (@Composable (String) -> Painter?)? = null
) {
    val menuWidth = pickerMenuWidth(options, horizontalSpace = if (optionIcon == null) 76.dp else 108.dp)
    val anchorContainer = MaterialTheme.colorScheme.tertiaryContainer
    val anchorContent = MaterialTheme.colorScheme.onTertiaryContainer
    val dropdownColors = MotionDropdownDefaults.tonalColors(
        containerColor = anchorContainer,
        contentColor = anchorContent,
    ).copy(
        textColor = anchorContent,
        expandedTextColor = anchorContent,
        labelColor = anchorContent.copy(alpha = 0.78f),
        expandedLabelColor = anchorContent.copy(alpha = 0.78f),
        leadingIconColor = anchorContent,
        expandedLeadingIconColor = anchorContent,
        trailingIconColor = anchorContent,
        expandedTrailingIconColor = anchorContent,
        menuContainerColor = anchorContainer,
        menuItemTextColor = anchorContent,
        menuItemIconColor = anchorContent,
        menuItemDescriptionColor = anchorContent.copy(alpha = 0.72f),
        selectedMenuItemContainerColor = anchorContent,
        selectedMenuItemTextColor = anchorContainer,
        selectedMenuItemIconColor = anchorContainer,
    )

    MotionDropdown(
        modifier = Modifier,
        options = options,
        selected = selected,
        onSelected = onSelectedChange,
        label = label,
        labelPosition = MotionDropdownLabelPosition.Inside,
        direction = if (isMostlyRtl(selected)) MotionDropdownDirection.Rtl else MotionDropdownDirection.Ltr,
        colors = dropdownColors,
        items = MotionDropdownDefaults.items(icon = optionIcon),
        trailingIcon = rememberSymbolPainter(MotionSymbols.ic_keyboard_arrow_down),
        text = MotionDropdownDefaults.text(value = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), label = MaterialTheme.typography.labelMedium, valueFit = MotionDropdownTextFit.Wrap, valueMaxLines = 2),
        sizes = MotionDropdownDefaults.sizes(menuMinWidth = 112.dp.coerceAtMost(menuWidth), menuMaxWidth = menuWidth),
        // The morph transition can leave a touch-blocking popup after rapid toggles.
        menu = MotionDropdownDefaults.menu(animation = MotionDropdownAnimation.Standard,
            matchAnchorWidth = false,
            alignment = MotionDropdownMenuAlignment.Center,
            showSelectedCheck = true,
            checkIcon = rememberSymbolPainter(MotionSymbols.ic_check),
        )
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
