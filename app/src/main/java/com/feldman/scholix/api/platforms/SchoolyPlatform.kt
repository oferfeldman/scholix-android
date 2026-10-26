package com.feldman.scholix.api.platforms

import android.content.Context
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.Type
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class SchoolyPlatform() : Platform {
    override var platformDisplayName = "Schooly"
    override var editing = false
    override var loggedIn = false
    override var id = UUID.randomUUID().toString()
        private set
    override val suportsGrades = true
    override val supportsSchedule = true
    override val supportsAttendance = false
    override val canRestoreSession get() = identity.isNotBlank()
    private var identity = ""
    private var profile = JSONObject()
    private var students = JSONArray()
    var selectedStudentId = ""
        private set
    internal var api = SchoolyApi()
    private var weekKey = ""
    private var weekFetchedAt = 0L
    private var week = JSONObject()
    private var lessonHours = JSONArray()
    private var periods: JSONArray? = null

    fun identify(userId: String): List<JSONObject> {
        identity = userId.trim()
        require(identity.matches(Regex("\\d{5,9}"))) { "Enter your national ID." }
        val response = api.request("api/auth/identify", body = JSONObject().put("userId", identity))
        val contacts = (response.optJSONArray("contactOptions") ?: response.schoolyData().optJSONArray("contactOptions")).schoolyObjects()
            .filter { it.optString("type") in listOf("email", "phone") }
        if (contacts.isEmpty()) throw IOException("Schooly has no supported contact method for this ID.")
        return contacts
    }

    fun sendCode(contact: JSONObject) {
        api.request("api/auth/send-otp", body = JSONObject().put("userId", identity)
            .put("contactType", contact.getString("type"))
            .put("contactValue", contact.optString("masked").ifBlank { contact.optString("value") }))
    }

    fun verifyCode(code: String, schoolId: String? = null): List<JSONObject> {
        require(code.matches(Regex("\\d{6}"))) { "Enter the six-digit verification code." }
        val response = api.request("api/auth/verify-otp", body = JSONObject().put("userId", identity)
            .put("otpCode", code).put("otp", code).apply { schoolId?.let { put("school_id", it) } })
        val nested = response.optJSONObject("data")
        val envelope = if (nested?.has("data") == true && (nested.has("token") || nested.has("signedToken"))) nested else response
        api.token = envelope.optString("signedToken").ifBlank { envelope.optString("token") }
        val schools = (envelope.optJSONArray("schoolEntities") ?: response.optJSONArray("schoolEntities")).schoolyObjects()
        if (schools.size > 1 && schoolId == null) return schools
        if (schoolId != null) api.request("api/schools/switch-school", body = JSONObject().put("school_id", schoolId))
        updateProfile(api.request("api/users/me").schoolyData())
        loggedIn = true
        return emptyList()
    }

    private fun updateProfile(value: JSONObject) {
        if (value.optString("student_login_id").isBlank()) throw IOException("Schooly did not return an account profile.")
        profile = value
        val user = value.optJSONObject("current_user") ?: value
        val children = user.optJSONArray("childrenPupils") ?: user.optJSONArray("children")
        students = if (children != null && children.length() > 0) JSONArray(children.toString()) else JSONArray().put(value)
        if (getStudents().none { it.optString("student_login_id") == selectedStudentId }) {
            selectedStudentId = getStudents().first().optString("student_login_id")
        }
    }

    fun getStudents(): List<JSONObject> = students.schoolyObjects()
    fun selectStudent(studentId: String) {
        require(getStudents().any { it.optString("student_login_id") == studentId }) { "Choose a student from this account." }
        selectedStudentId = studentId
        weekKey = ""
    }

    private fun request(path: String, params: Map<String, String> = emptyMap()): JSONObject = try {
        api.request(path, params)
    } catch (e: SchoolySessionExpired) {
        loggedIn = false
        throw e
    }

    override fun refreshCookies(): Boolean = try {
        updateProfile(request("api/users/me").schoolyData())
        loggedIn = true
        true
    } catch (_: IOException) { false }

    override fun getCourses(): MutableList<JSONObject> = getStudents().map { student ->
        val studentId = student.optString("student_login_id")
        JSONObject().put("name", "$platformDisplayName · ${studentLabel(student)}")
            .put("courseKey", "schooly:$studentId").put("platformId", id)
            .put("year", LocalDate.now().let { it.year + if (it.monthValue >= 9) 1 else 0 })
            .put("semesterPicker", false)
    }.toMutableList()

    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        val studentId = if (course.startsWith("schooly:")) course.removePrefix("schooly:") else {
            getCourses().firstOrNull { it.optString("name") == course }?.getString("courseKey")?.removePrefix("schooly:") ?: selectedStudentId
        }
        require(getStudents().any { it.optString("student_login_id") == studentId }) { "Choose a student from this account." }
        val academicYear = year ?: LocalDate.now().let { it.year + if (it.monthValue >= 9) 1 else 0 }
        val params = mutableMapOf("student_login_id" to studentId, "year" to academicYear.toString())
        if (periods == null) periods = request("v1/schools/periods").schoolyData().optJSONArray("periods") ?: JSONArray()
        val selectedPeriods = periods.schoolyObjects().filter { it.optInt("academicYear") == academicYear }
        val result = JSONArray()
        val seen = mutableSetOf<String>()
        val periodIds = selectedPeriods.map { it.optString("_id").ifBlank { it.optString("id") } }.filter(String::isNotBlank).ifEmpty { listOf("") }
        periodIds.forEach { period ->
            val query = params.toMutableMap().apply { if (period.isNotBlank()) put("period", period) }
            val grades = schoolyGrades(request("v1/academic/pupil-card", query).schoolyData(), studentId)
            grades.schoolyObjects().forEach { grade ->
                val gradeId = grade.optString("id")
                if (gradeId.isBlank() || seen.add(gradeId)) result.put(grade)
            }
        }
        return result
    }

    @Synchronized private fun schedule(dayIndex: Int, original: Boolean): JSONObject {
        if (!loggedIn) return JSONObject().put("error", "login_failed")
        val now = LocalDate.now()
        var sunday = now.minusDays((now.dayOfWeek.value % 7).toLong())
        if (now.dayOfWeek.value == 6 && dayIndex == 0) sunday = sunday.plusWeeks(1)
        val key = "$selectedStudentId:$sunday"
        try {
            if (weekKey != key || System.currentTimeMillis() - weekFetchedAt >= 60_000) {
                week = request("v1/schedule/weeklyTimeTable", mapOf("ttDate" to sunday.format(DateTimeFormatter.BASIC_ISO_DATE),
                    "pupilId" to selectedStudentId, "includeOverlay" to "true", "overlayEventsMode" to "published"))
                val data = week.optJSONArray("data")?.optJSONObject(0) ?: week.schoolyData()
                val scopeId = data.optJSONObject("scope")?.optString("_id").orEmpty().ifBlank { data.optString("scope_id") }
                lessonHours = if (scopeId.isBlank()) JSONArray() else request("v1/schedule/lesson-hours", mapOf("scope_id" to scopeId))
                    .schoolyData().optJSONArray("lessonHours") ?: JSONArray()
                weekKey = key
                weekFetchedAt = System.currentTimeMillis()
            }
            return schoolySchedule(week, dayIndex, original, lessonHours)
        } catch (_: SchoolySessionExpired) {
            return JSONObject().put("error", "login_failed")
        } catch (_: IOException) {
            return JSONObject().put("error", "server_unreachable")
        }
    }

    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) = schedule(dayIndex, false)
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) = schedule(dayIndex, true)
    override fun getScheduleIndexes() = JSONArray((0..6).toList())
    override fun getInfo() = JSONObject().put("supportsOriginalSchedule", true).put("supportsScheduleSelection", false)
        .put("supportsSaturdaySchedule", true).put("scheduleKind", "weekly").put("supportsMessages", false)
        .put("studentName", getStudents().firstOrNull { it.optString("student_login_id") == selectedStudentId }?.let(::studentLabel).orEmpty())
    override fun getName() = platformDisplayName
    override fun getUsername() = identity
    override fun getPassword() = ""
    override fun setName(name: String) { platformDisplayName = name }
    override fun setUsername(username: String) { identity = username }
    override fun setPassword(password: String) = Unit
    override fun isLoggedIn() = loggedIn
    override fun isEditing() = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun getLoginFields() = LoginFields().addField("id", Type.Id, getter = { it.getUsername() }, setter = { p, v -> p.setUsername(v.orEmpty()) })
    override fun getSubjectList() = emptyList<String>()
    override fun getAttendanceEvents(year: Int, period: String) = JSONObject()
    override fun getAttendanceEvents(period: String) = JSONObject()
    override fun getMessages(page: Int) = JSONArray()
    override fun getMessageDetails(messageId: String) = JSONObject()
    override suspend fun downloadAttachment(context: Context, attachment: JSONObject) = false
    override fun toJson() = JSONObject().put("class", javaClass.name).put("id", id).put("name", platformDisplayName)
        .put("identity", identity).put("loggedIn", loggedIn).put("profile", profile).put("students", students)
        .put("selectedStudentId", selectedStudentId).put("session", api.session())

    companion object : Platform.Companion {
        fun studentLabel(student: JSONObject): String = "${student.optString("student_f_name")} ${student.optString("student_l_name")}".trim()
            .ifBlank { student.optString("student_login_id") }
        @JvmStatic override fun fromJson(obj: JSONObject) = SchoolyPlatform().apply {
            id = obj.getString("id")
            platformDisplayName = obj.optString("name", "Schooly")
            identity = obj.optString("identity")
            loggedIn = obj.optBoolean("loggedIn")
            profile = obj.optJSONObject("profile") ?: JSONObject()
            students = obj.optJSONArray("students") ?: JSONArray()
            selectedStudentId = obj.optString("selectedStudentId")
            obj.optJSONObject("session")?.let(api::restore)
        }
    }
}
