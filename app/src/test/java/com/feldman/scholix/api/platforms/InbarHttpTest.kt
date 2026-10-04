package com.feldman.scholix.api.platforms

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder

/** Synthetic fixtures; no student identity, cookie, OTP or real grades are committed. */
class InbarHttpTest {
    private val login = """<form id="form1" action="/Live/Login.aspx?ReturnUrl=%2fLive%2fmain.aspx">
        <input type="hidden" name="__PageDataKey" value="login-state"><input type="hidden" name="__VIEWSTATE" value="">
        <input type="hidden" name="__EVENTVALIDATION" value="login-validation">
        <input name="edtUsername"><input name="edtMobile"><input type="submit" name="btnLogin" value="Enter">
        <input type="submit" name="language" value="EN"></form>"""
    private fun challenge(state: String = "sms-state") = """<form id="form1"
        action="/Live/Authenticate.aspx?ReturnUrl=%2fLive%2fmain.aspx&amp;AuthLevel=SmsAndNormal">
        <input type="hidden" name="__PageDataKey" value="$state">
        <input type="hidden" name="__EVENTVALIDATION" value="sms-validation">
        <input name="edtCode"><input type="submit" name="btnVerify" value="Continue">
        <input type="submit" name="btnSendSmsCode" value="Resend" disabled>
        <input type="submit" name="btnLogout" value="Back"></form>"""

    private fun grades(year: Int = 2026) = """<form id="form1" action="/Live/StudentGradesList.aspx">
        <input type="hidden" name="__PageDataKey" value="grades-state-$year">
        <input type="hidden" name="__EVENTVALIDATION" value="grades-validation">
        <input type="hidden" name="__EVENTTARGET" value=""><input type="hidden" name="__EVENTARGUMENT" value="">
        <select id="cmbActiveYear" name="ctl00${'$'}cmbActiveYear">
        <option value="2026" ${if (year == 2026) "selected" else ""}>2026</option>
        <option value="2027" ${if (year == 2027) "selected" else ""}>2027</option></select>
        <table id="ContentPlaceHolder1_gvGradesList"><tbody><tr><th>Code</th></tr>
        <tr><td>sample-01</td><td>Sample algebra</td><td>Lecturer</td><td>סמסטר קיץ</td><td>4</td><td>60</td>
        <td><span id="ContentPlaceHolder1_gvGradesList_lblRowFinalGrade_0">73</span></td><td>01/09/2026</td><td></td>
        <td><div style="display:none"><table><tr class="AssignmentHeader"><td></td><td>Weight</td><td>Grade</td></tr>
        <tr class="AssignmentText"><td>Exam</td><td>85</td><td>70</td></tr>
        <tr><td colspan="2">01/08/2026 (A)</td><td>70</td></tr>
        <tr class="AssignmentText"><td>Homework</td><td>15</td><td>90</td></tr></table></div></td><td></td></tr>
        <tr><td>sample-02</td><td>Pending course</td><td>Lecturer</td><td>סמסטר א'</td><td>2</td><td>60</td>
        <td><span id="ContentPlaceHolder1_gvGradesList_lblRowFinalGrade_1"></span></td>
        <td></td><td></td><td></td><td></td></tr></tbody></table></form>"""

