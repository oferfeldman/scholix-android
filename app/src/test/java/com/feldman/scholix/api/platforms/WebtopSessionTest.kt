package com.feldman.scholix.api.platforms

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebtopSessionTest {
    private val profile = """{"userId":"student","classCode":10,"classNumber":5,"institutionCode":123456}"""
    private val settings = """{"status":true,"data":{"currentStudyYear":2027,"children":[{"id":"encrypted-student"}],"divisionsList":[{"division_id":42,"year_division":1}]}}"""
    private val timetable = """{"status":true,"data":[{"dayIndex":3,"hoursData":[{"hour":1,"hourName":"1 08:00 - 08:45","scheduale":[{"subject":"Math","teacherPrivateName":"Test","teacherLastName":"Teacher"}]}]}]}"""

    private fun platform(handler: (Request) -> Pair<Int, String>): WebtopPlatform {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .apply {
                    if (request.url.encodedPath.endsWith("LoginByUserNameAndPassword") && code == 200) {
                        addHeader("Set-Cookie", "webToken=renewed")
                    }
                }.body(body.toResponseBody()).build()
        }.build()
        return WebtopPlatform(client).apply {
            setUsername("normal-user")
            setPassword("fixture-password")
            adoptPasswordLoginData(JSONObject(profile), "webToken=expired")
        }
    }

    @Test fun expiredSessionIsRenewedBeforeShotefCanReturnAnEmptyTimetable() {
        val paths = mutableListOf<String>()
        val platform = platform { request ->
            paths += request.url.encodedPath.substringAfterLast('/')
            when (paths.last()) {
                "GetSettingsList" -> if (request.header("Cookie") == "webToken=expired") 401 to "{}" else 200 to settings
                "LoginByUserNameAndPassword" -> 200 to """{"status":true,"data":$profile}"""
                "ShotefSchedualeData" -> {
                    assertEquals("webToken=renewed", request.header("Cookie"))
                    200 to timetable
                }
                else -> error("Unexpected request")
            }
        }
        assertEquals("Math", platform.getSchedule(2).getJSONObject("1_0").getString("subject"))
        assertEquals("Math", platform.getOriginalSchedule(2).getJSONObject("1_0").getString("subject"))
        assertEquals(listOf("GetSettingsList", "LoginByUserNameAndPassword", "GetSettingsList", "ShotefSchedualeData"), paths)
    }

    @Test fun legacyNormalAccountMislabeledAsMinistryLoginRecoversAndCorrectsItsMethod() {
        val platform = platform { request ->
            when {
                request.url.host == "www.webtop.co.il" -> 200 to "<script>_serverUrl='https://lgn.edu.gov.il/login';</script>"
                request.url.host == "lgn.edu.gov.il" -> 200 to """{"isError":true}"""
                request.url.encodedPath.endsWith("LoginByUserNameAndPassword") -> 200 to """{"status":true,"data":$profile}"""
                else -> error("Unexpected request")
            }
        }
        platform.loginMethod = "moe"
        assertTrue(platform.refreshCookies())
        assertEquals("password", platform.loginMethod)
        assertEquals("webToken=renewed", platform.cookies)
        assertFalse(platform.isMoe())
    }

    @Test fun attendanceRenewsAnExpiredSessionAndUsesTheFreshSettings() {
        var settingsCalls = 0
        val platform = platform { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "GetSettingsList" -> if (++settingsCalls == 1) 401 to "{}" else 200 to settings
                "LoginByUserNameAndPassword" -> 200 to """{"status":true,"data":$profile}"""
                "GetPupilDiciplineEvents" -> {
                    assertEquals("webToken=renewed", request.header("Cookie"))
                    val payload = JSONObject(Buffer().apply { request.body!!.writeTo(this) }.readUtf8())
                    assertEquals("encrypted-student", payload.getString("studentID"))
                    assertEquals(42, payload.getInt("periodID"))
                    200 to """{"status":true,"data":{"diciplineEvents":[{"eventType":"Absent","eventDate":"2026-11-03","subjectName":"Math"}]}}"""
                }
                else -> error("Unexpected request")
            }
        }
        assertEquals(1, platform.getAttendanceEvents(2027, "a").getJSONObject("events").getJSONArray("Absent").length())
        assertEquals(2, settingsCalls)
    }

    @Test fun attendanceUnauthorizedAfterSettingsWereCachedRetriesOnce() {
        var attendanceCalls = 0
        var logins = 0
        var settingsCalls = 0
        val platform = platform { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "GetSettingsList" -> { settingsCalls++; 200 to settings }
                "LoginByUserNameAndPassword" -> { logins++; 200 to """{"status":true,"data":$profile}""" }
                "GetPupilDiciplineEvents" -> if (++attendanceCalls == 1) 403 to "{}"
                    else 200 to """{"status":true,"data":{"diciplineEvents":[]}}"""
                else -> error("Unexpected request")
            }
        }
        platform.fetchPupilCardSettings(2027)
        assertFalse(platform.getAttendanceEvents(2027, "a").has("error"))
        assertEquals(2, attendanceCalls)
        assertEquals(2, settingsCalls)
        assertEquals(1, logins)
    }

    @Test fun failedRenewalIsAnAuthenticationErrorForScheduleAndAttendance() {
        for (original in listOf(false, true)) {
            var logins = 0
            val platform = platform { request ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "GetSettingsList" -> 401 to "{}"
                    "LoginByUserNameAndPassword" -> { logins++; 200 to """{"status":false}""" }
                    else -> error("Unauthenticated timetable must not be requested")
                }
            }
            val result = if (original) platform.getOriginalSchedule(2) else platform.getSchedule(2)
            assertEquals("login_failed", result.getString("error"))
            assertEquals(1, logins)
            assertFalse(platform.loggedIn)
            assertEquals("login_failed", platform.getAttendanceEvents(2027, "a").getString("error"))
        }
    }

    @Test fun attendanceServerFailureIsNotAnEmptyAttendanceRecord() {
        val platform = platform { request ->
            if (request.url.encodedPath.endsWith("GetSettingsList")) 200 to settings else 500 to "{}"
        }
        assertEquals("server_unreachable", platform.getAttendanceEvents(2027, "a").getString("error"))
    }
}
