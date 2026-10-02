package com.feldman.scholix.lemida

import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Telephony
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONTokener
import kotlinx.coroutines.*

/** Visible Microsoft login, including SMS, Authenticator, and CAPTCHA. */
class LemidaLoginActivity : Activity() {
    private lateinit var browser: WebView
    private var checking = false
    private var syncing = false
    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var alternativeClicked = false
    private var smsSelected = false
    private var challengeStarted = 0L
    private var pendingCode: String? = null
    private var codeSubmitted = false
    private lateinit var status: TextView
    private lateinit var retry: Button
    private var receiverRegistered = false
    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION ||
                challengeStarted == 0L || SystemClock.elapsedRealtime() - challengeStarted > 180_000 || codeSubmitted) return
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val code = LemidaSms.code(messages.joinToString("") { it.messageBody.orEmpty() },
                messages.firstOrNull()?.displayOriginatingAddress.orEmpty()) ?: return
            pendingCode = code
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            browser.evaluateJavascript(LemidaSms.selectScript(alternativeClicked, smsSelected)) { raw ->
                if (isFinishing || isDestroyed) return@evaluateJavascript
                when (runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()) {
                    "alternative" -> alternativeClicked = true
                    "sms" -> {
                        smsSelected = true
                        challengeStarted = SystemClock.elapsedRealtime()
                        status.text = "SMS requested. Waiting for the Microsoft verification code…"
                    }
                    "otp" -> {
                        if (challengeStarted == 0L) challengeStarted = SystemClock.elapsedRealtime()
                        val code = pendingCode
                        if (code != null && !codeSubmitted) {
                            codeSubmitted = true // Submit once. Rejected codes remain editable in the browser.
                            pendingCode = null
                            browser.evaluateJavascript(LemidaSms.submitScript(code)) { result ->
                                if (result == "true") status.text = "Microsoft SMS code submitted. Completing sign-in…"
                            }
                        }
                    }
                }
                handler.postDelayed(this, 750)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        status = TextView(this).apply {
            text = "Sign in to Lemida. SMS verification will be selected and filled automatically. Complete any CAPTCHA here."
            setPadding(20, 20, 20, 20)
        }
        layout.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        retry = Button(this).apply {
            text = "Retry loading homework"
            visibility = android.view.View.GONE
            setOnClickListener {
                visibility = android.view.View.GONE
                browser.loadUrl("${LemidaParser.BASE}/my/")
            }
        }
        actions.addView(retry)
        layout.addView(actions)
        browser = WebView(this)
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        LemidaRepository(this).setUserAgent(browser.settings.userAgentString)
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (android.net.Uri.parse(url).host != "lemida.biu.ac.il" || checking || syncing) return
                checking = true
                view.evaluateJavascript("""(() => location.origin === '${LemidaParser.BASE}'
                    && !document.body.classList.contains('notloggedin')
                    && Number(window.M?.cfg?.userId) > 1
                    && !!document.querySelector('a[href*="/login/logout.php"]'))()""") { value ->
                    checking = false
                    if (value == "true" && !isFinishing) {
                        CookieManager.getInstance().flush()
                        val cookie = CookieManager.getInstance().getCookie("${LemidaParser.BASE}/my/")
                        if (cookie.isNullOrBlank()) {
                            status.text = "Sign-in returned without a Moodle session. Please retry."
                            return@evaluateJavascript
                        }
                        LemidaCookieStore(this@LemidaLoginActivity).save(cookie)
                        syncing = true
                        retry.visibility = android.view.View.GONE
                        status.text = "Signed in. Loading your homework before closing…"
                        loginScope.launch {
                            try {
                                val repo = LemidaRepository(this@LemidaLoginActivity)
                                repo.sync(browser)
                                repo.setEnabled(true)
                                LemidaSyncWorker.schedule(this@LemidaLoginActivity)
                                setResult(RESULT_OK)
                                finish()
                            } catch (e: CancellationException) { throw e
                            } catch (e: Exception) {
                                status.text = "Sign-in was detected, but homework could not load: ${e.message}. Complete any browser check, then retry."
                                syncing = false
                                retry.visibility = android.view.View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
        layout.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        val supportsSms = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.RECEIVE_SMS)
        if (supportsSms) {
            ContextCompat.registerReceiver(this, smsReceiver,
                IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION), Manifest.permission.BROADCAST_SMS,
                null, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 73122)
        } else {
            status.text = "SMS will be selected automatically. Enter the received code in the browser."
        }
        browser.loadUrl("${LemidaParser.BASE}/my/")
        handler.postDelayed(poll, 750)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loginScope.cancel()
        if (receiverRegistered) unregisterReceiver(smsReceiver)
        pendingCode = null
        browser.stopLoading()
        browser.destroy()
        super.onDestroy()
    }
}
