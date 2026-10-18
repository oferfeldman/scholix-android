package com.feldman.scholix.api.platforms

import android.content.Context
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.lockerim.LockerimApi
import com.feldman.scholix.lockerim.LockerimApiException
import com.feldman.scholix.lockerim.objects
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LockerimPlatform(
    override val id: String = UUID.randomUUID().toString(),
    private val api: LockerimApi = LockerimApi()
) : Platform {
    override var platformDisplayName = "לוקרים"
    override var editing = false
    override var loggedIn = false
    override val suportsGrades = false
    override val supportsSchedule = false
    override val supportsAttendance = false
    override val canRestoreSession get() = refreshToken.isNotBlank()
    var phone = ""
    var displayName = "לוקרים"
    var token = ""
    var refreshToken = ""
    var subscriptions = JSONArray()

    fun acceptSession(session: JSONObject) {
        token = session.getString("token")
        refreshToken = session.getString("refreshToken")
        loggedIn = token.isNotBlank() && refreshToken.isNotBlank()
    }

    @Synchronized
    fun request(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST"): Any {
        try {
            return api.request(path, token, body, method)
        } catch (error: LockerimApiException) {
            if (error.status != 401 || !refreshCookies()) throw error
            return api.request(path, token, body, method)
        }
    }

    fun refreshLockers(): List<JSONObject> {
        val content = request("Subscription/GetMySubscriptions") as JSONObject
        // Academic subscriptions are separate from shared residential SmartBox subscriptions.
        subscriptions = content.optJSONArray("subscriptions") ?: JSONArray()
        return subscriptions.objects()
    }

    @Synchronized
    override fun refreshCookies(): Boolean {
        if (refreshToken.isBlank()) return false
        try {
            acceptSession(api.request("Account/RefreshToken", body = JSONObject().put("refreshToken", refreshToken)) as JSONObject)
            return true
        } catch (error: LockerimApiException) {
            if (error.status == 400 || error.status == 401 || error.status == 403) loggedIn = false
            throw error
        }
    }

    override fun getName() = displayName
    override fun getUsername() = phone
    override fun getPassword() = ""
    override fun setName(name: String) { displayName = name }
    override fun setUsername(username: String) { phone = username }
    override fun setPassword(password: String) {}
    override fun isLoggedIn() = loggedIn && token.isNotBlank()
    override fun isEditing() = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun getLoginFields() = LoginFields()
    override fun getCourses() = mutableListOf<JSONObject>()
    override fun getSubjectList() = emptyList<String>()
    override fun getGrades(course: String, year: Int?, semester: String?) = JSONArray()
    override fun getAttendanceEvents(year: Int, period: String) = JSONObject()
    override fun getAttendanceEvents(period: String) = JSONObject()
    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) = JSONObject()
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) = JSONObject()
    override fun getScheduleIndexes() = JSONArray()
    override fun getMessages(page: Int) = JSONArray()
    override fun getMessageDetails(messageId: String) = JSONObject()
    override suspend fun downloadAttachment(context: Context, attachment: JSONObject) = false
    override fun getInfo() = JSONObject().put("name", "לוקרים")

    override fun toJson() = JSONObject().put("class", javaClass.name).put("id", id)
        .put("platformDisplayName", platformDisplayName).put("name", displayName).put("phone", phone)
        .put("token", token).put("refreshToken", refreshToken).put("loggedIn", loggedIn)
        .put("subscriptions", subscriptions)

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject) = LockerimPlatform(json.getString("id")).apply {
            platformDisplayName = json.optString("platformDisplayName", "לוקרים")
            displayName = json.optString("name", "לוקרים")
            phone = json.optString("phone")
            token = json.optString("token")
            refreshToken = json.optString("refreshToken")
            loggedIn = json.optBoolean("loggedIn")
            subscriptions = json.optJSONArray("subscriptions") ?: JSONArray()
        }
    }
}
