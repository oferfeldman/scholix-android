package com.feldman.scholix.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.feldman.scholix.api.platforms.InbarPlatform
import com.feldman.scholix.api.platforms.inbarAutomaticSmsCode
import com.feldman.scholix.api.platforms.inbarSmsCode
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** No sign-in UI: the caller owns the common fields, loading indicator and error message. */
@Composable
fun HiddenInbarLogin(
    account: InbarPlatform,
    onSmsRequested: suspend (InbarPlatform) -> Unit,
    onResult: suspend (InbarPlatform?, String?) -> Unit,
    timeoutMs: Long = 90_000
) {
    val context = LocalContext.current
    val saveAccount by rememberUpdatedState(onSmsRequested)
    val finish by rememberUpdatedState(onResult)
    val codes = remember(account) { Channel<String>(Channel.CONFLATED) }
    val permission = remember(account) { CompletableDeferred<Boolean>() }
    val listening = remember(account) { AtomicBoolean(false) }
    val requestedAt = remember(account) { AtomicLong(0L) }
    val directAvailable = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.RECEIVE_SMS)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission.complete(it)
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (listening.get()) {
            val code = if (result.resultCode == Activity.RESULT_OK)
                inbarSmsCode(result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()) else null
            if (code != null) codes.trySend(code)
            else codes.close(IOException("SMS verification was declined or unavailable. Please try signing in again."))
        }
    }
    DisposableEffect(context, account) {
        val consentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!listening.get() || intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    val consent = intent.extras?.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                    if (consent != null) runCatching { consentLauncher.launch(consent) }.onFailure {
                        codes.close(IOException("SMS verification is unavailable. Please try signing in again."))
                    }
                } else if (status.statusCode == CommonStatusCodes.TIMEOUT) {
                    codes.close(IOException("SMS verification timed out. Please try signing in again."))
                }
            }
        }
        val directReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!listening.get() || intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
                val requestTime = requestedAt.get()
                if (requestTime == 0L || SystemClock.elapsedRealtime() - requestTime > timeoutMs) return
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                val code = inbarAutomaticSmsCode(messages.joinToString("") { it.messageBody.orEmpty() },
                    messages.firstOrNull()?.displayOriginatingAddress.orEmpty())
                if (code != null) codes.trySend(code)
            }
        }
        ContextCompat.registerReceiver(context, consentReceiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
        if (directAvailable) ContextCompat.registerReceiver(context, directReceiver,
            IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION), Manifest.permission.BROADCAST_SMS,
            null, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            listening.set(false)
            codes.cancel()
            context.unregisterReceiver(consentReceiver)
            if (directAvailable) context.unregisterReceiver(directReceiver)
        }
    }
    LaunchedEffect(account) {
        var phase = "permission"
        Log.d("InbarLogin", "Starting sign-in")
        try {
            val hasPermission = directAvailable &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            val useDirect = hasPermission || (directAvailable && run {
                permissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                permission.await()
            })
            if (!useDirect) {
                phase = "consent listener"
                val started = withTimeoutOrNull(5_000) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        SmsRetriever.getClient(context).startSmsUserConsent(null)
                            .addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
                            .addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
                    }
                } == true
                if (!started) throw IOException("SMS verification is unavailable. Please allow SMS access and try again.")
            }
            val startedAt = SystemClock.elapsedRealtime()
            requestedAt.set(startedAt)
            listening.set(true) // The receiver is registered before any SMS request; early delivery is buffered.
            phase = "SMS request"
            withContext(Dispatchers.IO) { account.requestSms(account.getUsername(), account.mobile) }
            Log.d("InbarLogin", "SMS request completed in ${SystemClock.elapsedRealtime() - startedAt} ms")
            saveAccount(account)
            phase = "SMS reception"
            val code = withTimeoutOrNull(timeoutMs) { codes.receive() }
                ?: throw IOException("SMS verification timed out. Please try signing in again.")
            listening.set(false) // Only one received code is submitted, with no retry loop.
            val verifyingAt = SystemClock.elapsedRealtime()
            phase = "verification"
            Log.d("InbarLogin", "SMS received after ${verifyingAt - startedAt} ms")
            withContext(Dispatchers.IO) { account.verifySms(code) }
            Log.d("InbarLogin", "Verification and grades completed in ${SystemClock.elapsedRealtime() - verifyingAt} ms")
            finish(account, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.d("InbarLogin", "Sign-in failed during $phase (${exception.javaClass.simpleName})")
            listening.set(false)
            finish(null, "Login failed: ${exception.localizedMessage ?: "Unable to sign in"}")
        } finally {
            listening.set(false)
        }
    }
}
