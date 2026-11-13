package com.feldman.scholix.lockerim

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow

data class DialStep(val number: String = "", val clockwise: Boolean = true, val turns: Int = 1) {
    fun toJson() = JSONObject().put("number", number).put("clockwise", clockwise).put("turns", turns)
}

data class LockerCode(
    val id: String = UUID.randomUUID().toString(), val name: String = "", val location: String = "",
    val dial: Boolean = false, val pin: String = "",
    val steps: List<DialStep> = listOf(DialStep(turns = 3), DialStep(clockwise = false, turns = 2), DialStep())
) {
    fun valid() = name.isNotBlank() && if (dial) steps.isNotEmpty() && steps.all {
        it.number.isNotEmpty() && it.number.all { c -> c in '0'..'9' } && it.turns > 0
    } else pin.isNotEmpty() && pin.all { it in '0'..'9' }

    fun toJson() = JSONObject().put("id", id).put("name", name).put("location", location)
        .put("dial", dial).put("pin", if (dial) "" else pin)
        .put("steps", JSONArray(if (dial) steps.map { it.toJson() } else emptyList<JSONObject>()))

    companion object {
        fun fromJson(json: JSONObject) = LockerCode(json.getString("id"), json.getString("name"),
            json.getString("location"), json.getBoolean("dial"), json.getString("pin"),
            json.getJSONArray("steps").let { rows -> (0 until rows.length()).map {
                val step = rows.getJSONObject(it)
                DialStep(step.getString("number"), step.getBoolean("clockwise"), step.getInt("turns"))
            } })
    }
}

/** Locker secrets stay encrypted in app-private, non-backed-up storage. */
internal class LockerCodesStore(context: Context) {
    companion object {
        private val lock = Any()
        val changes = MutableStateFlow(0L)
    }
    private val file = AtomicFile(File(context.noBackupFilesDir, "locker-codes"))
    private fun key(): SecretKey {
        val alias = "scholix.locker.codes.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<LockerCode> = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized emptyList()
        val bytes = Base64.decode(file.openRead().use { it.readBytes() }, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val rows = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        (0 until rows.length()).map { LockerCode.fromJson(rows.getJSONObject(it)) }
    }
    fun save(code: LockerCode) = synchronized(lock) {
        require(code.valid())
        write(load().filterNot { it.id == code.id } + code)
    }
    fun delete(id: String) = synchronized(lock) { write(load().filterNot { it.id == id }) }
    private fun write(codes: List<LockerCode>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = Base64.encode(cipher.iv + cipher.doFinal(JSONArray(codes.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        changes.value++
    }
}
