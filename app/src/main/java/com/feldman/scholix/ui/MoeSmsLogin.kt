package com.feldman.scholix.ui

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.feldman.motion.MotionButton
import com.feldman.scholix.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Mobile layout for the full-screen MOE fallback; native SMS fields use Compose. */
internal const val MOE_VIEWPORT_JS =
    "(function(){try{" +
        "if(location.hostname!=='lgn.edu.gov.il')return;" +
        "var m=document.querySelector('meta[name=\"viewport\"]');" +
        "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
        "(document.head||document.documentElement).appendChild(m);}" +
        "m.setAttribute('content','width=device-width, initial-scale=1');" +
        "if(new URL(location.href).searchParams.get('id')==='EduCombinedAuthSms'){" +
        "var s=document.getElementById('scholix-sms-layout');" +
        "if(!s){s=document.createElement('style');s.id='scholix-sms-layout';document.head.appendChild(s);}" +
        "s.textContent='app-header,.continerTabs,.side-menu,app-footer{display:none!important}'" +
        "+'.main-wrapper{margin-top:0!important;padding:0!important;width:100%!important}'" +
        "+'.main-section,.container,.content-wrapper{width:100%!important;padding:0!important;box-sizing:border-box!important}'" +
        "+'app-login-via-sms .wrapper{padding:16px!important;box-sizing:border-box!important}'" +
        "+'app-login-via-sms h2{font-size:22px!important;margin-top:0!important}'" +
        "+'app-login-via-sms p,app-login-via-sms form label,app-login-via-sms .cesher-phone{font-size:16px!important}'" +
        "+'app-login-via-sms form label{padding-right:0!important}'" +
        "+'app-login-via-sms .verification-code .footer-verification-code{padding-top:12px!important}';}" +
        "}catch(e){}})()"

internal fun WebView.applyMoeContentScale() {
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.textZoom = 100
    settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
    // Let the viewport and overview mode choose the scale; 1 means 1%, not 1x.
    setInitialScale(0)
}

internal fun WebView.injectMoeViewport() = evaluateJavascript(MOE_VIEWPORT_JS, null)

internal object MoeSmsLoginDetails {
    private fun prefs(context: Context) = context.getSharedPreferences("moe_sms_login", Context.MODE_PRIVATE)
    fun lastId(context: Context): String = prefs(context).getString("last_id", "").orEmpty()
    fun phone(context: Context, id: String): String = prefs(context).getString("phone_$id", "").orEmpty()
    fun save(context: Context, id: String, phone: String) {
        prefs(context).edit().putString("last_id", id).putString("phone_$id", phone).apply()
    }
}

