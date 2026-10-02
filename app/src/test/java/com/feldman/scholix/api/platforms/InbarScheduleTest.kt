package com.feldman.scholix.api.platforms

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Recreates the supplied empty grid and populated rows using synthetic course data only. */
class InbarScheduleTest {
    private fun row(day: String = "יום א'", time: String = "09:00 - 11:00", code: String = "sample-01") =
        """<tr><td>$day</td><td>$time</td><td>Sample course</td><td>$code</td><td>Semester A</td>
        <td>2</td><td>3</td><td>2</td><td>Lecturer</td><td>Building 1 / Room 2</td><td>Projector</td>
        <td></td><td>Lecture</td></tr>"""

    private fun page(year: Int = 2027, period: String = "1", rows: String = "", state: String = "initial") =
        """<form id="form1" action="/Live/StudentPeriodSchedule.aspx">
        <input type="hidden" name="__PageDataKey" value="$state">
        <input type="hidden" name="__EVENTVALIDATION" value="validation-$state">
        <input type="hidden" name="__EVENTTARGET" value=""><input type="hidden" name="__EVENTARGUMENT" value="">
        <select id="cmbActiveYear" name="ctl00${'$'}cmbActiveYear">
        <option value="2027" ${if (year == 2027) "selected" else ""}>2027</option>
        <option value="2026" ${if (year == 2026) "selected" else ""}>2026</option></select>
        <select id="tbMain_ctl03_ddlPeriodTypeFilter2" name="ctl00${'$'}tbMain${'$'}ctl03${'$'}ddlPeriodTypeFilter2">
        <option value="1" ${if (period == "1") "selected" else ""}>Semester A</option>
        <option value="2" ${if (period == "2") "selected" else ""}>Semester B</option>
        <option value="3" ${if (period == "3") "selected" else ""}>Summer</option>
        <option value="5" ${if (period == "5") "selected" else ""}>All semesters</option></select>
        <table id="ContentPlaceHolder1_PeriodScheduleA_gvPeriodSchedule"><tr>
        <th>יום</th><th>שעה</th><th>שם קבוצת קורס</th><th>קוד קבוצת קורס</th><th>תקופה</th><th>שעות</th>
        <th>נ"ז</th><th>ש"ש</th><th>מרצה</th><th>חדר</th><th>עזרים</th><th>סילבוס</th><th>סוג מפגש</th></tr>
        ${rows.ifBlank { "<tr><td>אין נתונים</td>" + "<td></td>".repeat(12) + "</tr>" }}</table></form>"""

