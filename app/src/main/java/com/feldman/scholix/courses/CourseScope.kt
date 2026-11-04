package com.feldman.scholix.courses

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import com.feldman.motion.MotionDest
import com.feldman.scholix.CourseSection
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import org.json.JSONObject

fun JSONObject.stableCourseKey(): String = optString("courseKey").ifBlank { optString("id") }

fun JSONObject.courseDestination(section: CourseSectionKind = CourseSectionKind.OVERVIEW) =
    CourseSection(optString("platformId"), stableCourseKey(), optString("term"), section)

fun MotionDest.courseScope(): CourseSection? =
    generateSequence(this) { it.parent }.filterIsInstance<CourseSection>().firstOrNull()

fun CourseSection.matches(course: JSONObject): Boolean = providerId == course.optString("platformId") &&
    courseKey == course.stableCourseKey() && term == course.optString("term")

fun courseSections(provider: Platform?, course: JSONObject): List<CourseSectionKind> = when {
    provider is OpenAUPlatform -> com.feldman.scholix.openu.course.OpenUCourseSections.available(course)
    provider is GoogleClassroomPlatform -> listOf(CourseSectionKind.OVERVIEW, CourseSectionKind.ASSIGNMENTS,
        CourseSectionKind.FILES, CourseSectionKind.GRADES, CourseSectionKind.STUDIES)
    course.optString("platformId").startsWith("lemida:") -> listOf(CourseSectionKind.OVERVIEW,
        CourseSectionKind.ASSIGNMENTS, CourseSectionKind.FILES, CourseSectionKind.STUDIES)
    else -> emptyList()
}

val LocalCourseStateHolder = compositionLocalOf<SaveableStateHolder?> { null }

fun CourseSection.sameCourse(other: CourseSection): Boolean =
    providerId == other.providerId && courseKey == other.courseKey && term == other.term
