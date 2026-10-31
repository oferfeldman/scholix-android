package com.feldman.scholix.api.platforms

/** Device Keystore is replaced by the hub's encrypted Store at the IPC boundary. */
internal object InbarSessionCipher {
    fun encrypt(value: String) = value
    fun decrypt(value: String) = value
}
