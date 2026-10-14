package com.feldman.scholix.pages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SchoolCalendarParsingTest {
    @Test fun webtopHolidaysAppearEvenWithoutExamsOrEvents() {
        val raw = JSONObject("""{"data":{"events":[],"exams":[],"vactications":[
            {"id":4,"name":"Holiday","startDate":"2026-09-25T00:00:00","endDate":"2026-10-03T00:00:00"}
        ]}}""")
        val items = parseWebtopCalendarBoard(raw, "10|5")
        assertEquals(9, items.size)
        assertEquals(LocalDate.of(2026, 9, 25), items.first().date)
        assertEquals(LocalDate.of(2026, 10, 3), items.last().date)
    }
}
