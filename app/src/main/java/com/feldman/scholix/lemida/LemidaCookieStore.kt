package com.feldman.scholix.lemida

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Retain Moodle session cookies across process restarts, encrypted on this device. */
class LemidaCookieStore(context: Context, fileName: String = "lemida_session.enc") {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(cookie: String) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(cookie.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv.size)
            stream.write(cipher.iv)
            stream.write(ciphertext)
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun load(): String? = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized null
        runCatching {
            val data = file.readFully()
            val ivLength = data[0].toInt()
            require(ivLength in 12..16 && data.size > ivLength + 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, ivLength + 1)))
            }
            String(cipher.doFinal(data.copyOfRange(ivLength + 1, data.size)), Charsets.UTF_8)
        }.getOrNull()
    }
    companion object {
        private const val ALIAS = "scholix_lemida_session"
        private val lock = Any()
    }
}
