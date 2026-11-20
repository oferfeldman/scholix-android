@file:Suppress("PROPERTY_WONT_BE_SERIALIZED")

package com.feldman.scholix

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    // OpenU course rows sit beside the page they open. A Lemida course section is one page of its own;
    // as a list pane it shared the screen with whatever list was open before, often another course.
    @IgnoredOnParcel @Transient override val pane =
        if (providerId.startsWith("lemida:")) MotionPaneType.FULL else MotionPaneType.LIST
    @IgnoredOnParcel @Transient override val icon = when (section) {
        CourseSectionKind.OVERVIEW -> MotionIcons.Outlined.Dashboard
        CourseSectionKind.UNITS -> MotionIcons.Outlined.ViewModule
        // Not the clipboard of the provider's Assignments page, which shares the course bar.
        CourseSectionKind.ASSIGNMENTS -> MotionIcons.Outlined.Checklist
        CourseSectionKind.RECORDINGS -> MotionIcons.Outlined.VideoLibrary
        CourseSectionKind.FORUMS -> MotionIcons.Outlined.Forum
        CourseSectionKind.ARCHIVE -> MotionIcons.Outlined.Archive
        CourseSectionKind.BOOKS -> MotionIcons.Outlined.MenuBook
        CourseSectionKind.BOOKLET -> MotionIcons.Outlined.Book
        CourseSectionKind.FILES -> MotionIcons.Outlined.Folder
        CourseSectionKind.GRADES -> AppDest.Grades.icon
        CourseSectionKind.STUDIES -> MotionIcons.Outlined.School
    }

    @Composable
    override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String,
                         onFabAction: ((() -> Unit) -> Unit) -> Unit) {
        CourseContent(onNavigate, onBack, searchQuery)
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
        val moved = if (provider is OpenAUPlatform) com.feldman.scholix.openu.course.OpenUCourseSections.redirect(section) else null
        if (moved != null) {
            LaunchedEffect(this) { state.onScopeSelected(copy(section = moved)) }
            return
        }
        if (course == null) {
            MotionScaffold(topBar = { com.feldman.scholix.ui.components.SettingsTopBar("Course", onBack) }) {
                Item { Text("This course is no longer available for this account.") }
            }
            return
        }
        when {
            section == CourseSectionKind.GRADES -> MotionScaffold(scrollable = false,
                contentPadding = PaddingValues(0.dp),
                bottomBarClearance = false,
                topBar = { com.feldman.scholix.ui.components.SettingsTopBar(section.label, onBack) }) {
                Item { com.feldman.scholix.pages.GradesScreen(
                modifier = Modifier.fillMaxSize(), preloadedCourses = state.preloadedCourses,
                focusedProviderId = providerId, focusedCourseKey = courseKey, focusedTerm = term,
                inbarAccessRequested = true, isActive = state.activePage?.courseScope() == this@CourseSection,
                onOpenCourse = { state.onScopeSelected(it) }, includeTopInset = false,
                onOpenAssignment = { scopedNavigator(it) }) }
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
                    // What each section holds, read from the saved Lemida snapshot.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val counts = androidx.compose.runtime.remember(providerId, courseKey) {
                        if (!providerId.startsWith("lemida:")) null else {
                            val repo = com.feldman.scholix.lemida.LemidaRepository(context)
                            val id = courseKey.toIntOrNull()
                            repo.cached().count { it.courseId == id } to repo.cachedMaterials().count { it.courseId == id }
                        }
                    }
                    Section {
                        courseSections(provider, course).filter { it != CourseSectionKind.OVERVIEW }.forEach { target ->
                            PageItem(title = target.label, onClick = { state.onScopeSelected(copy(section = target)) },
                                icon = rememberSymbolPainter(when (target) {
                                    CourseSectionKind.ASSIGNMENTS -> MotionSymbols.ic_assignment
                                    CourseSectionKind.FILES -> MotionSymbols.ic_folder
                                    CourseSectionKind.GRADES -> MotionSymbols.ic_grade
                                    else -> MotionSymbols.ic_open_in_new
                                }),
                                description = counts?.let { (work, files) -> when (target) {
                                    CourseSectionKind.ASSIGNMENTS -> if (work == 1) "1 activity" else "$work activities"
                                    CourseSectionKind.FILES -> if (files == 0) "Lectures, recitations and other course files"
                                        else if (files == 1) "1 course file" else "$files course files"
                                    else -> null
                                } })
                        }
                        if (providerId.startsWith("lemida:")) PageItem(title = "Save homework for PC",
                            description = "A file of all your Lemida homework, for Feldman Hub on a computer",
                            icon = rememberSymbolPainter(MotionSymbols.ic_save), onClick = export)
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
            onOpenCourse = { state.onScopeSelected(it) }, onOpenAssignment = { onNavigate(it) })
    }
}
