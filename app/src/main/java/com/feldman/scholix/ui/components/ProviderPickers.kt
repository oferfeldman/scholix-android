package com.feldman.scholix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionPageSettingsItem
import com.feldman.motion.MotionSegmentedPicker
import com.feldman.motion.rememberSymbolPainter
import com.feldman.motion.MotionItemPosition
import com.feldman.scholix.api.PlatformInfo

/** Corner treatment for a row at [index] of a connected group of [count] rows. */
private fun groupPosition(index: Int, count: Int): MotionItemPosition = when {
    count == 1 -> MotionItemPosition.Alone
    index == 0 -> MotionItemPosition.Start
    index == count - 1 -> MotionItemPosition.End
    else -> MotionItemPosition.Middle
}

/**
 * The connected list of sign-in providers, shared by the login page and the
 * "add provider" sheet in settings.
 */
@Composable
fun ProviderPickerList(
    providers: List<PlatformInfo>,
    onSelect: (PlatformInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        providers.forEachIndexed { index, provider ->
            val icon = provider.iconSymbol
                ?.let { rememberSymbolPainter(name = it, fill = 1f) }
                ?: painterResource(provider.iconRes)

            MotionCard(
                position = groupPosition(index, providers.size),
                contentPadding = 0.dp
            ) {
                MotionPageSettingsItem(
                    title = provider.name,
                    description = null,
                    icon = icon,
                    onClick = { onSelect(provider) }
                )
            }
        }
    }
}

private val WEBTOP_LOGIN_METHODS = listOf("password", "moe")

/**
 * Webtop accepts either its own password or the Ministry of Education SSO.
 * [state] holds the raw method key ("password" or "moe").
 */
@Composable
fun WebtopLoginMethodPicker(
    state: MutableState<String>,
    onSelectedChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    MotionSegmentedPicker(
        options = listOf("Webtop password", "Ministry of Education"),
        icons = null,
        selectedIndex = WEBTOP_LOGIN_METHODS.indexOf(state.value).coerceAtLeast(0),
        onSelected = { index ->
            val method = WEBTOP_LOGIN_METHODS[index]
            if (state.value != method) {
                state.value = method
                onSelectedChange(method)
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}
