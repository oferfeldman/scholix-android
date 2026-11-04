@file:Suppress("PROPERTY_WONT_BE_SERIALIZED")

package com.feldman.scholix

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feldman.motion.*
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.courses.*
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@Parcelize
data class CourseSection(
    val providerId: String = "",
    val courseKey: String = "",
    val term: String = "",
    val section: CourseSectionKind = CourseSectionKind.OVERVIEW,
) : AppDest() {
    @IgnoredOnParcel @Transient override val label = section.label
    @IgnoredOnParcel @Transient override val parent: MotionDest = AppDest.Home
    @IgnoredOnParcel @Transient override val pane = MotionPaneType.DETAIL
    @IgnoredOnParcel @Transient override val filledIcon = R.drawable.ic_docs
    @IgnoredOnParcel @Transient override val outlineIcon = R.drawable.ic_docs_outline

    @Composable
    override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String,
                         onFabAction: ((() -> Unit) -> Unit) -> Unit) {
        val holder = LocalCourseStateHolder.current
        if (holder != null) holder.SaveableStateProvider(this) { CourseContent(onNavigate, onBack, searchQuery) }
        else CourseContent(onNavigate, onBack, searchQuery)
    }

    @Composable
    private fun CourseContent(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String) {
        val state = LocalAppState.current
        val scopedNavigator = object : MotionNavigator {
            override fun invoke(dest: MotionDest, resetStack: Boolean) {
                if (dest is CourseSection && !resetStack) state.onScopeSelected(dest)
                else onNavigate(dest, resetStack)
            }
        }
        val course = state.courseCatalog.singleOrNull { matches(it) }
        val provider = state.platforms.firstOrNull { it.id == providerId }
        if (course == null) {
            MotionScaffold(topBar = { com.feldman.scholix.ui.components.SettingsTopBar("Course", onBack) }) {
                Item { Text("This course is no longer available for this account.") }
            }
            return
        }
        when {
            section == CourseSectionKind.GRADES -> MotionScaffold(scrollable = false,
                contentPadding = PaddingValues(0.dp),
                topBar = { com.feldman.scholix.ui.components.SettingsTopBar(section.label, onBack) }) {
                Item { com.feldman.scholix.pages.GradesScreen(
                modifier = Modifier.fillMaxSize(), preloadedCourses = state.preloadedCourses,
                focusedProviderId = providerId, focusedCourseKey = courseKey, focusedTerm = term,
                inbarAccessRequested = true, isActive = state.activePage == this,
                onOpenCourse = { state.onScopeSelected(it) }, includeTopInset = false) }
            }
            section == CourseSectionKind.STUDIES -> com.feldman.scholix.studies.StudiesPage(onBack, providerId, courseKey)
            provider is OpenAUPlatform -> com.feldman.scholix.openu.course.OpenUCourseSection(
                provider, courseKey, section, scopedNavigator, onBack, term)
            section == CourseSectionKind.OVERVIEW -> {
                val export = com.feldman.scholix.lemida.rememberHomeworkExportAction()
                MotionScaffold(topBar = { com.feldman.scholix.ui.components.SettingsTopBar(course.optString("name"), onBack) }) {
                    Item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(course.optString("name"), style = MaterialTheme.typography.headlineMedium)
                            Text(provider?.platformDisplayName ?: "Lemida")
                            if (term.isNotBlank()) Text(term)
                        }
                    }
                    Section {
                        courseSections(provider, course).filter { it != CourseSectionKind.OVERVIEW }.forEach { target ->
                            PageItem(title = target.label, onClick = { state.onScopeSelected(copy(section = target)) })
                        }
                    }
                    if (providerId.startsWith("lemida:")) Item {
                        MotionButton(text = "Save homework for PC", onClick = export)
                    }
                    BottomBarSpacer(minMargin = 24.dp)
                }
            }
            else -> com.feldman.scholix.lemida.LemidaPage(searchQuery, scopedNavigator,
                courseProviderId = providerId, courseKey = courseKey, filesOnly = section == CourseSectionKind.FILES, onBack = onBack)
        }
    }
}

@Serializable
@Parcelize
data class ProviderGrades(val providerId: String = "") : AppDest() {
    @IgnoredOnParcel @Transient override val label = "Grades"
    @Composable
    override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String,
                         onFabAction: ((() -> Unit) -> Unit) -> Unit) {
        val state = LocalAppState.current
        com.feldman.scholix.pages.GradesScreen(Modifier.fillMaxSize(), state.preloadedCourses,
            inbarAccessRequested = true, isActive = state.activePage == this, focusedProviderId = providerId,
            onOpenCourse = { state.onScopeSelected(it) })
    }
}
