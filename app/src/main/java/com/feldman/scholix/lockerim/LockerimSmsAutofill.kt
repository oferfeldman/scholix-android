package com.feldman.scholix.lockerim

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun lockerimSmsCode(body: String, sender: String? = null): String? {
    if (sender != null && !sender.contains("Lockerim", true) && !body.contains("Lockerim", true)
        && !sender.contains("לוקרים") && !body.contains("לוקרים")) return null
    return Regex("(?<![0-9])([0-9]{4,6})(?![0-9])").findAll(body)
        .map { it.groupValues[1] }.toList().singleOrNull()
}

/** Arms reception before requesting a code; never reads the SMS inbox. */
@Composable
internal fun rememberLockerimSmsAutofill(enabled: Boolean, onCode: (String) -> Unit): suspend () -> Unit {
    val context = LocalContext.current
    val fillCode by rememberUpdatedState(onCode)
    val active by rememberUpdatedState(enabled)
    var requestedAt by remember { mutableLongStateOf(0L) }
    var useDirect by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    fun listening() = active && requestedAt != 0L && SystemClock.elapsedRealtime() - requestedAt < 300_000
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission?.complete(it)
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (listening() && result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)?.let { lockerimSmsCode(it) }?.let(fillCode)
        }
    }
    DisposableEffect(context) {
        val directReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!listening() || !useDirect || intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                lockerimSmsCode(messages.joinToString("") { it.messageBody.orEmpty() },
                    messages.firstOrNull()?.displayOriginatingAddress.orEmpty())?.let(fillCode)
            }
        }
        val consentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!listening() || useDirect || intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    intent.extras?.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)?.let {
                        runCatching { consentLauncher.launch(it) }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, directReceiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            Manifest.permission.BROADCAST_SMS, null, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, consentReceiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            context.unregisterReceiver(directReceiver)
            context.unregisterReceiver(consentReceiver)
        }
    }
    return {
        requestedAt = 0L
        useDirect = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        if (!useDirect) {
            val result = CompletableDeferred<Boolean>()
            permission = result
            permissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
            useDirect = result.await()
            permission = null
        }
        if (!useDirect) withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                SmsRetriever.getClient(context).startSmsUserConsent(null)
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
            }
        }
        requestedAt = SystemClock.elapsedRealtime()
    }
}
