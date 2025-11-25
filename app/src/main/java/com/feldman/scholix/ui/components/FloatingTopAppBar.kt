package com.feldman.scholix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        windowInsets = windowInsets,
        colors = colors,
        scrollBehavior = scrollBehavior,
        expandedHeight = expandedHeight,
        title = {
            ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                Box(
                    modifier = Modifier.height(IntrinsicSize.Min)
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = 1.2f
                                scaleY = 1.2f
                            }
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(percent = 50)
                            )
                            .align(Alignment.Center)
                    )

                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            title()
                        }
                    }
                }
            }
        },
        navigationIcon = {
            FloatingIcon {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    navigationIcon()
                }
            }
        },
        actions = {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                FloatingActions(spacing = 8.dp) {
                    this.actions()
                }
            }
        }

    )
}
@Composable
private fun FloatingBubble(
    slotSize: Dp = 48.dp,     // total slot
    bubbleSize: Dp = 40.dp,   // blurred circle/pill
    shapeCircle: Boolean = true,
    content: @Composable () -> Unit
) {
    SubcomposeLayout { constraints ->
        val contentMeasurables = subcompose("content", content)
        if (contentMeasurables.isEmpty()) return@SubcomposeLayout layout(0, 0) {}

        val placeables = contentMeasurables.map { it.measure(constraints) }
        val hasSize = placeables.any { it.width > 0 || it.height > 0 }
        if (!hasSize) return@SubcomposeLayout layout(0, 0) {}

        val slot = slotSize.roundToPx()
        val bubblePx = bubbleSize.roundToPx()

        val bgPlaceables = subcompose("bg") {
            Box(
                Modifier
                    .size(bubbleSize)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = if (shapeCircle) CircleShape else RoundedCornerShape(percent = 50)
                    )
            )
        }.map { it.measure(constraints) }

        val contentW = placeables.maxOf { it.width }
        val contentH = placeables.maxOf { it.height }

        layout(slot, slot) {
            val bx = (slot - bubblePx) / 2
            val by = (slot - bubblePx) / 2
            bgPlaceables.forEach { it.place(bx, by) }

            val ix = (slot - contentW) / 2
            val iy = (slot - contentH) / 2
            placeables.forEach { it.place(ix, iy) }
        }
    }
}


@Composable
private fun FloatingIcon(iconContent: @Composable () -> Unit) {
    FloatingBubble(
        slotSize = 48.dp,
        bubbleSize = 40.dp,
        shapeCircle = true,
        content = iconContent
    )
}

@Composable
fun FloatingActions(
    spacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        content()
    }
}

@Composable
fun FloatingAction(content: @Composable () -> Unit) {
    FloatingBubble(
        slotSize = 48.dp,
        bubbleSize = 40.dp,
        shapeCircle = true,
        content = content
    )
}
@Composable
fun FloatingActionsGroup(
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    contentSpacing: Dp = 6.dp,
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shape = RoundedCornerShape(percent = 50)
            )
            .blur(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(contentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingSearchTopBar(
    textFieldState: TextFieldState,
    searchResults: List<String>,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current

    SearchBar(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        expanded = false,
        onExpandedChange = { expanded = it },
        inputField = {
            SearchBarDefaults.InputField(
                query = textFieldState.text.toString(),
                onQueryChange = { textFieldState.edit { replace(0, length, it) } },
                onSearch = {
                    onSearch(textFieldState.text.toString())
                    keyboardController?.hide()
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                placeholder = { Text("Search tools…") },
                leadingIcon = {
                    IconButton(
                        onClick = {}
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                trailingIcon = {
                    if (textFieldState.text.toString().isNotEmpty()) {
                        IconButton(
                            onClick = {
                                textFieldState.edit { replace(0, length, "") }
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) {

    }



}