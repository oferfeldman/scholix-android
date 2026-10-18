package com.feldman.scholix.lemida

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@OptIn(ExperimentalCoroutinesApi::class)
class LemidaPdfRepositoryTest {
    private val pdf = LemidaPdf("Questions", "${LemidaParser.BASE}/pluginfile.php/1/questions.pdf")
    private val item = Homework("1", 1, "Course", "Homework", "assign", "", "")
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences = "lemida_pdf_test_${UUID.randomUUID()}"
    private var user = "42"
    private var expired = false
    private var interrupted = false
    private var downloads = 0
    private var closed = false
    private var output: File? = null
    private fun repo(): LemidaRepository {
        context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().putString("user_id", "42")
            .putString("detail:42:1", HomeworkDetail("", "", emptyList(), "", pdfs = listOf(pdf)).json().toString()).commit()
        return LemidaRepository(context, preferences) { _, _, _ -> object : LemidaTransport {
            override suspend fun prepare() = Unit
            override suspend fun get(url: String) = if (expired) "<body class='notloggedin'>Sign in</body>" else
                """<body><a href='/login/logout.php'>Logout</a><script>M.cfg={"userId":$user}</script></body>"""
            override suspend fun post(url: String, body: String) = error("No AJAX needed")
            override suspend fun downloadPdf(url: String, file: File) {
                downloads++; output = file; file.writeText("%PDF-1.7\nsynthetic")
                if (interrupted) throw IOException("Interrupted")
            }
            override suspend fun close() { closed = true }
        } }
    }
    @Before fun setMain() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun cleanup() { output?.delete(); context.deleteSharedPreferences(preferences); Dispatchers.resetMain() }
    @Test fun sameAccountDownloadsPrivatePdfAndClosesBrowser() = runBlocking {
        val file = repo().openPdf(item, pdf)
        assertEquals("%PDF-1.7\nsynthetic", file.readText()); assertTrue(closed); assertEquals(1, downloads)
        assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
    }
    @Test fun changedAccountCannotDownloadThePreviousAccountsAttachment() = runBlocking {
        user = "99"
        try { repo().openPdf(item, pdf); fail("Must reject another account") } catch (_: IllegalStateException) { }
        assertEquals(0, downloads); assertTrue(closed)
    }
    @Test fun expiredHomepageRequiresSignInAndDoesNotDownload() = runBlocking {
        expired = true
        val repo = repo()
        try { repo.openPdf(item, pdf); fail("Must require sign-in") } catch (_: LemidaSessionExpired) { }
        assertTrue(repo.needsLogin()); assertEquals(0, downloads); assertTrue(closed)
    }
    @Test fun interruptedDownloadIsRemovedAndCachedHomeworkIsRetained() = runBlocking {
        interrupted = true
        val repo = repo()
        try { repo.openPdf(item, pdf); fail("Must report interruption") } catch (_: IOException) { }
        assertFalse(output!!.exists()); assertEquals(listOf(pdf), repo.cachedDetail(item)!!.pdfs); assertTrue(closed)
    }
}
