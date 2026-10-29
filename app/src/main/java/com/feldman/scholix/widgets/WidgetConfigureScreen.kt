package com.feldman.scholix.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionSymbols
import com.feldman.scholix.api.Platform
import com.feldman.scholix.ui.components.ProviderPickerBar
import com.feldman.scholix.ui.components.SettingsTopBar

@Composable
internal fun WidgetConfigureScreen(
    title: String,
    providers: List<Platform>,
    initialProviderId: String?,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (Platform) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf(initialProviderId ?: providers.firstOrNull()?.id) }
    var expanded by remember { mutableStateOf(false) }
    val selected = providers.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    MotionScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { SettingsTopBar(title = title, onBack = onBack) },
        scrollable = false,
        bottomBarClearance = false,
        bottomBar = {
            MotionButton(text = if (saving) "Saving\u2026" else "Save", icon = MotionSymbols.ic_save,
                onClick = { providers.getOrNull(selected)?.let(onSave) },
                enabled = !saving && providers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        },
    ) {
        Spacer(Modifier.height(16.dp))
        if (providers.isNotEmpty()) ProviderPickerBar(
            providers = providers, selectedIndex = selected,
            onSelected = { if (!saving) selectedId = providers[it].id },
            expanded = expanded && !saving,
            onExpandedChange = { if (!saving) expanded = it },
        )
    }
}
