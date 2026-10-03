package com.feldman.scholix.lemida

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
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
        override fun loadUrl(url: String) { requested = url }
        override fun getUrl(): String? = requested
        override fun stopLoading() { stops++ }
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
