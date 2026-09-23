package com.feldman.scholix.api.platforms

import android.content.Context
import android.os.Build
import android.util.Log
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.Type
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

data class MashovSchool(
    val semel: String,
    val name: String
)

/** Mashov students/parents provider backed by Mashov's public web API. */
class MashovPlatform() : Platform {
    override var platformDisplayName: String = "Mashov"

    private val loginFields = LoginFields()
        .addField(
            id = "School code",
            type = Type.Custom("schoolCode"),
            getter = { (it as? MashovPlatform)?.schoolCode },
            setter = { platform, value -> (platform as? MashovPlatform)?.setSchoolCode(value.orEmpty()) }
        )
        .addField(
            id = "School year",
            type = Type.Custom("schoolYear"),
            value = currentSchoolYear(),
            getter = { (it as? MashovPlatform)?.schoolYear },
            setter = { platform, value -> (platform as? MashovPlatform)?.setSchoolYear(value.orEmpty()) }
        )
        .addField(
            id = "Username",
            type = Type.Username,
            getter = { it.getUsername() },
            setter = { platform, value -> platform.setUsername(value.orEmpty()) }
        )
        .addField(
            id = "Password",
            type = Type.Password,
            getter = { it.getPassword() },
            setter = { platform, value -> platform.setPassword(value.orEmpty()) }
        )

    /** lesson number -> "08:15 - 09:00", from the school's bell schedule. */
    private val bellTimes = linkedMapOf<Int, String>()
    private var schoolCode: String? = null
    private var schoolYear: String? = currentSchoolYear()
    private var username: String? = null
    private var password: String? = null
    private var studentGuid: String? = null
    private var studentGrade: String? = null
    private var studentClass: String? = null
    private var displayName: String? = null
    private var csrfToken: String? = null
    private var gradesLoaded = false
    private var timetableLoaded = false
    private val courses = ArrayList<JSONObject>()
    private var cachedGrades = JSONArray()
    private var cachedTimetable = JSONArray()

    private val sessionCookies = mutableListOf<Cookie>()
    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(sessionCookies) {
                    cookies.forEach { cookie ->
                        sessionCookies.removeAll {
                            it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                        }
                        sessionCookies += cookie
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(sessionCookies) {
                sessionCookies.removeAll { it.expiresAt < System.currentTimeMillis() }
                sessionCookies.filter { it.matches(url) }
            }
        })
        .build()

    override var editing: Boolean = false
    override var loggedIn: Boolean = false
    override var id: String = generateId()
        private set

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = true
    override val supportsAttendance: Boolean = true

    constructor(loginFields: LoginFields) : this() {
        schoolCode = loginFields.getValue("School code")?.trim()
        schoolYear = loginFields.getValue("School year")?.trim()
        username = loginFields.getValueByType(Type.Username)?.trim()
        password = loginFields.getValueByType(Type.Password)
        loggedIn = authenticate() && refreshGrades()
    }

    private fun authenticate(): Boolean {
        val code = schoolCode?.takeIf { it.isNotBlank() } ?: return false
        val year = schoolYear?.takeIf { it.isNotBlank() } ?: return false
        val user = username?.takeIf { it.isNotBlank() } ?: return false
        val pass = password?.takeIf { it.isNotBlank() } ?: return false

        val payload = JSONObject()
            .put("semel", code)
            .put("year", year)
            .put("username", user)
            .put("password", pass)
            .put("IsBiometric", false)
            .put("appName", MASHOV_APP_ID)
            .put("apiVersion", MASHOV_API_VERSION)
            .put("appVersion", MASHOV_API_VERSION)
            .put("appBuild", MASHOV_API_VERSION)
            .put("deviceUuid", "scholix-$id")
            .put("devicePlatform", "android")
            .put("deviceManufacturer", Build.MANUFACTURER)
            .put("deviceModel", Build.MODEL)
            .put("deviceVersion", Build.VERSION.RELEASE)

        return try {
            val request = Request.Builder()
                .url("$API/login")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", WEB_APP)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Mashov login failed with HTTP ${response.code}")
                    return false
                }

