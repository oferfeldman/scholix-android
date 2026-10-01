package com.feldman.scholix.api.platforms

import android.content.Context
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.Type
import org.json.JSONArray
import org.json.JSONObject

/** Inbar is separate from the existing Bar-Ilan math/Michlol provider. */
class InbarPlatform(override val id: String = generateId()) : Platform {
    override var platformDisplayName = "Inbar (Bar-Ilan)"
    override var editing = false
    override var loggedIn = false
    override val suportsGrades = true
    override val supportsSchedule = false
    override val supportsAttendance = false
    private var identity = ""
    var mobile = ""
        private set
    private var displayName = "Inbar"
    private val http = InbarHttp()
    private val courses = mutableListOf<JSONObject>()
    private val loginFields = LoginFields()
        .addField("id", Type.Id, getter = { it.getUsername() }, setter = { p, v -> p.setUsername(v.orEmpty()) })
        .addField("mobile", Type.Custom("mobile"), getter = { (it as InbarPlatform).mobile },
            setter = { p, v -> (p as InbarPlatform).mobile = v.orEmpty() })

    constructor(fields: LoginFields) : this() {
        identity = fields.getValueByType(Type.Id).orEmpty().trim()
        mobile = fields.getValue("mobile").orEmpty().trim()
    }

    fun hasSavedLoginDetails() = identity.isNotBlank() && mobile.isNotBlank()
    override val canRestoreSession: Boolean get() = hasSavedLoginDetails() && getCourses().isNotEmpty()

    /** Keep the saved account and courses while starting a fresh SMS challenge. */
    @Synchronized fun forSmsLogin(): InbarPlatform = InbarPlatform(id).also { next ->
        next.identity = identity
        next.mobile = mobile
        next.displayName = displayName
        next.platformDisplayName = platformDisplayName
        next.courses += getCourses()
    }

    fun requestSms(identity: String, mobile: String) {
        this.identity = identity
        this.mobile = mobile
        http.requestSms(identity, mobile)
    }
    fun resendSms() = http.resendSms()
    @Synchronized fun verifySms(code: String) {
        val current = http.verifySms(code)
        update(current)
        loggedIn = true
        // Discover each available year so older courses are visible immediately.
        for (year in current.years.filter { it != current.year }) update(http.grades(year))
    }
    private fun update(page: InbarGradePage) {
        courses.removeAll { it.optInt("year") == page.year }
        courses += page.courses.map { it.put("platformId", id) }
    }
    @Synchronized override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        if (!loggedIn) return JSONArray().put(JSONObject().put("error", "login_failed"))
        // Course keys include their academic year; UI defaults must not override it.
        val requestedYear = course.substringBefore(":", "").toIntOrNull() ?: year
        try {
            if (requestedYear != null) update(http.grades(requestedYear))
            else {
                val current = http.grades()
                update(current)
                for (available in current.years.filter { it != current.year }) update(http.grades(available))
            }
        } catch (_: InbarSessionExpired) {
            loggedIn = false
            return JSONArray().put(JSONObject().put("error", "login_failed"))
        }
        val result = JSONArray()
        for (item in courses) {
            if (course == "all" && requestedYear != null && item.optInt("year") != requestedYear) continue
            if (course != "all" && course != item.optString("courseKey") && course != item.optString("name")) continue
            val grades = item.optJSONArray("grades") ?: continue
            for (i in 0 until grades.length()) result.put(grades.getJSONObject(i))
        }
        return result
    }
    @Synchronized override fun refreshCookies(): Boolean = try {
        update(http.grades())
        loggedIn = true
        true
    } catch (_: InbarSessionExpired) {
        loggedIn = false
        false // Background refresh must never send another SMS.
    }
    override fun getName() = displayName
    override fun getUsername() = identity
    override fun getPassword() = "" // OTPs are never saved or reused as passwords.
    override fun setName(name: String) { displayName = name }
    override fun setUsername(username: String) { identity = username }
    override fun setPassword(password: String) = Unit
    @Synchronized override fun getCourses(): MutableList<JSONObject> = courses.map { JSONObject(it.toString()) }.toMutableList()
    override fun getSubjectList(): List<String> = getCourses().map { it.optString("name") }.distinct().sorted()
    override fun isLoggedIn() = loggedIn
    override fun isEditing() = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun getLoginFields() = loginFields
    override fun getInfo() = JSONObject().put("name", "Inbar").put("supportsGrades", true)
        .put("supportsSchedule", false).put("supportsAttendance", false)
        .put("loginVariables", JSONArray(listOf("id", "mobile", "smsCode")))
        .put("supportsEndpoints", JSONArray(listOf("grades")))
    override fun getAttendanceEvents(period: String) = JSONObject()
    override fun getAttendanceEvents(year: Int, period: String) = JSONObject()
    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) = JSONObject()
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) = JSONObject()
    override fun getScheduleIndexes() = JSONArray()
    override fun getMessages(page: Int) = JSONArray()
    override fun getMessageDetails(messageId: String) = JSONObject()
    override suspend fun downloadAttachment(context: Context, attachment: JSONObject) = false
    @Synchronized override fun toJson(): JSONObject = JSONObject().put("class", javaClass.name).put("id", id)
        .put("platformDisplayName", platformDisplayName).put("name", displayName).put("identity", identity)
        .put("mobile", mobile).put("loggedIn", loggedIn).put("courses", JSONArray(courses))
        .put("encryptedSession", InbarSessionCipher.encrypt(http.cookieJar.toJson().toString()))

    companion object : Platform.Companion {
        @JvmStatic override fun fromJson(obj: JSONObject): Platform = InbarPlatform(obj.optString("id").ifBlank { generateId() }).apply {
            identity = obj.optString("identity")
            mobile = obj.optString("mobile")
            displayName = obj.optString("name", "Inbar")
            platformDisplayName = obj.optString("platformDisplayName", "Inbar (Bar-Ilan)")
            obj.optJSONArray("courses")?.let { source ->
                courses += InbarGrades.combineTeachingGroups((0 until source.length()).map { source.getJSONObject(it) })
            }
            loggedIn = runCatching {
                http.cookieJar.restore(JSONArray(InbarSessionCipher.decrypt(obj.getString("encryptedSession"))))
                obj.optBoolean("loggedIn", false)
            }.getOrDefault(false) // Device restore without the key requires sign-in.
        }
    }
}
