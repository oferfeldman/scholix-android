package com.feldman.scholix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import com.feldman.motion.MotionDropdown
import com.feldman.motion.MotionDropdownDefaults
import com.feldman.motion.MotionDropdownDirection
import com.feldman.motion.MotionDropdownMenuAlignment
import com.feldman.motion.MotionDropdownTextFit
import com.feldman.motion.MotionSymbols
import com.feldman.motion.rememberSymbolPainter
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.platformOptions
import com.feldman.scholix.pages.isRtlText

/**
 * The app's standard "‹ [item] ›" picker: chevrons to step through the list and a
 * dropdown to jump straight to one.
 *
 * Shared so every page that picks between things -- courses on Grades, providers
 * on Schedule -- looks and behaves the same instead of drifting apart.
 */
@Composable
fun <T> PickerBar(
    options: List<T>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    optionLabel: (T) -> String,
    optionKey: (T) -> String,
    contentDescription: String,
    previousDescription: String,
    nextDescription: String,
    modifier: Modifier = Modifier,
    optionDescription: (T) -> String = { "" },
    optionIcon: @Composable (T) -> Unit = {},
) {
    val selected = options.getOrNull(selectedIndex) ?: return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MotionButton(
            icon = MotionSymbols.ic_chevron_left,
            onClick = { onSelected(selectedIndex - 1) },
            enabled = selectedIndex > 0,
            modifier = Modifier.semantics { this.contentDescription = previousDescription },
            width = 48.dp,
            height = 64.dp,
            iconSize = 32.dp,
            defaultState = MotionButtonState(
                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        )

        MotionDropdown(
            options = options,
            selected = selected,
            onSelected = { option -> onSelected(options.indexOf(option)) },
            optionLabel = optionLabel,
            optionDescription = { option -> optionDescription(option).takeIf { it.isNotBlank() } },
            optionKey = optionKey,
            leadingContent = { optionIcon(selected) },
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            shape = RoundedCornerShape(28.dp),
            colors = MotionDropdownDefaults.tonalColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ).copy(
                menuContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                menuItemTextColor = MaterialTheme.colorScheme.onSurface,
                menuItemIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                menuItemDescriptionColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            ),
            borderWidth = 0.dp,
            minHeight = 64.dp,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            valueTextFit = MotionDropdownTextFit.Wrap,
            valueMaxLines = 2,
            menuMaxHeight = 420.dp,
            menuShape = RoundedCornerShape(28.dp),
            menuItemShape = RoundedCornerShape(24.dp),
            menuMinWidth = 320.dp,
            menuMaxWidth = 360.dp,
            menuMatchAnchorWidth = false,
            menuAlignment = MotionDropdownMenuAlignment.Center,
            direction = MotionDropdownDirection.Auto,
            contentDescription = contentDescription,
            optionContent = { option, isSelected ->
                val name = optionLabel(option)
                val supportingText = optionDescription(option)
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isRtlText(name)) {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        optionIcon(option)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (supportingText.isNotBlank()) {
                                Text(
                                    text = supportingText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (isSelected) {
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                painter = rememberSymbolPainter(MotionSymbols.ic_check),
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
        )

        MotionButton(
            icon = MotionSymbols.ic_chevron_right,
            onClick = { onSelected(selectedIndex + 1) },
            enabled = selectedIndex < options.lastIndex,
            modifier = Modifier.semantics { this.contentDescription = nextDescription },
            width = 48.dp,
            height = 64.dp,
            iconSize = 32.dp,
            defaultState = MotionButtonState(
                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        )
    }
}

/** The provider's product name ("Webtop", "Mashov"), not the account holder's. */
fun Platform.providerLabel(): String {
    if (platformDisplayName.isNotBlank() && !platformDisplayName.endsWith("Platform")) {
        return platformDisplayName
    }
    return platformOptions.firstOrNull { it.factory()::class == this::class }?.name
        ?: javaClass.simpleName.removeSuffix("Platform")
}

/** The provider's logo as a painter, or null if it has none. */
@Composable
fun providerPainter(provider: Platform): Painter? {
    val info = platformOptions.firstOrNull { it.factory()::class == provider::class }
    return info?.iconSymbol?.let { rememberSymbolPainter(name = it, fill = 1f) }
        ?: info?.let { painterResource(it.iconRes) }
}

/** The provider's logo, sized for [PickerBar]. */
@Composable
fun ProviderIcon(provider: Platform) {
    SubjectIcon(
        subject = provider.providerLabel(),
        painter = providerPainter(provider),
        size = 40.dp,
        iconSize = 22.dp
    )
}

/**
 * The provider picker used by Grades, Schedule and Attendance so all three sit in
 * the same place and behave identically.
 */
@Composable
fun ProviderPickerBar(
    providers: List<Platform>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    PickerBar(
        options = providers,
        selectedIndex = selectedIndex,
        onSelected = { onSelected(it.coerceIn(0, providers.lastIndex)) },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        optionLabel = { it.providerLabel() },
        optionDescription = { it.getName() },
        optionKey = { it.id },
        optionIcon = { ProviderIcon(it) },
        contentDescription = "Choose provider",
        previousDescription = "Previous provider",
        nextDescription = "Next provider",
        modifier = modifier
    )
}

