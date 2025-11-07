package com.feldman.scholix.api

import android.content.Context
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

interface Platform {
    var displayName: String?
    var _username: String?
    var _password: String?
    var editing: Boolean
    var loggedIn: Boolean
    val id: String

    val suportsGrades: Boolean
    val supportsSchedule: Boolean
    val supportsAttendance: Boolean

    fun getName(): String
    fun getUsername(): String
    fun getPassword(): String


    //semester can be a / b / c
    @Throws(JSONException::class, IOException::class)
    fun getGrades(
        course: String,
        year: Int? = null,
        semester: String? = null
    ): JSONArray

    // period can be "a", "b" or "ab"
    @Throws(JSONException::class, IOException::class)
    fun getAttendanceEvents(year: Int,period: String = "b"): JSONObject
    @Throws(JSONException::class, IOException::class)
    fun getAttendanceEvents(period: String = "b"): JSONObject

    @Throws(JSONException::class, IOException::class)
    fun getSubjectList(): List<String>

    @Throws(JSONException::class, IOException::class)
    fun getSchedule(dayIndex: Int, institutionCode: Int? = null, selectedValue: String? = null): JSONObject

    @Throws(JSONException::class, IOException::class)
    fun getOriginalSchedule(dayIndex: Int, institutionCode: Int? = null, selectedValue: String? = null): JSONObject

    fun isLoggedIn(): Boolean

    @Throws(IOException::class, JSONException::class)
    fun refreshCookies(): Boolean

    @Throws(JSONException::class)
    fun toJson(): JSONObject

    @Throws(JSONException::class, IOException::class)
    fun getScheduleIndexes(): JSONArray

    fun isEditing(): Boolean
    fun startEditing()
    fun stopEditing()
    fun setName(name: String)
    fun setUsername(username: String)
    fun setPassword(password: String)
    fun getCourses(): MutableList<JSONObject>
    fun getInfo(): JSONObject

    interface Companion {

        fun generateId(): String {
            val chars = ('A'..'Z') + ('0'..'9')
            return (1..8)
                .map { chars.random() }
                .joinToString("")
        }

        @Throws(JSONException::class, IOException::class)
        fun fromJson(obj: JSONObject): Platform? {
            return null
        }

         fun checkCredentials(loginFields: LoginFields): Boolean {
             return true
         }

    }



    fun getLoginFields(): LoginFields

    @Throws(JSONException::class, IOException::class)
    fun getMessages(page: Int = 1): JSONArray

    @Throws(JSONException::class, IOException::class)
    fun getMessageDetails(messageId: String): JSONObject

    suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean

}

fun Platform.applyLoginFields(fields: LoginFields) {
    for (field in fields.getFields()) {
        val value = field.value ?: continue

        // Each LoginField should know how to apply itself
        field.setter?.invoke(this, value)
    }
}
