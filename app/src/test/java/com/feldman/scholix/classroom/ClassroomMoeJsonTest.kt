package com.feldman.scholix.classroom

import com.feldman.scholix.ui.parseEvalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomMoeJsonTest {

    @Test
    fun parsesEvaluateJavascriptDoubleEscapedString() {
        // This is exactly what evaluateJavascript returned in the live device logcat:
        // "{\"success\":true,\"email\":\"1002755976@educ.org.il\",\"studentId\":\"1002755976\"}"
        val rawFromAndroid = "\"{\\\"success\\\":true,\\\"email\\\":\\\"1002755976@educ.org.il\\\",\\\"studentId\\\":\\\"1002755976\\\"}\""
        val json = parseEvalJson(rawFromAndroid)
        assertNotNull(json)
        assertTrue(json!!.optBoolean("success"))
        assertEquals("1002755976@educ.org.il", json.optString("email"))
        assertEquals("1002755976", json.optString("studentId"))
    }

    @Test
    fun parsesTrimmedSingleEscapedString() {
        val trimmed = "{\\\"success\\\":true,\\\"email\\\":\\\"1002755976@educ.org.il\\\",\\\"studentId\\\":\\\"1002755976\\\"}"
        val json = parseEvalJson(trimmed)
        assertNotNull(json)
        assertTrue(json!!.optBoolean("success"))
        assertEquals("1002755976@educ.org.il", json.optString("email"))
    }

    @Test
    fun parsesDirectJsonObjectString() {
        val direct = "{\"success\":true,\"email\":\"1002755976@educ.org.il\",\"studentId\":\"1002755976\"}"
        val json = parseEvalJson(direct)
        assertNotNull(json)
        assertTrue(json!!.optBoolean("success"))
        assertEquals("1002755976@educ.org.il", json.optString("email"))
    }

    @Test
    fun ignoresPendingOrNull() {
        assertNull(parseEvalJson(null))
        assertNull(parseEvalJson("null"))
        assertNull(parseEvalJson("\"pending\""))
        assertNull(parseEvalJson("pending"))
        assertNull(parseEvalJson(""))
    }
}
