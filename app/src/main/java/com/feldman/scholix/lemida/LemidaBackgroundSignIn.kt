package com.feldman.scholix.lemida

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Telephony
import android.webkit.WebView
import androidx.core.content.ContextCompat

/** An owned, invisible WebView can recover a remembered account without an Activity. */
internal class LemidaBackgroundSignIn(
    private val context: Context,
    private val view: WebView,
    private val account: String,
    private val credentials: LemidaCredentials?,
    private val alive: () -> Boolean,
    private val manual: () -> Unit,
    private val reserveSms: () -> Boolean = { LemidaSignInStore(context).reserveSms() },
    private val ready: () -> Boolean = { true },
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val mfa = LemidaMfaState()
    private var closed = false
    private fun active() = !closed && alive()
    private val mfaPoll = LemidaMfaPoll(mfa, { SystemClock.elapsedRealtime() }, { active() && ready() }, { view.url },
        { active() }, { script, callback -> view.evaluateJavascript(script, callback) },
        { callback -> handler.postDelayed({ callback() }, 5_000) }, {},
        requestSms = { reserveSms().also { if (!it) manual() } })
    private val credentialPoll = LemidaCredentialPoll(
        { credentials?.let { it.email to it.password } }, { active() && ready() }, { view.url },
        { script, callback -> view.evaluateJavascript(script, callback) },
        { callback -> handler.postDelayed({ callback() }, 5_000) }, manual,
        {}, { mfaPoll.poll() }, preferredAccount = { account }, requiresInteraction = manual)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!active() || intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            acceptSms(messages.joinToString("") { it.messageBody.orEmpty() },
                messages.firstOrNull()?.displayOriginatingAddress.orEmpty())
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!active()) return
            handler.postDelayed(this, 750)
            if (ready()) credentialPoll.poll()
        }
    }
    init {
        check(available(context))
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            Manifest.permission.BROADCAST_SMS, null, ContextCompat.RECEIVER_EXPORTED)
        handler.postDelayed(poll, 750)
    }
    fun documentChanged() { credentialPoll.invalidate(); mfaPoll.invalidate() }
    internal fun acceptSms(body: String, sender: String): Boolean = active() && mfa.acceptCode(body, sender, SystemClock.elapsedRealtime())
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        context.unregisterReceiver(receiver)
        mfa.discardCode()
    }
    companion object {
        fun available(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }
}
