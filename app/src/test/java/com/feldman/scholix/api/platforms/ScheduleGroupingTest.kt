package com.feldman.scholix.api.platforms

import com.feldman.scholix.pages.WINDOW_FLAG
import com.feldman.scholix.pages.withFreePeriods
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduleGroupingTest {
    private fun lesson(hour: Int, subject: String) = JSONObject().put("hour", hour).put("subject", subject)

    @Test fun widgetStaysOnTodayUntilTheLastActiveLessonEnds() {
        val today = java.time.LocalDate.of(2026, 10, 26)
        val lessons = listOf(lesson(1, "Math").put("time", "08:00 - 08:45"),
            lesson(2, "English").put("time", "09:00 - 09:45"))
        assertEquals(today, com.feldman.scholix.widgets.scheduleWidgetDate(today, java.time.LocalTime.of(9, 44), lessons, emptySet()))
        assertEquals(today.plusDays(1), com.feldman.scholix.widgets.scheduleWidgetDate(today, java.time.LocalTime.of(9, 45), lessons, emptySet()))
    }

    @Test fun widgetIgnoresCancelledAndFreeLessonsWhenAdvancing() {
        val today = java.time.LocalDate.of(2026, 12, 31)
        val lessons = listOf(lesson(1, "Math").put("time", "08:00 - 08:45"),
            lesson(2, "English").put("time", "09:00 - 09:45").put("colorClass", "cancel-cell"),
            lesson(3, "History").put("time", "10:00 - 10:45"))
        assertEquals(today.plusDays(1), com.feldman.scholix.widgets.scheduleWidgetDate(today, java.time.LocalTime.of(8, 45), lessons, setOf("History")))
        assertEquals(today.plusDays(1), com.feldman.scholix.widgets.scheduleWidgetDate(today, java.time.LocalTime.MIDNIGHT, emptyList(), emptySet()))
    }

    @Test fun clockTimesDoNotCreateHundredsOfInventedSchoolPeriods() {
        val groups = withFreePeriods(listOf(lesson(540, "Course A"), lesson(720, "Course B")),
            emptySet(), synthesizeGaps = false)
        assertEquals(2, groups.size)
        assertEquals(listOf(540, 720), groups.map { it.single().getInt("hour") })
        assertTrue(groups.flatten().none { it.optBoolean(WINDOW_FLAG) })
    }

    @Test fun simultaneousCoursesAndUserMarkedFreePeriodsAreRetained() {
        val groups = withFreePeriods(listOf(lesson(540, "Course A"), lesson(540, "Course B"), lesson(720, "Course C")),
            setOf("Course A"), synthesizeGaps = false)
        assertEquals(2, groups.size)
        assertEquals(2, groups.first().size)
        assertTrue(groups.first().first { it.getString("subject") == "Course A" }.getBoolean(WINDOW_FLAG))
        assertFalse(groups.first().first { it.getString("subject") == "Course B" }.optBoolean(WINDOW_FLAG))
    }

    @Test fun existingSchoolProvidersStillGetTheirNumberedFreePeriods() {
        val groups = withFreePeriods(listOf(lesson(1, "Class A"), lesson(3, "Class B")), emptySet())
        assertEquals(4, groups.size)
        assertTrue(groups[0].single().getBoolean(WINDOW_FLAG))
        assertTrue(groups[2].single().getBoolean(WINDOW_FLAG))
    }
}
