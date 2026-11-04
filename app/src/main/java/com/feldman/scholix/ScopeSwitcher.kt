@file:Suppress("PROPERTY_WONT_BE_SERIALIZED")

package com.feldman.scholix

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.feldman.motion.*
import com.feldman.scholix.courses.courseScope
import com.feldman.scholix.courses.matches
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

data class ScopeControl(val label: String, val open: () -> Unit)
val LocalScopeControl = compositionLocalOf<ScopeControl?> { null }

@Composable
fun ScopeChip(modifier: Modifier = Modifier) {
    val scope = LocalScopeControl.current ?: return
    TextButton(onClick = scope.open, modifier = modifier.heightIn(min = 48.dp)) {
        Text("${scope.label} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Serializable
@Parcelize
data class ScopeSwitcher(val origin: AppDest = AppDest.Home) : AppDest() {
    @IgnoredOnParcel @Transient override val label = "Switch scope"
    @IgnoredOnParcel @Transient override val parent: MotionDest = origin
    @IgnoredOnParcel @Transient override val pane = MotionPaneType.BOTTOM_SHEET
    @IgnoredOnParcel @Transient override val showNavigation = false
    @IgnoredOnParcel @Transient override val bottomSheetEffects = MotionBottomSheetEffects(
        backdropScope = MotionBottomSheetBackdropScope.FULL_SCREEN)

    @Composable
    override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String,
                         onFabAction: ((() -> Unit) -> Unit) -> Unit) {
        val state = LocalAppState.current
        fun open(destination: AppDest) { onBack(); state.onScopeSelected(destination) }
        MotionScaffold(fitContentHeight = true) {
            Title("Go to")
            Section {
                PageItem(title = "Home", onClick = { open(AppDest.Home) })
                if (state.platforms.any { it.suportsGrades }) {
                    PageItem(title = "All grades", onClick = { open(AppDest.Grades) })
                    state.platforms.filter { it.suportsGrades }.forEach { provider ->
                        PageItem(title = provider.platformDisplayName.ifBlank { provider.getName() },
                            description = "Grades", onClick = { open(ProviderGrades(provider.id)) })
                    }
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
                            PageItem(title = course.optString("name"), description = recent.section.label,
                                onClick = { onBack(); state.onCourseResumed(recent) })
                        }
                    }
                }
            }
        }
    }
}
