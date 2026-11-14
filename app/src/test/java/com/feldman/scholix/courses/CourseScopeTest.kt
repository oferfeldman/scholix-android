package com.feldman.scholix.courses

import com.feldman.motion.MotionDestBackStack
import com.feldman.scholix.AppDest
import com.feldman.scholix.CourseSection
import com.feldman.scholix.ProviderGrades
import com.feldman.scholix.ScopeSwitcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.ui.components.coursePickerDescription
import org.json.JSONArray

class CourseScopeTest {
    @Test fun openUCoursesSortCurrentThenUpcomingThenCompletedIncludingOldSnapshots() {
        assertEquals(0, OpenAUPlatform.courseStatusRank("בלימוד", null))
        assertEquals(1, OpenAUPlatform.courseStatusRank("נרשם", "2999-01-01"))
        assertEquals(1, OpenAUPlatform.courseStatusRank("נרשם", null))
        assertEquals(2, OpenAUPlatform.courseStatusRank("הצלחה", "2025-01-01"))
        val restored = OpenAUPlatform.fromJson(JSONObject().put("courses", JSONArray().put(
            JSONObject().put("status", "נרשם").put("courseStatusRank", 2))))
        assertEquals(1, restored.toJson().getJSONArray("courses").getJSONObject(0).getInt("courseStatusRank"))
    }

    @Test fun pickerShowsFinalAndExamOrAverageWithoutInventingMissingGrades() {
        val course = JSONObject().put("status", "Learning").put("assignments", JSONArray()
            .put(JSONObject().put("grade", "90")).put(JSONObject().put("grade", "80")))
        assertEquals("Learning\nAvg 85", coursePickerDescription(course))
        course.put("finalGrade", "99").put("examGrade", "98")
        assertEquals("Learning\nFinal 99 · Exam 98", coursePickerDescription(course))
        assertEquals("", coursePickerDescription(JSONObject().put("finalGrade", "null").put("examGrade", "-")))
    }

    @Test fun courseTabsHaveDistinctSectionIcons() {
        val icons = CourseSectionKind.entries.map { CourseSection(section = it).icon.name }
        assertEquals(CourseSectionKind.entries.size, icons.distinct().size)
        assertFalse(icons.contains("description"))
        assertEquals(AppDest.Grades.icon, CourseSection(section = CourseSectionKind.GRADES).icon)
    }

    private fun course(provider: String = "one", term: String = "2027a") = JSONObject()
        .put("platformId", provider).put("courseKey", "20476:$term")
        .put("name", "Same title").put("term", term)

    @Test fun displayNamesDoNotRetargetCoursesAcrossAccountsOrTerms() {
        val original = course().courseDestination()
        assertTrue(original.matches(course().put("name", "Renamed")))
        assertFalse(original.matches(course(provider = "two")))
        assertFalse(original.matches(course(term = "2026c")))
        assertFalse(original.sameCourse(original.copy(providerId = "two")))
        assertFalse(original.sameCourse(original.copy(term = "2026c")))
    }

    @Test fun idOnlyCoursesStillHaveAStableRoute() {
        val course = JSONObject().put("platformId", "classroom").put("id", "123")
        assertEquals("123", course.courseDestination().courseKey)
        assertTrue(course.courseDestination().matches(course.put("name", "Renamed")))
    }

    @Test fun scopedAssignmentAndAttachmentKeepTheirCourseAncestry() {
        val section = CourseSection("one", "20476:2027a", "2027a", CourseSectionKind.GRADES)
        val assignment = AppDest.OpenUAssignment("one", "activity", section.courseKey, section.term)
        val attachment = AppDest.OpenUAttachment("one", "Question", "https://example.test/question.pdf",
            section.courseKey, section.term, assignment.assignmentKey)
        assertEquals(section, assignment.courseScope())
        assertEquals(assignment, attachment.parent)
        assertEquals(section, attachment.courseScope())
        assertEquals(section, ScopeSwitcher(attachment).courseScope())
        assertEquals(AppDest.Grades, AppDest.OpenUAssignment("one", "activity").parent)
    }

    @Test fun globalExcursionPreservesTheOpenCourseItem() {
        val stack = MotionDestBackStack(AppDest.Home)
        val section = course().courseDestination(CourseSectionKind.GRADES)
        val assignment = AppDest.OpenUAssignment("one", "activity", section.courseKey, section.term)
        stack.navigateTo(assignment)
        stack.navigateTop(AppDest.Grades)
        stack.navigateTop(AppDest.Home)
        assertEquals(assignment, stack.backStack.last())
        assertTrue(stack.pop())
        assertEquals(section, stack.backStack.last())
    }

    @Test fun providerGradesIsAnExcursionThatReturnsToTheExactCourseInOneBack() {
        val stack = MotionDestBackStack(AppDest.Home)
        val section = course().courseDestination(CourseSectionKind.GRADES)
        val assignment = AppDest.OpenUAssignment("one", "activity", section.courseKey, section.term)
        stack.navigateTo(assignment)
        val provider = ProviderGrades("school")
        assertNull(provider.parent)
        stack.navigateTop(provider)
        assertEquals(provider, stack.backStack.last())
        assertTrue(stack.pop())
        assertEquals(assignment, stack.backStack.last())
        assertEquals(section, stack.backStack.last().courseScope())
    }
}
