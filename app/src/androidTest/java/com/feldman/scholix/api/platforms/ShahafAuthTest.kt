package com.feldman.scholix.api.platforms

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShahafAuthTest {
    @Test fun decodesShahafMoeCallbackAndRestoresSession() {
        val auth = JSONObject().put("Name", "תלמיד לבדיקה").put("Token", "fixture-token")
            .put("Student", JSONObject().put("Semel", 123456).put("Grade", 10).put("Class", 5).put("ClassMid", 1005))
        val encoded = Base64.encodeToString(auth.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .replace('=', '.').replace('/', '_').replace('+', '-')
        val decoded = ShahafApi.authResponseFromUrl("https://timetable.shahaf.info/SignInSuccess.aspx?s=$encoded")!!
        assertEquals("fixture-token", decoded.getString("Token"))
        assertEquals("תלמיד לבדיקה", decoded.getString("Name"))
        val profile = ShahafPlatform().toJson().put("authResponse", decoded).put("loggedIn", true)
        val restored = ShahafPlatform.fromJson(profile)
        assertTrue(restored.isLoggedIn())
        assertEquals(profile.getString("id"), restored.id)
        assertEquals(profile.getString("deviceId"), restored.deviceId)
        assertEquals(1005, restored.toJson().getJSONObject("authResponse").getJSONObject("Student").getInt("ClassMid"))
    }

    @Test fun onlyAcceptsTheShahafHttpsCallback() {
        assertNull(ShahafApi.authResponseFromUrl("https://example.com/SignInSuccess.aspx?s=fixture"))
        assertNull(ShahafApi.authResponseFromUrl("http://timetable.shahaf.info/SignInSuccess.aspx?s=fixture"))
        assertNull(ShahafApi.authResponseFromUrl("https://timetable.shahaf.info/ShfSignIn.aspx?sid=shfapp"))
    }
}