    private fun transport(requests: MutableList<Request>, vararg pages: String): OkHttpClient {
        var index = 0
        return OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            check(index < pages.size) { "Unexpected extra request" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(pages[index++].toResponseBody()).build()
        }.build()
    }
    private fun fields(request: Request): Map<String, String> {
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        return body.split("&").associate {
            val parts = it.split("=", limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
    }

    @Test fun requestsSmsAndVerifiesAgainstFreshAuthenticationState() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, login, challenge(), "<html>Signed in</html>", grades()))
        http.requestSms("test-passport", "test-mobile")
        assertEquals(2026, http.verifySms("12345").year)
        assertEquals("test-passport", fields(requests[1])["edtUsername"])
        assertEquals("/Live/Authenticate.aspx", requests[2].url.encodedPath)
        assertEquals("SmsAndNormal", requests[2].url.queryParameter("AuthLevel"))
        val sent = fields(requests[2])
        assertEquals("sms-state", sent["__PageDataKey"])
        assertEquals("sms-validation", sent["__EVENTVALIDATION"])
        assertEquals("12345", sent["edtCode"])
        assertEquals(setOf("__PageDataKey", "__EVENTVALIDATION", "edtCode", "btnVerify"), sent.keys)
    }

    private fun restrictedChallenge() = challenge() + """<script>
        function OLScriptCounter0alert() { window.alert('לא ניתן לשלוח עוד הודעות/לאמת קוד בשלב זה , יש לנסות מאוחר יותר'); }
        </script>"""

    @Test fun serverSmsRestrictionFailsImmediatelyDespiteCodeField() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, login, restrictedChallenge()))
        val error = assertThrows(InbarSmsRestricted::class.java) { http.requestSms("test", "test") }
        assertTrue(error.message!!.contains("try again later"))
        assertEquals(2, requests.size)
        assertThrows(IOException::class.java) { http.verifySms("12345") }
        assertEquals(2, requests.size)
    }

    @Test fun serverVerificationRestrictionIsDistinctFromIncorrectCode() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, login, challenge(), restrictedChallenge()))
        http.requestSms("test", "test")
        assertThrows(InbarSmsRestricted::class.java) { http.verifySms("12345") }
        assertThrows(IOException::class.java) { http.verifySms("56789") }
        assertEquals(3, requests.size)
    }

    @Test fun yearSelectionPostsFreshStateToGradesEndpoint() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, grades(2027), grades(2026)))
        assertEquals(2026, http.grades(2026).year)
        val sent = fields(requests[1])
        assertEquals("POST", requests[1].method)
        assertEquals("grades-state-2027", sent["__PageDataKey"])
        assertEquals("ctl00${'$'}cmbActiveYear", sent["__EVENTTARGET"])
        assertEquals("2026", sent["ctl00${'$'}cmbActiveYear"])
        assertEquals("", sent["__EVENTARGUMENT"])
    }

    @Test fun verificationReusesGradesLandingInsteadOfFetchingItAgain() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val index = requests.lastIndex
            check(index <= 3) { "Redundant grades request after verification" }
            val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .message("OK")
            if (index == 2) response.code(302).header("Location", InbarHttp.GRADES).body("".toResponseBody())
            else response.code(200).body(when (index) {
                0 -> login
                1 -> challenge()
                else -> grades()
            }.toResponseBody())
            response.build()
        }.build()
        val http = InbarHttp(transport = client)
        http.requestSms("test", "test")
        assertEquals(2026, http.verifySms("12345").year)
        assertEquals(4, requests.size)
        assertEquals("/Live/StudentGradesList.aspx", requests.last().url.encodedPath)
        assertEquals("/Live/StudentGradesList.aspx", requests.first().url.queryParameter("ReturnUrl"))
    }

    @Test fun successiveYearSwitchesReuseLatestFormAndSameYearRefreshStillFetches() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, grades(2027), grades(2026), grades(2027), grades(2027)))
        http.grades()
        http.grades(2026)
        http.grades(2027)
        http.grades(2027)
        assertEquals(listOf("GET", "POST", "POST", "GET"), requests.map { it.method })
        assertEquals("grades-state-2027", fields(requests[1])["__PageDataKey"])
        assertEquals("grades-state-2026", fields(requests[2])["__PageDataKey"])
    }

    @Test fun parsesCourseRowsHiddenAssignmentsFinalGradesAndPendingCourses() {
        val parsed = InbarGrades.parse(grades())
        assertEquals(listOf(2026, 2027), parsed.years)
        assertEquals(2, parsed.courses.size)
        val course = parsed.courses[0]
        assertEquals("2026:sample-01", course.getString("courseKey"))
        assertEquals("c", course.getString("semester"))
        val grades = course.getJSONArray("grades")
        assertEquals(3, grades.length())
        assertEquals("85", grades.getJSONObject(0).getString("weight"))
        assertEquals(1, grades.getJSONObject(0).getJSONArray("submissions").length())
        assertEquals("final", grades.getJSONObject(2).getString("type"))
        assertEquals("73", grades.getJSONObject(2).getString("grade"))
        assertEquals(0, parsed.courses[1].getJSONArray("grades").length())
        assertEquals("a", parsed.courses[1].getString("semester"))
    }

    @Test fun badCodeKeepsUpdatedChallengeForManualRetry() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, login, challenge(), challenge("retry-state"), "<html>Success</html>", grades()))
        http.requestSms("test", "test")
        assertThrows(IOException::class.java) { http.verifySms("11111") }
        http.verifySms("22222")
        assertEquals("retry-state", fields(requests[3])["__PageDataKey"])
    }

    @Test fun emptyTeachingGroupDoesNotCreateAnotherTabForGradedCourse() {
        val page = grades().replace("Pending course", "Sample algebra").replace("סמסטר א'", "סמסטר קיץ")
        val parsed = InbarGrades.parse(page)
        assertEquals(1, parsed.courses.size)
        val course = parsed.courses.single()
        assertEquals("sample-01", course.getString("id"))
        assertEquals(3, course.getJSONArray("grades").length())
        assertEquals("sample-02", course.getJSONArray("relatedGroups").getJSONObject(0).getString("id"))
    }

    @Test fun independentlyGradedGroupsRemainSeparate() {
        val page = grades().replace("Pending course", "Sample algebra").replace("סמסטר א'", "סמסטר קיץ")
            .replace("lblRowFinalGrade_1\"></span>", "lblRowFinalGrade_1\">61</span>")
        assertEquals(2, InbarGrades.parse(page).courses.size)
    }

    @Test fun pendingCourseWithOnlyEmptyGroupsStillAppearsOnce() {
        val doc = org.jsoup.Jsoup.parse(grades())
        val row = doc.select("#ContentPlaceHolder1_gvGradesList > tbody > tr").last()!!
        row.parent()!!.appendChild(row.clone().apply { child(0).text("sample-03") })
        val parsed = InbarGrades.parse(doc.outerHtml())
        assertEquals(2, parsed.courses.size)
        assertEquals(1, parsed.courses.count { it.optString("name") == "Pending course" })
    }

    @Test fun expiredSessionIsNotReportedAsEmptyGrades() {
        assertThrows(InbarSessionExpired::class.java) { InbarGrades.parse(login) }
    }

    @Test fun resendCooldownPreventsExtraSmsRequests() {
        val requests = mutableListOf<Request>()
        val http = InbarHttp(transport = transport(requests, login, challenge()))
        http.requestSms("test", "test")
        assertThrows(IOException::class.java) { http.resendSms() }
        assertEquals(2, requests.size)
    }

    @Test fun externalRedirectIsRejectedBeforeFollowingIt() {
        val requests = mutableListOf<Request>()
        val transport = OkHttpClient.Builder().addInterceptor {
            requests += it.request()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://example.org/sso").body("".toResponseBody()).build()
        }.build()
        assertThrows(IOException::class.java) { InbarHttp(transport = transport).requestSms("test", "test") }
        assertEquals(1, requests.size)
    }

    @Test fun expiredGradesStopBeforeLoginOrSmsAuthenticationRedirect() {
        for (path in listOf("/Live/Login.aspx", "/Live/Authenticate.aspx?AuthLevel=SmsAndNormal")) {
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor {
                requests += it.request()
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location", path).body("".toResponseBody()).build()
            }.build()
            assertThrows(InbarSessionExpired::class.java) { InbarHttp(transport = client).grades() }
            assertEquals(1, requests.size)
            assertEquals("/Live/StudentGradesList.aspx", requests.single().url.encodedPath)
        }
    }

    @Test fun expiredYearPostbackDoesNotFollowAnotherSmsChallenge() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor {
            requests += it.request()
            val response = Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).message("OK")
            if (requests.size == 1) response.code(200).body(grades(2027).toResponseBody())
            else response.code(303).header("Location", "/Live/Authenticate.aspx").body("".toResponseBody())
            response.build()
        }.build()
        val http = InbarHttp(transport = client)
        http.grades()
        assertThrows(InbarSessionExpired::class.java) { http.grades(2026) }
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
    }

    @Test fun cookiesMatchTheirPortalAndSurviveSerialization() {
        val jar = InbarCookieJar()
        val url = InbarHttp.BASE.toHttpUrl()
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=test-only; Secure; HttpOnly; Path=/Live")!!))
        val restored = InbarCookieJar().apply { restore(jar.toJson()) }
        assertEquals(1, restored.loadForRequest(InbarHttp.GRADES.toHttpUrl()).size)
        assertEquals(0, restored.loadForRequest("https://example.org/Live".toHttpUrl()).size)
    }

    @Test fun smsExtractionPreservesLeadingZeroAndRejectsAmbiguousMessages() {
        assertEquals("01234", inbarSmsCode("Inbar verification code: 01234"))
        assertNull(inbarSmsCode("Codes 12345 and 56789"))
        assertNull(inbarSmsCode("Phone 0501234567"))
        assertNull(inbarSmsCode("No verification code"))
    }

    @Test fun automaticVerificationAcceptsOnlyBrandedSingleCodeMessages() {
        assertEquals("01234", inbarAutomaticSmsCode("Inbar verification code: 01234", "Portal"))
        assertEquals("01234", inbarAutomaticSmsCode("קוד האימות לאינ-בר הוא 01234", "Portal"))
        assertEquals("01234", inbarAutomaticSmsCode("Verification code: 01234", "Bar-Ilan"))
        assertNull(inbarAutomaticSmsCode("Bank verification code: 01234", "Bank"))
        assertNull(inbarAutomaticSmsCode("Inbar codes 01234 and 56789", "Portal"))
    }
}
