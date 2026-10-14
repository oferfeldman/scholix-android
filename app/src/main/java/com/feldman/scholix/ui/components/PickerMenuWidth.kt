package com.feldman.scholix.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun pickerMenuWidth(
    labels: List<String>,
    descriptions: List<String> = emptyList(),
    horizontalSpace: Dp = 76.dp,
): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
    val descriptionStyle = MaterialTheme.typography.bodySmall
    val textWidth = remember(labels, descriptions, measurer, labelStyle, descriptionStyle) {
        val labelWidths = labels.flatMap { it.lines() }.map {
            measurer.measure(AnnotatedString(it), labelStyle, softWrap = false).size.width
        }
        val descriptionWidths = descriptions.flatMap { it.lines() }.map {
            measurer.measure(AnnotatedString(it), descriptionStyle, softWrap = false).size.width
        }
        (labelWidths + descriptionWidths).maxOrNull() ?: 0
    }
    val availableWidth = (with(density) { LocalWindowInfo.current.containerSize.width.toDp() } - 32.dp).coerceAtLeast(1.dp)
    return (with(density) { textWidth.toDp() } + horizontalSpace)
        .coerceAtLeast(112.dp).coerceAtMost(availableWidth)
}
