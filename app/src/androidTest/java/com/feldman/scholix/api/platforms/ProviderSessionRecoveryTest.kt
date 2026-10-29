package com.feldman.scholix.api.platforms

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.MainActivity
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.Type
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderSessionRecoveryTest {
    @Test fun webtopScheduleFailuresAreRetriedAndNeverCachedAsEmptyDays() {
        val valid = """{"status":true,"data":[{"dayIndex":1,"hoursData":[{"hour":1,"hourName":"08:00 - 08:45","scheduale":[{"subject":"Math"}]}]}]}"""
        for (failure in listOf("""{"status":false,"data":[]}""", """{"status":true,"data":null}""")) {
            for (original in listOf(false, true)) {
                var calls = 0
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    calls++
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body((if (calls <= 2) failure else valid).toResponseBody()).build()
                }.build()
                val platform = WebtopPlatform(client).apply {
                    studentInstitution = "123456"
                    studentClass = "9|6"
                }
                fun load() = if (original) platform.getOriginalSchedule(0, null, null) else platform.getSchedule(0, null, null)
                assertEquals("server_unreachable", load().optString("error"))
                assertEquals("Retry must be bounded", 2, calls)
                assertEquals("Math", load().getJSONObject("1_0").getString("subject"))
                assertEquals("Failed data must not be cached", 3, calls)
                assertEquals("Math", load().getJSONObject("1_0").getString("subject"))
                assertEquals("Successful data should still be cached", 3, calls)
            }
        }
    }

    @Test fun webtopRenewalPersistsWithoutOverwritingAccountEditsOrNewerSignIns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("platform_prefs", Context.MODE_PRIVATE)
        val original = prefs.getString(PlatformStorage.KEY_PLATFORMS, null)
        fun account(cookie: String) = WebtopPlatform.fromJson(JSONObject()
            .put("id", "webtop-renewal-fixture").put("cookies", cookie).put("loggedIn", true)
            .put("username", "fixture").put("password", "fixture").put("loginMethod", "password"))
        try {
            val saved = account("old").apply { platformDisplayName = "Renamed account" }
            PlatformStorage.savePlatforms(context, listOf(saved))
            PlatformStorage.saveWebtopSession(context, account("renewed"), "old")
            val renewed = PlatformStorage.loadPlatforms(context).single() as WebtopPlatform
            assertEquals("renewed", renewed.cookies)
            assertEquals("Renamed account", renewed.platformDisplayName)
            PlatformStorage.savePlatforms(context, listOf(account("new-sign-in")))
            PlatformStorage.saveWebtopSession(context, account("stale-renewal"), "renewed")
            assertEquals("new-sign-in", (PlatformStorage.loadPlatforms(context).single() as WebtopPlatform).cookies)
            PlatformStorage.savePlatforms(context, emptyList())
            PlatformStorage.saveWebtopSession(context, account("renewed"), "old")
            assertTrue(PlatformStorage.loadPlatforms(context).isEmpty())
        } finally {
            prefs.edit().apply {
                if (original == null) remove(PlatformStorage.KEY_PLATFORMS) else putString(PlatformStorage.KEY_PLATFORMS, original)
            }.commit()
        }
    }

    @Test fun mashovEditReadsFilledCredentialsByType() {
        val fields = MashovPlatform().getLoginFields().apply {
            setValue("School code", "123456")
            setValue("School year", "2027")
            setValue("Username", "student")
            setValue("Password", "fixture")
        }
        assertEquals("student", fields.getValueByType(Type.Username))
        assertEquals("fixture", fields.getValueByType(Type.Password))
    }

    /** Opt-in: uses saved accounts without printing credentials or changing remote data. */
    @Test fun savedAccountsRecoverExpiredSessionsWithoutSettingsSave() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerLiveRecovery") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("platform_prefs", Context.MODE_PRIVATE)
        val original = prefs.getString(PlatformStorage.KEY_PLATFORMS, "[]")!!
        val profiles = JSONArray(original)
        val targets = (0 until profiles.length()).map { profiles.getJSONObject(it) }.filter {
            it.optString("class") in listOf(OpenAUPlatform::class.java.name, StudentsPortalPlatform::class.java.name)
        }
        assumeTrue(targets.size == 2)
        try {
            for (profile in targets) {
                profile.put("cookies", "expired=fixture").put("loggedIn", false).remove("cookieJar")
            }
            assertTrue(prefs.edit().putString(PlatformStorage.KEY_PLATFORMS, profiles.toString()).commit())
            ActivityScenario.launch(MainActivity::class.java).use {
                val deadline = SystemClock.elapsedRealtime() + 150_000
                var recovered = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    val current = PlatformStorage.loadPlatforms(context).associateBy { it.id }
                    if (targets.all { current[it.getString("id")]?.isLoggedIn() == true }) {
                        recovered = true
                        break
                    }
                    SystemClock.sleep(250)
                }
                assertTrue("Saved providers did not recover their expired sessions", recovered)
                val restored = PlatformStorage.loadPlatforms(context)
                val openU = restored.filterIsInstance<OpenAUPlatform>().single()
                assertTrue(openU.getCourses().isNotEmpty())
                val serialized = openU.toJson()
                assertTrue(serialized.getJSONArray("cookieJar").length() > 0)
                val reloaded = OpenAUPlatform.fromJson(JSONObject(serialized.toString()))
                assertTrue("OpenU session must work after serialization", reloaded.refreshCookies())
                assertTrue(restored.filterIsInstance<StudentsPortalPlatform>().single().refreshCookies())
            }
        } finally {
            prefs.edit().putString(PlatformStorage.KEY_PLATFORMS, original).commit()
        }
    }
}
