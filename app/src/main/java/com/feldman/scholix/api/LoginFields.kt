package com.feldman.scholix.api

sealed class Type() {
    data object Id : Type()
    data object Username : Type()
    data object Password : Type()
    data object Email : Type()
    data object Token : Type()

    data class Custom(val name: String) : Type()

}

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

    private fun tryGetByReflection(platform: Platform, id: String): String? {
        return try {
            val f = platform.javaClass.getDeclaredField(id)
            f.isAccessible = true
            f.get(platform) as? String
        } catch (_: Exception) { null }
    }
}
