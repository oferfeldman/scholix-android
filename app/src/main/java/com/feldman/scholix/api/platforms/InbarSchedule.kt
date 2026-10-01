package com.feldman.scholix.api.platforms

import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

internal data class InbarSchedulePage(
    val year: Int,
    val years: List<Int>,
    val period: String,
    val periods: Map<String, String>,
    val lessons: List<JSONObject>
)

/** The period schedule is a Web Forms grid, including when it contains an empty-data row. */
internal object InbarSchedule {
    fun parse(html: String): InbarSchedulePage {
        val doc = Jsoup.parse(html)
        if (doc.selectFirst("input[name=edtUsername], input[name=edtCode]") != null) throw InbarSessionExpired()
        val grids = doc.select("table[id$=_gvPeriodSchedule]")
        if (grids.isEmpty()) throw IOException("Inbar schedule table is missing")
        val yearSelector = doc.selectFirst("select#cmbActiveYear") ?: throw IOException("Inbar year selector missing")
        val periodSelector = doc.selectFirst("select[id$=ddlPeriodTypeFilter2]")
            ?: throw IOException("Inbar semester selector missing")
        fun selected(selector: org.jsoup.nodes.Element) = selector.selectFirst("option[selected]") ?: selector.selectFirst("option")
        val year = selected(yearSelector)?.attr("value")?.toIntOrNull() ?: throw IOException("Invalid schedule year")
        val period = selected(periodSelector)?.attr("value") ?: throw IOException("Invalid schedule semester")
        val lessons = mutableListOf<JSONObject>()
        for (grid in grids) {
            val headers = grid.select("th").map { it.text().replace(Regex("\\s+"), " ").trim() }
            fun column(label: String): Int = headers.indexOf(label).also {
                if (it < 0) throw IOException("Inbar schedule columns changed")
            }
            val dayColumn = column("יום")
            val timeColumn = column("שעה")
            val subjectColumn = column("שם קבוצת קורס")
            val codeColumn = column("קוד קבוצת קורס")
            val periodColumn = column("תקופה")
            val teacherColumn = column("מרצה")
            val roomColumn = column("חדר")
            for (row in grid.select("tr")) {
                if (row.parents().firstOrNull { it.tagName() == "table" } !== grid) continue
                val cells = row.children().filter { it.tagName() == "td" }
                if (cells.isEmpty()) continue
                if (cells.all { it.text().isBlank() } ||
                    (cells.first().text().trim() in listOf("אין נתונים", "No data", "No records") &&
                        cells.drop(1).all { it.text().isBlank() })) continue
                if (cells.size != headers.size) throw IOException("Inbar schedule row changed")
                val subject = cells[subjectColumn].text()
                if (subject.isBlank()) throw IOException("Inbar schedule course is missing")
                val day = dayIndex(cells[dayColumn].text()) ?: throw IOException("Unrecognized Inbar schedule day")
                val times = Regex("(?<![0-9])([0-9]{1,2}):([0-9]{2})(?![0-9])")
                    .findAll(cells[timeColumn].text()).map { match ->
                        val hour = match.groupValues[1].toInt()
                        val minute = match.groupValues[2].toInt()
                        if (hour !in 0..23 || minute !in 0..59) throw IOException("Invalid Inbar lesson time")
                        "%02d:%02d".format(hour, minute)
                    }.toList()
                if (times.isEmpty() || times.size > 2) throw IOException("Unrecognized Inbar lesson time")
                // Hebrew directionality can reverse the visual order of the endpoints.
                val ordered = times.sorted()
                val start = ordered.first()
                val startMinutes = start.substringBefore(':').toInt() * 60 + start.substringAfter(':').toInt()
                val code = cells[codeColumn].text()
                val lesson = JSONObject().put("id", "$year:$code:$day:$start:${lessons.size}")
                    .put("day", day).put("num", startMinutes).put("hour", startMinutes)
                    .put("subject", subject).put("courseCode", code).put("year", year)
                    .put("period", cells[periodColumn].text()).put("teacher", cells[teacherColumn].text())
                    .put("room", cells[roomColumn].text()).put("time", ordered.joinToString(" - "))
                    .put("startTime", start).put("endTime", ordered.getOrElse(1) { "" })
                for ((header, key) in listOf("שעות" to "hours", "נ\"ז" to "credits", "ש\"ש" to "weeklyHours",
                    "עזרים" to "accessories", "סוג מפגש" to "meetingType")) {
                    headers.indexOf(header).takeIf { it >= 0 }?.let { lesson.put(key, cells[it].text()) }
                }
                lessons += lesson
            }
        }
        return InbarSchedulePage(year,
            yearSelector.select("option").mapNotNull { it.attr("value").toIntOrNull() }, period,
            periodSelector.select("option").associate { it.attr("value") to it.text() },
            lessons.sortedWith(compareBy({ it.optInt("day") }, { it.optInt("hour") })))
    }

    private fun dayIndex(raw: String): Int? {
        val day = raw.lowercase().replace("יום", "").replace(Regex("[\\s'\"׳״]"), "")
        return listOf(listOf("א", "ראשון", "sunday", "sun", "1"),
            listOf("ב", "שני", "monday", "mon", "2"), listOf("ג", "שלישי", "tuesday", "tue", "3"),
            listOf("ד", "רביעי", "wednesday", "wed", "4"), listOf("ה", "חמישי", "thursday", "thu", "5"),
            listOf("ו", "שישי", "friday", "fri", "6"), listOf("ש", "שבת", "saturday", "sat", "7"))
            .indexOfFirst { day in it }.takeIf { it >= 0 }
    }
}
