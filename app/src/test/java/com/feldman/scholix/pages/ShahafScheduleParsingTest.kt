package com.feldman.scholix.pages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ShahafScheduleParsingTest {
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
