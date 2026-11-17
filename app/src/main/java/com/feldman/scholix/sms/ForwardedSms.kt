package com.feldman.scholix.sms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A verification text that arrived on the user's phone and was forwarded here by Seamless
 * ("Send codes to my devices"). Seamless puts only the code in the clipboard text and the
 * message's sender and body in the clip's description, so sign-in flows can apply the same
 * provider checks they use for an SMS received on this device. Nothing is stored.
 */
internal data class ForwardedSms(val body: String, val sender: String, val code: String, val receivedAt: Long)

internal object ForwardedSmsClip {
    private const val LABEL = "Seamless code"
    private const val EXTRA = "com.seamless.ONE_TIME_CODE"
    private const val EXTRA_SENDER = "com.seamless.ONE_TIME_CODE_SENDER"
    private const val EXTRA_MESSAGE = "com.seamless.ONE_TIME_CODE_MESSAGE"

    fun read(clip: ClipData?): ForwardedSms? {
        val description = clip?.description ?: return null
        val extras = description.extras ?: return null
        if (description.label?.toString() != LABEL || !extras.getBoolean(EXTRA, false) || clip.itemCount != 1) return null
        val code = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
        val body = extras.getString(EXTRA_MESSAGE).orEmpty()
        if (code.isEmpty() || body.isEmpty()) return null
        // The clipboard timestamp uses the same clock as SystemClock.elapsedRealtime().
        val at = if (Build.VERSION.SDK_INT >= 26) description.timestamp else SystemClock.elapsedRealtime()
        return from(body, extras.getString(EXTRA_SENDER).orEmpty(), code, at)
    }

    /** The forwarded code must be one the message itself contains. */
    fun from(body: String, sender: String, code: String, receivedAt: Long): ForwardedSms? =
        if (code.length in 4..10 && body.replace(Regex("[ -]"), "").contains(code)) ForwardedSms(body, sender, code, receivedAt) else null
}

/**
 * Watches for forwarded texts while a sign-in is waiting for a code. Android only lets the
 * focused app read the clipboard, so this also checks when the screen resumes.
 */
internal class ForwardedSmsWatcher(context: Context, private val onSms: (ForwardedSms) -> Unit) :
    ClipboardManager.OnPrimaryClipChangedListener {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    @Volatile private var since = Long.MAX_VALUE
    @Volatile private var lastDelivered = 0L

    /** Accept texts forwarded at or after [requestedAt] (SystemClock.elapsedRealtime). */
    fun start(requestedAt: Long = SystemClock.elapsedRealtime()) {
        since = requestedAt
        runCatching { clipboard?.addPrimaryClipChangedListener(this) }
        check()
    }

    fun stop() {
        since = Long.MAX_VALUE
        runCatching { clipboard?.removePrimaryClipChangedListener(this) }
    }

    override fun onPrimaryClipChanged() = check()

    fun check() {
        val sms = ForwardedSmsClip.read(runCatching { clipboard?.primaryClip }.getOrNull()) ?: return
        if (!fresh(sms.receivedAt, since, lastDelivered)) return
        lastDelivered = sms.receivedAt
        onSms(sms)
    }

    companion object {
        /** Forwarded after the request and not already delivered. */
        fun fresh(receivedAt: Long, since: Long, lastDelivered: Long) = receivedAt >= since && receivedAt != lastDelivered
    }
}

/** Compose form: watches while [requestedAt] is set (not 0); [onSms] receives each forwarded text once. */
@Composable
internal fun ForwardedSmsEffect(requestedAt: Long, onSms: (ForwardedSms) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val deliver by rememberUpdatedState(onSms)
    DisposableEffect(context, lifecycle, requestedAt) {
        if (requestedAt == 0L) return@DisposableEffect onDispose { }
        val watcher = ForwardedSmsWatcher(context) { deliver(it) }
        watcher.start(requestedAt)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) watcher.check() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            watcher.stop()
        }
    }
}
