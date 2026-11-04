@file:Suppress("PROPERTY_WONT_BE_SERIALIZED")

package com.feldman.scholix

import androidx.compose.runtime.Composable
import com.feldman.motion.MotionDest
import com.feldman.motion.MotionNavigator
import com.feldman.motion.MotionPaneType
import com.feldman.scholix.courses.CourseSectionKind
import com.feldman.scholix.openu.course.*
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@Parcelize
data class OpenUCourseItem(
    val providerId: String = "", val courseKey: String = "", val term: String = "",
    val section: CourseSectionKind = CourseSectionKind.UNITS, val kind: String = "activity",
    val itemId: String = "", val title: String = "", val url: String = "",
    val unitId: String = "", val unitTitle: String = "",
    val forumUrl: String = "", val forumTitle: String = "",
) : AppDest() {
    @IgnoredOnParcel @Transient override val label = title.ifBlank { section.label }
    @IgnoredOnParcel @Transient override val parent: MotionDest = when {
        kind != "unit" && unitId.isNotBlank() -> OpenUCourseItem(providerId, courseKey, term, section, "unit", unitId, unitTitle)
        forumUrl.isNotBlank() -> OpenUCourseItem(providerId, courseKey, term, section, "activity", title = forumTitle, url = forumUrl)
        else -> CourseSection(providerId, courseKey, term, section)
    }
    @IgnoredOnParcel @Transient override val pane = MotionPaneType.EXTRA
    @IgnoredOnParcel @Transient override val showNavigation = kind != "quiz"
    @Composable
    override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String,
                         onFabAction: ((() -> Unit) -> Unit) -> Unit) {
        OpenUCourseItemPage(this, onNavigate, onBack)
    }
}

@Serializable
@Parcelize
data class OpenUCourseDocument(
    val providerId: String = "", val courseKey: String = "", val term: String = "",
    val section: CourseSectionKind = CourseSectionKind.BOOKS, val title: String = "", val url: String = "",
    val origin: OpenUCourseItem? = null,
) : AppDest() {
    @IgnoredOnParcel @Transient override val label = title.ifBlank { "Document" }
    @IgnoredOnParcel @Transient override val parent: MotionDest = origin ?: CourseSection(providerId, courseKey, term, section)
    @IgnoredOnParcel @Transient override val pane = MotionPaneType.EXTRA
    @IgnoredOnParcel @Transient override val showNavigation = false
    @Composable
    override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String,
                         onFabAction: ((() -> Unit) -> Unit) -> Unit) {
        OpenUCourseDocumentPage(this, onBack)
    }
}

@Serializable
@Parcelize
data class OpenUCoursePlayer(
    val providerId: String = "", val courseKey: String = "", val term: String = "",
    val section: CourseSectionKind = CourseSectionKind.RECORDINGS, val title: String = "", val recording: String = "",
    val playerUrl: String = "",
    val origin: OpenUCourseItem? = null,
) : AppDest() {
    @IgnoredOnParcel @Transient override val label = title.ifBlank { "Recording" }
    @IgnoredOnParcel @Transient override val parent: MotionDest = origin ?: CourseSection(providerId, courseKey, term, section)
    @IgnoredOnParcel @Transient override val pane = MotionPaneType.EXTRA
    @IgnoredOnParcel @Transient override val showNavigation = false
    @Composable
    override fun Content(onNavigate: MotionNavigator, onBack: () -> Unit, searchQuery: String,
                         onFabAction: ((() -> Unit) -> Unit) -> Unit) {
        OpenUCoursePlayerPage(this, onBack)
    }
}

fun openUCourseDestinations(): List<AppDest> = listOf(OpenUCourseItem(), OpenUCourseDocument(), OpenUCoursePlayer())
