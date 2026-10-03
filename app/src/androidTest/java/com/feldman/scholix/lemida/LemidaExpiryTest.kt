package com.feldman.scholix.lemida

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Synthetic responses and isolated preferences: no cookies, login requests or SMS. */
class LemidaExpiryTest {
    @get:Rule val compose = createComposeRule()
    private val item = Homework("1:assign:9", 1, "Expiry test (simulation)", "Cached homework",
        "assign", "${LemidaParser.BASE}/mod/assign/view.php?id=9", "Friday")
    private val cachedDetail = HomeworkDetail("Cached instructions", "Friday", emptyList(), "Cached instructions")

    private enum class Response { LOGIN_PAGE, INVALID_KEY, MICROSOFT, NETWORK_ERROR, RECOVERED }
    private class FixtureTransport(var response: Response) : LemidaTransport {
        var closed = false
        override suspend fun prepare() = Unit
        override suspend fun close() { closed = true }
        override suspend fun get(url: String): String = when (response) {
            Response.LOGIN_PAGE -> "<body class='notloggedin'>Sign in</body>"
            Response.MICROSOFT -> throw LemidaSessionExpired()
            Response.NETWORK_ERROR -> throw IOException("Offline fixture")
            else -> """<body><a href="/login/logout.php">Logout</a>
                <script>M.cfg={"userId":42,"sesskey":"fixture"};</script></body>"""
        }
        override suspend fun post(url: String, body: String): String {
            if (response == Response.INVALID_KEY)
                return """[{"error":true,"exception":{"errorcode":"invalidsesskey"}}]"""
            val args = JSONArray(body).getJSONObject(0)
            val data: Any = if (args.getString("methodname") == "core_courseformat_get_state") {
                """{"cm":[{"name":"Cached homework","url":"https://lemida.biu.ac.il/mod/assign/view.php?id=9"}]}"""
            } else if (args.getJSONObject("args").getInt("offset") == 0) {
                JSONObject("""{"courses":[{"id":1,"fullname":"Expiry test (simulation)"}],"nextoffset":1}""")
            } else JSONObject("""{"courses":[],"nextoffset":1}""")
            return JSONArray().put(JSONObject().put("error", false).put("data", data)).toString()
        }
    }

    private fun isolated(response: Response, block: suspend (LemidaRepository, SharedPreferences, FixtureTransport) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "lemida_expiry_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val transport = FixtureTransport(response)
        try {
            check(prefs.edit().putBoolean("enabled", true).putString("user_id", "42")
                .putString("homework", LemidaParser.encode(listOf(item)))
                .putString("pending", LemidaParser.encode(listOf(item))).putStringSet("seen", setOf(item.id))
                .putString("detail:42:${item.id}", cachedDetail.json().toString())
                .putLong("last_sync", 42L).commit())
            block(LemidaRepository(context, name) { _, _, _ -> transport }, prefs, transport)
        } finally { context.deleteSharedPreferences(name) }
    }

    private suspend fun expire(repo: LemidaRepository) {
        try { repo.sync(); fail("Expired response must not commit a successful update") }
        catch (_: LemidaSessionExpired) { }
    }

    @Test fun loginRedirectsAndRejectedKeysPreserveCacheAndPauseHomeworkAlerts() {
        listOf(Response.LOGIN_PAGE, Response.INVALID_KEY, Response.MICROSOFT).forEach { response ->
            isolated(response) { repo, prefs, transport ->
                expire(repo)
                assertTrue(repo.needsLogin())
                assertTrue(transport.closed)
                assertFalse(repo.syncing.value)
                assertEquals(listOf(item), repo.cached())
                assertEquals(cachedDetail, repo.cachedDetail(item))
                assertEquals(42L, repo.lastSync())
                repo.deliverPending("42") { fail("Expiry must pause homework notifications"); true }
                assertEquals(listOf(item), LemidaParser.decode(prefs.getString("pending", "[]")!!))
                var reminders = 0
                repeat(2) { repo.deliverLoginReminder { reminders++; true } }
                assertEquals(1, reminders)
            }
        }
    }

    @Test fun recoveredSessionResumesUpdatesAndRemovesSignInRequirement() = isolated(Response.INVALID_KEY) { repo, prefs, transport ->
        expire(repo)
        repo.deliverLoginReminder { true }
        transport.response = Response.RECOVERED
        assertEquals("42", repo.sync())
        assertFalse(repo.needsLogin())
        assertFalse(prefs.getBoolean("login_notified", true))
        assertTrue(repo.lastSync() > 42L)
        assertEquals(listOf(item), repo.cached())
        assertEquals(cachedDetail, repo.cachedDetail(item))
        repo.deliverLoginReminder { fail("Recovery must suppress sign-in reminders"); true }
    }

    @Test fun networkFailureKeepsCacheWithoutFalselyExpiringTheSession() = isolated(Response.NETWORK_ERROR) { repo, _, _ ->
        try { repo.sync(); fail("Network failure must fail the update") }
        catch (error: IOException) { assertFalse(error is LemidaSessionExpired) }
        assertFalse(repo.needsLogin())
        assertEquals(listOf(item), repo.cached())
        assertEquals(42L, repo.lastSync())
    }

    @Test fun expiredSessionShowsSignInAlongsideCachedHomework() = isolated(Response.LOGIN_PAGE) { repo, _, _ ->
        expire(repo)
        compose.setContent { MaterialTheme { LemidaPageContent(repo) } }
        compose.onNodeWithText("Sign in to Lemida").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(item.title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sign in to Lemida").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "lemida-expiry-simulation.png")
        try { file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }
}
