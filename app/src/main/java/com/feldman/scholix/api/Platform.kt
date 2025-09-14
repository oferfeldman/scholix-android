package com.feldman.scholix.api

import com.feldman.scholix.api.UnsafeOkHttpClient
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

    fun getName(): String
    fun getUsername(): String
    fun getPassword(): String


    @Throws(JSONException::class, IOException::class)
    fun getGrades(): JSONArray

    @Throws(JSONException::class, IOException::class)
    fun getGrades(course: String): JSONArray

    //semester can be a / b / c
    @Throws(JSONException::class, IOException::class)
    fun getGrades(course: String, semester: String): JSONArray

    // period can be "a", "b" or "ab"
    @Throws(JSONException::class, IOException::class)
    fun getDisciplineEvents(year: Int,period: String = "b"): JSONObject
    @Throws(JSONException::class, IOException::class)
    fun getDisciplineEvents(period: String = "b"): JSONObject

    //semester can be a / b / c
    @Throws(JSONException::class, IOException::class)
    fun getGrades(course: String, year: Int, semester: String): JSONArray


    @Throws(JSONException::class, IOException::class)
    fun getSchedule(dayIndex: Int): JSONObject

    @Throws(JSONException::class, IOException::class)
    fun getOriginalSchedule(dayIndex: Int): JSONObject

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

    companion object {
        val client: OkHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient()

        @Throws(JSONException::class, IOException::class)
        fun fromJson(obj: JSONObject): Platform? {
            return null
        }
    }
}
