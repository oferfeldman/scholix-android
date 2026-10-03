package com.feldman.scholix.lemida

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import kotlin.coroutines.resume

/** Explicit opt-in: logs out the REAL Moodle session, then tests passive Microsoft recovery. */
class LemidaRealExpiryTest {
    @Test fun resumeAutomaticUpdatesForConnectedSession() = runBlocking<Unit> {
        assumeTrue("Run only when automatic updates are requested for the real account",
            InstrumentationRegistry.getArguments().getString("resume_real_lemida_sync") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = LemidaRepository(context)
        repo.sync()
        repo.setEnabled(true)
        LemidaSyncWorker.schedule(context)
        withTimeout(10_000) {
            while (WorkManager.getInstance(context).getWorkInfosForUniqueWork("lemida_homework_sync")
                    .get().none { !it.state.isFinished }) delay(100)
        }
        assertTrue("Automatic updates must be enabled", repo.enabled())
        assertFalse("The connected session must not require sign-in", repo.needsLogin())
    }

    private suspend fun evaluate(view: WebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value ?: "null") }
        }
    }

    @Test fun realMoodleLogoutReconnectsWithoutUserInput() = runBlocking<Unit> {
        assumeTrue("Run only when the account holder explicitly authorizes real expiry",
            InstrumentationRegistry.getArguments().getString("allow_real_lemida_expiry") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = LemidaRepository(context)
        val enabled = repo.enabled()
        val view = withContext(Dispatchers.Main) { WebView(context) }
        val browser = withContext(Dispatchers.Main) { LemidaBrowser(context, null, view) }
        repo.setEnabled(false)
        try {
            withTimeout(30_000) { while (repo.syncing.value) delay(100) }
            browser.prepare()
            val html = browser.get("${LemidaParser.BASE}/my/")
            assertTrue("Start from a genuinely authenticated Moodle page", LemidaParser.authenticated(html))
            val beforeUser = LemidaParser.config(html, "userId") ?: error("No Moodle user")
            val key = LemidaParser.config(html, "sesskey") ?: error("No Moodle session key")
            val beforeIds = repo.cached().map { it.id }.toSet()
            val beforeSync = repo.lastSync()
            val logout = Jsoup.parse(html, LemidaParser.BASE).selectFirst("a[href*=/login/logout.php]")?.absUrl("href")
                ?: error("No Moodle logout entry")
            val uri = URI(logout)
            check(uri.scheme == "https" && uri.host == "lemida.biu.ac.il" && uri.path == "/login/logout.php")
            // Invalidate the Moodle session at its real endpoint. Do not follow a separate Microsoft logout redirect.
            assertEquals("true", evaluate(view, """(() => {
                window.__lemidaRealLogout = null;
                fetch(${JSONObject.quote(logout)}, {credentials:'same-origin', redirect:'manual'})
                    .then(r => {window.__lemidaRealLogout = {ok:true};})
                    .catch(() => {window.__lemidaRealLogout = {ok:false};});
                return true;
            })()"""))
            withTimeout(30_000) {
                while (true) {
                    val result = evaluate(view, "window.__lemidaRealLogout && window.__lemidaRealLogout.ok")
                    if (result == "true") break
                    check(result != "false") { "Logout request failed" }
                    delay(250)
                }
            }
            val payload = JSONArray().put(JSONObject().put("index", 0)
                .put("methodname", "core_course_get_enrolled_courses_by_timeline_classification")
                .put("args", JSONObject().put("classification", "allincludinghidden").put("limit", 50).put("offset", 0)))
            val response = browser.post("${LemidaParser.BASE}/lib/ajax/service.php?sesskey=$key", payload.toString())
            try { LemidaParser.ajaxData(response); fail("Real logout must reject the old authenticated API request") }
            catch (_: LemidaSessionExpired) { }
            android.util.Log.i("LemidaRealExpiry", "Real Moodle expiry confirmed by rejected authenticated API request")
            // Preserve the now-anonymous cookie state, never restore the invalid session.
            val cookie = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(LemidaParser.BASE) }
            if (cookie != null) withContext(Dispatchers.IO) { LemidaCookieStore(context).save(cookie) }
            val activity = repo.cached().first()
            repo.detail(activity)
            assertNotNull("Detail recovery must cache real homework content", repo.cachedDetail(activity))
            assertFalse("Detail recovery must not require another sign-in", repo.needsLogin())
            assertEquals(beforeUser, repo.sync())
            assertTrue("Automatic recovery must commit a new successful sync", repo.lastSync() > beforeSync)
            assertFalse("Recovery must clear the sign-in requirement", repo.needsLogin())
            assertEquals("Recovery must retain the same homework/account", beforeIds, repo.cached().map { it.id }.toSet())
            android.util.Log.i("LemidaRealExpiry", "Automatic recovery passed; homework=${repo.cached().size}; no user input")
        } finally {
            repo.setEnabled(enabled)
            browser.close()
            withContext(Dispatchers.Main) { view.destroy() }
        }
    }
}
