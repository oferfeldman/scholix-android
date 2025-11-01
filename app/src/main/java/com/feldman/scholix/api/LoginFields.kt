package com.feldman.scholix.api

import com.feldman.scholix.api.UnsafeOkHttpClient
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

// ──────────────────────────────────────────────
// Type System
// ──────────────────────────────────────────────
sealed class Type(val key: String) {
    data object Id : Type("id")
    data object Username : Type("username")
    data object Password : Type("password")
    data object Email : Type("email")
    data object Token : Type("token")

    data class Custom(val name: String) : Type(name)

    fun toJson(): String = key

    companion object {
        fun fromKey(key: String): Type = when (key) {
            "id" -> Id
            "username" -> Username
            "password" -> Password
            "email" -> Email
            "token" -> Token
            else -> Custom(key)
        }
    }
}

// ──────────────────────────────────────────────
// LoginField + LoginFields
// ──────────────────────────────────────────────
data class LoginField(
    val id: String,
    val type: Type,
    var value: String? = null,
    val getter: ((Platform) -> String?)? = null,
    val setter: ((Platform, String?) -> Unit)? = null
)

class LoginFields(private val fields: MutableList<LoginField> = mutableListOf()) {

    fun addField(
        id: String,
        type: Type,
        value: String? = null,
        getter: ((Platform) -> String?)? = null,
        setter: ((Platform, String?) -> Unit)? = null
    ): LoginFields {
        fields.add(LoginField(id, type, value, getter, setter))
        return this
    }

    fun getValue(id: String): String? = fields.find { it.id == id }?.value

    fun getValueByType(type: Type): String? = fields.find { it.type == type }?.value

    fun getFields(): List<LoginField> = fields

    fun setValue(id: String, value: String?) {
        fields.find { it.id == id }?.value = value
    }

    // 🔹 Apply to a platform
    fun applyTo(platform: Platform) {
        fields.forEach { field ->
            val value = field.value ?: return@forEach
            // use explicit setter if defined
            field.setter?.invoke(platform, value) ?: run {
                when (field.type) {
                    Type.Username, Type.Id, Type.Email -> platform.setUsername(value)
                    Type.Password, Type.Token -> platform.setPassword(value)
                    is Type.Custom -> trySetByReflection(platform, field.id, value)
                }
            }
        }
    }

    // 🔹 Load from a platform
    fun loadFrom(platform: Platform) {
        fields.forEach { field ->
            val v = field.getter?.invoke(platform) ?: when (field.type) {
                Type.Username, Type.Id, Type.Email -> platform.getUsername()
                Type.Password, Type.Token -> platform.getPassword()
                is Type.Custom -> tryGetByReflection(platform, field.id)
            }
            field.value = v
        }
    }

    private fun trySetByReflection(platform: Platform, id: String, value: String?) {
        try {
            val f = platform.javaClass.getDeclaredField(id)
            f.isAccessible = true
            f.set(platform, value)
        } catch (_: Exception) { }
    }

    private fun tryGetByReflection(platform: Platform, id: String): String? {
        return try {
            val f = platform.javaClass.getDeclaredField(id)
            f.isAccessible = true
            f.get(platform) as? String
        } catch (_: Exception) { null }
    }
}
