package com.feldman.scholix.lemida

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Moodle's anti-bot session is browser-bound: keep both reads and AJAX in WebView. */
class LemidaBrowser(context: Context, agent: String?, supplied: WebView? = null) {
    private val owned = supplied == null
    private val view = supplied ?: WebView(context)
    private val previousClient = view.webViewClient
    private val cookies = LemidaCookieStore(context)
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
    suspend fun prepare() = withContext(Dispatchers.Main) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(view, true)
        val existing = cm.getCookie(LemidaParser.BASE).orEmpty().split(';')
            .map { it.trim().substringBefore('=') }.toSet()
        cookies.load()?.split(';')?.map { it.trim() }?.filter { it.contains('=') && it.substringBefore('=') !in existing }?.forEach { cookie ->
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
    suspend fun get(url: String): String = withContext(Dispatchers.Main) {
        try {
            withTimeout(60_000) {
                val html = suspendCancellableCoroutine<String> { continuation ->
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(web: WebView, finished: String) {
                            // Perfdrive may complete an automatic browser check and redirect itself.
                            // Wait for Moodle; a CAPTCHA that needs a person will time out visibly.
                            val host = android.net.Uri.parse(finished).host
                            if (host in setOf("login.microsoftonline.com", "login.live.com")) {
                                if (continuation.isActive) continuation.resumeWithException(LemidaSessionExpired())
                                return
                            }
                            if (host != "lemida.biu.ac.il") return
                            web.evaluateJavascript("location.origin === '${LemidaParser.BASE}' ? document.documentElement.outerHTML : null") { raw ->
                                val result = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                                if (result != null && continuation.isActive) continuation.resume(result)
                            }
                        }
                    }
                    view.loadUrl(url)
                    continuation.invokeOnCancellation { view.post { view.stopLoading() } }
                }
                snapshot()
                html
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (android.net.Uri.parse(view.url).host != "lemida.biu.ac.il") throw LemidaVerificationRequired()
            throw IOException("Lemida page load timed out. Check your connection and try Refresh.")
        }
    }
    suspend fun post(url: String, body: String): String = withContext(Dispatchers.Main) {
        val slot = "lemida_" + UUID.randomUUID().toString().replace("-", "")
        val started = evaluate("""(() => {
            if (location.origin !== '${LemidaParser.BASE}') return false;
            window['$slot'] = null;
            fetch(${JSONObject.quote(url)}, {method: 'POST', credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'}, body: ${JSONObject.quote(body)}})
              .then(async r => {window['$slot'] = {status:r.status, url:r.url, body:await r.text()};})
              .catch(() => {window['$slot'] = {error:true};});
            return true;
        })()""")
        if (started != "true") throw LemidaSessionExpired()
        try {
            withTimeout(45_000) {
                while (true) {
                    val raw = evaluate("JSON.stringify(window['$slot'] || null)")
                    val value = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                    if (value != null && value != "null") {
                        evaluate("delete window['$slot']")
                        val result = JSONObject(value)
                        if (result.optBoolean("error")) throw IOException("Moodle browser request failed.")
                        if (android.net.Uri.parse(result.optString("url")).host != "lemida.biu.ac.il")
                            throw LemidaVerificationRequired()
                        if (result.optInt("status") !in 200..299) throw IOException("Moodle returned HTTP ${result.optInt("status")}")
                        snapshot()
                        return@withTimeout result.getString("body")
                    }
                    delay(250)
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw IOException("Moodle course request timed out.") }
    }
    private fun snapshot() {
        CookieManager.getInstance().flush()
        CookieManager.getInstance().getCookie("${LemidaParser.BASE}/my/")?.let { cookies.save(it) }
    }
    suspend fun close() = withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
        view.webViewClient = previousClient
        if (owned) { view.stopLoading(); view.destroy() }
    }
}
