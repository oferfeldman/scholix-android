package com.feldman.scholix.widgets

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.platforms.DemoPlatform
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GradesWidgetFreshnessTest {
    @Test fun fetchesCurrentGradesInsteadOfEmbeddedOldCourseGrades() {
        var requests = 0
        val provider = object : Platform by DemoPlatform() {
            override fun getCourses() = arrayListOf(JSONObject().put("name", "Math").put("courseKey", "math")
                .put("grades", JSONArray().put(JSONObject().put("name", "Old semester").put("grade", "50"))))
            override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
                requests++
                assertEquals("math", course)
                return JSONArray().put(JSONObject().put("name", "Current semester").put("grade", "98"))
            }
        }
        val result = fetchGradesWidgetGrades(InstrumentationRegistry.getInstrumentation().targetContext, provider)
        assertEquals(1, requests)
        assertEquals("Current semester", result.getJSONObject(0).getString("name"))
    }

    @Test fun failedRefreshIsNotAcceptedAsAnEmptyGradeList() {
        val provider = object : Platform by DemoPlatform() {
            override fun getCourses() = arrayListOf(JSONObject().put("name", "Math"))
            override fun getGrades(course: String, year: Int?, semester: String?) = JSONArray().put(JSONObject().put("error", "login_failed"))
        }
        try {
            fetchGradesWidgetGrades(InstrumentationRegistry.getInstrumentation().targetContext, provider)
            fail("A failed request must remain an error")
        } catch (error: IllegalStateException) { assertEquals("login_failed", error.message) }
    }
}