    private fun transport(requests: MutableList<Request>, vararg pages: String) =
        OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val html = pages[requests.lastIndex]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(html.toResponseBody()).build()
        }.build()

    private fun fields(request: Request): Map<String, String> = (request.body as FormBody).let { body ->
        (0 until body.size).associate { body.name(it) to body.value(it) }
    }

    @Test fun emptyPortalGridStillProvidesYearAndSemesterMetadata() {
        val result = InbarSchedule.parse(page())
        assertEquals(2027, result.year)
        assertEquals(listOf(2027, 2026), result.years)
        assertEquals("1", result.period)
        assertEquals(listOf("1", "2", "3", "5"), result.periods.keys.toList())
        assertTrue(result.lessons.isEmpty())
    }

    @Test fun populatedRowsMapToExistingScheduleCardsWithoutInventedTimes() {
        val lesson = InbarSchedule.parse(page(rows = row())).lessons.single()
        assertEquals(0, lesson.getInt("day"))
        assertEquals(540, lesson.getInt("hour"))
        assertEquals("09:00 - 11:00", lesson.getString("time"))
        assertEquals("09:00", lesson.getString("startTime"))
        assertEquals("11:00", lesson.getString("endTime"))
        assertEquals("Sample course", lesson.getString("subject"))
        assertEquals("sample-01", lesson.getString("courseCode"))
        assertEquals("Lecturer", lesson.getString("teacher"))
        assertEquals("Building 1 / Room 2", lesson.getString("room"))
        assertEquals("Lecture", lesson.getString("meetingType"))
        assertEquals("3", lesson.getString("credits"))
    }

    @Test fun weekdaysReversedTimeEndpointsAndConcurrentCoursesArePreserved() {
        val result = InbarSchedule.parse(page(rows = row("יום ג׳", "11:00–09:00", "sample-02") +
            row("ראשון", "9:00 - 11:00", "sample-01") + row("ראשון", "9:00 - 10:00", "sample-03") +
            row("שבת", "12:00 - 14:00", "sample-04")))
        assertEquals(listOf(0, 0, 2, 6), result.lessons.map { it.getInt("day") })
        assertEquals(4, result.lessons.map { it.getString("id") }.distinct().size)
        assertEquals("09:00 - 11:00", result.lessons[2].getString("time"))
    }

    @Test fun allSemesterGridsAreRead() {
        val html = page(period = "5", rows = row())
        val otherGrid = org.jsoup.Jsoup.parse(page(rows = row("שני", "13:00 - 15:00", "sample-02")))
            .selectFirst("table")!!.attr("id", "ContentPlaceHolder1_PeriodScheduleB_gvPeriodSchedule")
        val result = InbarSchedule.parse(html.replace("</form>", "$otherGrid</form>"))
        assertEquals(listOf(0, 1), result.lessons.map { it.getInt("day") })
    }

    @Test fun changedLayoutAndInvalidDayOrTimeAreNotReportedAsEmpty() {
        for (html in listOf(page().replace("שם קבוצת קורס", "Changed column"), page(rows = row("unknown")),
            page(rows = row(time = "25:00 - 26:00")), page(rows = row(time = "TBD")), "<html>Unknown page</html>")) {
            assertThrows(IOException::class.java) { InbarSchedule.parse(html) }
        }
    }

    @Test fun loginFormsAreReportedAsExpiredSessions() {
        for (field in listOf("edtUsername", "edtCode")) {
            assertThrows(InbarSessionExpired::class.java) { InbarSchedule.parse("<input name='$field'>") }
        }
    }

    @Test fun yearAndSemesterPostbacksUseFreshFormStateAndOnlyOneWeeklyFetch() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, page(), page(2026, state = "after-year"),
            page(2026, "3", row(), "after-period")))
        val result = http.schedule(2026, "3")
        assertEquals(2026, result.year)
        assertEquals("3", result.period)
        assertEquals(listOf("GET", "POST", "POST"), requests.map { it.method })
        assertEquals("/Live/StudentPeriodSchedule.aspx", requests.first().url.encodedPath)
        assertEquals("ctl00${'$'}cmbActiveYear", fields(requests[1])["__EVENTTARGET"])
        assertEquals("2026", fields(requests[1])["ctl00${'$'}cmbActiveYear"])
        val sent = fields(requests[2])
        assertEquals("after-year", sent["__PageDataKey"])
        assertEquals("validation-after-year", sent["__EVENTVALIDATION"])
        assertEquals("ctl00${'$'}tbMain${'$'}ctl03${'$'}ddlPeriodTypeFilter2", sent["__EVENTTARGET"])
        assertEquals("3", sent[sent.getValue("__EVENTTARGET")])
        repeat(7) { assertSame(result, http.schedule(2026, "3")) }
        assertEquals(3, requests.size)
    }

    @Test fun changingSelectionFetchesTheNewSchedule() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, page(), page(), page(period = "2", state = "new")))
        http.schedule(2027, "1")
        assertEquals("2", http.schedule(2027, "2").period)
        assertEquals(3, requests.size)
    }

    @Test fun unavailableFiltersDoNotSendUnsupportedPostbacks() {
        for (selection in listOf(1999 to "1", 2027 to "unsupported")) {
            val requests = mutableListOf<Request>()
            val http = InbarHttp(transport = transport(requests, page()))
            assertThrows(IOException::class.java) { http.schedule(selection.first, selection.second) }
            assertEquals(1, requests.size)
        }
    }

    @Test fun expiredScheduleGetAndPostStopBeforeSendingAnotherSms() {
        for (initialPage in listOf<String?>(null, page())) {
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("OK")
                if (initialPage != null && requests.size == 1) response.code(200).body(initialPage.toResponseBody())
                else response.code(302).header("Location", "/Live/Authenticate.aspx").body("".toResponseBody())
                response.build()
            }.build()
            assertThrows(InbarSessionExpired::class.java) { InbarHttp(transport = client).schedule(2026, "3") }
            assertEquals(if (initialPage == null) 1 else 2, requests.size)
            assertTrue(requests.all { it.url.encodedPath == "/Live/StudentPeriodSchedule.aspx" })
        }
    }
}
