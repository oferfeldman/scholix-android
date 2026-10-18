package com.feldman.scholix.lockerim

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonDefaults
import com.feldman.scholix.api.platforms.LockerimPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LockerimLoginView(
    account: LockerimPlatform? = null,
    onSuccess: (LockerimPlatform) -> Unit,
    onCancel: () -> Unit
) {
    val draft = remember(account?.id) { account?.let { LockerimPlatform.fromJson(it.toJson()) } ?: LockerimPlatform() }
    val api = remember { LockerimApi() }
    val scope = rememberCoroutineScope()
    var phone by rememberSaveable(account?.id) { mutableStateOf(account?.phone.orEmpty()) }
    var otpToken by remember { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendAt by remember { mutableLongStateOf(0) }
    var resendSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(resendAt) {
        while (resendAt > System.currentTimeMillis()) {
            resendSeconds = ((resendAt - System.currentTimeMillis()) / 1000).toInt() + 1
            delay(1000)
        }
        resendSeconds = 0
    }
    fun submit(operation: String) {
        if (busy) return
        if (!phone.matches(Regex("05[0-9]{8}"))) {
            error = "Enter the Israeli mobile number registered with Lockerim (05xxxxxxxx)."
            return
        }
        if (operation == "verify" && !code.matches(Regex("[0-9]{4,6}"))) {
            error = "Enter the code from the SMS."
            return
        }
        error = null
        busy = true
        pending = operation
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("לוקרים", style = MaterialTheme.typography.headlineSmall)
        Text(if (otpToken.isBlank()) "Sign in with the mobile number registered with Lockerim."
            else "Enter the SMS code sent to $phone.")
        OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(10) },
            label = { Text("Mobile number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true, enabled = !busy && otpToken.isBlank(), modifier = Modifier.fillMaxWidth())
        if (otpToken.isNotBlank()) {
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) },
                label = { Text("SMS code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        MotionButton(text = if (otpToken.isBlank()) "Send SMS code" else "Connect", enabled = !busy,
            modifier = Modifier.fillMaxWidth(), sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp),
            onClick = { submit(if (otpToken.isBlank()) "send" else "verify") })
        if (otpToken.isNotBlank()) Row {
            TextButton(enabled = !busy && resendSeconds == 0, onClick = { submit("resend") }) {
                Text(if (resendSeconds > 0) "Resend in ${resendSeconds}s" else "Resend code")
            }
            TextButton(enabled = !busy, onClick = { otpToken = ""; code = ""; error = null }) { Text("Change number") }
        }
        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
        pending?.let { operation ->
            LockerimCaptcha(onResult = { captcha, captchaError ->
                pending = null
                if (captcha == null) {
                    busy = false
                    error = captchaError
                } else scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            when (operation) {
                                "send" -> otpToken = api.sendCode(phone, captcha).getString("otpToken")
                                "resend" -> otpToken = api.resendCode(otpToken, captcha).getString("otpToken")
                                "verify" -> {
                                    draft.phone = phone
                                    draft.acceptSession(api.verifyCode(otpToken, code, captcha))
                                }
                            }
                        }
                        if (operation == "verify") onSuccess(draft)
                        else resendAt = System.currentTimeMillis() + 30_000
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.localizedMessage ?: "Could not sign in to Lockerim. Try again."
                    } finally {
                        busy = false
                    }
                }
            })
        }
    }
}

/** Same reCAPTCHA v3 flow used by the official app; no challenge or verification is bypassed. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LockerimCaptcha(onResult: (String?, String?) -> Unit) {
    val callback by rememberUpdatedState(onResult)
    var completed by remember { mutableStateOf(false) }
    fun finish(token: String?, error: String?) {
        if (!completed) {
            completed = true
            callback(token, error)
        }
    }
    LaunchedEffect(Unit) {
        delay(30_000)
        finish(null, "Lockerim verification timed out. Check your connection and try again.")
    }
    AndroidView(modifier = Modifier.size(1.dp), factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun result(token: String) {
                    Handler(Looper.getMainLooper()).post { finish(token.takeIf { it.isNotBlank() }, "Could not verify with Lockerim. Try again.") }
                }
            }, "ScholixCaptcha")
            loadDataWithBaseURL("https://localhost:3000/", """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <script src="https://www.google.com/recaptcha/api.js?render=6LddF1sqAAAAAOJOi2wfSzEGMm61zBUyOCEL38EZ"
                  onerror="ScholixCaptcha.result('')"></script></head><body><script>
                if(window.grecaptcha) grecaptcha.ready(function(){
                  grecaptcha.execute('6LddF1sqAAAAAOJOi2wfSzEGMm61zBUyOCEL38EZ',{action:''})
                    .then(function(token){ScholixCaptcha.result(token)},function(){ScholixCaptcha.result('')});
                }); else ScholixCaptcha.result('');
                </script></body></html>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
    }, onRelease = { it.removeJavascriptInterface("ScholixCaptcha"); it.stopLoading(); it.destroy() })
}