@Composable
fun MoeSmsLoginButton(enabled: Boolean = true, onClick: () -> Unit) {
    MotionButton(
        text = stringResource(R.string.moe_sms),
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Keeps the provider's SSO session while its dedicated activity owns the UI. */
@Composable
internal fun MoeSmsLoginScreen(web: WebView, savedId: String, savedPhone: String = "", onCancel: () -> Unit) {
    val context = LocalContext.current
    val cancel by rememberUpdatedState(onCancel)
    DisposableEffect(web) {
        val session = MoeSmsActivitySession(web, savedId, savedPhone) { cancel() }
        MoeSmsLoginActivity.session = session
        context.startActivity(android.content.Intent(context, MoeSmsLoginActivity::class.java))
        onDispose {
            if (MoeSmsLoginActivity.session === session) MoeSmsLoginActivity.session = null
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            session.activity?.finish()
        }
    }
}

internal fun moeSmsRequestJs(id: String, phone: String): String = """
    (function(){
      if(window.__scholixSmsRequested)return 'sent';
      var id=document.getElementById('idCard'),phone=document.getElementById('phoneNumber'),prefix=document.getElementById('phoneCodeDrop');
      if(!id||!phone||!prefix)return 'waiting';
      id.setAttribute('autocomplete','off');phone.setAttribute('autocomplete','off');
      function set(el,value){
        var proto=el.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto,'value').set.call(el,value);
        el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
      }
      if(!window.__scholixSmsFilled){
        var option=Array.from(prefix.options).find(function(o){return o.textContent.trim()===${JSONObject.quote(phone.take(3))};});
        if(!option)return 'waiting';
        set(id,${JSONObject.quote(id)});set(phone,${JSONObject.quote(phone.drop(3))});set(prefix,option.value);
        window.__scholixSmsFilled=true;
      }
      var button=id.form&&id.form.querySelector('button[type=submit]');
      if(!button||button.disabled)return 'waiting';
      window.__scholixSmsRequested=true;
      button.click();return 'sent';
    })()
""".trimIndent()

private enum class HeadlessSmsPhase { Input, Sending, Code, Verifying }

/**
 * Fully native MOE SMS sign-in dialog: no WebView. The caller supplies headless
 * [requestCode]/[verifyCode] operations (OkHttp, no JS); this dialog only collects
 * the saved ID + phone, then the one-time code the user receives by SMS.
 *
 * Saved ID + phone skip straight to requesting a fresh code, like the Inbar SMS login.
 */
@Composable
fun HeadlessMoeSmsDialog(
    initialId: String,
    initialPhone: String,
    onCancel: () -> Unit,
    onAuthenticated: (JSONObject) -> Unit,
    requestCode: suspend (id: String, phone: String) -> String,
    verifyCode: suspend (code: String) -> JSONObject,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(initialId.ifBlank { MoeSmsLoginDetails.lastId(context) }) }
    var phone by remember { mutableStateOf(initialPhone.ifBlank { MoeSmsLoginDetails.phone(context, id) }) }
    var code by remember { mutableStateOf("") }
    var masked by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var phase by remember {
        mutableStateOf(if (isMoeSmsDetailsValid(id, phone)) HeadlessSmsPhase.Sending else HeadlessSmsPhase.Input)
    }

    fun doRequest() {
        if (!isMoeSmsDetailsValid(id, phone)) {
            error = "Enter your ID number and registered mobile number"
            phase = HeadlessSmsPhase.Input
            return
        }
        scope.launch {
            phase = HeadlessSmsPhase.Sending
            error = null
            try {
                masked = requestCode(id, phone)
                MoeSmsLoginDetails.save(context, id, phone)
                code = ""
                phase = HeadlessSmsPhase.Code
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Could not request the SMS code"
                phase = HeadlessSmsPhase.Input
            }
        }
    }
    fun doVerify() {
        if (code.isBlank()) {
            error = "Enter the SMS code sent to your mobile number"
            return
        }
        scope.launch {
            phase = HeadlessSmsPhase.Verifying
            error = null
            try {
                onAuthenticated(verifyCode(code))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = e.localizedMessage ?: "MOE did not accept the SMS code"
                phase = HeadlessSmsPhase.Code
            }
        }
    }
    // Saved ID + phone go straight to the fresh SMS code, like the Inbar SMS login.
    LaunchedEffect(Unit) { if (phase == HeadlessSmsPhase.Sending) doRequest() }

    Dialog(onDismissRequest = onCancel) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Ministry of Education · SMS", modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                when (phase) {
                    HeadlessSmsPhase.Input -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = id, onValueChange = { id = it.filter(Char::isDigit).take(9) },
                            label = { Text("ID number") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = phone, onValueChange = { phone = it.filter(Char::isDigit).take(10) },
                            label = { Text("Mobile number (05…)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { doRequest() }) { Text("Send code") }
                    }
                    HeadlessSmsPhase.Sending -> Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Requesting your SMS code…")
                    }
                    HeadlessSmsPhase.Code -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (masked.isNotBlank()) "Enter the code sent to $masked" else "Enter the code sent to your mobile number")
                        OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit) },
                            label = { Text("SMS code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { doVerify() }) { Text("Verify") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { doRequest() }) { Text("Send a new code") }
                            TextButton(onClick = { phase = HeadlessSmsPhase.Input; error = null }) { Text("Change number") }
                        }
                    }
                    HeadlessSmsPhase.Verifying -> Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Verifying…")
                    }
                }
            }
        }
    }
}

private fun isMoeSmsDetailsValid(id: String, phone: String) =
    id.isNotBlank() && phone.length == 10 && phone.startsWith("05")
