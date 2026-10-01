package com.feldman.scholix.api.platforms

import android.util.Base64
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.ProviderCourseOverrides
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Type
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android Keystore, using synthetic sessions only. */
@RunWith(AndroidJUnit4::class)
class InbarSessionTest {
    @Test fun savedLoginDetailsUseExistingProviderStorageAndSurviveSessionExpiry() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("inbar_saved_login_test_$name", mode)
        }
        val prefs = context.getSharedPreferences("platform_prefs", Context.MODE_PRIVATE)
        try {
            val course = JSONObject().put("id", "synthetic-01").put("courseKey", "2026:synthetic-01")
                .put("year", 2026).put("name", "Synthetic course").put("period", "Summer").put("grades", JSONArray())
            val encoded = JSONObject().put("id", "LOGIN123").put("class", InbarPlatform::class.java.name)
                .put("identity", "synthetic-passport").put("mobile", "synthetic-mobile").put("name", "Saved account")
                .put("loggedIn", false).put("courses", JSONArray().put(course))
                .put("encryptedSession", InbarSessionCipher.encrypt("[]"))
            val pending = InbarPlatform.fromJson(encoded) as InbarPlatform
            PlatformStorage.addPlatforms(context, listOf(pending))
            val courseKey = PlatformStorage.courseOverrideKey(course)
            val overrides = ProviderCourseOverrides(hiddenCourseKeys = setOf(courseKey),
                courseOrder = listOf(courseKey), courseNames = mapOf(courseKey to "My course"))
            PlatformStorage.saveProviderCourseOverrides(context, pending.id, overrides)
            val stored = PlatformStorage.loadPlatforms(context).single() as InbarPlatform
            val nextLogin = stored.forSmsLogin()
            assertTrue(nextLogin.hasSavedLoginDetails())
            assertEquals("synthetic-passport", nextLogin.getUsername())
            assertEquals("synthetic-mobile", nextLogin.mobile)
            assertEquals("LOGIN123", nextLogin.id)
            assertEquals("Saved account", nextLogin.getName())
            assertEquals("Synthetic course", nextLogin.getCourses().single().getString("name"))
            assertFalse(nextLogin.isLoggedIn())
            assertTrue(nextLogin.canRestoreSession)
            PlatformStorage.addPlatforms(context, listOf(nextLogin))
            assertEquals(1, PlatformStorage.loadPlatforms(context).size)
            assertEquals(overrides, PlatformStorage.loadProviderCourseOverrides(context, nextLogin.id))
            val serialized = nextLogin.toJson()
            assertFalse(serialized.has("smsCode"))
            assertFalse(serialized.has("password"))
            assertEquals("[]", InbarSessionCipher.decrypt(serialized.getString("encryptedSession")))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun loginFieldsConstructorUsesTheSameBaseAsOtherPlatforms() {
        val fields = LoginFields().addField("id", Type.Id, "synthetic-passport")
            .addField("mobile", Type.Custom("mobile"), "synthetic-mobile")
        val account = InbarPlatform(fields)
        assertTrue(account.hasSavedLoginDetails())
        assertEquals("synthetic-passport", account.getUsername())
        assertEquals("synthetic-mobile", account.mobile)
        assertFalse(account.isLoggedIn()) // Construction alone never requests an SMS.
        assertFalse(account.canRestoreSession) // First-time setup still uses the shared login form.
    }

    @Test fun encryptedCookiesRoundTripWithRandomIv() {
        val cookies = "[\"session=synthetic; secure; path=/Live\"]"
        val first = InbarSessionCipher.encrypt(cookies)
        val second = InbarSessionCipher.encrypt(cookies)
        assertNotEquals(first, second)
        assertEquals(cookies, InbarSessionCipher.decrypt(first))
        assertEquals(cookies, InbarSessionCipher.decrypt(second))
    }

    @Test fun alteredCiphertextIsRejected() {
        val encoded = InbarSessionCipher.encrypt("synthetic session")
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            InbarSessionCipher.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    @Test fun reflectiveRestoreKeepsProviderIdAndRequiresLoginWhenKeyDataIsMissing() {
        val source = InbarPlatform("TEST1234").apply {
            setName("Test account")
            setUsername("synthetic-passport")
        }.toJson()
        assertFalse(source.has("password"))
        assertFalse(source.has("smsCode"))
        assertFalse(source.has("cookies"))
        val method = Class.forName(source.getString("class")).getMethod("fromJson", JSONObject::class.java)
        fun cachedGroup(code: String, hasGrade: Boolean) = JSONObject()
            .put("id", code).put("courseKey", "2026:$code").put("year", 2026).put("name", "Synthetic course")
            .put("period", "Summer").put("credits", if (hasGrade) "4" else "2")
            .put("grades", if (hasGrade) JSONArray().put(JSONObject().put("grade", "71")) else JSONArray())
        source.put("courses", JSONArray().put(cachedGroup("sample-01", false)).put(cachedGroup("sample-02", true)))
        val restored = method.invoke(null, source) as InbarPlatform
        assertEquals("TEST1234", restored.id)
        assertEquals("Test account", restored.getName())
        assertEquals(1, restored.getCourses().size)
        assertEquals("sample-02", restored.getCourses().single().getString("id"))
        source.put("encryptedSession", "invalid").put("loggedIn", true)
        assertFalse((method.invoke(null, source) as InbarPlatform).isLoggedIn())
    }
}
