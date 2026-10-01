package com.feldman.scholix.api.platforms

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android Keystore, using synthetic sessions only. */
@RunWith(AndroidJUnit4::class)
class InbarSessionTest {
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
