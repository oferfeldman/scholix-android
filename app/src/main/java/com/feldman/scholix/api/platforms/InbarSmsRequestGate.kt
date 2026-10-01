package com.feldman.scholix.api.platforms

import android.content.Context
import java.io.IOException
import java.security.MessageDigest

internal fun checkInbarSmsRequestInterval(previous: Long, now: Long) {
    if (previous > 0 && now >= previous && now - previous < 90_000) {
        val seconds = (90_000 - (now - previous) + 999) / 1000
        throw IOException("An SMS sign-in was recently started. Please wait $seconds seconds before trying again.")
    }
}

/** Persist the active request window across screen changes and app restarts, without storing account details. */
internal fun reserveInbarSmsRequest(context: Context, identity: String, mobile: String) {
    val key = MessageDigest.getInstance("SHA-256")
        .digest((identity.trim() + ":" + mobile.filter(Char::isDigit)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val prefs = context.getSharedPreferences("inbar_sms_requests", Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()
    checkInbarSmsRequestInterval(prefs.getLong(key, 0), now)
    if (!prefs.edit().putLong(key, now).commit()) throw IOException("Unable to save the SMS request state")
}
