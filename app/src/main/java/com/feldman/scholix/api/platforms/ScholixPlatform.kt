package com.feldman.scholix.api.platforms

import android.content.Context
import android.util.Log
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.Type
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class ScholixPlatform() : Platform {

    override var platformDisplayName = "Scholix"
    override var editing = false
    override var loggedIn = false
    override var id: String = generateId()

    override val suportsGrades = true
    override val supportsSchedule = false
    override val supportsAttendance = false

    private val client: OkHttpClient = OkHttpClient()

    private var email: String = ""
    private var password: String = ""
    private var token: String? = null // Firebase ID token from backend

    private val baseUrl = "https://scholix-api-826282782447.europe-west1.run.app"

    // ---------------------------------------------------------------
    // Login implementation (Scholix API instead of Firebase direct)
    // ---------------------------------------------------------------
    init {
        if (id.isBlank()) {
            id = generateId()
        }
    }
    constructor(fields: LoginFields) : this() {
        setUsername(fields.getValueByType(Type.Email) ?: "")
        setPassword(fields.getValueByType(Type.Password) ?: "")

        val ok = refreshCookies()
        loggedIn = ok
        if (ok) registerFcmToken()
    }
    @Throws(IOException::class, JSONException::class)
    private fun apiLogin(): Boolean {
        val payload = JSONObject()
            .put("email", email)
            .put("password", password)

        val body = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            payload.toString()
        )

        val req = Request.Builder()
            .url("$baseUrl/login")
            .post(body)
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return false

            val json = JSONObject(response.body.string())
            token = json.getString("idToken")
            loggedIn = true
            registerFcmToken()
            return true
        }
    }

    // ----------------------------------------------------------
    // FCM TOKEN REGISTER FUNCTION
    // ----------------------------------------------------------
    private fun registerFcmToken() {
        if (!loggedIn || token == null) return

        FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
            Log.d("ScholixPlatform", "FCM Token: $fcmToken — sending to server")

            val json = JSONObject().put("fcm_token", fcmToken)
            val body = RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())

            val req = Request.Builder()
                .url("$baseUrl/me/fcm")
                .post(body)
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("ScholixPlatform", "FCM update failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d("ScholixPlatform", "FCM token saved successfully")
                    } else {
                        Log.e("ScholixPlatform", "FCM token save failed: ${response.code}")
                    }
                }
            })
        }
    }

    override fun getUsername(): String = email
    override fun getPassword(): String = password
    override fun getName(): String = platformDisplayName
    override fun setUsername(username: String) { email = username }
    override fun setPassword(password: String) { this.password = password }
    override fun setName(name: String) { this.platformDisplayName = name }

    override fun getLoginFields(): LoginFields {
        return LoginFields()
            .addField(
                id = "email",
                type = Type.Email,
                setter = { platform, value ->
                    (platform as ScholixPlatform).setUsername(value ?: "")
                },
                getter = { p -> (p as ScholixPlatform).getUsername() }
            )
            .addField(
                id = "password",
                type = Type.Password,
                setter = { platform, value ->
                    (platform as ScholixPlatform).setPassword(value ?: "")
                },
                getter = { p -> (p as ScholixPlatform).getPassword() }
            )
    }


    override fun isLoggedIn(): Boolean = loggedIn

    override fun refreshCookies(): Boolean {
        return try { apiLogin() } catch (_:Exception){ false }
    }

    // ---------------------------------------------------------------
    // Grades
    // ---------------------------------------------------------------

    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        if (!loggedIn) apiLogin()

        fun request(): Response {
            val req = Request.Builder()
                .url("$baseUrl/me/grades")
                .addHeader("Authorization", "Bearer $token")
                .build()
            return client.newCall(req).execute()
        }

        var res = request()

        // If unauthorized, refresh login automatically and retry once
        if (res.code == 401 || res.code == 403) {
            Log.w("ScholixPlatform", "Token invalid, retrying login...")
            if (apiLogin()) {
                res = request()
            } else {
                throw IOException("Login refresh failed")
            }
        }

        if (!res.isSuccessful)
            throw IOException("Failed to fetch grades: ${res.body?.string()}")

        val arr = JSONArray(res.body.string())
        val converted = JSONArray()

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)

            converted.put(
                JSONObject()
                    .put("subject", item.optString("subject_name"))
                    .put("name", item.optString("assignment_name"))
                    .put("grade", item.optString("grade"))
                    .put("date", item.optString("created_at"))
            )
        }

        return converted
    }


    // ---------------------------------------------------------------
    // Placeholder I/O until backend endpoints exist
    // ---------------------------------------------------------------

    override fun getSubjectList(): List<String> = listOf("General")
    override fun getCourses(): MutableList<JSONObject> {
        val list = mutableListOf<JSONObject>()

        list.add(
            JSONObject()
                .put("name", "Scholix")         // title in the UI
                .put("index", 0)                // required
                .put("semester", "A")           // unused but expected
                .put("semesterPicker", false)   // hide semester UI
                .put("year", 2025)              // irrelevant but required
        )

        return list
    }

    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject =
        JSONObject().put("error", "Not implemented")

    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject =
        JSONObject().put("error", "Not implemented")

    override fun getAttendanceEvents(year: Int, period: String): JSONObject =
        JSONObject().put("error", "Not implemented")

    override fun getAttendanceEvents(period: String): JSONObject =
        JSONObject().put("error", "Not implemented")

    override fun getScheduleIndexes(): JSONArray = JSONArray().put(0)

    override fun getMessages(page: Int): JSONArray = JSONArray()
    override fun getMessageDetails(messageId: String): JSONObject = JSONObject()

    override suspend fun downloadAttachment(context: Context, attachment: JSONObject) = false

    override fun toJson(): JSONObject = JSONObject()
        .put("class", ScholixPlatform::class.java.name)
        .put("id", id)
        .put("email", email)
        .put("password", password)
        .put("token", token)
        .put("platformDisplayName", platformDisplayName)
        .put("loggedIn", loggedIn)

    override fun isEditing() = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun getInfo() = JSONObject()
        .put("supportsGrades", true)
        .put("supportsSchedule", false)
        .put("supportsAttendance", false)

    companion object : Platform.Companion {

        @JvmStatic
        override fun fromJson(obj: JSONObject): ScholixPlatform {
            val p = ScholixPlatform()
            p.id = obj.optString("id", generateId())
            p.setUsername(obj.optString("email", ""))
            p.setPassword(obj.optString("password", ""))
            p.token = obj.optString("token", null)

            p.platformDisplayName = obj.optString("platformDisplayName", "Scholix")
            p.loggedIn = obj.optBoolean("loggedIn", false)

            if (!p.loggedIn && p.getUsername().isNotEmpty() && p.getPassword().isNotEmpty()) {
                p.refreshCookies()
            }

            return p
        }
    }

}
