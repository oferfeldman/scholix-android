package com.feldman.scholix.lockerim

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** PEM protocol recovered from LockerimRB's createUnlockCommand/buildEncryptKey. */
internal object LockerimProtocol {
    fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.matches(Regex("[0-9a-fA-F]+"))) { "Invalid Lockerim lock data." }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun key(skda: String): SecretKeySpec {
        require(skda.matches(Regex("[0-9a-fA-F]{6}"))) { "Missing Lockerim lock credentials." }
        val key = hexBytes("ABC532744BBF456532833D368682D560")
        val offset = skda.takeLast(2).toInt(16) % 12
        hexBytes(skda).copyInto(key, offset)
        return SecretKeySpec(key, "AES")
    }

    fun command(skda: String, userId: Long, counter: Int, unlock: Boolean): ByteArray {
        require(userId in 0..0xffffffffL && counter in 1..65535) { "Invalid Lockerim command counter." }
        val message = "%04x%08x%s%s000000000000".format(counter, userId, if (unlock) "01" else "00", skda)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(skda))
        return byteArrayOf('['.code.toByte()) + cipher.doFinal(hexBytes(message)) + byteArrayOf(']'.code.toByte())
    }

    fun decrypt(frame: ByteArray, skda: String): String {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(skda))
        return hex(cipher.doFinal(frame.copyOfRange(1, frame.size - 1)))
    }

    fun enrollment(ec: String) = byteArrayOf('<'.code.toByte()) + hexBytes(ec) + byteArrayOf('>'.code.toByte())

    fun error(response: ByteArray): String? {
        val code = response.toString(Charsets.US_ASCII)
        return when {
            code.startsWith("<PIC") -> "Locker counter synchronized. Please try again."
            code == "<PIS>" -> "Your locker rental has not started yet."
            code == "<PES>" -> "Your locker rental has ended."
            code == "<PLF>" -> "Push the lock into its latch, then try locking again."
            code == "<PCB>" -> "The locker battery needs replacing."
            code in listOf("<PIU>", "<PIP>") -> "Your permission to use this locker is unavailable or has been used up."
            code in listOf("<PIL>", "<PUF>", "<PUA>", "<PDF>", "<ECR>") -> "The locker rejected the command ($code). Try again."
            else -> null
        }
    }
}
