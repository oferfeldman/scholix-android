@file:Suppress("PROPERTY_WONT_BE_SERIALIZED")

package com.feldman.scholix

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.feldman.motion.*
import com.feldman.scholix.courses.courseScope
import com.feldman.scholix.courses.matches
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

data class ScopeControl(val label: String?, val open: () -> Unit)
val LocalScopeControl = compositionLocalOf<ScopeControl?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSwitcherButton(modifier: Modifier = Modifier) {
    val scope = LocalScopeControl.current ?: return
    TooltipBox(modifier = modifier, positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("All platforms") } }, state = rememberTooltipState()) {
        IconButton(onClick = scope.open, modifier = Modifier.motionBottomSheetAnchor()) {
            Icon(Icons.Rounded.Apps, contentDescription = "All platforms")
        }
    }
}

@Serializable
@Parcelize
data class ScopeSwitcher(val origin: AppDest = AppDest.Home) : AppDest() {
    @IgnoredOnParcel @Transient override val label = "All platforms"
    @IgnoredOnParcel @Transient override val parent: MotionDest = origin
    @IgnoredOnParcel @Transient override val pane = MotionPaneType.BOTTOM_SHEET
    @IgnoredOnParcel @Transient override val showNavigation = false
    @IgnoredOnParcel @Transient override val bottomSheetEffects = MotionBottomSheetEffects(
        backdropScope = MotionBottomSheetBackdropScope.FULL_SCREEN)

    @Composable
    override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String,
                         onFabAction: ((() -> Unit) -> Unit) -> Unit) {
        val state = LocalAppState.current
        val gradeProviders = state.platforms.filter { it.suportsGrades }
        fun open(destination: AppDest) { onBack(); state.onScopeSelected(destination) }
        MotionScaffold(fitContentHeight = true) {
            if (gradeProviders.isNotEmpty()) {
            Title("Provider grades")
            Section {
                gradeProviders.forEach { provider ->
                    PageItem(title = provider.platformDisplayName.ifBlank { provider.getName() },
                        description = provider.getUsername().ifBlank { provider.getName() },
                        onClick = { open(ProviderGrades(provider.id)) })
                }
            }
            }
            Title("All Scholix")
            Section {
                PageItem(title = "Home", onClick = { open(AppDest.Home) })
                if (state.platforms.any { it.suportsGrades }) {
                    PageItem(title = "All grades", onClick = { open(AppDest.Grades) })
                }
                if (state.platforms.any { it.supportsSchedule }) PageItem(title = "Schedule", onClick = { open(AppDest.Schedule) })
                PageItem(title = "Messages", onClick = { open(AppDest.Messages) })
                PageItem(title = "All assignments", onClick = { open(AppDest.Homework) })
                PageItem(title = "Studies", onClick = { open(AppDest.Studies) })
                PageItem(title = "Settings", onClick = { open(AppDest.Settings) })
            }
            if (state.recentCourses.isNotEmpty()) {
                Title("Recent courses")
                Section {
                    state.recentCourses.forEach { recent ->
                        state.courseCatalog.singleOrNull { recent.matches(it) }?.let { course ->
                            val provider = state.platforms.firstOrNull { it.id == recent.providerId }
                            PageItem(title = course.optString("name"), description =
                                listOf(provider?.platformDisplayName ?: "Lemida", recent.term, recent.section.label)
                                    .filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                                onClick = { onBack(); state.onCourseResumed(recent) })
                        }
                    }
                }
            }
        }
    }
}
