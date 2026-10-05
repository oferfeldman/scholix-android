package com.feldman.scholix.api.platforms

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.GradesScreen
import com.feldman.scholix.pages.SchedulePage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated synthetic accounts. A reserved SMS gate prevents any live sign-in requests. */
@RunWith(AndroidJUnit4::class)
class InbarOnDemandUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(withCourses: Boolean = true): Pair<Context, InbarPlatform> {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "inbar_access_${UUID.randomUUID()}_"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(prefix + name, mode)
        }
        val course = JSONObject().put("id", "sample-01").put("courseKey", "2027:sample-01")
            .put("name", "Synthetic Inbar course").put("platformId", "INBAR_ACCESS_TEST")
            .put("year", 2027).put("semesterPicker", false)
            .put("grades", JSONArray().put(JSONObject().put("id", "cached-assignment")
                .put("name", "Cached assignment").put("subject", "Synthetic Inbar course")
                .put("grade", "87").put("date", "01/10/2026")))
        val account = InbarPlatform.fromJson(JSONObject().put("id", "INBAR_ACCESS_TEST")
            .put("identity", "synthetic-passport").put("mobile", "synthetic-mobile123")
            .put("verifiedAccount", true).put("loggedIn", false)
            .put("courses", if (withCourses) JSONArray().put(course) else JSONArray())
            .put("encryptedSession", InbarSessionCipher.encrypt("[]"))) as InbarPlatform
        PlatformStorage.savePlatforms(context, listOf(account))
        // If a page attempts authentication, it will fail locally before reaching Inbar.
        reserveInbarSmsRequest(context, account.getUsername(), account.mobile)
        return context to account
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun defaultGradesKeepCachedDataUntilTheUserRequestsInbar() {
        val (context, account) = fixture()
        compose.setContent {
            val owner = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides owner) {
                AppTheme { GradesScreen(Modifier, account.getCourses()) }
            }
        }
        awaitText("Cached assignment")
        compose.onNodeWithText("Load Inbar grades").assertExists()
        compose.onAllNodesWithText("recently started", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Load Inbar grades").performClick()
        awaitText("recently started")
    }

    @Test fun emptySavedAccountDoesNotStartSignInOnAppOpening() {
        val (context, _) = fixture(withCourses = false)
        compose.setContent {
            val owner = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides owner) {
                AppTheme { GradesScreen(Modifier, emptyList()) }
            }
        }
        awaitText("Load Inbar grades")
        compose.onAllNodesWithText("recently started", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Load Inbar grades").performClick()
        awaitText("recently started")
    }

    @Test fun scheduleSignInRequiresOpeningInbarSchedule() {
        val (context, account) = fixture()
        compose.setContent {
            val owner = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides owner) {
                AppTheme { SchedulePage(listOf(account)) }
            }
        }
        awaitText("Load Inbar schedule")
        compose.onAllNodesWithText("recently started", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Load Inbar schedule").performClick()
        awaitText("recently started")
    }

    @Test fun backgroundRefreshLeavesExpiredInbarForExplicitAccess() = runBlocking {
        val (context, account) = fixture()
        assertTrue(PlatformStorage.refreshCookies(context).isEmpty())
        val restored = PlatformStorage.loadPlatforms(context).single() as InbarPlatform
        assertFalse(restored.isLoggedIn())
        assertEquals(account.getCourses().size, restored.getCourses().size)
    }
}
