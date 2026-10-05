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
    override val supportsSchedule = true
    override val supportsAttendance = false
    private var identity = ""
    var mobile = ""
        private set
    private var displayName = "Inbar"
    private val http = InbarHttp()
    private val courses = mutableListOf<JSONObject>()
    private var verifiedAccount = false
    @Volatile private var scheduleYears = emptyList<Int>()
    @Volatile private var scheduleYear = currentAcademicYear()
    @Volatile private var schedulePeriod = "1"
    @Volatile private var schedulePeriods = linkedMapOf("1" to "Semester A", "2" to "Semester B", "3" to "Summer", "5" to "All semesters")
    private val loginFields = LoginFields()
        .addField("id", Type.Id, getter = { it.getUsername() }, setter = { p, v -> p.setUsername(v.orEmpty()) })
        .addField("mobile", Type.Custom("mobile"), getter = { (it as InbarPlatform).mobile },
            setter = { p, v -> (p as InbarPlatform).mobile = v.orEmpty() })

    constructor(fields: LoginFields) : this() {
        identity = fields.getValueByType(Type.Id).orEmpty().trim()
        mobile = fields.getValue("mobile").orEmpty().trim()
    }

    fun hasSavedLoginDetails() = identity.isNotBlank() && mobile.isNotBlank()
    override val canRestoreSession: Boolean get() = hasSavedLoginDetails() && verifiedAccount

    /** Keep the saved account and courses while starting a fresh SMS challenge. */
    @Synchronized fun forSmsLogin(): InbarPlatform = InbarPlatform(id).also { next ->
        next.identity = identity
        next.mobile = mobile
        next.displayName = displayName
        next.platformDisplayName = platformDisplayName
        next.courses += getCourses()
        next.verifiedAccount = verifiedAccount
        next.scheduleYears = scheduleYears
        next.scheduleYear = scheduleYear
        next.schedulePeriod = schedulePeriod
        next.schedulePeriods = LinkedHashMap(schedulePeriods)
    }

    fun requestSms(identity: String, mobile: String) {
        this.identity = identity
        this.mobile = mobile
        http.requestSms(identity, mobile)
    }
    fun resendSms() = http.resendSms()
    @Synchronized fun verifySms(code: String) {
        val current = try { http.verifySms(code) } catch (e: InbarGradeLayoutChanged) {
            loggedIn = true
            verifiedAccount = true
            scheduleYears = e.years
            throw e
        }
        update(current)
        loggedIn = true
        verifiedAccount = true
        // Discover each available year so older courses are visible immediately.
        for (year in current.years.filter { it != current.year }) update(http.grades(year))
    }
    private fun update(page: InbarGradePage) {
        scheduleYears = page.years
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
        .put("supportsSchedule", true).put("supportsAttendance", false)
        .put("supportsOriginalSchedule", false).put("supportsScheduleSelection", false)
        .put("supportsAcademicScheduleSelection", true).put("numberedLessonPeriods", false)
        .put("supportsSaturdaySchedule", true)
        .put("scheduleKind", "weekly").put("scheduleYear", scheduleYear).put("schedulePeriod", schedulePeriod)
        .put("scheduleYears", JSONArray((scheduleYears.ifEmpty { listOf(scheduleYear) }).sortedDescending()))
        .put("schedulePeriods", JSONArray(schedulePeriods.map { (id, label) -> JSONObject().put("id", id).put("label", label) }))
        .put("loginVariables", JSONArray(listOf("id", "mobile", "smsCode")))
        .put("supportsEndpoints", JSONArray(listOf("grades", "schedule")))
    override fun getAttendanceEvents(period: String) = JSONObject()
    override fun getAttendanceEvents(year: Int, period: String) = JSONObject()
    @Synchronized override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
        require(dayIndex in 0..6) { "Invalid schedule day" }
        if (!loggedIn) return JSONObject().put("error", "login_failed")
        val selection = selectedValue?.split('|', limit = 2)
        val year = selection?.getOrNull(0)?.toIntOrNull() ?: scheduleYear
        val period = selection?.getOrNull(1)?.takeIf { it in schedulePeriods } ?: schedulePeriod
        val page = try { http.schedule(year, period) } catch (_: InbarSessionExpired) {
            loggedIn = false
            return JSONObject().put("error", "login_failed")
        }
        scheduleYears = page.years
        scheduleYear = page.year
        schedulePeriod = page.period
        schedulePeriods = LinkedHashMap(page.periods)
        return JSONObject().apply {
            page.lessons.filter { it.optInt("day") == dayIndex }.forEach { lesson ->
                put(lesson.getString("id"), JSONObject(lesson.toString()).put("platformId", id))
            }
        }
    }
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?) =
        getSchedule(dayIndex, institutionCode, selectedValue)
    override fun getScheduleIndexes() = JSONArray((0..6).toList())
    override fun getMessages(page: Int) = JSONArray()
    override fun getMessageDetails(messageId: String) = JSONObject()
    override suspend fun downloadAttachment(context: Context, attachment: JSONObject) = false
    @Synchronized override fun toJson(): JSONObject = JSONObject().put("class", javaClass.name).put("id", id)
        .put("platformDisplayName", platformDisplayName).put("name", displayName).put("identity", identity)
        .put("mobile", mobile).put("loggedIn", loggedIn).put("courses", JSONArray(courses))
        .put("verifiedAccount", verifiedAccount)
        .put("scheduleYear", scheduleYear).put("schedulePeriod", schedulePeriod).put("scheduleYears", JSONArray(scheduleYears))
        .put("schedulePeriods", JSONObject(schedulePeriods as Map<*, *>))
        .put("encryptedSession", InbarSessionCipher.encrypt(http.cookieJar.toJson().toString()))

    companion object : Platform.Companion {
        private fun currentAcademicYear(): Int = java.time.LocalDate.now().let { it.year + if (it.monthValue >= 9) 1 else 0 }
        @JvmStatic override fun fromJson(obj: JSONObject): Platform = InbarPlatform(obj.optString("id").ifBlank { generateId() }).apply {
            identity = obj.optString("identity")
            mobile = obj.optString("mobile")
            displayName = obj.optString("name", "Inbar")
            platformDisplayName = obj.optString("platformDisplayName", "Inbar (Bar-Ilan)")
            obj.optJSONArray("courses")?.let { source ->
                courses += InbarGrades.combineTeachingGroups((0 until source.length()).map { source.getJSONObject(it) })
            }
            verifiedAccount = obj.optBoolean("verifiedAccount", obj.optBoolean("loggedIn", false) || courses.isNotEmpty())
            scheduleYear = obj.optInt("scheduleYear", currentAcademicYear())
            schedulePeriod = obj.optString("schedulePeriod", "1")
            scheduleYears = obj.optJSONArray("scheduleYears")?.let { years -> (0 until years.length()).map { years.getInt(it) } }
                ?.takeIf { it.isNotEmpty() } ?: (courses.map { it.optInt("year") }.filter { it > 0 } + scheduleYear).distinct()
            obj.optJSONObject("schedulePeriods")?.let { periods ->
                schedulePeriods = LinkedHashMap(periods.keys().asSequence().associateWith { periods.getString(it) })
            }
            loggedIn = runCatching {
                http.cookieJar.restore(JSONArray(InbarSessionCipher.decrypt(obj.getString("encryptedSession"))))
                obj.optBoolean("loggedIn", false)
            }.getOrDefault(false) // Device restore without the key requires sign-in.
        }
    }
}
