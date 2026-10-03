package com.feldman.scholix.lemida

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Controlled loads never contact a server, prepare cookies, or read the saved session. */
class LemidaBrowserLifecycleTest {
    private class ControlledWebView(context: Context) : WebView(context) {
        var requested: String? = null
        var stops = 0
        var scriptCallback: ValueCallback<String>? = null
        override fun loadUrl(url: String) { requested = url }
        override fun getUrl(): String? = requested
        override fun stopLoading() { stops++ }
        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) { scriptCallback = callback }
    }

    @Test fun universityEntryStartsPassiveRecoveryWithoutCompletingAnAnonymousPage() = runBlocking {
        withContext(Dispatchers.Main) {
            val view = ControlledWebView(InstrumentationRegistry.getInstrumentation().targetContext)
            val browser = LemidaBrowser(view.context, null, view)
            try {
                val home = "${LemidaParser.BASE}/my/"
                val page = async(start = CoroutineStart.UNDISPATCHED) { browser.get(home) }
                view.webViewClient.onPageFinished(view, home)
                view.scriptCallback!!.onReceiveValue(org.json.JSONObject.quote(
                    "<body class='notloggedin'><a href='/auth/multioauth/login.php?providerid=1'>Sign in</a></body>"))
                assertEquals("${LemidaParser.BASE}/auth/multioauth/login.php?providerid=1", view.requested)
                assertTrue(page.isActive)
                page.cancelAndJoin()
            } finally { browser.close(); view.destroy() }
        }
    }

    @Test fun lateAnonymousSnapshotCannotNavigateAnotherDocumentToSignIn() = runBlocking {
        withContext(Dispatchers.Main) {
            val view = ControlledWebView(InstrumentationRegistry.getInstrumentation().targetContext)
            val browser = LemidaBrowser(view.context, null, view)
            try {
                val home = "${LemidaParser.BASE}/my/"
                val page = async(start = CoroutineStart.UNDISPATCHED) { browser.get(home) }
                view.webViewClient.onPageFinished(view, home)
                view.requested = "${LemidaParser.BASE}/course/view.php?id=1"
                view.scriptCallback!!.onReceiveValue(org.json.JSONObject.quote(
                    "<body class='notloggedin'><a href='/auth/multioauth/login.php?providerid=1'>Sign in</a></body>"))
                assertEquals("${LemidaParser.BASE}/course/view.php?id=1", view.requested)
                assertTrue(page.isActive)
                page.cancelAndJoin()
            } finally { browser.close(); view.destroy() }
        }
    }

    @Test fun passiveMicrosoftPageMayRedirectButInteractiveControlsRequireSignIn() = runBlocking {
        withContext(Dispatchers.Main) {
            val view = ControlledWebView(InstrumentationRegistry.getInstrumentation().targetContext)
            val browser = LemidaBrowser(view.context, null, view)
            try {
                val page = async(start = CoroutineStart.UNDISPATCHED) {
                    try { browser.get("${LemidaParser.BASE}/my/"); false }
                    catch (_: LemidaSessionExpired) { true }
                }
                val microsoft = "https://login.microsoftonline.com/fixture"
                view.requested = microsoft
                view.webViewClient.onPageFinished(view, microsoft)
                view.scriptCallback!!.onReceiveValue("false")
                assertTrue(page.isActive)
                view.webViewClient.onPageFinished(view, microsoft)
                view.scriptCallback!!.onReceiveValue("true")
                assertTrue("Interactive Microsoft page must require sign-in", page.await())
            } finally { browser.close(); view.destroy() }
        }
    }

    @Test fun cancellationStopsBeforeTheNextPageCanStart() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = ControlledWebView(context)
            val browser = LemidaBrowser(context, null, view)
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { browser.get("${LemidaParser.BASE}/fixture-one") }
                first.cancelAndJoin()
                assertEquals(1, view.stops)
                val second = async(start = CoroutineStart.UNDISPATCHED) { browser.get("${LemidaParser.BASE}/fixture-two") }
                delay(50) // Allow any wrongly posted cleanup to run against the new page.
                assertEquals("${LemidaParser.BASE}/fixture-two", view.requested)
                assertEquals(1, view.stops)
                second.cancelAndJoin()
                assertEquals(2, view.stops)
            } finally { browser.close(); view.destroy() }
        }
    }

    @Test fun repeatedCloseCannotReplaceANewClient() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = ControlledWebView(context)
            val browser = LemidaBrowser(context, null, view)
            try {
                browser.close()
                val replacement = WebViewClient()
                view.webViewClient = replacement
                browser.close()
                assertSame(replacement, view.webViewClient)
                try {
                    browser.get("${LemidaParser.BASE}/fixture")
                    fail("Closed browser must not start a page")
                } catch (_: IllegalStateException) { }
                assertNull(view.requested)
            } finally { browser.close(); view.destroy() }
        }
    }

    @Test fun closePreservesAnExternallyReplacedClient() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = ControlledWebView(context)
            val browser = LemidaBrowser(context, null, view)
            try {
                val page = async(start = CoroutineStart.UNDISPATCHED) { browser.get("${LemidaParser.BASE}/fixture") }
                page.cancelAndJoin()
                val replacement = WebViewClient()
                view.webViewClient = replacement
                browser.close()
                assertSame(replacement, view.webViewClient)
            } finally { browser.close(); view.destroy() }
        }
    }
}
