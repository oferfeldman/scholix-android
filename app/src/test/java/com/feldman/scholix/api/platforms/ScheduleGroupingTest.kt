package com.feldman.scholix.api.platforms

import com.feldman.scholix.pages.WINDOW_FLAG
import com.feldman.scholix.pages.withFreePeriods
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduleGroupingTest {
    private fun lesson(hour: Int, subject: String) = JSONObject().put("hour", hour).put("subject", subject)

    @Test fun widgetMarksCurrentOrNextLessonAndSkipsCancelledAndFreePeriods() {
        val lessons = listOf(
            lesson(1, "Math").put("time", "08:00 - 08:45"),
            lesson(2, "English").put("time", "09:00 - 09:45").put("colorClass", "cancel-cell"),
            lesson(3, "Free").put("time", "10:00 - 10:45").put(WINDOW_FLAG, true),
            lesson(4, "History").put("time", "11:00 - 11:45"),
        )
        assertEquals(0, com.feldman.scholix.widgets.scheduleWidgetActiveIndex(lessons, java.time.LocalTime.of(8, 30), true))
        assertEquals(3, com.feldman.scholix.widgets.scheduleWidgetActiveIndex(lessons, java.time.LocalTime.of(8, 45), true))
        assertEquals(3, com.feldman.scholix.widgets.scheduleWidgetActiveIndex(lessons, java.time.LocalTime.of(11, 0), true))
        assertEquals(-1, com.feldman.scholix.widgets.scheduleWidgetActiveIndex(lessons, java.time.LocalTime.of(11, 45), true))
        assertEquals(0, com.feldman.scholix.widgets.scheduleWidgetActiveIndex(lessons, java.time.LocalTime.of(20, 0), false))
    }

    @Test fun widgetLoadsWithAnExpiredSessionWithoutOpeningTheApp() {
        var renewed = false
        var renewals = 0
        val requestedDays = mutableListOf<Int>()
        val provider = object : com.feldman.scholix.api.Platform by DemoPlatform() {
            override fun getInfo() = JSONObject().put("scheduleSelection", "9|6")
            override fun refreshCookies(): Boolean {
                renewals++
                renewed = true
                return true
            }
            override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
                assertEquals("9|6", selectedValue)
                requestedDays += dayIndex
                return if (!renewed) JSONObject().put("error", "login_failed")
                    else JSONObject().put("1", lesson(1, "Math"))
            }
        }
        val today = java.time.LocalDate.of(2026, 10, 26)
        val days = com.feldman.scholix.widgets.fetchScheduleWidgetDays(provider, today)
        assertEquals(1, renewals)
        assertEquals(listOf(1, 1, 2), requestedDays)
        assertEquals("Math", days.getJSONArray(today.toString()).getJSONObject(0).getString("subject"))
        assertEquals(1, days.getJSONArray(today.plusDays(1).toString()).length())
    }

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
