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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionDialog
import com.feldman.scholix.R
import androidx.core.content.ContextCompat
import com.feldman.scholix.api.platforms.MashovCaptchaRequired
import com.feldman.scholix.api.platforms.MashovPlatform
import com.feldman.scholix.api.platforms.MashovSmsRateLimited
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
import kotlinx.coroutines.delay
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
    requestSmsPermission: Boolean = true,
) {
    val context = LocalContext.current
    val saveAccount by rememberUpdatedState(onSmsRequested)
    val finish by rememberUpdatedState(onResult)
    val updateStatus by rememberUpdatedState(onStatus)
    val codes = remember(account.id) { Channel<String>(Channel.BUFFERED) }
    val permission = remember(account.id) { CompletableDeferred<Boolean>() }
    val listening = remember(account.id) { AtomicBoolean(false) }
    val requestedAt = remember(account.id) { AtomicLong(0L) }
    var waitingForCode by remember(account.id) { mutableStateOf(false) }
    var manualCode by remember(account.id) { mutableStateOf(false) }
    var code by remember(account.id) { mutableStateOf("") }
    var codeError by remember(account.id) { mutableStateOf<String?>(null) }
    val rejectedCode = stringResource(R.string.mashov_rejected_code)
    val cancelledLogin = stringResource(R.string.sms_login_cancelled)
    val requestingCode = stringResource(R.string.requesting_sms)
    val verifyingCode = stringResource(R.string.verifying_sms)
    val restoringSession = stringResource(R.string.restoring_mashov)
    val waitingCode = stringResource(R.string.waiting_sms)

    LaunchedEffect(waitingForCode) {
        if (waitingForCode) {
            delay(5_000)
            manualCode = true
        }
    }
    if (waitingForCode && manualCode) MotionDialog(
        onDismissRequest = { codes.close(IOException(cancelledLogin)) },
        title = stringResource(R.string.mashov_sms_title),
        text = stringResource(R.string.enter_code_mobile),
        dismissText = stringResource(R.string.cancel),
        onDismiss = { codes.close(IOException(cancelledLogin)) },
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(8) },
            label = { Text(stringResource(R.string.sms_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
        )
        codeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        MotionButton(
            text = stringResource(R.string.connect),
            enabled = code.length in 4..8,
            onClick = { codes.trySend(code); code = "" },
            modifier = Modifier.fillMaxWidth(),
        )
    }

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
                mashovSmsCode(body, messages.firstOrNull()?.displayOriginatingAddress.orEmpty())?.let(codes::trySend)
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
                updateStatus(restoringSession)
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
                val useDirect = if (hasDirectPermission) true else if (requestSmsPermission) {
                    permissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    withTimeoutOrNull(15_000L) { permission.await() } == true
                } else false
                if (!useDirect) {
                    val consentStarted = withTimeoutOrNull(5_000L) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            SmsRetriever.getClient(context).startSmsUserConsent(null)
                                .addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
                                .addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
                        }
                    } == true
                    if (!consentStarted) manualCode = true
                }

                listening.set(true)
                requestedAt.set(SystemClock.elapsedRealtime())
                updateStatus(requestingCode)
                try {
                    withContext(Dispatchers.IO) { account.requestSms() }
                } catch (_: MashovSmsRateLimited) {
                    codeError = context.getString(R.string.mashov_sms_rate_limited)
                    manualCode = true
                }
                saveAccount(account)
                updateStatus(waitingCode)
                waitingForCode = true
                val verified = withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        val candidate = codes.receive()
                        waitingForCode = false
                        updateStatus(verifyingCode)
                        if (withContext(Dispatchers.IO) { account.verifySms(candidate) }) return@withTimeoutOrNull true
                        codeError = rejectedCode
                        manualCode = true
                        waitingForCode = true
                        updateStatus(waitingCode)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                } == true
                if (!verified) throw IOException(context.getString(R.string.mashov_sms_timeout))
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
        } finally {
            waitingForCode = false
        }
    }
}
