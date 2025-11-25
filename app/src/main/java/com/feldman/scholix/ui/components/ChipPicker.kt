package com.feldman.scholix.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipPicker(
    label: String,
    options: List<String>,
    selected: String,
    onSelectedChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var chipWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "arrowRotation"
    )

    // Animate the bottom corners only
    val bottomRadius by animateDpAsState(
        targetValue = if (expanded) 12.dp else 28.dp,
        animationSpec = tween(200),
        label = "bottomRadius"
    )

    // Top corners stay constant
    val chipShape = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomStart = bottomRadius,
        bottomEnd = bottomRadius
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = chipShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onGloballyPositioned { chipWidthPx = it.size.width }
                .clickable { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 32.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        selected,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .width(with(density) { chipWidthPx.toDp() })
                .align(Alignment.BottomCenter),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    leadingIcon = {
                        if (option == selected)
                            Icon(Icons.Default.Check, contentDescription = null)
                    },
                    onClick = {
                        onSelectedChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}