package com.feldman.scholix.classroom

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ClassroomApiTest {
    @Test fun followsAllPagesAndUsesStudentAccountAndRepeatedStates() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer test-token", request.header("Authorization"))
            assertEquals("me", request.url.queryParameter("studentId"))
            assertEquals(listOf("ACTIVE", "ARCHIVED"), request.url.queryParameterValues("courseStates"))
            val body = if (calls++ == 0) {
                assertNull(request.url.queryParameter("pageToken"))
                """{"courses":[{"id":"1"}],"nextPageToken":"page,two"}"""
            } else {
                assertEquals("page,two", request.url.queryParameter("pageToken"))
                """{"courses":[{"id":"2"}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val result = ClassroomApi({ "test-token" }, client).list("courses", "courses", mapOf("studentId" to "me", "courseStates" to "ACTIVE,ARCHIVED"))
        assertEquals(listOf("1", "2"), result.map { it.getString("id") })
    }

    @Test fun failedSecondPageDoesNotReturnAnIncompleteSnapshot() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val first = calls++ == 0
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (first) 200 else 403).message("Response")
                .body((if (first) """{"courses":[{"id":"1"}],"nextPageToken":"next"}"""
                else """{"error":{"message":"School administrator blocked access"}}""").toResponseBody()).build()
        }.build()
        val error = assertThrows(IOException::class.java) { ClassroomApi({ "token" }, client).list("courses", "courses") }
        assertEquals("School administrator blocked access", error.message)
    }

    @Test fun invalidTokenIsRenewedAndRetriedOnce() {
        var token = "expired"
        var renewals = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val fresh = chain.request().header("Authorization") == "Bearer fresh"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (fresh) 200 else 401).message("Response").body("{}".toResponseBody()).build()
        }.build()
        ClassroomApi({ token }, client, { renewals++; token = "fresh" }).get("courses")
        assertEquals(1, renewals)
    }

    @Test fun persistentUnauthorizedResponseRequiresSignInWithoutLooping() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(401).message("Unauthorized").body("{}".toResponseBody()).build()
        }.build()
        assertThrows(ClassroomSignInRequired::class.java) { ClassroomApi({ "expired" }, client, {}).get("courses") }
        assertEquals(2, calls)
    }

    @Test fun publishesAssignedPointsAsPercentageAndKeepsZeroGrades() {
        val work = JSONObject("""{"courseId":"123456789012","id":"456","title":"Exam","maxPoints":20}""")
        val grade = ClassroomMapping.grade(work, JSONObject("""{"assignedGrade":16}"""), "Math")!!
        assertEquals(80.0, grade.getDouble("grade"), 0.0)
        assertEquals(16.0, grade.getDouble("points"), 0.0)
        assertEquals("123456789012:456", grade.getString("id"))
        val zero = ClassroomMapping.grade(work, JSONObject("""{"assignedGrade":0}"""), "Math")!!
        assertEquals(0.0, zero.getDouble("grade"), 0.0)
        val (_, average, _) = com.feldman.scholix.pages.processGrades(org.json.JSONArray(listOf(grade, zero)))
        assertEquals(40f, average, 0f)
    }

    @Test fun neverShowsTeacherDraftGradesAsPublishedGrades() {
        assertNull(ClassroomMapping.grade(JSONObject(), JSONObject("""{"draftGrade":16}"""), "Math"))
        assertNull(ClassroomMapping.grade(JSONObject(), JSONObject("""{"assignedGrade":null}"""), "Math"))
    }

    @Test fun extractsCourseAttachmentsAndSubmittedDriveFiles() {
        val post = JSONObject("""{"materials":[
            {"driveFile":{"driveFile":{"title":"Instructions","alternateLink":"https://drive.google.com/file/1"}}},
            {"driveFile":{"title":"Submitted answer","alternateLink":"https://drive.google.com/file/2"}},
            {"form":{"title":"Quiz","formUrl":"https://docs.google.com/forms/1"}},
            {"link":{"title":"Reference","url":"https://example.com"}}
        ]}""")
        assertEquals(listOf("Instructions", "Submitted answer", "Quiz", "Reference"), ClassroomMapping.materials(post).map { it.getString("title") })
    }

    @Test fun missingDueDateAndLateSubmissionRemainExplicit() {
        assertEquals("No due date", ClassroomMapping.due(JSONObject()))
        assertEquals("Turned in · Late", ClassroomMapping.status(JSONObject("""{"state":"TURNED_IN","late":true}""")))
        assertEquals("Not submitted", ClassroomMapping.status(null))
    }

    @Test fun convertsClassroomUtcDeadlineToLocalTime() {
        val previousZone = java.util.TimeZone.getDefault()
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+02:00"))
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("Oct 4, 2026, 1:30:00 AM", ClassroomMapping.due(JSONObject("""{
                "dueDate":{"year":2026,"month":10,"day":3},"dueTime":{"hours":23,"minutes":30}
            }""")).replace('\u202f', ' ').replace('\u00a0', ' '))
        } finally {
            java.util.TimeZone.setDefault(previousZone)
            java.util.Locale.setDefault(previousLocale)
        }
    }
}
