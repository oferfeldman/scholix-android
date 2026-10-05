package com.feldman.scholix.api.platforms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.feldman.scholix.MainActivity
import com.feldman.scholix.api.PlatformStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Opt-in live test: expires only the development app's local Inbar session, retaining its account. */
@RunWith(AndroidJUnit4::class)
class InbarReauthenticationSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun openingInbarGradesSignsInAndRestoresGrades() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inbarLiveReauthentication") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".inbar.dev"))
        assumeTrue(context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
        val prefs = context.getSharedPreferences("platform_prefs", Context.MODE_PRIVATE)
        fun profiles() = JSONArray(prefs.getString(PlatformStorage.KEY_PLATFORMS, "[]"))
        fun findInbar(array: JSONArray): JSONObject? = (0 until array.length()).map { array.getJSONObject(it) }
            .firstOrNull { it.optString("class") == InbarPlatform::class.java.name }
        val accounts = profiles()
        val account = findInbar(accounts)
        assumeTrue(account != null && account.optString("identity").isNotBlank() && account.optString("mobile").isNotBlank())
        val original = JSONObject(account!!.toString())
        val originalId = account.getString("id")
        val overrides = PlatformStorage.loadProviderCourseOverrides(context, originalId)
        val expiredSession = InbarSessionCipher.encrypt("[]")
        account.put("encryptedSession", expiredSession).put("loggedIn", true)
        assertTrue(prefs.edit().putString(PlatformStorage.KEY_PLATFORMS, accounts.toString()).commit())
        var restored = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val firstInbar = PlatformStorage.getCourses(context).indexOfFirst { it.optString("platformId") == originalId }
                assertTrue("No visible Inbar course available for this check", firstInbar >= 0)
                if (firstInbar == 0) {
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithText("Load Inbar grades").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("Load Inbar grades").performClick()
                } else {
                    // Other providers may appear first; explicitly selecting Inbar requests sign-in.
                    repeat(firstInbar) { compose.onNodeWithContentDescription("Next course").performClick() }
                }
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val current = findInbar(profiles())
                    if (current?.optBoolean("loggedIn") == true && current.optString("encryptedSession") != expiredSession) {
                        val provider = InbarPlatform.fromJson(current) as InbarPlatform
                        if (provider.refreshCookies()) {
                            restored = true
                            assertTrue(current.optString("id") == originalId)
                            assertTrue(current.optString("identity") == original.optString("identity"))
                            assertTrue(current.optString("mobile") == original.optString("mobile"))
                            assertTrue(provider.getCourses().isNotEmpty())
                            assertTrue(overrides == PlatformStorage.loadProviderCourseOverrides(context, originalId))
                            val latest = profiles()
                            assertEquals(1, (0 until latest.length()).count { latest.getJSONObject(it).optString("id") == originalId })
                            break
                        }
                    }
                    SystemClock.sleep(250)
                }
                assertTrue("Automatic expired-session recovery did not finish within 60 seconds", restored)
            }
        } finally {
            if (!restored) {
                // Restore only this provider if recovery failed; leave other providers untouched.
                val latest = profiles()
                val index = (0 until latest.length()).firstOrNull { latest.getJSONObject(it).optString("id") == originalId }
                if (index != null) latest.put(index, original)
                prefs.edit().putString(PlatformStorage.KEY_PLATFORMS, latest.toString()).commit()
            }
        }
    }
}
