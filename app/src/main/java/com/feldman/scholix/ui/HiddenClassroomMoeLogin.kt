package com.feldman.scholix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val TAG = "HiddenClassroomMoe"

/**
 * Executes a headless MOE SSO login sequence inside an off-screen WebView:
 *  1. Opens https://my.edu.gov.il/auth/eduidp
 *  2. If already logged in, redirects immediately to https://my.edu.gov.il/home
 *     If not logged in, lands on https://lgn.edu.gov.il and submits the credentials
 *  3. Once on my.edu.gov.il, calls /auth/my to resolve the user's student ID
 *     and derives their institutional Google email (<studentId>@educ.org.il)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HiddenClassroomMoeLogin(
    username: String,
    password: String,
    onStatus: (String) -> Unit,
    onResult: (email: String?, error: String?) -> Unit,
    timeoutMs: Long = 60_000,
) {
    val context = LocalContext.current
    val filled = remember { AtomicBoolean(false) }
    val done = remember { AtomicBoolean(false) }

    fun finish(email: String?, err: String?) {
        if (done.compareAndSet(false, true)) {
            Log.i(TAG, "finish: email=$email, error=$err")
            onResult(email, err)
        }
    }

    val web = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            }
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.i(TAG, "onPageStarted: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val host = runCatching { url?.toUri()?.host }.getOrNull().orEmpty()
                    Log.i(TAG, "onPageFinished: $url (host=$host, submitted=${filled.get()})")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            web.stopLoading()
            web.destroy()
        }
    }

    LaunchedEffect(username, password) {
        onStatus("מתחבר לשער משרד החינוך...")
        val completed = withTimeoutOrNull(timeoutMs) {
            var loopCount = 0
            while (!done.get()) {
                loopCount++
                val currentUrl = web.url
                val host = runCatching { currentUrl?.toUri()?.host }.getOrNull().orEmpty()

                if (loopCount % 5 == 0 || loopCount <= 3) {
                    Log.i(TAG, "poll #$loopCount: host=$host, submitted=${filled.get()}, url=$currentUrl")
                }

                // 1. Submit credentials on lgn.edu.gov.il NetIQ form if needed
                if (!filled.get() && host.endsWith("lgn.edu.gov.il", true)) {
                    onStatus("מזין פרטי הזדהות משרד החינוך...")
                    val r = suspendEvaluate(web, loginJs(username, password))?.trim('"', ' ')
                    when {
                        r == "filled" -> {
                            Log.i(TAG, "credentials submitted to MOE IdP")
                            filled.set(true)
                            onStatus("מאמת כניסה מול משרד החינוך...")
                        }
                        r != null && r.startsWith("error") -> {
                            Log.w(TAG, "loginJs error: $r")
                            finish(null, r)
                            return@withTimeoutOrNull true
                        }
                        else -> {
                            // "noform" - still loading or waiting on interstitial
                        }
                    }
                }

                // 2. When on my.edu.gov.il, extract Google email
                // Note: We deliberately do NOT require filled.get() because if an SSO session
                // was already active, the WebView lands directly on my.edu.gov.il without needing form submission!
                if (host.equals("my.edu.gov.il", true) || host.endsWith(".my.edu.gov.il", true)) {
                    onStatus("מאתר חשבון בית-ספר...")
                    val rawResult = suspendEvaluate(web, EXTRACT_EMAIL_JS)
                    val json = parseEvalJson(rawResult)
                    if (json != null) {
                        if (json.optBoolean("success", false)) {
                            val email = json.optString("email").takeIf { it.isNotBlank() }
                            val studentId = json.optString("studentId")
                            if (email != null) {
                                Log.i(TAG, "Extracted school Google email: $email (ID: $studentId)")
                                finish(email, null)
                                return@withTimeoutOrNull true
                            }
                        } else if (json.optBoolean("notAuth", false)) {
                            if (!filled.get()) {
                                Log.i(TAG, "Session expired on my.edu.gov.il, redirecting to login portal...")
                                web.loadUrl("https://my.edu.gov.il/auth/eduidp?next=https%3A%2F%2Fmy.edu.gov.il%2F")
                            }
                        } else if (json.optBoolean("notReady", false)) {
                            Log.d(TAG, "auth/my claims not ready yet")
                        } else if (json.has("error")) {
                            Log.w(TAG, "EXTRACT_EMAIL_JS returned error: ${json.optString("error")}")
                        }
                    }
                }
                delay(500)
            }
            true
        }

        if (completed == null && !done.get()) {
            Log.w(TAG, "Timeout waiting for MOE login")
            val msg = if (filled.get()) {
                "ההתחברות הצליחה, אך טעינת נתוני חשבון גוגל ממשרד החינוך ארכה זמן רב מדי. אנא נסה שוב."
            } else {
                "זמן ההתחברות למשרד החינוך פג. אנא ודא ששם המשתמש והסיסמה נכונים ונסה שוב."
            }
            finish(null, msg)
        }
    }

    DisposableEffect(Unit) {
        val startUrl = "https://my.edu.gov.il/auth/eduidp?next=https%3A%2F%2Fmy.edu.gov.il%2F"
        web.loadUrl(startUrl)
        onDispose { }
    }
}

/**
 * Safely parses the output of evaluateJavascript.
 * Android's evaluateJavascript serializes whatever JS returns into a JSON-formatted string.
 * This parser correctly unwraps JSON objects, doubly-encoded JSON strings, and raw strings.
 */
