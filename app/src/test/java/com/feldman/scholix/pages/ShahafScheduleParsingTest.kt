package com.feldman.scholix.pages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ShahafScheduleParsingTest {
    @Test fun widgetUsesTheRequestedPublishedDayAndItsClockTimes() {
        val tabs = org.json.JSONArray().apply {
            repeat(6) { put(JSONObject().put("k", "day$it")) }
        }
        val row = JSONObject("""{"h":[{"t":"3"},{"t":"10:00 - 10:45"}],"i":[{"t":"Math","s":"bold"},{"t":"Teacher"}]}""")
        val block = JSONObject().put("k", "day1").put("r", org.json.JSONArray().put(row))
        val view = JSONObject().put("tb", JSONObject().put("tb", tabs)).put("d", org.json.JSONArray().put(block))
        val data = JSONObject().put("views", org.json.JSONArray().put(view))
        val lesson = shahafWidgetLessons(data, 1).single()
        assertEquals("Math", lesson.getString("subject"))
        assertEquals(3, lesson.getInt("hour"))
        assertEquals("10:00 - 10:45", lesson.getString("time"))
        assertEquals(emptyList<JSONObject>(), shahafWidgetLessons(data, 2))
    }

    @Test fun periodHeadingIsNotUsedAsTeacher() {
        val row = JSONObject("""{"h":[{"t":"3"},{"t":"שעה שלישית"}],"i":[
            {"t":"מתמטיקה","s":"bold"},{"t":"שעה שלישית"},{"t":"דנה לוי"}
        ]}""")
        assertEquals("דנה לוי", row.toShahafScheduleItem()!!.optString("teacher"))
    }

    @Test fun combinedPeriodLabelIsNotUsedAsTeacher() {
        val row = JSONObject("""{"i":[{"t":"אנגלית","s":"bold"},{"t":"שעה 3–4"}]}""")
        assertEquals("", row.toShahafScheduleItem()!!.optString("teacher"))
    }

    @Test fun parallelGroupsDoNotInventOneTeacher() {
        val row = JSONObject("""{"i":[
            {"t":"אנגלית","s":"bold"},{"t":"דנה"},
            {"t":"אנגלית","s":"bold"},{"t":"יוסי"}
        ]}""")
        val item = row.toShahafScheduleItem()!!
        assertEquals("אנגלית", item.optString("subject"))
        assertEquals("", item.optString("teacher"))
    }
}
