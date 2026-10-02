package com.feldman.scholix.lemida

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/** Visible Microsoft login, including SMS, Authenticator, and CAPTCHA. */
class LemidaLoginActivity : ComponentActivity() {
    private lateinit var browser: WebView
    private var checking = false
    private var syncing = false
    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val mfa = LemidaMfaState()
    private lateinit var status: TextView
    private lateinit var retry: Button
    private var receiverRegistered = false
    private var consentRegistered = false
    private var smsReady = false
    private var consentLaunched = false
    private fun currentChallenge() = mfa.active(SystemClock.elapsedRealtime())
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (currentChallenge() && result.resultCode == RESULT_OK) {
            mfa.acceptCode(result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty(), "", SystemClock.elapsedRealtime())
        }
        if (!mfa.hasPending && !mfa.submitted && !syncing && !isFinishing)
            status.text = "Enter the Microsoft verification code in the browser to continue."
    }
    private val consentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION || !currentChallenge() || consentLaunched || mfa.hasPending) return
            val result = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
            if (result.statusCode == CommonStatusCodes.SUCCESS) {
                val consent = intent.extras?.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT) ?: return
                consentLaunched = true
                runCatching { consentLauncher.launch(consent) }.onFailure {
                    status.text = "Enter the Microsoft verification code in the browser to continue."
                }
            }
        }
    }
    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION ||
                !currentChallenge()) return
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            mfa.acceptCode(messages.joinToString("") { it.messageBody.orEmpty() },
                messages.firstOrNull()?.displayOriginatingAddress.orEmpty(), SystemClock.elapsedRealtime())
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (!smsReady) { handler.postDelayed(this, 250); return }
            browser.evaluateJavascript(LemidaSms.selectScript(mfa.alternativeClicked, mfa.smsSelected)) { raw ->
                if (isFinishing || isDestroyed) return@evaluateJavascript
                when (runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()) {
                    "alternative" -> {
                        if (mfa.prepareAlternative()) browser.evaluateJavascript(LemidaSms.chooseScript("alternative"), null)
                    }
                    "sms" -> {
                        if (mfa.prepareSms(SystemClock.elapsedRealtime())) {
                            status.text = "SMS requested. Waiting for the Microsoft verification code…"
                            browser.evaluateJavascript(LemidaSms.chooseScript("sms")) { result ->
                                if (result == "false" && !isFinishing && !isDestroyed)
                                    status.text = "The verification page changed. Select SMS in the browser to continue."
                            }
                        }
                    }
                    "otp", "otp-waiting" -> {
                        mfa.observeOtp(SystemClock.elapsedRealtime())
                        val code = mfa.pendingCode(SystemClock.elapsedRealtime())
                        if (code != null) {
                            // Filling may enable Verify asynchronously. Keep the code until it can be submitted.
                            browser.evaluateJavascript(LemidaSms.prepareCodeScript(code)) ready@{ ready ->
                                if (isFinishing || isDestroyed) return@ready
                                if (ready != "true") return@ready
                                if (mfa.pendingCode(SystemClock.elapsedRealtime()) != code) return@ready
                                val toSubmit = mfa.consumeCode(SystemClock.elapsedRealtime()) ?: return@ready
                                browser.evaluateJavascript(LemidaSms.submitScript(toSubmit)) submitted@{ result ->
                                    if (isFinishing || isDestroyed) return@submitted
                                    if (result == "true") status.text = "Microsoft SMS code submitted. Completing sign-in…"
                                    else if (result == "false") status.text = "The verification page changed. Enter the code in the browser to continue."
                                }
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
                if (isFinishing || isDestroyed || android.net.Uri.parse(url).host != "lemida.biu.ac.il" || checking || syncing) return
                checking = true
                view.evaluateJavascript("""(() => location.origin === '${LemidaParser.BASE}'
                    && !document.body.classList.contains('notloggedin')
                    && Number(window.M?.cfg?.userId) > 1
                    && !!document.querySelector('a[href*="/login/logout.php"]'))()""") { value ->
                    if (isFinishing || isDestroyed) return@evaluateJavascript
                    checking = false
                    if (value == "true" && !isFinishing) {
                        CookieManager.getInstance().flush()
                        val cookie = CookieManager.getInstance().getCookie("${LemidaParser.BASE}/my/")
                        if (cookie.isNullOrBlank()) {
                            status.text = "Sign-in returned without a Moodle session. Please retry."
                            retry.visibility = android.view.View.VISIBLE
                            return@evaluateJavascript
                        }
                        syncing = true
                        retry.visibility = android.view.View.GONE
                        status.text = "Signed in. Loading your homework before closing…"
                        loginScope.launch {
                            try {
                                withContext(Dispatchers.IO) { LemidaCookieStore(this@LemidaLoginActivity).save(cookie) }
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
        ContextCompat.registerReceiver(this, consentReceiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        consentRegistered = true
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
            status.text = "SMS will be selected automatically. Android may ask to share the verification message; you can also enter the code here."
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            smsReady = true
        } else {
            // Start listening before the picker can request an SMS; failure keeps manual entry available.
            runCatching {
                SmsRetriever.getClient(this).startSmsUserConsent(null).addOnCompleteListener { smsReady = true }
            }.onFailure { smsReady = true }
            // An unavailable/stalled Play services task must not block method selection forever.
            handler.postDelayed({ smsReady = true }, 5_000)
        }
        browser.loadUrl("${LemidaParser.BASE}/my/")
        handler.postDelayed(poll, 750)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loginScope.cancel()
        if (receiverRegistered) unregisterReceiver(smsReceiver)
        if (consentRegistered) unregisterReceiver(consentReceiver)
        mfa.discardCode()
        browser.stopLoading()
        browser.destroy()
        super.onDestroy()
    }
}
