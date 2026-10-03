package com.feldman.scholix.lemida

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Moodle's anti-bot session is browser-bound: keep both reads and AJAX in WebView. */
internal class LemidaBrowser(context: Context, agent: String?, supplied: WebView? = null) : LemidaTransport {
    private val owned = supplied == null
    private val view = supplied ?: WebView(context)
    private val previousClient = view.webViewClient
    private val cookies = LemidaCookieStore(context)
    private var closed = false
    private var installedClient: WebViewClient? = null
    private fun ownerDestroyed() = (view.context as? android.app.Activity)?.isDestroyed == true
    init {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        if (!agent.isNullOrBlank()) view.settings.userAgentString = agent
        if (owned) {
            val metrics = context.resources.displayMetrics
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }
    override suspend fun prepare() = withContext(Dispatchers.Main) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(view, true)
        val existing = cm.getCookie(LemidaParser.BASE).orEmpty().split(';')
            .map { it.trim().substringBefore('=') }.toSet()
        val savedCookies = withContext(Dispatchers.IO) { cookies.load() }
        savedCookies?.split(';')?.map { it.trim() }?.filter { it.contains('=') && it.substringBefore('=') !in existing }?.forEach { cookie ->
            suspendCancellableCoroutine<Unit> { continuation ->
                cm.setCookie(LemidaParser.BASE, "$cookie; Path=/; Secure") {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        cm.flush()
    }
    private suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result ?: "null")
            }
        }
    }
    override suspend fun get(url: String): String = withContext(Dispatchers.Main) {
        check(!closed) { "Lemida browser is closed." }
        var complete = false
        val allowReconnect = url == "${LemidaParser.BASE}/my/"
        val reconnectPaths = mutableSetOf<String>()
        var requestClient: WebViewClient? = null
        try {
            withTimeoutOrNull(60_000) {
                val html = suspendCancellableCoroutine<String> { continuation ->
                    val client = object : WebViewClient() {
                        private var pageGeneration = 0L
                        override fun onPageStarted(web: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            // A reload can keep the URL and client while replacing the document.
                            pageGeneration++
                        }
                        override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                            // Broken images/resources must not discard an otherwise usable page.
                            if (request.isForMainFrame && continuation.isActive) {
                                continuation.resumeWithException(IOException("Lemida could not load. Check your connection and try Refresh."))
                            }
                        }
                        override fun onReceivedHttpError(web: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame && response.statusCode >= 400 && continuation.isActive) {
                                continuation.resumeWithException(IOException("Lemida returned HTTP ${response.statusCode}. Try Refresh."))
                            }
                        }
                        override fun onPageFinished(web: WebView, finished: String) {
                            if (!continuation.isActive || closed || ownerDestroyed() ||
                                web.webViewClient !== this || web.url != finished) return
                            val generation = pageGeneration
                            // Perfdrive may complete an automatic browser check and redirect itself.
                            // Wait for Moodle; a CAPTCHA that needs a person will time out visibly.
                            val host = android.net.Uri.parse(finished).host
                            if (host in setOf("login.microsoftonline.com", "login.live.com")) {
                                if (!allowReconnect) {
                                    continuation.resumeWithException(LemidaSessionExpired())
                                } else {
                                    // Allow Microsoft's existing session to redirect itself back to Moodle.
                                    // Visible input/choice controls require the normal interactive login.
                                    web.evaluateJavascript(LemidaRequestScript.interactiveSignIn()) { value ->
                                        if (value == "true" && continuation.isActive && !closed && !ownerDestroyed() &&
                                            generation == pageGeneration && web.webViewClient === this && web.url == finished)
                                            continuation.resumeWithException(LemidaSessionExpired())
                                    }
                                }
                                return
                            }
                            if (host != "lemida.biu.ac.il") return
                            web.evaluateJavascript(LemidaRequestScript.document(finished)) { raw ->
                                val result = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                                if (result != null && continuation.isActive && !closed && !ownerDestroyed() &&
                                    generation == pageGeneration && web.webViewClient === this && web.url == finished) {
                                    val entry = if (allowReconnect && !LemidaParser.authenticated(result))
                                        LemidaParser.reconnectUrl(result) else null
                                    if (entry != null && reconnectPaths.add(android.net.Uri.parse(entry).path.orEmpty())) {
                                        // Follow each observed portal/provider step once; never loop on a failed SSO return.
                                        web.loadUrl(entry)
                                    } else continuation.resume(result)
                                }
                            }
                        }
                    }
                    requestClient = client
                    installedClient = client
                    view.webViewClient = client
                    view.loadUrl(url)
                }
                snapshot()
                complete = true
                html
            } ?: run {
                if (android.net.Uri.parse(view.url).host != "lemida.biu.ac.il") throw LemidaVerificationRequired()
                throw IOException("Lemida page load timed out. Check your connection and try Refresh.")
            }
        } finally {
            // This runs on Main before the caller can close/reuse the browser, including cancellation.
            // A posted stopLoading callback could otherwise interrupt a later navigation.
            if (!complete && !closed && !ownerDestroyed() && view.webViewClient === requestClient) view.stopLoading()
        }
    }
    override suspend fun post(url: String, body: String): String = withContext(Dispatchers.Main) {
        val slot = "lemida_" + UUID.randomUUID().toString().replace("-", "")
        try {
            withTimeoutOrNull(45_000) {
                val started = evaluate(LemidaRequestScript.start(slot, url, body))
                if (started != "true") throw LemidaSessionExpired()
                while (true) {
                    val raw = evaluate(LemidaRequestScript.poll(slot))
                    val value = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                    if (value != null && value != "null") {
                        val result = JSONObject(value)
                        if (result.optBoolean("verification")) throw LemidaVerificationRequired()
                        if (result.optBoolean("lost")) throw IOException("Lemida navigated during an update. Try Refresh.")
                        if (result.optBoolean("error")) throw IOException("Moodle browser request failed.")
                        val responseUrl = android.net.Uri.parse(result.optString("url"))
                        if (responseUrl.scheme != "https" || responseUrl.host != "lemida.biu.ac.il" ||
                            responseUrl.port !in setOf(-1, 443) || responseUrl.userInfo != null)
                            throw LemidaVerificationRequired()
                        if (result.optInt("status") !in 200..299) throw IOException("Moodle returned HTTP ${result.optInt("status")}")
                        snapshot()
                        return@withTimeoutOrNull result.getString("body")
                    }
                    delay(250)
                }
                @Suppress("UNREACHABLE_CODE") ""
            } ?: throw IOException("Moodle course request timed out. Try Refresh.")
        } finally {
            // Also abort when a worker is replaced/cancelled. Cleanup cannot delay shutdown indefinitely.
            withContext(NonCancellable) {
                runCatching { withTimeoutOrNull(1_000) { evaluate(LemidaRequestScript.cleanup(slot)) } }
            }
        }
    }
    private suspend fun snapshot() {
        CookieManager.getInstance().flush()
        val value = CookieManager.getInstance().getCookie("${LemidaParser.BASE}/my/")
        if (value != null) withContext(Dispatchers.IO) { cookies.save(value) }
    }
    override suspend fun close() = withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
        if (closed) return@withContext
        closed = true
        if (ownerDestroyed()) return@withContext
        if (installedClient == null || view.webViewClient === installedClient) view.webViewClient = previousClient
        if (owned) { view.stopLoading(); view.destroy() }
    }
}
