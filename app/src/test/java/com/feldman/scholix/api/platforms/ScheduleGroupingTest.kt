package com.feldman.scholix.api.platforms

import com.feldman.scholix.pages.WINDOW_FLAG
import com.feldman.scholix.pages.withFreePeriods
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduleGroupingTest {
    private fun lesson(hour: Int, subject: String) = JSONObject().put("hour", hour).put("subject", subject)

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
