package com.feldman.scholix.api.platforms

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SchoolyPlatformTest {
    @Test fun gradesKeepZeroAndTextButExcludeOtherStudentsAndUnpublishedEvents() {
        val card = JSONObject("""{"assessmentDetails":[
            {"_id":"zero","studentId":101,"value":0,"event":{"name":"Quiz","subjectName":"Math","dt":"20261021","maxValue":20}},
            {"_id":"text","student_login_id":101,"value":null,"text":"Excellent","event":{"name":"Reading","subjectName":"English","dt":"2026-10-22"}},
            {"_id":"other","studentId":202,"value":95,"event":{"name":"Other student's grade"}},
            {"_id":"hidden","studentId":101,"value":95,"event":{"published":false}},
            {"_id":"deleted","studentId":101,"value":95,"event":{"_deleted":true}}
        ]}""")
        val grades = schoolyGrades(card, "101")
        assertEquals(2, grades.length())
        assertEquals("0", grades.getJSONObject(0).getString("grade"))
        assertEquals("21/10/2026", grades.getJSONObject(0).getString("date"))
        assertEquals(20, grades.getJSONObject(0).getInt("maxGrade"))
        assertEquals("Excellent", grades.getJSONObject(1).getString("grade"))
    }

    @Test fun scheduleMapsUpdatedAndOriginalLessonsWithBellTimesAndHourOffset() {
        val response = JSONObject("""{"success":true,"data":{
            "firstHour":1,"groups":[{"_id":"g","teachers":["teacher"],"CODE_RAMAT_KVUTSA":5}],
            "teachers":[{"_id":"teacher","name":"Teacher A"}],"subjects":[{"_id":"math","name":"Math"}],
            "byDate":[{"ttGroups":[{"_id":"fixed","day":1,"slot":0,"group_id":"g","subject":"math"}],
                "ttSubjects":[{"_id":"updated","day":1,"slot":0,"group_id":"g","group_name":"Math","effectiveRoom_name":"12","isVoided":true,"note":"Cancelled","movedTo":false},
                    {"_id":"other-day","day":2,"slot":0,"group_name":"English"}]}]}}""")
        val bells = JSONArray("""[{"periods":[{"start":"08:00","end":"08:45"}]}]""")
        val updated = schoolySchedule(response, 1, false, bells)
        assertEquals(1, updated.length())
        val lesson = updated.getJSONObject("updated")
        assertEquals(1, lesson.getInt("hour"))
        assertEquals("08:00 - 08:45", lesson.getString("time"))
        assertEquals("Teacher A", lesson.getString("teacher"))
        assertEquals("cancel-cell", lesson.getString("colorClass"))
        val original = schoolySchedule(response, 1, true, bells)
        assertEquals("Math", original.getJSONObject("fixed").getString("subject"))
        assertEquals("", original.getJSONObject("fixed").getString("colorClass"))
    }

    @Test fun legacyWeeklyArrayResponseIsAlsoAccepted() {
        val response = JSONObject("""{"data":[{"subjects":[{"_id":"s","name":"Science"}],
            "ttGroups":[{"_id":"fixed","day":0,"slot":2,"subject":"s"}]}]}""")
        assertEquals("Science", schoolySchedule(response, 0, false).getJSONObject("fixed").getString("subject"))
    }

    @Test fun otpSessionUsesCookiesAndBearerTokenAndKeepsLinkedStudentsSeparate() {
        val server = MockWebServer()
        server.start()
        try {
            fun enqueue(json: String, cookie: Boolean = false) {
                server.enqueue(MockResponse().setBody(json).apply {
                    if (cookie) addHeader("Set-Cookie", "schooly-session=fixture; Path=/; HttpOnly")
                })
            }
            val provider = SchoolyPlatform().apply { api = SchoolyApi(server.url("/")) }
            enqueue("""{"success":true,"contactOptions":[{"type":"phone","label":"SMS","masked":"050***0000"}]}""")
            val contact = provider.identify("123456789").single()
            assertEquals("/api/auth/identify", server.takeRequest().path)
            enqueue("""{"success":true}""")
            provider.sendCode(contact)
            assertEquals("050***0000", JSONObject(server.takeRequest().body.readUtf8()).getString("contactValue"))
            enqueue("""{"success":true,"token":"fixture-token","data":{},"schoolEntities":[{"school_id":11,"school_name":"School A"},{"school_id":12,"school_name":"School B"}]}""", true)
            assertEquals(2, provider.verifyCode("123456").size)
            server.takeRequest()
            enqueue("""{"success":true,"token":"fixture-token","data":{}}""")
            enqueue("""{"success":true}""")
            enqueue("""{"success":true,"data":{"student_login_id":500,"current_school_id":12,"current_user":{
                "isParent":true,"childrenPupils":[{"student_login_id":101,"student_f_name":"Student A"},{"student_login_id":202,"student_f_name":"Student B"}]}}}""")
            assertTrue(provider.verifyCode("123456", "12").isEmpty())
            val verify = server.takeRequest()
            assertEquals("12", JSONObject(verify.body.readUtf8()).getString("school_id"))
            server.takeRequest()
            val me = server.takeRequest()
            assertEquals("/api/users/me", me.path)
            assertEquals("Bearer fixture-token", me.getHeader("Authorization"))
            assertEquals("schooly-session=fixture", me.getHeader("Cookie"))
            provider.selectStudent("202")
            val restored = SchoolyPlatform.fromJson(provider.toJson())
            assertTrue(restored.isLoggedIn())
            assertEquals("202", restored.selectedStudentId)
            assertEquals(listOf("schooly:101", "schooly:202"), restored.getCourses().map { it.getString("courseKey") })

            enqueue("""{"data":{"periods":[]}}""")
            enqueue("""{"data":{"assessmentDetails":[{"_id":"a","studentId":101,"value":85,"event":{"name":"Quiz","subjectName":"Math"}}]}}""")
            assertEquals("85", provider.getGrades("schooly:101", 2027, null).getJSONObject(0).getString("grade"))
            assertEquals("/v1/schools/periods", server.takeRequest().path)
            assertEquals("101", server.takeRequest().requestUrl!!.queryParameter("student_login_id"))
        } finally { server.shutdown() }
    }

    @Test fun expiredSessionNeverSendsAnotherVerificationCodeInTheBackground() {
        val server = MockWebServer()
        server.start()
        try {
            val provider = SchoolyPlatform().apply { api = SchoolyApi(server.url("/")); loggedIn = true }
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            assertFalse(provider.refreshCookies())
            assertFalse(provider.isLoggedIn())
            assertEquals("/api/users/me", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun yearlyGradesMergeAllMatchingPeriodsWithoutDuplicatingAnEvent() {
        val server = MockWebServer()
        server.start()
        try {
            val provider = SchoolyPlatform.fromJson(JSONObject("""{"id":"fixture","loggedIn":true,"selectedStudentId":"101",
                "students":[{"student_login_id":101,"student_f_name":"Student A"}]}""")).apply { api = SchoolyApi(server.url("/")) }
            server.enqueue(MockResponse().setBody("""{"data":{"periods":[
                {"_id":"first","academicYear":2027},{"_id":"second","academicYear":2027},{"_id":"older","academicYear":2026}]}}"""))
            val first = """{"_id":"a","studentId":101,"value":80,"event":{"name":"Exam A","subjectName":"Math"}}"""
            val second = """{"_id":"b","studentId":101,"value":90,"event":{"name":"Exam B","subjectName":"Math"}}"""
            server.enqueue(MockResponse().setBody("""{"data":{"assessmentDetails":[$first]}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"assessmentDetails":[$first,$second]}}"""))
            assertEquals(2, provider.getGrades("schooly:101", 2027, null).length())
            server.takeRequest()
            assertEquals("first", server.takeRequest().requestUrl!!.queryParameter("period"))
            assertEquals("second", server.takeRequest().requestUrl!!.queryParameter("period"))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
