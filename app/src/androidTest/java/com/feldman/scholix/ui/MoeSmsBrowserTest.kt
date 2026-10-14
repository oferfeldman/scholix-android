package com.feldman.scholix.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MoeSmsBrowserTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withPage(html: String, test: (WebView) -> Unit) {
        lateinit var web: WebView
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext)
            web.settings.javaScriptEnabled = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("https://lgn.edu.gov.il/", html, "text/html", "UTF-8", null)
        }
        assertTrue(loaded.await(10, TimeUnit.SECONDS))
        try { test(web) } finally { instrumentation.runOnMainSync { web.destroy() } }
    }

    private fun eval(web: WebView, script: String): String {
        val ready = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script) { result = it; ready.countDown() }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        return result
    }

    @Test fun autofillSubmitsOnceAndDoesNotOverwriteAnEditedField() = withPage("""
        <form onsubmit="window.posts++;return false"><input id="idCard"><input id="phoneNumber">
          <select id="phoneCodeDrop"><option>050</option></select><button type="submit">Send</button></form>
        <script>window.posts=0</script>
    """) { web ->
        assertEquals("\"sent\"", eval(web, moeSmsRequestJs("123456789", "0501234567")))
        eval(web, "document.getElementById('phoneNumber').value='7654321'")
        assertEquals("\"sent\"", eval(web, moeSmsRequestJs("123456789", "0501234567")))
        assertEquals("1", eval(web, "window.posts"))
        assertEquals("\"7654321\"", eval(web, "document.getElementById('phoneNumber').value"))
    }

    @Test fun connectUsesTheOfficialOtpFormAndImmediatelyMarksConnecting() = withPage("""
        <form id="verificationCodeForm" onsubmit="window.submits++;return false">
          <input id="otp"><button type="submit">Connect</button>
        </form><script>window.submits=0</script>
    """) { web ->
        eval(web, MOE_SMS_OBSERVER_JS)
        assertEquals("true", eval(web, moeSmsVerifyJs("654321")))
        assertEquals("\"654321\"", eval(web, "document.getElementById('otp').value"))
        assertEquals("1", eval(web, "window.submits"))
        assertEquals("true", eval(web, "window.__scholixSmsConnecting"))
    }
}
