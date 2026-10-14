package com.feldman.scholix.lemida

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Android Keystore and WebView, with synthetic credentials and network disabled. */
class LemidaCredentialsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val email = "student@example.edu"
    private val secret = "test-only-'quoted\\password雪"
    private val origin = "https://login.microsoftonline.com/fixture"
    private val emailPage = "<input id='i0116'><button id='idSIButton9' onclick='window.clicked=(window.clicked||0)+1'>Next</button>"
    private val passwordPage = "<div id='displayName'>$email</div><input id='i0118' type='password'><button id='idSIButton9' onclick='window.clicked=(window.clicked||0)+1'>Sign in</button>"

    private fun page(html: String, base: String = origin, run: (WebView) -> Unit) {
        lateinit var view: WebView
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(web: WebView, url: String) { ready.countDown() }
            }
            view.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
        }
        try {
            assertTrue("Fixture document loaded", ready.await(10, TimeUnit.SECONDS))
            run(view)
        } finally { instrumentation.runOnMainSync { view.destroy() } }
    }
    private fun evaluate(view: WebView, script: String): Any? {
        val complete = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result = it; complete.countDown() } }
        assertTrue("Script callback completed", complete.await(10, TimeUnit.SECONDS))
        return JSONTokener(result).nextValue()
    }
    @Test fun encryptedCredentialsSurviveRestartWithoutPlaintext() {
        val context = instrumentation.targetContext
        val name = "lemida_credentials_test.enc"
        val file = File(context.noBackupFilesDir, name)
        try {
            LemidaCredentialStore(context, name).save(LemidaCredentials(email, secret))
            val restored = LemidaCredentialStore(context, name).load()!!
            assertEquals(email, restored.email); assertEquals(secret, restored.password)
            assertFalse(restored.toString().contains(secret))
            val disk = file.readBytes().toString(Charsets.UTF_8)
            assertFalse(disk.contains(email)); assertFalse(disk.contains(secret))
            file.writeText("corrupt"); assertNull(LemidaCredentialStore(context, name).load())
        } finally { file.delete() }
    }
    @Test fun emailEntryFiresInputAndSubmits() = page(emailPage + "<script>document.querySelector('input').oninput=()=>window.inputEvent=true</script>") { web ->
        assertEquals("email", evaluate(web, LemidaCredentialScript.probe(email)))
        assertEquals(true, evaluate(web, LemidaCredentialScript.submit("email", email, secret)))
        assertEquals(email, evaluate(web, "document.querySelector('input').value"))
        assertEquals(true, evaluate(web, "window.inputEvent"))
        assertEquals(1, evaluate(web, "window.clicked"))
    }
    @Test fun quotedPasswordIsEnteredForMatchingAccount() = page(passwordPage) { web ->
        assertEquals("password", evaluate(web, LemidaCredentialScript.probe(email)))
        assertEquals(true, evaluate(web, LemidaCredentialScript.submit("password", email, secret)))
        assertEquals(secret, evaluate(web, "document.querySelector('input').value"))
        assertEquals(1, evaluate(web, "window.clicked"))
    }
    @Test fun otherAccountCannotReceivePassword() = page(passwordPage.replace(email, "other@example.edu")) { web ->
        assertEquals("mismatch", evaluate(web, LemidaCredentialScript.probe(email)))
        assertEquals(false, evaluate(web, LemidaCredentialScript.submit("password", email, secret)))
        assertEquals("", evaluate(web, "document.querySelector('input').value"))
    }
    @Test fun otherAccountCannotProceedToAutomaticSms() = page("<div id='displayName'>other@example.edu</div><a id='signInAnotherWay'>Use another method</a>") { web ->
        assertEquals("mismatch", evaluate(web, LemidaCredentialScript.probe(email)))
    }
    @Test fun rememberedEmailTileIsTappedInsteadOfEnteringEmail() = page("<div id='tilesHolder'><div role='button' data-test-id='$email' onclick='window.chosen=true'>Remembered account</div></div><input id='i0116'>") { web ->
        assertEquals("account", evaluate(web, LemidaCredentialScript.probe(email)))
        assertEquals(true, evaluate(web, LemidaCredentialScript.submit("account", email, secret)))
        assertEquals(true, evaluate(web, "window.chosen"))
        assertEquals("", evaluate(web, "document.querySelector('input').value"))
    }
    @Test fun absentOrDuplicateAccountCannotTapAnotherEmail() = page("<div id='tilesHolder'><div role='button' data-test-id='other@example.edu' onclick='window.chosen=true'>Other account</div></div>") { web ->
        assertEquals("missing-account", evaluate(web, LemidaCredentialScript.probe(email)))
        assertEquals(false, evaluate(web, LemidaCredentialScript.submit("account", email, secret)))
        evaluate(web, "document.querySelector('[role=button]').setAttribute('data-test-id', '$email'); document.querySelector('#tilesHolder').innerHTML += document.querySelector('#tilesHolder').innerHTML")
        assertEquals("missing-account", evaluate(web, LemidaCredentialScript.probe(email)))
    }
    @Test fun selectedEmailIsLearnedOnlyFromMicrosoftDocument() = page("<div id='displayName'>$email</div>") { web ->
        assertEquals(email, evaluate(web, LemidaCredentialScript.selectedAccount()))
    }
    @Test fun accountAndSmsReservationSurviveProcessStoreRecreation() {
        val context = instrumentation.targetContext
        val prefs = "lemida_sign_in_test"
        val file = File(context.noBackupFilesDir, "lemida_account_test.enc")
        context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        try {
            val store = LemidaSignInStore(context, file.name, prefs)
            store.remember(email)
            assertEquals(email, LemidaSignInStore(context, file.name, prefs).email())
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(email))
            assertTrue(store.reserveSms(100_000))
            assertFalse(LemidaSignInStore(context, file.name, prefs).reserveSms(100_001))
            assertFalse(store.reserveSms(90_000))
            assertTrue(store.reserveSms(1_000_000))
            store.setEnabled(false); assertFalse(store.canRecover())
        } finally {
            file.delete()
            context.getSharedPreferences(prefs, android.content.Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(prefs)
        }
    }
    @Test fun hiddenWebViewSelectsAccountAndCompletesSmsWithoutActivity() = page("""
        <div id='tilesHolder'><div role='button' data-test-id='$email' onclick='chooseAccount()'>Account</div></div>
        <script>
          window.smsRequests = 0;
          function chooseAccount() {
            document.body.innerHTML = '<div id="displayName">$email</div><div role="button" data-value="OneWaySMS" onclick="chooseSms()">Text</div>';
          }
          function chooseSms() {
            window.smsRequests++;
            document.body.innerHTML = '<div id="displayName">$email</div><input id="idTxtBx_SAOTCC_OTC"><button id="idSubmit_SAOTCC_Continue" onclick="window.finished=document.querySelector(\'#idTxtBx_SAOTCC_OTC\').value===\'123456\'">Verify</button>';
          }
        </script>
    """.trimIndent()) { web ->
        lateinit var hidden: LemidaBackgroundSignIn
        var manual = false
        var ready = false
        instrumentation.runOnMainSync {
            hidden = LemidaBackgroundSignIn(instrumentation.targetContext, web, email, null,
                { true }, { manual = true }, reserveSms = { true }, ready = { ready })
        }
        try {
            fun waitFor(script: String) {
                val deadline = System.currentTimeMillis() + 10_000
                while (evaluate(web, script) != true && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertEquals(true, evaluate(web, script))
            }
            Thread.sleep(1_000)
            assertEquals(0, evaluate(web, "window.smsRequests"))
            instrumentation.runOnMainSync { ready = true }
            waitFor("window.smsRequests === 1")
            instrumentation.runOnMainSync { assertTrue(hidden.acceptSms("Microsoft verification code 123456", "Microsoft")) }
            waitFor("window.finished === true")
            assertFalse(manual)
            assertEquals(1, evaluate(web, "window.smsRequests"))
        } finally { instrumentation.runOnMainSync { hidden.close(); assertFalse(hidden.acceptSms("Microsoft verification code 123456", "Microsoft")) } }
    }
    @Test fun lookalikeOriginCannotReceiveCredentials() = page(passwordPage, "https://login.microsoftonline.com.example.invalid/") { web ->
        assertEquals(false, evaluate(web, LemidaCredentialScript.submit("password", email, secret)))
        assertEquals("", evaluate(web, "document.querySelector('input').value"))
    }
    @Test fun visibleErrorBlocksAnotherSubmission() = page(passwordPage + "<div id='passwordError'>Incorrect password</div>") { web ->
        assertEquals("blocked", evaluate(web, LemidaCredentialScript.probe(email)))
        assertEquals(false, evaluate(web, LemidaCredentialScript.submit("password", email, secret)))
    }
    @Test fun captchaBlocksPasswordEntry() = page(passwordPage + "<div id='captcha'>Browser check</div>") { web ->
        assertEquals("blocked", evaluate(web, LemidaCredentialScript.probe(email)))
        assertEquals(false, evaluate(web, LemidaCredentialScript.submit("password", email, secret)))
    }
    @Test fun navigationBetweenProbeAndSubmitCannotFillWrongStep() = page(emailPage) { web ->
        assertEquals("email", evaluate(web, LemidaCredentialScript.probe(email)))
        evaluate(web, "document.body.innerHTML='<input id=i0118 type=password><button id=idSIButton9>Sign in</button>'")
        assertEquals(false, evaluate(web, LemidaCredentialScript.submit("email", email, secret)))
        assertEquals("", evaluate(web, "document.querySelector('input').value"))
    }
    @Test fun userEnteredPasswordIsPreserved() = page(passwordPage) { web ->
        evaluate(web, "document.querySelector('input').value='manual-test-value'")
        assertEquals(false, evaluate(web, LemidaCredentialScript.submit("password", email, secret)))
        assertEquals("manual-test-value", evaluate(web, "document.querySelector('input').value"))
    }
    @Test fun inputSanitizationCannotSubmitAnAlteredPassword() = page(passwordPage) { web ->
        assertEquals(false, evaluate(web, LemidaCredentialScript.submit("password", email, secret + "\n")))
        assertEquals(0, evaluate(web, "window.clicked || 0"))
    }
}
