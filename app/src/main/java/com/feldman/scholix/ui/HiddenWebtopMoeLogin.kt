package com.feldman.scholix.ui

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Invisible "log in to Webtop with the Ministry of Education" flow.
 *
 * The user types their MOE credentials into Scholix's own fields; this drives the
 * real pages off-screen and hands back the short-lived SSO `key`, which
 * [com.feldman.scholix.api.platforms.WebtopPlatform.loginWithMoe] exchanges for a
 * Webtop session. No browser UI is ever shown.
 *
 * The sequence matters -- each step was established against a live account, and
 * skipping any of them breaks the login:
 *
 *  1. Start at Webtop's OWN login page, not at config.loginMinstry. Going straight
 *     to the MOE entry produces a valid assertion whose key Webtop then rejects
 *     with "InvalidUser" -- indistinguishable from an unlinked account, but really
 *     just the wrong entry path.
 *  2. Click "אשר cookies" first. Webtop binds every login button's `disable` to
 *     `checkAllowCookie()`, so the MOE button is inert until cookies are accepted
 *     (verified: innerDisabled=true before the click, false after).
 *  3. Click "הזדהות משרד החינוך" and fill the MOE form.
 *  4. CANCEL the redirect to /loginMoe?key=... and keep the key.
 *
 * Step 4 is the important one. The `key` is SINGLE-USE: if the Webtop SPA is
 * allowed to load that URL it spends the key itself, and any later exchange fails.
 * Cancelling the navigation leaves the key unused for us. (Reading the SPA's own
 * response instead is a race -- the request can complete before a WebView can
 * inject an interceptor, which hung this flow indefinitely.)
 *
 * A browser engine is needed at all only because lgn.edu.gov.il sits behind F5
 * Distributed Cloud Bot Defense, whose `x-security-csrf-token` comes from
 * obfuscated /TSbd/ JavaScript -- see research/FINDINGS_TSbd_bot_defense.md.
 *
 * @param onResult (key, error) — exactly one is non-null.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HiddenWebtopMoeLogin(
    username: String,
    password: String,
    timeoutMs: Long = 120_000,
    onResult: (key: String?, error: String?) -> Unit,
) {
    val context = LocalContext.current
    val done = remember { AtomicBoolean(false) }
    val cookiesAccepted = remember { AtomicBoolean(false) }
    val moeClicked = remember { AtomicBoolean(false) }
    val credsFilled = remember { AtomicBoolean(false) }

    fun finish(key: String?, error: String?) {
        if (done.compareAndSet(false, true)) onResult(key, error)
    }

    val web = remember {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // A stale session short-circuits the SSO into a half-signed-in state.
        cm.removeAllCookies(null)
        cm.flush()
        WebStorage.getInstance().deleteAllData()

        WebView(context).apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    val key = keyFrom(url)
                    if (key != null) {
                        // Cancel the load so the SPA cannot spend the key.
                        Log.d(TAG, "captured MOE key; cancelling SPA navigation")
                        finish(key, null)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (done.get()) return
                    // Belt and braces: some redirects arrive without going through
                    // shouldOverrideUrlLoading (server-side 30x chains).
                    keyFrom(url.orEmpty())?.let { finish(it, null); return }
                    Log.d(TAG, "page: ${runCatching { url?.toUri()?.host }.getOrNull()}")
                }
            }
            loadUrl(WEBTOP_LOGIN)
        }
    }

    LaunchedEffect(username, password) {
        // Log each probe result once, not every 500ms, so a stall is diagnosable
        // without drowning the log.
        var lastConsent: String? = null
        var lastMoe: String? = null
        val ok = withTimeoutOrNull(timeoutMs) {
            while (!done.get()) {
                val host = runCatching { web.url?.toUri()?.host }.getOrNull().orEmpty()

                if (host.endsWith("smartschool.co.il", true)) {
                    if (!cookiesAccepted.get()) {
                        val r = suspendEval(web, clickByText("אשר cookies"))?.trim('"', ' ')
                        if (r != lastConsent) { Log.d(TAG, "consent probe: $r"); lastConsent = r }
                        // "notready" => keep waiting for the app to render.
                        // "notfound" => consent already granted in this session.
                        if (r == "clicked" || r == "notfound") {
                            Log.d(TAG, "cookie consent: $r")
                            cookiesAccepted.set(true)
                        }
                    } else if (!moeClicked.get()) {
                        val r = suspendEval(web, clickByText("משרד החינוך"))?.trim('"', ' ')
                        if (r != lastMoe) { Log.d(TAG, "MOE probe: $r"); lastMoe = r }
                        when (r) {
                            "clicked" -> {
                                Log.d(TAG, "MOE button clicked")
                                moeClicked.set(true)
                            }
                            // Webtop keeps every login button disabled until the
                            // cookie banner is accepted, so a disabled button means
                            // our consent click never landed. Go back and retry it
                            // instead of spinning here until the timeout.
                            "disabled" -> {
                                Log.d(TAG, "MOE button disabled; retrying cookie consent")
                                cookiesAccepted.set(false)
                            }
                        }
                    }
                }

                if (host.endsWith("lgn.edu.gov.il", true) && !credsFilled.get()) {
                    val r = suspendEval(web, loginJs(username, password))?.trim('"', ' ')
                    when {
                        r == "filled" -> {
                            Log.d(TAG, "MOE credentials submitted")
                            credsFilled.set(true)
                        }
                        r != null && r.startsWith("error") -> finish(null, r)
                    }
                }
                delay(500)
            }
            true
        }
        if (ok == null) {
            finish(null, when {
                !moeClicked.get() ->
                    "Could not open the Ministry of Education login from Webtop. " +
                        "Please try again."
                !credsFilled.get() ->
                    "The Ministry of Education login page did not load. Please try again."
                else ->
                    "Signed in to the Ministry of Education, but Webtop did not " +
                        "complete the login. Please try again."
            })
        }
    }

    AndroidView(modifier = Modifier.size(1.dp).alpha(0f), factory = { web })
}

