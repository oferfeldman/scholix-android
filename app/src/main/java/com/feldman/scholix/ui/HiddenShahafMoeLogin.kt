package com.feldman.scholix.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.feldman.scholix.api.platforms.ShahafApi
import com.feldman.scholix.api.platforms.ShahafMoeLogin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MOE sign-in for Shahaf. Blank password opens the native, full-screen SMS activity.
 * The browser retains the SSO session and handles any required CAPTCHA; the
 * Shahaf callback is captured as soon as navigation starts. Password login
 * uses the HTTP client.
 */
@Composable
fun HiddenShahafMoeLogin(
    username: String,
    password: String,
    deviceId: String,
    savedPhone: String = "",
    onResult: (JSONObject?, String?) -> Unit,
) {
    var ownsLogin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        moeBrowserLoginMutex.lock()
        try { ownsLogin = true; awaitCancellation() }
        finally { ownsLogin = false; moeBrowserLoginMutex.unlock() }
    }
    if (!ownsLogin) return
    if (password.isBlank()) ShahafSmsWebLogin(username, deviceId, savedPhone, onResult)
    else HeadlessShahafPasswordLogin(username, password, deviceId, onResult)
}

/** Retains Shahaf's browser session while the SMS activity owns the UI. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ShahafSmsWebLogin(
    username: String,
    deviceId: String,
    savedPhone: String,
    onResult: (JSONObject?, String?) -> Unit,
) {
    val context = LocalContext.current
    val done = remember { AtomicBoolean(false) }

    fun finish(response: JSONObject?, error: String?) {
        if (done.compareAndSet(false, true)) onResult(response, error)
    }
    fun checkUrl(url: String?) {
        if (url.isNullOrBlank() || done.get()) return
        try {
            ShahafApi.authResponseFromUrl(url)?.let { finish(it, null) }
        } catch (_: Exception) {
            finish(null, "Shahaf returned an invalid sign-in response")
        }
    }

    val web = remember {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // A stale session short-circuits the SSO into a half-signed-in state.
        WebStorage.getInstance().deleteAllData()

        WebView(android.content.MutableContextWrapper(context)).apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Content scale must be set BEFORE the first loadUrl: the dialog's
            // update block runs after the load starts, which left the MOE
            // desktop page hugely zoomed.
            applyMoeContentScale()

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    checkUrl(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    checkUrl(url)
                    // Cancel only once the payload is captured; every other
                    // navigation (Shahaf entry, MOE interstitials, WS-Fed POSTs)
                    // must proceed or the chain breaks.
                    return done.get()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // lgn.edu.gov.il ships no mobile viewport meta: inject one so
                    // the desktop-oriented SPA layouts at device width.
                    view?.injectMoeViewport()
                    // Belt and braces: some redirects arrive without going
                    // through shouldOverrideUrlLoading (server-side 30x chains).
                    checkUrl(url)
                }
            }
            cm.removeAllCookies {
                cm.flush()
                if (!done.get()) loadUrl(ShahafApi.SIGN_IN_PAGE + deviceId)
            }
        }
    }

    LaunchedEffect(web) {
        val completed = withTimeoutOrNull(SMS_TIMEOUT_MS) {
            while (!done.get()) {
                // The success navigation can slip past both client callbacks
                // while the dialog's own poll loop drives the MOE pages.
                checkUrl(web.url)
                delay(500)
            }
            true
        }
        if (completed == null) finish(null, "SMS sign-in timed out. Please try again.")
    }

    DisposableEffect(web) {
        onDispose {
            done.set(true)
            web.stopLoading()
            web.destroy()
        }
    }

    MoeSmsLoginScreen(web, username, savedPhone) { finish(null, "SMS sign-in cancelled") }
}

private const val SMS_TIMEOUT_MS = 600_000L

@Composable
private fun HeadlessShahafPasswordLogin(
    username: String,
    password: String,
    deviceId: String,
    onResult: (JSONObject?, String?) -> Unit,
) {
    val resultCallback by rememberUpdatedState(onResult)
    LaunchedEffect(username, password, deviceId) {
        val entryUrl = ShahafApi.SIGN_IN_PAGE + deviceId
        val completed = withTimeoutOrNull(120_000) {
            try {
                val response = withContext(Dispatchers.IO) {
                    ShahafMoeLogin().loginWithPassword(entryUrl, username, password)
                }
                resultCallback(response, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                resultCallback(null, e.localizedMessage
                    ?: "Ministry of Education sign-in failed. Check your credentials and try again.")
            }
            true
        }
        if (completed == null) resultCallback(null,
            "Ministry of Education sign-in timed out. Check your credentials and try again.")
    }
}
