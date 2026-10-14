package com.feldman.scholix.ui

import android.content.MutableContextWrapper
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionTheme
import com.feldman.scholix.R
import com.feldman.scholix.ui.components.SettingsTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

// The four providers already serialize browser login with moeBrowserLoginMutex.
// Keep that exact WebView/cookie session and callback; never pass credentials in an Intent.
internal class MoeSmsActivitySession(
    val web: WebView,
    val id: String,
    val phone: String,
    val cancel: () -> Unit,
) {
    var activity: MoeSmsLoginActivity? = null
}

class MoeSmsLoginActivity : ComponentActivity() {
    companion object {
        internal var session: MoeSmsActivitySession? = null
    }
    private var active: MoeSmsActivitySession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val login = session ?: run { finish(); return }
        active = login
        login.activity = this
        (login.web.context as? MutableContextWrapper)?.baseContext = this
        enableEdgeToEdge()
        setContent {
            MotionTheme {
                MotionScaffold(
                    modifier = Modifier.fillMaxSize(), scrollable = false,
                    contentPadding = PaddingValues(0.dp),
                    topBar = { SettingsTopBar(getString(R.string.ministry_of_education), onBack = { finish() }) },
                ) {
                    Item { SmsContent(login) }
                }
            }
        }
    }

    override fun onDestroy() {
        val login = active
        if (login != null) {
            (login.web.parent as? android.view.ViewGroup)?.removeView(login.web)
            login.activity = null
            (login.web.context as? MutableContextWrapper)?.baseContext = applicationContext
            if (session === login) {
                session = null
                login.cancel()
            }
        }
        super.onDestroy()
    }

    @Composable
    private fun SmsContent(login: MoeSmsActivitySession) {
        val web = login.web
        val scope = rememberCoroutineScope()
        var id by remember { mutableStateOf(login.id.ifBlank { MoeSmsLoginDetails.lastId(this) }) }
        var phone by remember { mutableStateOf(login.phone.ifBlank { MoeSmsLoginDetails.phone(this, id) }) }
        var code by remember { mutableStateOf("") }
        var phase by remember { mutableStateOf(if (id.isNotBlank() && phone.length == 10) "sending" else "input") }
        var requested by remember { mutableStateOf(false) }
        var browser by remember { mutableStateOf(false) }
        var manualBrowser by remember { mutableStateOf(false) }
        var captchaNeeded by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var masked by remember { mutableStateOf("") }
        val rejectedCode = stringResource(R.string.moe_rejected_code)
        val rejectedDetails = stringResource(R.string.moe_rejected_details)
        val slowFallback = stringResource(R.string.moe_slow_fallback)

        // The old screen could remain on "Connecting…" forever when the MOE SPA
        // accepted the code but failed to complete its redirect. Give the native
        // flow a short chance, then expose the real page automatically so the user
        // can finish instead of being trapped behind a spinner.
        LaunchedEffect(phase) {
            if (phase != "connecting" && phase != "sending") return@LaunchedEffect
            delay(12_000)
            if ((phase == "connecting" || phase == "sending") && !browser) {
                error = slowFallback
                manualBrowser = true
                browser = true
            }
        }

        LaunchedEffect(web) {
            while (true) {
                val uri = web.url?.toUri()
                if (uri?.host == "lgn.edu.gov.il") {
                    if (uri.getQueryParameter("id") != "EduCombinedAuthSms") {
                        web.evalSms("(function(){var t=document.getElementById('tab-1');if(t&&!window.__scholixSmsTab){window.__scholixSmsTab=true;t.click();}})()")
                    } else {
                        web.evalSms(MOE_SMS_OBSERVER_JS)
                        if (phase == "sending" && !requested) {
                            requested = web.evalSms(moeSmsRequestJs(id, phone)) == "\"sent\""
                        }
                        val state = runCatching { JSONObject(web.evalSms(MOE_SMS_STATE_JS)) }.getOrNull()
                        if (state != null) {
                            val result = state.optJSONObject("result")
                            val needsVerification = result?.optBoolean("isCaptchaNeeded") == true ||
                                result?.optString("errorCode") == "WRONG_CAPTCHA"
                            if (result != null && result.optBoolean("isError") && !needsVerification) {
                                error = if (result.optString("kind") == "verify") rejectedCode else rejectedDetails
                                phase = if (state.optBoolean("code") || result.optString("kind") == "verify") "code" else "input"
                            }
                            if (needsVerification) { captchaNeeded = true; error = null }
                            if (result != null && !result.optBoolean("isError", true) && !needsVerification && result.optString("kind") == "request") {
                                id = result.optString("id", id)
                                phone = result.optString("mobile", phone)
                                MoeSmsLoginDetails.save(this@MoeSmsLoginActivity, id, phone)
                                masked = result.optString("phone")
                                captchaNeeded = false
                            }
                            if (state.optBoolean("connecting")) phase = "connecting"
                            browser = manualBrowser || captchaNeeded || state.optBoolean("captcha")
                            if (state.optBoolean("code") && phase != "connecting") {
                                phase = "code"
                                captchaNeeded = false
                                browser = manualBrowser
                                masked = state.optString("phone")
                            }
                        }
                    }
                }
                delay(250)
            }
        }

        Box(Modifier.fillMaxSize().imePadding()) {
            // Keep one mounted browser at its real width even when native fields cover it.
            // A 1dp WebView produces a different viewport and breaks autofill/layout.
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) {
                it.isEnabled = browser
                it.isFocusable = browser
                it.isFocusableInTouchMode = browser
                it.importantForAccessibility = if (browser) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            if (!browser) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    MotionCard(
                        position = MotionItemPosition.Alone,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentPadding = 20.dp,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                stringResource(R.string.moe_sms_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (phase == "input") {
                                OutlinedTextField(
                                    id,
                                    { id = it.filter(Char::isDigit).take(9) },
                                    label = { Text(stringResource(R.string.id_number)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    phone,
                                    { phone = it.filter(Char::isDigit).take(10) },
                                    label = { Text(stringResource(R.string.mobile_number)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                val invalidDetails = stringResource(R.string.enter_registered_details)
                                MotionButton(
                                    text = stringResource(R.string.send_code),
                                    onClick = {
                                        if (id.isBlank() || phone.length != 10 || !phone.startsWith("05")) {
                                            error = invalidDetails
                                        } else {
                                            MoeSmsLoginDetails.save(this@MoeSmsLoginActivity, id, phone)
                                            scope.launch {
                                                web.evalSms("window.__scholixSmsRequested=false;window.__scholixSmsFilled=false;window.__scholixSmsConnecting=false;")
                                                requested = false
                                                error = null
                                                phase = "sending"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (phase == "code") {
                                Text(
                                    if (masked.isBlank()) stringResource(R.string.enter_code_mobile)
                                    else stringResource(R.string.enter_code_phone, masked),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                OutlinedTextField(
                                    code,
                                    { code = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.sms_code)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
                                )
                                val notReady = stringResource(R.string.moe_not_ready)
                                MotionButton(
                                    text = stringResource(R.string.connect),
                                    enabled = code.isNotBlank(),
                                    onClick = {
                                        phase = "connecting"
                                        error = null
                                        scope.launch {
                                            if (web.evalSms(moeSmsVerifyJs(code)) != "true") {
                                                phase = "code"
                                                error = notReady
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(Modifier.size(28.dp))
                                    Text(
                                        if (phase == "connecting") stringResource(R.string.connecting_moe)
                                        else stringResource(R.string.requesting_sms),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                            error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                            MotionButton(
                                text = stringResource(R.string.continue_on_moe_page),
                                onClick = { manualBrowser = true; browser = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun WebView.evalSms(script: String): String = withTimeoutOrNull(1_500) {
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it ?: "null") }
    }
} ?: "null"

internal const val MOE_SMS_OBSERVER_JS = """
    (function(){
      if(window.__scholixSmsObserver)return;
      window.__scholixSmsObserver=true;
      // Observe the site's handlers; they own request sequencing and CAPTCHA.
      var send=XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.send=function(body){
        if(typeof body==='string'&&(body.indexOf('HIN_ID=')>=0||body.indexOf('Ecom_Token=')>=0)){
          this.addEventListener('load',function(){try{
            var r=JSON.parse(this.responseText);
            var params=new URLSearchParams(body);
            window.__scholixSmsResult={kind:params.has('Ecom_Token')?'verify':'request',id:params.get('HIN_ID'),mobile:params.get('HIN_MOBILE'),phone:r.otpDestMasked,isError:r.isError,errorCode:r.errorCode,isCaptchaNeeded:r.isCaptchaNeeded};
            if(r.isError)window.__scholixSmsConnecting=false;
          }catch(e){}});
        }
        return send.apply(this,arguments);
      };
      document.addEventListener('click',function(e){
        if(e.target.closest('#verificationCodeForm button'))window.__scholixSmsConnecting=true;
      },true);
    })()
"""

internal const val MOE_SMS_STATE_JS = """
    (function(){
      var captcha=Array.from(document.querySelectorAll('iframe')).some(function(f){
        var p=f.parentElement&&f.parentElement.parentElement;
        return /bframe/.test(f.src)&&p&&getComputedStyle(p).visibility!=='hidden'&&p.getBoundingClientRect().height>0;
      });
      var result=window.__scholixSmsResult;window.__scholixSmsResult=null;
      var otp=document.querySelector('#otp,#otpMoe');
      if(otp){otp.setAttribute('autocomplete','one-time-code');otp.setAttribute('inputmode','numeric');}
      return {code:!!otp,captcha:captcha,connecting:!!window.__scholixSmsConnecting,result:result,
        phone:(document.querySelector('.verification-code .phone-number')||{}).textContent||''};
    })()
"""

internal fun moeSmsVerifyJs(code: String) = """
    (function(){
      var input=document.querySelector('#otp,#otpMoe');
      if(!input)return false;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,${JSONObject.quote(code)});
      input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));
      var button=input.form&&input.form.querySelector('button[type=submit]');if(!button||button.disabled)return false;
      window.__scholixSmsConnecting=true;button.click();return true;
    })()
""".trimIndent()
