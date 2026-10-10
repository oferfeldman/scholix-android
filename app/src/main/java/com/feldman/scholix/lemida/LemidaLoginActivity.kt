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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import androidx.core.content.ContextCompat
import org.json.JSONTokener
import kotlinx.coroutines.*
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/** Visible Microsoft login, including SMS, Authenticator, and CAPTCHA. */
class LemidaLoginActivity : ComponentActivity() {
    private lateinit var browser: WebView
    private val signInProbe = LemidaLoginProbe()
    private var syncing = false
    private val reconnectPaths = mutableSetOf<String>()
    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val mfa = LemidaMfaState()
    private val smsConsent = LemidaSmsConsentState()
    private var credentials: LemidaCredentials? = null
    private var preferredEmail: String? = null
    private var credentialsLoading = true
    private var detailsRequested = false
    private var detailsDialog: AlertDialog? = null
    private val credentialPolling = LemidaCredentialPoll(
        credentials = { credentials?.let { it.email to it.password } },
        alive = { !isFinishing && !isDestroyed && !syncing && !credentialsLoading && detailsDialog == null && retry.visibility != View.VISIBLE },
        url = { browser.url },
        evaluate = { script, callback -> browser.evaluateJavascript(script) { callback(it) } },
        scheduleTimeout = { callback -> handler.postDelayed({ callback() }, 5_000) },
        missing = { if (!detailsRequested) { detailsRequested = true; editSignInDetails() } },
        status = { status.text = it },
        continueMfa = {
            if (directSmsGranted() || smsConsent.ready(SystemClock.elapsedRealtime())) mfaPolling.poll()
        },
        preferredAccount = { preferredEmail ?: credentials?.email },
        rememberAccount = { email ->
            preferredEmail = email
            loginScope.launch(Dispatchers.IO) { LemidaSignInStore(this@LemidaLoginActivity).remember(email) }
        },
    )
    private val mfaPolling = LemidaMfaPoll(mfa,
        now = { SystemClock.elapsedRealtime() },
        alive = { !isFinishing && !isDestroyed && !syncing && retry.visibility != android.view.View.VISIBLE },
        url = { browser.url }, prepareReception = { prepareSmsReception() },
        evaluate = { script, callback -> browser.evaluateJavascript(script) { callback(it) } },
        scheduleTimeout = { callback -> handler.postDelayed({ callback() }, 5_000) },
        status = { status.text = it },
        requestSms = { LemidaSignInStore(this).reserveSms() },
    )
    private lateinit var status: TextView
    private lateinit var retry: Button
    private var receiverRegistered = false
    private var consentRegistered = false
    private var consentLaunched = false
    private fun currentChallenge() = mfa.active(SystemClock.elapsedRealtime())
    private fun showPageFailure(message: String) {
        if (isFinishing || isDestroyed || syncing) return
        signInProbe.invalidate()
        mfaPolling.invalidate()
        credentialPolling.invalidate()
        status.text = message
        retry.visibility = android.view.View.VISIBLE
    }
    private fun directSmsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    private fun startSmsConsent() {
        val request = smsConsent.start(SystemClock.elapsedRealtime())
        runCatching {
            SmsRetriever.getClient(this).startSmsUserConsent(null).addOnCompleteListener { result ->
                if (!isFinishing && !isDestroyed)
                    smsConsent.complete(request, result.isSuccessful, SystemClock.elapsedRealtime())
            }
        }.onFailure { smsConsent.complete(request, false, SystemClock.elapsedRealtime()) }
    }
    private fun prepareSmsReception(): Boolean {
        if (directSmsGranted()) return true
        val now = SystemClock.elapsedRealtime()
        if (!smsConsent.ready(now)) return false
        if (smsConsent.prepareChallenge(now)) {
            startSmsConsent()
            return false // Reinspect the picker after listener startup; never click a stale choice.
        }
        return true
    }
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
            // Schedule independently: navigation can lose the current JavaScript callback.
            handler.postDelayed(this, 750)
            if (retry.visibility == android.view.View.VISIBLE || syncing) return
            credentialPolling.poll()
        }
    }

    private fun editSignInDetails() {
        if (detailsDialog != null || isFinishing || isDestroyed) return
        credentialPolling.invalidate()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
        }
        form.addView(TextView(this).apply {
            text = "Your email identifies the remembered Microsoft account to tap automatically. A password is optional, for when Microsoft asks for it again. Details stay encrypted on this phone."
        })
        val email = EditText(this).apply {
            hint = "University Microsoft email"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setAutofillHints(View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS)
            setText(preferredEmail ?: credentials?.email.orEmpty())
            isSingleLine = true
        }
        val password = EditText(this).apply {
            hint = "University password (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
            isSingleLine = true
        }
        form.addView(email)
        form.addView(password)
        val dialog = AlertDialog.Builder(this).setTitle("Automatic Lemida sign-in")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save and sign in", null).create()
        var savedDetails = false
        detailsDialog = dialog
        dialog.setOnDismissListener {
            password.text?.clear(); detailsDialog = null
            if (browser.url.isNullOrBlank() && !savedDetails) finish()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedEmail = email.text.toString().trim()
                if (!Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(selectedEmail)) {
                    email.error = "Enter your full university Microsoft email"
                    return@setOnClickListener
                }
                val entered = password.text.toString().takeIf { it.isNotEmpty() }?.let { LemidaCredentials(selectedEmail, it) }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                loginScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            LemidaSignInStore(this@LemidaLoginActivity).apply { remember(selectedEmail); setEnabled(true) }
                            if (entered != null) LemidaCredentialStore(this@LemidaLoginActivity).save(entered)
                        }.isSuccess
                    }
                    if (saved) {
                        savedDetails = true
                        preferredEmail = selectedEmail
                        credentials = entered ?: credentials?.takeIf { it.email.equals(selectedEmail, true) }
                        credentialPolling.detailsChanged()
                        status.text = "Sign-in details saved on this phone. Continuing automatically…"
                        if (browser.url.isNullOrBlank()) browser.loadUrl("${LemidaParser.BASE}/my/")
                        dialog.dismiss()
                    } else {
                        status.text = "Sign-in details could not be saved. Try again or continue in the browser."
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        email.requestFocus()
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
        actions.addView(Button(this).apply { text = "Sign-in details"; setOnClickListener { editSignInDetails() } })
        retry = Button(this).apply {
            text = "Retry"
            visibility = android.view.View.GONE
            setOnClickListener {
                visibility = android.view.View.GONE
                signInProbe.invalidate()
                mfaPolling.invalidate()
                credentialPolling.invalidate()
                reconnectPaths.clear() // A deliberate Retry gets a new bounded portal/provider attempt.
                status.text = "Retrying Lemida sign-in…"
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
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                signInProbe.invalidate()
                mfaPolling.invalidate()
                credentialPolling.invalidate()
                if (!isFinishing && !isDestroyed && !syncing && retry.visibility == android.view.View.VISIBLE) {
                    retry.visibility = android.view.View.GONE
                    status.text = "Loading Lemida sign-in…"
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame)
                    showPageFailure("Sign-in could not load. Check your connection, then tap Retry.")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400)
                    showPageFailure("Sign-in returned HTTP ${response.statusCode}. Tap Retry to try again.")
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (isFinishing || isDestroyed || android.net.Uri.parse(url).host != "lemida.biu.ac.il" || view.url != url || syncing) return
                val request = signInProbe.start() ?: return
                view.evaluateJavascript(LemidaRequestScript.signedIn(url)) { value ->
                    if (isFinishing || isDestroyed || !signInProbe.complete(request)) return@evaluateJavascript
                    if (view.url != url || syncing) return@evaluateJavascript
                    if (value != "true" && reconnectPaths.size < 2) {
                        view.evaluateJavascript(LemidaRequestScript.document(url)) entry@{ raw ->
                            if (isFinishing || isDestroyed || syncing || !signInProbe.isCurrent(request) ||
                                reconnectPaths.size >= 2 || view.url != url ||
                                view.webViewClient !== this) return@entry
                            val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return@entry
                            val entry = LemidaParser.reconnectUrl(html) ?: return@entry
                            if (!reconnectPaths.add(android.net.Uri.parse(entry).path.orEmpty())) return@entry
                            view.loadUrl(entry)
                        }
                    }
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
        loginScope.launch {
            withContext(Dispatchers.IO) {
                preferredEmail = LemidaSignInStore(this@LemidaLoginActivity).email()
                credentials = LemidaCredentialStore(this@LemidaLoginActivity).load()?.takeIf { preferredEmail == null || it.email.equals(preferredEmail, true) }
            }
            credentialsLoading = false
            if (intent.getBooleanExtra("edit_sign_in_details", false)) editSignInDetails()
        }
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
        if (!directSmsGranted()) {
            // Start listening before the picker can request an SMS; failure keeps manual entry available.
            startSmsConsent()
        }
        if (!intent.getBooleanExtra("edit_sign_in_details", false)) browser.loadUrl("${LemidaParser.BASE}/my/")
        handler.postDelayed(poll, 750)
    }
    override fun onDestroy() {
        detailsDialog?.dismiss()
        credentials = null
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
