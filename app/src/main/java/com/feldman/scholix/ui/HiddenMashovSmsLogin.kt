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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.feldman.scholix.api.platforms.MashovCaptchaRequired
import com.feldman.scholix.api.platforms.MashovPlatform
import com.feldman.scholix.api.platforms.mashovSmsCode
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private val mashovSmsLoginMutex = Mutex()

/**
 * Background-first Mashov SMS login.
 *
 * A saved private-device token is always tried first. Only if it no longer works
 * do we request a fresh SMS. With RECEIVE_SMS permission the code is consumed
 * silently; otherwise Android's SMS User Consent prompt grants access to only the
 * matching message, without showing Mashov's website.
 */
@Composable
fun HiddenMashovSmsLogin(
    account: MashovPlatform,
    onSmsRequested: suspend (MashovPlatform) -> Unit = {},
    onStatus: (String) -> Unit = {},
    onResult: suspend (MashovPlatform?, String?) -> Unit,
    timeoutMs: Long = 90_000L,
) {
    val context = LocalContext.current
    val saveAccount by rememberUpdatedState(onSmsRequested)
    val finish by rememberUpdatedState(onResult)
    val updateStatus by rememberUpdatedState(onStatus)
    val codes = remember(account.id) { Channel<String>(Channel.BUFFERED) }
    val permission = remember(account.id) { CompletableDeferred<Boolean>() }
    val listening = remember(account.id) { AtomicBoolean(false) }
    val requestedAt = remember(account.id) { AtomicLong(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!permission.isCompleted) permission.complete(granted)
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!listening.get()) return@rememberLauncherForActivityResult
        val message = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
        } else ""
        mashovSmsCode(message)?.let(codes::trySend)
    }

    DisposableEffect(context, account.id) {
        val consentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!listening.get() || intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode == CommonStatusCodes.SUCCESS) {
                    intent.extras?.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)?.let { consent ->
                        runCatching { consentLauncher.launch(consent) }
                    }
                }
            }
        }
        val directReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!listening.get() || intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
                val started = requestedAt.get()
                if (started == 0L || SystemClock.elapsedRealtime() - started > timeoutMs) return
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
                mashovSmsCode(body)?.let(codes::trySend)
            }
        }
        ContextCompat.registerReceiver(
            context,
            consentReceiver,
            IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context,
            directReceiver,
            IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            Manifest.permission.BROADCAST_SMS,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose {
            listening.set(false)
            codes.cancel()
            runCatching { context.unregisterReceiver(consentReceiver) }
            runCatching { context.unregisterReceiver(directReceiver) }
        }
    }

    LaunchedEffect(account.id, account.getUsername(), account.mobile) {
        try {
            mashovSmsLoginMutex.withLock {
                updateStatus("Restoring Mashov session")
                val restored = withContext(Dispatchers.IO) { account.restoreDeviceSession() }
                if (restored) {
                    finish(account, null)
                    return@withLock
                }

                if (!account.hasSmsLoginDetails()) {
                    finish(null, "Enter your Mashov username, school and mobile number")
                    return@withLock
                }

                val hasDirectPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECEIVE_SMS,
                ) == PackageManager.PERMISSION_GRANTED
                val useDirect = if (hasDirectPermission) true else {
                    updateStatus("Preparing SMS verification")
                    permissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    withTimeoutOrNull(15_000L) { permission.await() } == true
                }
                if (!useDirect) {
                    val consentStarted = withTimeoutOrNull(5_000L) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            SmsRetriever.getClient(context).startSmsUserConsent(null)
                                .addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
                                .addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
                        }
                    } == true
                    if (!consentStarted) throw IOException("Android SMS verification is unavailable")
                }

                listening.set(true)
                requestedAt.set(SystemClock.elapsedRealtime())
                updateStatus("Requesting Mashov SMS code")
                withContext(Dispatchers.IO) { account.requestSms() }
                saveAccount(account)
                updateStatus("Waiting for Mashov SMS code")

                val code = withTimeoutOrNull(timeoutMs) { codes.receive() }
                    ?: throw IOException("Mashov SMS verification timed out")
                updateStatus("Verifying Mashov code")
                val verified = withContext(Dispatchers.IO) { account.verifySms(code) }
                if (!verified) throw IOException("Mashov did not accept the SMS code")
                listening.set(false)
                saveAccount(account)
                finish(account, null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MashovCaptchaRequired) {
            listening.set(false)
            finish(null, "Mashov requires CAPTCHA before it can send another SMS. Try again from the provider sheet.")
        } catch (error: Exception) {
            listening.set(false)
            finish(null, error.localizedMessage ?: "Mashov SMS login failed")
        }
    }
}
