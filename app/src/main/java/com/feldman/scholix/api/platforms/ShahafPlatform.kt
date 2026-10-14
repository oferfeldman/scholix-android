package com.feldman.scholix.api.platforms

import android.content.Context
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.Type
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ShahafPlatform() : Platform {
    override var platformDisplayName = "Shahaf"
    override var editing = false
    override var loggedIn = false
    override var id = UUID.randomUUID().toString()
        private set
    override val suportsGrades = false
    override val supportsSchedule = true
    override val supportsAttendance = false
    var loginMethod = "moe"
    var deviceId = UUID.randomUUID().toString()
        private set
    private var username = ""
    private var password = ""
    // MOE SMS sign-in stores no password: the national ID goes in username and the
    // registered mobile here, so an expired session only asks for a fresh SMS code.
    private var moePhone = ""
    private var authResponse = JSONObject()
    private val api = ShahafApi()
    var selectedSchool = JSONObject()
        private set
    var selectedClassId = "0"
        private set
    private var viewParameters = JSONObject()

    override fun getName() = platformDisplayName
    override fun getUsername() = username
    override fun getPassword() = password
    override fun setName(name: String) { platformDisplayName = name }
    override fun setUsername(username: String) { this.username = username }
    override fun setPassword(password: String) { this.password = password }
    fun getMoePhone() = moePhone
    fun getStudentName() = authResponse.optString("Name")
    fun getStudentClassId() = authResponse.optJSONObject("Student")?.optString("ClassMid").orEmpty()
    fun getStudentSchoolSemel() = authResponse.optJSONObject("Student")?.optString("Semel").orEmpty()
    fun getStudentGrade() = authResponse.optJSONObject("Student")?.optInt("Grade", -1) ?: -1
    fun getStudentClassNumber() = authResponse.optJSONObject("Student")?.optInt("Class", -1) ?: -1
    fun getStudentClassLabel(): String {
        val student = authResponse.optJSONObject("Student") ?: return ""
        val direct = student.opt("Class")?.toString().orEmpty().trim()
        if (direct.isNotBlank() && direct != "0" && direct != "[]" && direct != "null") return direct
        val grade = student.opt("Grade")?.toString().orEmpty().trim()
        return grade.takeIf { it.isNotBlank() && it != "0" && it != "[]" && it != "null" }.orEmpty()
    }
    fun setMoePhone(phone: String) { moePhone = phone }
    /** Saved MOE SMS details: national ID in username plus the registered mobile. */
    fun hasSavedMoeDetails() = loginMethod == "moe" && username.isNotBlank() && moePhone.isNotBlank()
    override fun isLoggedIn() = loggedIn && authResponse.optString("Token").isNotBlank()
    override val canRestoreSession: Boolean get() = loginMethod == "moe" && password.isBlank() && username.isNotBlank()
    override fun isEditing() = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun getLoginFields() = LoginFields()
        .addField("username", Type.Username, getter = { it.getUsername() }, setter = { p, v -> p.setUsername(v.orEmpty()) })
        .addField("password", Type.Password, getter = { it.getPassword() }, setter = { p, v -> p.setPassword(v.orEmpty()) })

    private fun request(path: String, parameters: Map<String, String> = emptyMap()): Any {
        if (!isLoggedIn()) throw ShahafException(true, "Sign in to Shahaf again")
        return try { api.get(path, parameters + ("token" to authResponse.getString("Token"))) }
        catch (e: ShahafException) {
            if (e.authFailure) loggedIn = false
            throw e
        }
    }

    fun getSchools(): JSONArray = request("school/student/") as JSONArray
    fun getClasses(): JSONArray = (request(
        "student/${selectedSchool.getString("semel")}/${selectedSchool.getString("code")}/classes/"
    ) as JSONObject).getJSONArray("Classes")
    fun selectSchool(school: JSONObject) {
        selectedSchool = school
        val student = authResponse.optJSONObject("Student")
        selectedClassId = if (student?.optString("Semel") == school.optString("semel"))
            student.optString("ClassMid", "0") else "0"
    }
    fun selectClass(classId: String) { selectedClassId = classId }
    fun getViewParameters(): Map<String, String> = buildMap {
        for (key in viewParameters.keys()) {
            viewParameters.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
        }
    }
    fun setViewParameter(id: String, value: String?) {
        if (id.isBlank()) return
        if (value.isNullOrBlank()) viewParameters.remove(id) else viewParameters.put(id, value)
    }
    fun getViewParameter(id: String): String = viewParameters.optString(id)
    fun getAppData(parameters: Map<String, String> = emptyMap()): JSONObject {
        return getAppDataForClassId(selectedClassId, getViewParameters() + parameters)
    }
    fun getAppDataForClassId(classId: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        val semel = selectedSchool.getString("semel")
        val code = selectedSchool.getString("code")
        return request("view/$semel/$code/appdata/", parameters + ("clsid" to classId)) as JSONObject
    }
    fun getAppParametersForClassId(classId: String): Any {
        val semel = selectedSchool.getString("semel")
        val code = selectedSchool.getString("code")
        return request("view/$semel/$code/appprms/", mapOf("clsid" to classId))
    }
    override fun refreshCookies(): Boolean {
        if (!isLoggedIn()) return false
        getSchools()
        return true
    }

    // Published rows are rendered directly on the Schedule page; they do not expose
    // the subject/teacher/period fields required by Scholix's normalized schedule.
    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) =
        JSONObject().put("error", "shahaf_published_views")
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) =
        getSchedule(dayIndex, institutionCode, selectedValue)
    override fun getScheduleIndexes() = JSONArray()
    override fun getGrades(course: String, year: Int?, semester: String?) = JSONArray()
    override fun getAttendanceEvents(year: Int, period: String) = JSONObject()
    override fun getAttendanceEvents(period: String) = JSONObject()
    override fun getSubjectList() = emptyList<String>()
    override fun getCourses() = mutableListOf<JSONObject>()
    override fun getMessages(page: Int) = JSONArray()
    override fun getMessageDetails(messageId: String) = JSONObject()
    override suspend fun downloadAttachment(context: Context, attachment: JSONObject) = false
    override fun getInfo() = JSONObject().put("supportsOriginalSchedule", false)
        .put("supportsScheduleSelection", false).put("studentName", authResponse.optString("Name"))

    override fun toJson(): JSONObject = JSONObject()
        .put("class", javaClass.name).put("id", id).put("name", platformDisplayName)
        .put("username", username).put("password", password).put("moePhone", moePhone).put("loginMethod", loginMethod)
        .put("deviceId", deviceId).put("authResponse", authResponse).put("loggedIn", loggedIn)
        .put("selectedSchool", selectedSchool).put("selectedClassId", selectedClassId)
        .put("viewParameters", viewParameters)

    fun adoptAuthResponse(response: JSONObject) {
        if (response.optString("Token").isBlank()) throw ShahafException(true, "Shahaf did not return a session token")
        authResponse = response
        loggedIn = true
        getSchools()
    }

    companion object : Platform.Companion {
        @JvmStatic override fun fromJson(obj: JSONObject) = ShahafPlatform().apply {
            id = obj.getString("id")
            platformDisplayName = obj.optString("name", "Shahaf")
            username = obj.optString("username")
            password = obj.optString("password")
            moePhone = obj.optString("moePhone")
            loginMethod = obj.optString("loginMethod", "moe")
            deviceId = obj.optString("deviceId", deviceId)
            authResponse = obj.optJSONObject("authResponse") ?: JSONObject()
            loggedIn = obj.optBoolean("loggedIn")
            selectedSchool = obj.optJSONObject("selectedSchool") ?: JSONObject()
            selectedClassId = obj.optString("selectedClassId", "0")
            viewParameters = obj.optJSONObject("viewParameters") ?: JSONObject()
        }
        fun login(username: String, password: String) = ShahafPlatform().apply {
            this.username = username
            this.password = password
            loginMethod = "password"
            adoptAuthResponse(ShahafApi().get("user/authstudent/", mapOf("usr" to username, "pwd" to password)) as JSONObject)
        }
    }
}
