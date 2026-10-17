package com.feldman.scholix.api.platforms

import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtopPasswordLoginTest {
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