                csrfToken = response.header("X-Csrf-Token")
                val body = JSONObject(response.body.string())
                val accessToken = body.optJSONObject("accessToken") ?: return false
                val children = accessToken.optJSONArray("children") ?: return false
                val child = children.optJSONObject(0) ?: return false
                studentGuid = child.optString("childGuid").takeIf { it.isNotBlank() } ?: return false
                studentGrade = normalizeGradeCode(child.optString("classCode"))
                studentClass = child.optInt("classNum", -1).takeIf { it > 0 }?.toString()
                displayName = child.optString("privateName").takeIf { it.isNotBlank() } ?: user
                loggedIn = true
                true
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Mashov login failed: ${exception.javaClass.simpleName}")
            loggedIn = false
            false
        }
    }

    private fun refreshGrades(): Boolean {
        if (!loggedIn && !authenticate()) return false
        val guid = studentGuid ?: return false

        return try {
            val requestBuilder = Request.Builder()
                .url("$API/students/$guid/grades")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", WEB_APP)
            csrfToken?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("X-Csrf-Token", it)
            }

            client.newCall(requestBuilder.get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Mashov grades failed with HTTP ${response.code}")
                    return false
                }
                updateGrades(JSONArray(response.body.string()))
                true
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Mashov grades failed: ${exception.javaClass.simpleName}")
            false
        }
    }

    private fun updateGrades(source: JSONArray) {
        val grouped = linkedMapOf<String, JSONArray>()
        val mapped = JSONArray()
        for (index in 0 until source.length()) {
            val grade = source.optJSONObject(index) ?: continue
            val subject = grade.optString("subjectName")
                .ifBlank { grade.optString("groupName") }
                .ifBlank { "משו\"ב" }
            val mappedGrade = JSONObject()
                .put("subject", subject)
                .put("name", grade.optString("gradingEvent").ifBlank { grade.optString("gradeType") })
                .put("grade", gradeValue(grade))
                .put("date", grade.optString("eventDate"))
                .put("type", grade.optString("gradeType"))
                .put("teacher", grade.optString("teacherName"))
            mapped.put(mappedGrade)
            val groupName = mappedGrade.optString("subject")
            grouped.getOrPut(groupName) { JSONArray() }.put(mappedGrade)
        }

        cachedGrades = mapped
        courses.clear()
        grouped.forEach { (groupName, grades) ->
            courses += JSONObject()
                .put("name", groupName)
                .put("courseKey", groupName)
                .put("grades", grades)
        }
        gradesLoaded = true
    }

    private fun refreshTimetable(retryAfterLogin: Boolean = true): Boolean {
        if (!loggedIn && !authenticate()) return false
        val guid = studentGuid ?: return false
        val year = schoolYear?.takeIf { it.isNotBlank() } ?: return false

        return try {
            val requestBuilder = Request.Builder()
                .url("$API/students/$guid/timetable?year=$year")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", WEB_APP)
            csrfToken?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("X-Csrf-Token", it)
            }

            val responseCode = client.newCall(requestBuilder.get().build()).execute().use { response ->
                if (response.isSuccessful) {
                    cachedTimetable = JSONArray(response.body.string())
                    timetableLoaded = true
                    refreshBells()
                    return true
                }
                response.code
            }
            Log.w(TAG, "Mashov timetable failed with HTTP $responseCode")
            if (retryAfterLogin && responseCode in listOf(401, 403)) {
                loggedIn = false
                if (authenticate()) return refreshTimetable(retryAfterLogin = false)
            }
            false
        } catch (exception: Exception) {
            Log.w(TAG, "Mashov timetable failed: ${exception.javaClass.simpleName}")
            false
        }
    }

    private fun gradeValue(grade: JSONObject): Any {
        val numericGrade = grade.opt("grade")
        return if (numericGrade != null && numericGrade != JSONObject.NULL) {
            numericGrade
        } else {
            grade.optString("textualGrade").ifBlank { grade.optString("rangeGrade") }
        }
    }

    override fun getCourses(): ArrayList<JSONObject> {
        if (!gradesLoaded) refreshGrades()
        return ArrayList(courses)
    }

    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        if (!gradesLoaded) refreshGrades()
        if (course == "all") return JSONArray(cachedGrades.toString())
        return JSONArray().apply {
            for (index in 0 until cachedGrades.length()) {
                val grade = cachedGrades.optJSONObject(index) ?: continue
                if (grade.optString("subject") == course) put(grade)
            }
        }
    }

    /**
     * Subjects for the free-periods picker.
     *
     * These come from the TIMETABLE, not from grades: a subject the student has
     * finished may carry no grades at all, and early in the school year there
     * are no grades yet -- which left the picker empty. Course names are folded
     * in afterwards for anything the timetable does not mention.
     */
    override fun getSubjectList(): List<String> {
        if (!timetableLoaded) refreshTimetable()
        val subjects = LinkedHashSet<String>()
        for (index in 0 until cachedTimetable.length()) {
            val group = cachedTimetable.optJSONObject(index)?.optJSONObject("groupDetails")
                ?: continue
            val name = group.optString("subjectName").ifBlank { group.optString("groupName") }
            if (name.isNotBlank()) subjects.add(name)
        }
        subjects += getCourses().map { it.optString("name") }.filter { it.isNotBlank() }
        return subjects.toList()
    }

    override fun refreshCookies(): Boolean {
        timetableLoaded = false
        if (studentGrade == null || studentClass == null) loggedIn = false
        if (loggedIn && refreshGrades()) return true
        loggedIn = false
        return refreshGrades()
    }
    override fun isLoggedIn(): Boolean = loggedIn

    override fun getName(): String = displayName ?: username.orEmpty()
    override fun getUsername(): String = username.orEmpty()
    override fun getPassword(): String = password.orEmpty()
    override fun getLoginFields(): LoginFields = loginFields

    override fun isEditing(): Boolean = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun setName(name: String) { displayName = name }
    override fun setUsername(username: String) {
        this.username = username
        loggedIn = false
        timetableLoaded = false
    }
    override fun setPassword(password: String) {
        this.password = password
        loggedIn = false
        timetableLoaded = false
    }
    private fun setSchoolCode(code: String) {
        schoolCode = code.trim()
        loggedIn = false
        timetableLoaded = false
    }
    private fun setSchoolYear(year: String) {
        schoolYear = year.trim()
        loggedIn = false
        timetableLoaded = false
    }

    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
        if (!timetableLoaded && !refreshTimetable()) {
            return JSONObject().put("error", if (loggedIn) "server_unreachable" else "login_failed")
        }
        return timetableForDay(dayIndex)
    }

    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject =
        getSchedule(dayIndex, institutionCode, selectedValue)

    override fun getScheduleIndexes(): JSONArray {
        if (!timetableLoaded) refreshTimetable()
        return sortedSetOf<Int>().apply {
            for (index in 0 until cachedTimetable.length()) {
                val day = cachedTimetable.optJSONObject(index)
                    ?.optJSONObject("timeTable")
                    ?.optInt("day", 0)
                    ?.minus(1)
                    ?: continue
                if (day in 0..5) add(day)
            }
        }.let(::JSONArray)
    }


    /**
     * Load the school's bell schedule.
     *
     * Timetable entries carry only the lesson number -- no clock time -- so the
     * times come from /api/bells, which returns
     * [{"lessonNumber":1,"startTime":"08:15:00","endTime":"09:00:00"}, ...].
     * Schools that do not publish bells simply leave the map empty and the
     * schedule falls back to showing the hour number alone.
     */
    private fun refreshBells() {
        try {
            val requestBuilder = Request.Builder()
                .url("$API/bells")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", WEB_APP)
            csrfToken?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("X-Csrf-Token", it)
            }

            client.newCall(requestBuilder.get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Mashov bells failed with HTTP ${response.code}")
                    return
                }
                val bells = JSONArray(response.body.string())
                val parsed = linkedMapOf<Int, String>()
                for (index in 0 until bells.length()) {
                    val bell = bells.optJSONObject(index) ?: continue
                    val lesson = bell.optInt("lessonNumber", -1)
                    if (lesson < 0) continue
                    val start = clockTime(bell.optString("startTime"))
                    val end = clockTime(bell.optString("endTime"))
                    val label = when {
                        start.isNotBlank() && end.isNotBlank() -> "$start - $end"
                        start.isNotBlank() -> start
                        else -> continue
                    }
                    parsed[lesson] = label
                }
                if (parsed.isNotEmpty()) {
                    bellTimes.clear()
                    bellTimes.putAll(parsed)
                }
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Mashov bells failed: ${exception.javaClass.simpleName}")
        }
    }

    /** "08:15:00" / "1970-01-01T08:15:00" -> "08:15"; anything else -> "". */
    private fun clockTime(raw: String): String =
        Regex("""\d{1,2}:\d{2}""").find(raw.trim())?.value ?: ""

    /**
     * The lesson's clock time, if this school publishes one.
     *
     * A few Mashov deployments inline the times on the timetable entry, so probe
     * those spellings first and otherwise use the bell schedule.
     */
    private fun lessonTime(timeTable: JSONObject, lesson: Int): String {
        val starts = listOf("startTime", "lessonStart", "fromHour", "startHour", "hourStart")
        val ends = listOf("endTime", "lessonEnd", "toHour", "endHour", "hourEnd")
        fun pick(keys: List<String>): String {
            for (k in keys) {
                val v = timeTable.optString(k, "").trim()
                if (v.isNotBlank() && v != "null") return clockTime(v).ifBlank { v }
            }
            return ""
        }
        val start = pick(starts)
        val end = pick(ends)
        return when {
            start.isNotBlank() && end.isNotBlank() -> "$start - $end"
            start.isNotBlank() -> start
            else -> bellTimes[lesson] ?: ""
        }
    }

    private fun timetableForDay(dayIndex: Int): JSONObject = JSONObject().apply {
        for (index in 0 until cachedTimetable.length()) {
            val entry = cachedTimetable.optJSONObject(index) ?: continue
            val timeTable = entry.optJSONObject("timeTable") ?: continue
            if (timeTable.optInt("day") != dayIndex + 1) continue

            val lesson = timeTable.optInt("lesson")
            if (lesson <= 0) continue
            val group = entry.optJSONObject("groupDetails") ?: JSONObject()
            val subject = group.optString("subjectName")
                .ifBlank { group.optString("groupName") }
                .ifBlank { "משו\"ב" }
            val teacher = teacherNames(group)
            put(
                "${lesson}_$index",
                JSONObject()
                    .put("num", lesson)
                    .put("subject", subject)
                    .put("teacher", teacher)
                    .put("time", lessonTime(timeTable, lesson))
                    .put("room", timeTable.optString("roomNum"))
                    .put("colorClass", "")
                    .put("changes", "")
                    .put("exams", "")
            )
        }
    }

    private fun teacherNames(group: JSONObject): String {
        val teachers = group.optJSONArray("groupTeachers") ?: return group.optString("teacherName")
        return buildList {
            for (index in 0 until teachers.length()) {
                val teacher = teachers.optJSONObject(index)?.optString("teacherName")
                    ?: teachers.optString(index)
                if (teacher.isNotBlank()) add(teacher)
            }
        }.distinct().joinToString(", ")
    }
    override fun getAttendanceEvents(period: String): JSONObject =
        getAttendanceEvents(schoolYear?.toIntOrNull() ?: currentSchoolYear().toInt(), period)

    override fun getAttendanceEvents(year: Int, period: String): JSONObject {
        require(period in listOf("a", "b", "ab")) { "Period must be a, b, or ab" }
        if (!loggedIn && !authenticate()) return JSONObject().put("events", JSONObject())
        val guid = studentGuid ?: return JSONObject().put("events", JSONObject())
        val (start, end) = attendanceRange(year, period)
        val query = "?start=$start&end=$end&year=$year"
        val events = JSONObject()

        listOf("behave", "outBehave").forEach { endpoint ->
            val source = getStudentArray(guid, "$endpoint$query") ?: return@forEach
            for (index in 0 until source.length()) {
                val event = source.optJSONObject(index) ?: continue
                val type = event.optString("achvaName")
                    .ifBlank { event.optString("eventName") }
                    .ifBlank { event.optString("eventType") }
                    .ifBlank { event.optString("lessonType") }
                    .ifBlank { "לא ידוע" }
                val subject = event.optString("subject")
                    .ifBlank { event.optString("subjectName") }
                    .ifBlank { event.optString("groupName") }
                    .ifBlank { "משו\"ב" }
                val justification = event.optString("justification")
                val mapped = JSONObject()
                    .put("type", type)
                    .put(
                        "date",
                        event.optString("lessonDate")
                            .ifBlank { event.optString("eventDate") }
                            .ifBlank { event.optString("timestamp") }
                    )
                    .put("subject", subject)
                    .put(
                        "teacher",
                        event.optString("reporter")
                            .ifBlank { event.optString("lessonReporter") }
                            .ifBlank { event.optString("teacherName") }
                    )
                    .put("enableJustified", event.optBoolean("enableJustified", true))
                    .put(
                        "isJustified",
                        event.optBoolean("justified", false) || event.optInt("justificationId", 0) > 0
                    )
                    .put("justifiedReason", justification)
                    .put(
                        "remark",
                        event.optString("remark")
                            .ifBlank { event.optString("achvaAval") }
                            .ifBlank { justification }
                    )

                if (!events.has(type)) events.put(type, JSONArray())
                events.getJSONArray(type).put(mapped)
            }
        }

        return JSONObject().put("events", events)
    }

    private fun getStudentArray(guid: String, endpoint: String): JSONArray? = try {
        val requestBuilder = Request.Builder()
            .url("$API/students/$guid/$endpoint")
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", WEB_APP)
        csrfToken?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("X-Csrf-Token", it)
        }

        client.newCall(requestBuilder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Mashov $endpoint failed with HTTP ${response.code}")
                return null
            }
            JSONArray(response.body.string())
        }
    } catch (exception: Exception) {
        Log.w(TAG, "Mashov $endpoint failed: ${exception.javaClass.simpleName}")
        null
    }

    private fun attendanceRange(year: Int, period: String): Pair<LocalDate, LocalDate> = when (period) {
        "a" -> LocalDate.of(year - 1, 9, 1) to LocalDate.of(year, 1, 31)
        "b" -> LocalDate.of(year, 2, 1) to LocalDate.of(year, 8, 31)
        else -> LocalDate.of(year - 1, 9, 1) to LocalDate.of(year, 8, 31)
    }
    override fun getMessages(page: Int): JSONArray = JSONArray()
    override fun getMessageDetails(messageId: String): JSONObject = JSONObject()
    override suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean = false

    override fun getInfo(): JSONObject = JSONObject()
        .put("name", "Mashov")
        .put("supportsGrades", true)
        .put("supportsSchedule", true)
        .put("supportsAttendance", true)
        // getOriginalSchedule returns the same timetable as getSchedule -- Mashov
        // publishes no "original" version -- and the timetable is the signed-in
        // student's own, so there is no grade/class to pick.
        .put("supportsOriginalSchedule", false)
        .put("supportsScheduleSelection", false)
        .put("scheduleKind", "weekly")
        .put(
            "scheduleSelection",
            studentGrade?.let { grade -> studentClass?.let { clazz -> "$grade|$clazz" } }
        )
        .put("loginVariables", JSONArray(listOf("School code", "School year", "Username", "Password")))
        // The school's bell schedule, so the schedule page can put a time on an
        // hour that has no lesson on any day (a free period at hour zero).
        .put(
            "lessonTimes",
            JSONObject().apply { bellTimes.forEach { (hour, label) -> put(hour.toString(), label) } }
        )

    override fun toJson(): JSONObject = JSONObject()
        .put("class", javaClass.name)
        .put("id", id)
        .put("platformDisplayName", platformDisplayName)
        .put("schoolCode", schoolCode)
        .put("schoolYear", schoolYear)
        .put("username", username)
        .put("password", password)
        .put("studentGuid", studentGuid)
        .put("studentGrade", studentGrade)
        .put("studentClass", studentClass)
        .put("displayName", displayName)
        .put("csrfToken", csrfToken)
        .put("loggedIn", loggedIn)
        .put(
            "cookies",
            JSONArray(synchronized(sessionCookies) { sessionCookies.map(Cookie::toString) })
        )
        .put("courses", JSONArray(courses))

    companion object : Platform.Companion {
        private const val TAG = "MashovPlatform"
        private const val API = "https://web.mashov.info/api"
        private const val WEB_APP = "https://web.mashov.info/students/"
        private const val MASHOV_APP_ID = "info.mashov.students"
        private const val MASHOV_API_VERSION = "3.20210425"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private var schoolsCache: List<MashovSchool>? = null

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        override fun fromJson(obj: JSONObject): MashovPlatform {
            val platform = MashovPlatform()
            platform.id = obj.optString("id").ifBlank { generateId() }
            platform.platformDisplayName = obj.optString("platformDisplayName", "Mashov")
            platform.schoolCode = obj.optString("schoolCode").takeIf { it.isNotBlank() }
            platform.schoolYear = obj.optString("schoolYear").takeIf { it.isNotBlank() } ?: currentSchoolYear()
            platform.username = obj.optString("username").takeIf { it.isNotBlank() }
            platform.password = obj.optString("password").takeIf { it.isNotBlank() }
            platform.studentGuid = obj.optString("studentGuid").takeIf { it.isNotBlank() }
            platform.studentGrade = obj.optString("studentGrade").takeIf { it.isNotBlank() }
            platform.studentClass = obj.optString("studentClass").takeIf { it.isNotBlank() }
            platform.displayName = obj.optString("displayName").takeIf { it.isNotBlank() }
            platform.csrfToken = obj.optString("csrfToken").takeIf { it.isNotBlank() }

            val cookieUrl = API.toHttpUrl()
            val savedCookies = obj.optJSONArray("cookies") ?: JSONArray()
            for (index in 0 until savedCookies.length()) {
                Cookie.parse(cookieUrl, savedCookies.optString(index))?.let(platform.sessionCookies::add)
            }
            platform.loggedIn = obj.optBoolean("loggedIn", false) &&
                platform.studentGuid != null &&
                platform.csrfToken != null &&
                platform.sessionCookies.isNotEmpty()

            val savedCourses = obj.optJSONArray("courses") ?: JSONArray()
            for (index in 0 until savedCourses.length()) {
                savedCourses.optJSONObject(index)?.let(platform.courses::add)
            }
            platform.cachedGrades = JSONArray().apply {
                platform.courses.forEach { course ->
                    val grades = course.optJSONArray("grades") ?: return@forEach
                    for (index in 0 until grades.length()) grades.optJSONObject(index)?.let(::put)
                }
            }
            platform.gradesLoaded = platform.cachedGrades.length() > 0
            return platform
        }

        override fun checkCredentials(loginFields: LoginFields): Boolean =
            MashovPlatform(loginFields).isLoggedIn()

        private fun normalizeGradeCode(classCode: String): String? {
            val normalized = classCode.trim()
                .replace("׳", "")
                .replace("״", "")
                .replace("\"", "")
                .replace("'", "")
            val hebrewGrades = listOf("א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט", "י", "יא", "יב")
            val index = hebrewGrades.indexOf(normalized)
            return if (index >= 0) (index + 1).toString() else normalized.takeIf { it.isNotBlank() }
        }

        @JvmStatic
        fun getSchools(): List<MashovSchool> = synchronized(this) {
            schoolsCache?.let { return it }

            val request = Request.Builder()
                .url("$API/schools")
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", WEB_APP)
                .build()
            val schools = try {
                OkHttpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return emptyList()
                    val source = JSONArray(response.body.string())
                    buildList {
                        for (index in 0 until source.length()) {
                            val school = source.optJSONObject(index) ?: continue
                            val semel = school.optString("semel")
                            val name = school.optString("name")
                            if (semel.isNotBlank() && name.isNotBlank()) {
                                add(MashovSchool(semel, name))
                            }
                        }
                    }
                }
            } catch (exception: Exception) {
                Log.w(TAG, "Mashov schools request failed: ${exception.javaClass.simpleName}")
                emptyList()
            }
            schoolsCache = schools
            schools
        }

        private fun currentSchoolYear(): String {
            val today = LocalDate.now()
            return (today.year + if (today.monthValue >= 7) 1 else 0).toString()
        }
    }
}