internal fun parseEvalJson(raw: String?): JSONObject? {
    if (raw == null || raw == "null" || raw.isBlank()) return null
    val clean = raw.trim()
    if (clean == "\"pending\"" || clean == "pending") return null

    return try {
        val tokener = JSONTokener(clean)
        when (val v = tokener.nextValue()) {
            is JSONObject -> v
            is String -> {
                if (v.isBlank() || v == "pending" || v == "null") null
                else JSONObject(v)
            }
            else -> null
        }
    } catch (e: Exception) {
        runCatching {
            val unescaped = clean.trim('"', ' ')
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            JSONObject(unescaped)
        }.getOrNull()
    }
}

private suspend fun suspendEvaluate(
    web: WebView,
    js: String,
    timeoutMs: Long = 4_000,
): String? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        try {
            web.evaluateJavascript(js) { value ->
                if (cont.isActive) cont.resume(value)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }
}

private fun loginJs(username: String, password: String): String {
    val u = JSONObject.quote(username)
    val p = JSONObject.quote(password)
    return """
    (function(){
      try {
        function setVal(el, val) {
          if (!el) return;
          el.focus();
          el.value = val;
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        }
        function pick(sels) {
          for (var i=0;i<sels.length;i++){
            var el = document.querySelector(sels[i]);
            if (el) return el;
          }
          return null;
        }
        var user = pick(['input[formcontrolname="userName"]','input[name="userName"]',
                         'input[type="text"]:not([type="hidden"])']);
        var pass = pick(['input[formcontrolname="password"]','input[type="password"]']);
        if (!user || !pass) {
          // If password field is missing, check if we need to switch tab from SMS to username/password
          var tabs = document.querySelectorAll('a, button, [role="tab"], .tab, span');
          for (var i = 0; i < tabs.length; i++) {
            var txt = (tabs[i].innerText || tabs[i].textContent || '').trim();
            if (txt.indexOf('קוד משתמש') > -1 || txt.indexOf('סיסמ') > -1) {
              tabs[i].click();
              break;
            }
          }
          return 'noform';
        }
        setVal(user, $u);
        setVal(pass, $p);
        var btn = pick(['button[type="submit"]','form button','.login-button','button']);
        if (btn) { btn.click(); } else if (pass.form) { pass.form.submit(); }
        return 'filled';
      } catch(e){ return 'error: ' + e; }
    })()
    """.trimIndent()
}

/**
 * Evaluates in-page fetch to `/auth/my` to extract the student's exidentifier.
 * If already resolved, returns the cached result synchronously.
 */
private const val EXTRACT_EMAIL_JS = """
(function() {
    if (window.__scholix_res) {
        try {
            var parsed = JSON.parse(window.__scholix_res);
            if (parsed && (parsed.success || parsed.error)) return window.__scholix_res;
        } catch(e) {}
    }
    if (!window.__scholix_fetching) {
        window.__scholix_fetching = true;
        fetch('/auth/my', { credentials: 'include' })
            .then(function(r) { 
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json(); 
            })
            .then(function(data) {
                var exId = null;
                if (data && data.claims && Array.isArray(data.claims)) {
                    for (var i = 0; i < data.claims.length; i++) {
                        var c = data.claims[i];
                        if (c.type && (c.type === 'exidentifier' || c.type.indexOf('exidentifier') !== -1)) {
                            exId = c.value;
                            break;
                        }
                    }
                }
                if (!exId && data && data.user) {
                    exId = data.user.exidentifier || data.user.id || data.user.tz;
                }
                if (exId) {
                    window.__scholix_res = JSON.stringify({ success: true, email: exId + '@educ.org.il', studentId: exId });
                } else if (data && data.isAuth === false) {
                    window.__scholix_fetching = false;
                    window.__scholix_res = JSON.stringify({ notAuth: true });
                } else {
                    window.__scholix_fetching = false;
                    window.__scholix_res = JSON.stringify({ notReady: true, raw: data });
                }
            })
            .catch(function(err) {
                window.__scholix_fetching = false;
                window.__scholix_res = JSON.stringify({ error: 'fetch failed: ' + err });
            });
    }
    return window.__scholix_res || 'pending';
})()
"""
