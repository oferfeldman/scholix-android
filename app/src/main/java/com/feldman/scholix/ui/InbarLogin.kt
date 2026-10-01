package com.feldman.scholix.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.feldman.scholix.api.platforms.InbarPlatform
import com.feldman.scholix.api.platforms.inbarSmsCode
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
fun InbarLogin(
    initialAccount: InbarPlatform? = null,
    onSuccess: suspend (InbarPlatform) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = remember(initialAccount?.id) {
        if (initialAccount == null) InbarPlatform() else InbarPlatform(initialAccount.id).apply { setName(initialAccount.getName()) }
    }
    var identity by remember { mutableStateOf(initialAccount?.getUsername().orEmpty()) }
    var mobile by remember { mutableStateOf(initialAccount?.mobile.orEmpty()) }
    var code by remember { mutableStateOf("") }
    var waitingForCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var autofill by remember { mutableStateOf(true) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var autofillNotice by remember { mutableStateOf<String?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
            val received = inbarSmsCode(message)
            if (received != null && (waitingForCode || busy)) code = received
            else autofillNotice = "Enter the code from the SMS manually."
        }
    }
    val canReceive by rememberUpdatedState(autofill && (busy || waitingForCode))
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!canReceive || intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    val consent = intent.extras?.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT) ?: return
                    runCatching { consentLauncher.launch(consent) }.onFailure {
                        autofillNotice = "SMS autofill is unavailable. Enter the code manually."
                    }
                } else if (status.statusCode == CommonStatusCodes.TIMEOUT) {
                    autofillNotice = "SMS autofill timed out. You can still enter the code manually."
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) { delay(1000); secondsLeft-- }
    }

    suspend fun startAutofill() {
        if (!autofill) return
        val started = suspendCancellableCoroutine<Boolean> { continuation ->
            SmsRetriever.getClient(context).startSmsUserConsent(null)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
        }
        if (!started) autofillNotice = "SMS autofill is unavailable. Enter the code manually."
    }

    fun sendSms(resend: Boolean = false) {
        if (busy) return
        busy = true
        error = null
        code = ""
        scope.launch {
            try {
                startAutofill() // Listen before the server sends the SMS.
                withContext(Dispatchers.IO) {
                    if (resend) account.resendSms() else account.requestSms(identity.trim(), mobile.trim())
                }
                waitingForCode = true
                secondsLeft = 45
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                error = exception.localizedMessage ?: "Could not request an SMS code"
            } finally { busy = false }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!waitingForCode) {
            Text("Sign in with your ID/passport and the mobile number registered with Bar-Ilan.")
            OutlinedTextField(identity, { identity = it }, label = { Text("ID or passport") },
                enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(mobile, { mobile = it }, label = { Text("Registered mobile") },
                enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            Row {
                Checkbox(checked = autofill, onCheckedChange = { autofill = it }, enabled = !busy)
                Text("Fill SMS code automatically", modifier = Modifier.padding(top = 12.dp))
            }
            if (autofill) Text("Android will ask you to share the single verification message.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { sendSms() }, enabled = !busy && identity.isNotBlank() && mobile.isNotBlank()) { Text("Send SMS code") }
        } else {
            Text("Enter the verification code sent to your registered mobile.")
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(10) }, label = { Text("SMS verification code") },
                enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Button(onClick = {
                if (!busy) {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { account.verifySms(code.trim()) }
                            code = ""
                            onSuccess(account)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (exception: Exception) {
                            error = exception.localizedMessage ?: "Could not verify the SMS code"
                        } finally { busy = false }
                    }
                }
            }, enabled = !busy && code.length in 4..10) { Text("Verify and add Inbar") }
            TextButton(onClick = { sendSms(resend = true) }, enabled = !busy && secondsLeft == 0) {
                Text(if (secondsLeft > 0) "Resend code in ${secondsLeft}s" else "Resend SMS code")
            }
            TextButton(onClick = { waitingForCode = false; code = ""; error = null }, enabled = !busy) { Text("Change login details") }
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        autofillNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
    }
}
