package com.feldman.scholix.api.platforms

import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class WebtopPasswordLoginTest {
    @Test
    fun acceptsNumericIdsFromSuccessfulPasswordLogin() {
        val platform = WebtopPlatform()
        val data = JSONObject("""{"userId":12345,"classCode":10,"classNumber":2,"institutionCode":123456,"firstName":"Test","lastName":"Student","userType":2}""")
        assertTrue(platform.adoptPasswordLoginData(data, "session=test"))
        assertTrue(platform.isLoggedIn())
        assertEquals("12345", platform.studentId)
        assertEquals("10|2", platform.studentClass)
        assertEquals("123456", platform.studentInstitution)
        assertEquals("Test Student", platform.getName())
        assertEquals("session=test", platform.cookies)
    }

    @Test
    fun acceptsStringIdsAndOptionalProfileFields() {
        val platform = WebtopPlatform()
        assertTrue(platform.adoptPasswordLoginData(JSONObject("""{"userId":"parent-id","institutionCode":123456,"classCode":null,"classNumber":null}"""), "session=parent"))
        assertEquals("parent-id", platform.studentId)
        assertEquals("123456", platform.studentInstitution)
        assertNull(platform.studentClass)
        assertEquals("", platform.getName())
    }

    @Test
    fun rejectsMissingUserIdWithoutCreatingASession() {
        val platform = WebtopPlatform()
        assertFalse(platform.adoptPasswordLoginData(JSONObject(), "session=test"))
        assertFalse(platform.isLoggedIn())
        assertTrue(platform.loginError!!.contains("user ID"))
    }

    @Test
    fun matchesCurrentWebsiteEncryption() {
        // Captured directly from the live website's encryptStringToServer on 2026-10-17.
        val platform = WebtopPlatform()
        assertEquals("xIKv4WqO5o7fO/LgmjaVSw==", platform.encrypt("test-user0"))
        assertEquals("c39fRupZjW7EDKUoH0L09g==", platform.encrypt("0123456780"))
        assertEquals("uDppSUp599nPYfFXTJr6DA==", platform.encrypt("abc\"\\0"))
    }

    @Test
    fun missingCredentialsExposeASafeActionableErrorWithoutNetwork() {
        val platform = WebtopPlatform(LoginFields()
            .addField("username", Type.Username, "")
            .addField("password", Type.Password, ""))
        assertFalse(platform.isLoggedIn())
        assertTrue(platform.loginError!!.contains("Webtop username and password"))
    }
}