private const val TAG = "HiddenWebtopMoe"
private const val WEBTOP_LOGIN = "https://webtop.smartschool.co.il/account/login"

/** The `key` from a .../loginMoe?key=... redirect, or null. */
private fun keyFrom(url: String): String? {
    if (!url.contains("loginMoe", ignoreCase = true)) return null
    // The key is base64 (+ / =), so percent-decode only -- never plus-decode.
    val raw = Regex("[?&]key=([^&#]+)", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.get(1) ?: return null
    return runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }
        .getOrDefault(raw)
        .takeIf { it.isNotBlank() }
}

/**
 * Click a button by its visible text.
 *
 * Webtop is an Angular app whose buttons are `app-button` wrappers, but it ships
 * new builds often, so fall back to ordinary clickable elements rather than
 * depending on that one tag. Reports:
 *  - "notready"  nothing clickable on the page yet (still rendering)
 *  - "notfound"  the page has buttons, but none with this text
 *  - "disabled"  found, but not clickable yet (Webtop gates on cookie consent)
 */
private fun clickByText(text: String): String {
    val t = JSONObject.quote(text)
    return """
    (function(){
      try{
        var want = $t;
        var all = document.querySelectorAll(
          'app-button, button, a, input[type=submit], [role=button]');
        if (!all.length) return 'notready';
        var hits = [];
        for (var i = 0; i < all.length; i++) {
          var el = all[i];
          var s = (el.innerText || el.textContent || el.value || '').trim();
          if (s.indexOf(want) > -1) hits.push(el);
        }
        if (!hits.length) return 'notfound';
        // Prefer the innermost clickable so Angular's own handler fires.
        var target = hits[0];
        var inner = target.querySelector('button,a,input[type=submit],[role=button]');
        if (inner) target = inner;
        if (target.disabled || target.getAttribute('aria-disabled') === 'true') {
          return 'disabled';
        }
        target.click();
        return 'clicked';
      } catch(e){ return 'error: ' + e; }
    })()
    """.trimIndent()
}

/** Fill + submit the MOE Angular form (native setter + input event). */
private fun loginJs(username: String, password: String): String {
    val u = JSONObject.quote(username)
    val p = JSONObject.quote(password)
    return """
    (function(){
      try{
        function setVal(el, val){
          var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
          if (d && d.set) { d.set.call(el, val); } else { el.value = val; }
          el.dispatchEvent(new Event('input',  {bubbles:true}));
          el.dispatchEvent(new Event('change', {bubbles:true}));
          el.dispatchEvent(new Event('blur',   {bubbles:true}));
        }
        function pick(sels){
          for (var i=0;i<sels.length;i++){ var e=document.querySelector(sels[i]); if(e) return e; }
          return null;
        }
        var user = pick(['input[formcontrolname="userName"]','input[name="userName"]',
                         'input[type="text"]:not([type="hidden"])']);
        var pass = pick(['input[formcontrolname="password"]','input[type="password"]']);
        if (!user || !pass) return 'noform';
        setVal(user, $u); setVal(pass, $p);
        var btn = pick(['button[type="submit"]','form button','.login-button','button']);
        if (btn) { btn.click(); } else if (pass.form) { pass.form.submit(); }
        return 'filled';
      } catch(e){ return 'error: ' + e; }
    })()
    """.trimIndent()
}

/**
 * Evaluate JS in the page, giving up if the answer never comes.
 *
 * WebView silently drops pending `evaluateJavascript` callbacks when the page
 * navigates underneath them -- and this flow polls a page that is redirecting
 * through an SSO chain, so that happens routinely. Without a timeout a single
 * dropped callback wedges the polling loop until the whole login times out,
 * which is what "stuck loading" looked like. A lost answer just means retry.
 */
private suspend fun suspendEval(
    web: WebView,
    js: String,
    timeoutMs: Long = 3_000,
): String? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        try {
            web.evaluateJavascript(js) { v -> if (cont.isActive) cont.resume(v) }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
