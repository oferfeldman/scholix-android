package com.feldman.scholix.lemida

import android.content.Context

/** Selected Microsoft identity stays on-device, outside backups and homework exports. */
internal class LemidaSignInStore(context: Context, fileName: String = "lemida_account.enc", prefsName: String = "lemida_sign_in") {
    private val account = LemidaCookieStore(context, fileName)
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    fun email(): String? = account.load()?.takeIf { Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(it) }
    fun remember(email: String) {
        require(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email))
        account.save(email)
    }
    fun enabled() = prefs.getBoolean("automatic", true)
    fun setEnabled(enabled: Boolean) { prefs.edit().putBoolean("automatic", enabled).apply() }
    fun canRecover() = enabled() && email() != null
    /** One persistent reservation covers simultaneous foreground/background attempts. */
    fun reserveSms(now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val previous = prefs.getLong("sms_requested", 0L)
        if (previous != 0L && (now < previous || now - previous < 15 * 60_000L)) return@synchronized false
        prefs.edit().putLong("sms_requested", now).commit()
    }
    companion object { private val lock = Any() }
}
