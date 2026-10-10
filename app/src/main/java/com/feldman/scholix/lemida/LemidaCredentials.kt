package com.feldman.scholix.lemida

import android.content.Context
import org.json.JSONObject

/** Never include authentication details in logs or generated data-class strings. */
internal class LemidaCredentials(val email: String, val password: String) {
    init {
        require(email == email.trim() && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email))
        require(password.isNotEmpty())
    }
    override fun toString() = "LemidaCredentials(redacted)"
}

/** Uses Android Keystore encryption and a device-only file excluded from backups. */
internal class LemidaCredentialStore(context: Context, fileName: String = "lemida_credentials.enc") {
    private val encrypted = LemidaCookieStore(context, fileName)
    fun load(): LemidaCredentials? = runCatching {
        val json = JSONObject(encrypted.load() ?: return null)
        LemidaCredentials(json.getString("email"), json.getString("password"))
    }.getOrNull()
    fun save(credentials: LemidaCredentials) {
        encrypted.save(JSONObject().put("email", credentials.email).put("password", credentials.password).toString())
    }
}
