package com.feldman.scholix.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.feldman.scholix.api.platforms.*
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.resume

/** Owns SMS reception and the HTTP challenge independently of the page awaiting sign-in. */
internal class InbarSmsLoginSession {
    val consentIntent = MutableStateFlow<Intent?>(null)
    private val codes = Channel<String>(Channel.BUFFERED)
    lateinit var result: Deferred<InbarPlatform>
    var retainForCooldown = false
        private set
    fun acceptConsent(message: String?) {
        val code = message?.let(::inbarSmsCode)
        if (code != null) codes.trySend(code) else failConsent()
    }
    fun failConsent() {
        codes.close(IOException("SMS verification was declined or unavailable. Please try signing in again."))
    }
    suspend fun signIn(context: Context, account: InbarPlatform, useDirect: Boolean,
        saveAccount: suspend (InbarPlatform) -> Unit, reuseSession: suspend () -> InbarPlatform?,
        timeoutMs: Long): InbarPlatform = inbarSmsLoginMutex.withLock {
        reuseSession()?.let { return@withLock it }
        val appContext = context.applicationContext
        var requestedAt = 0L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (requestedAt == 0L || SystemClock.elapsedRealtime() - requestedAt > timeoutMs) return
                if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION && useDirect) {
                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    val code = inbarAutomaticSmsCode(messages.joinToString("") { it.messageBody.orEmpty() },
                        messages.firstOrNull()?.displayOriginatingAddress.orEmpty())
                    Log.d("InbarLogin", "SMS broadcast received; eligible=${code != null}")
                    if (code != null) codes.trySend(code)
                } else if (intent.action == SmsRetriever.SMS_RETRIEVED_ACTION && !useDirect) {
                    val status = intent.extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                    when (status.statusCode) {
                        CommonStatusCodes.SUCCESS -> {
                            consentIntent.value = intent.extras?.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                            if (consentIntent.value == null) failConsent()
                        }
                        CommonStatusCodes.TIMEOUT -> codes.close(IOException("SMS verification timed out. Please try signing in again."))
                    }
                }
            }
        }
        val action = if (useDirect) Telephony.Sms.Intents.SMS_RECEIVED_ACTION else SmsRetriever.SMS_RETRIEVED_ACTION
        val senderPermission = if (useDirect) Manifest.permission.BROADCAST_SMS else SmsRetriever.SEND_PERMISSION
        ContextCompat.registerReceiver(appContext, receiver, IntentFilter(action), senderPermission,
            null, ContextCompat.RECEIVER_EXPORTED)
        try {
            if (!useDirect) {
                val started = withTimeoutOrNull(5_000) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        SmsRetriever.getClient(appContext).startSmsUserConsent(null)
                            .addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
                            .addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
                    }
                } == true
                if (!started) throw IOException("SMS verification is unavailable. Please allow SMS access and try again.")
            }
            withContext(Dispatchers.IO) { reserveInbarSmsRequest(context, account.getUsername(), account.mobile) }
            retainForCooldown = true
            requestedAt = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { account.requestSms(account.getUsername(), account.mobile) }
            Log.d("InbarLogin", "SMS request completed in ${SystemClock.elapsedRealtime() - requestedAt} ms")
            saveAccount(account)
            try { verifyInbarSmsCandidates(codes,
                timeoutMs = (timeoutMs - (SystemClock.elapsedRealtime() - requestedAt)).coerceAtLeast(1)) { code ->
                Log.d("InbarLogin", "SMS candidate received after ${SystemClock.elapsedRealtime() - requestedAt} ms")
                withContext(Dispatchers.IO) { account.verifySms(code) }
            } } catch (e: InbarGradeLayoutChanged) {
                // A reader error must not discard successful authentication and send another SMS.
                if (account.isLoggedIn()) saveAccount(account)
                throw e
            }
            saveAccount(account) // Save verified cookies even when the requesting page has closed.
            Log.d("InbarLogin", "Sign-in verified and saved")
            account
        } finally {
            appContext.unregisterReceiver(receiver)
            consentIntent.value = null
            codes.cancel()
        }
    }
}

internal object InbarSmsLoginSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val attempts = InbarSmsAttemptRegistry<InbarSmsLoginSession>(
        completed = { it.result.isCompleted }, retainCompleted = { it.retainForCooldown })
    private fun key(account: InbarPlatform) = account.getUsername().trim() + ":" + account.mobile.filter(Char::isDigit)
    fun find(account: InbarPlatform) = attempts.find(key(account))
    fun startOrJoin(context: Context, account: InbarPlatform, useDirect: Boolean,
        saveAccount: suspend (InbarPlatform) -> Unit, reuseSession: suspend () -> InbarPlatform?,
        timeoutMs: Long): InbarSmsLoginSession {
        val session = attempts.getOrCreate(key(account)) {
            InbarSmsLoginSession().also { session ->
                session.result = scope.async(start = CoroutineStart.LAZY) {
                    session.signIn(context, account, useDirect, saveAccount, reuseSession, timeoutMs)
                }
            }
        }
        session.result.start()
        return session
    }
}
