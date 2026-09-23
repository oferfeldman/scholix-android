package com.feldman.scholix.api.platforms

import android.content.Context
import android.content.Intent.createChooser
import android.os.Environment
import android.util.Log
import com.feldman.scholix.api.LoginField
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.UnsafeOkHttpClient
import com.feldman.scholix.api.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.LocalDate
import java.time.Year
import java.time.format.DateTimeFormatter
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WebtopPlatform() : Platform {
    override var platformDisplayName: String = "WebtopPlatform"

    private val loginFields = LoginFields()
        .addField(
            id = "username",
            type = Type.Username,
            getter = { it.getUsername() },
            setter = { platform, value -> platform.setUsername(value ?: "") }
        )
        .addField(
            id = "password",
            type = Type.Password,
            getter = { it.getPassword() },
            setter = { platform, value -> platform.setPassword(value ?: "") }
        )
    private var username: String? = null
    private var password: String? = null
    var studentName: String? = null
    var studentId: String? = null
    var studentClass: String? = null
    var studentInstitution: String? = null
    var userStudentId: String? = null
    var userType: Int? = null
    var schoolName: String? = null
    var loginMethod: String? = null

    private var _cookies: String? = null
    @Volatile
    private var cachedShotefKey: String? = null
    @Volatile
    private var cachedShotefDays: JSONArray? = null
    @Volatile
    private var cachedShotefTime: Long = 0
    private val _client: OkHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient()
    val mailbox: WebtopMailbox = WebtopMailbox(
        cookie = { _cookies ?: "" },
        refreshSession = { refreshCookies() },
        client = _client
    )

    fun withId(newId: String): WebtopPlatform = apply { id = newId }

    fun isMoe(): Boolean =
        loginMethod == "moe" || (loginMethod.isNullOrBlank() && (username?.any { it.isLetter() } == true || !userStudentId.isNullOrBlank()))

    fun needsInteractiveRelogin(): Boolean =
        isMoe() && !loggedIn && !username.isNullOrBlank() && !password.isNullOrBlank()

    fun adoptSession(key: String): Boolean {
        val payload = JSONObject()
            .put("rememberMe", false)
            .put("key", key)
            .put("UniqueId", UUID.randomUUID().toString())
            .put("deviceDataJson", "{\"isMobile\":false,\"isTablet\":false,\"isDesktop\":true}")

        val request = Request.Builder()
            .url("https://webtopserver.smartschool.co.il/server/api/user/LoginMoe")
            .header("Origin", "https://webtop.smartschool.co.il")
            .header("Referer", "https://webtop.smartschool.co.il/")
            .header("language", "he")
            .header("rememberMe", "0")
            .header("X-XSRF-TOKEN", "")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            _client.newCall(request).execute().use { response ->
                val body = response.body.string()
                val jsonResponse = JSONObject(body)
                if (!jsonResponse.optBoolean("status", false)) return false
                val data = jsonResponse.optJSONObject("data") ?: return false
                studentId = data.optString("userId")
                studentClass = "${data.optString("classCode")}|${data.opt("classNumber")}"
                studentInstitution = data.optString("institutionCode")
                studentName = "${data.optString("firstName")} ${data.optString("lastName")}".trim()
                userStudentId = data.optString("studentId").ifEmpty { null }
                userType = if (data.has("userType") && !data.isNull("userType")) data.optInt("userType") else null
                schoolName = data.optString("institutionName").ifEmpty { null }
                loginMethod = "moe"
                _cookies = response.headers("Set-Cookie").joinToString("; ")
                cachedShotefKey = null
                cachedShotefDays = null
                loggedIn = true
                true
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to adopt MOE session", e)
            false
        }
    }
    override var editing: Boolean = false
    override var loggedIn: Boolean = false
    private val _courses: ArrayList<JSONObject> = ArrayList()
    private val inputFormatter = DateTimeFormatter.ofPattern("yyyy-M-d['T'HH:mm:ss]")
    private val outputFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    override var id: String = generateId()
        private set

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = true
    override val supportsAttendance: Boolean = true


    init {
        if (id.isBlank()) {
            id = generateId()
        }
    }

    constructor(loginFields: LoginFields) : this() {
        val username = loginFields.getValueByType(Type.Username)
        val password = loginFields.getValueByType(Type.Password)
        Log.d("WebtopPlatform", "constructing: $username $password")
        val loginSuccess = login(username ?: "", password ?: "")

        Log.d("WebtopPlatform", "success: $loginSuccess")
        if (username != null && password != null && loginSuccess) {
            this.username = username
            this.password = password
            loggedIn = true

            // Add single course entry (grades fetched lazily)
            _courses.add(
                JSONObject()
                    .put("name", "Webtop")
                    .put("courseKey", "Webtop")
                    .put("platformId", id)
                    .put("index", 0)
                    .put("semester", getCurrentSemester())
                    .put("semesterPicker", true)
                    .put("year", Year.now().value)
            )
        } else {
            Log.e("WebtopPlatform", "Missing username or password in LoginFields")
        }
    }


    /** Perform login once, keep cookies */
    private fun login(username: String, password: String): Boolean {
        val u = username
        val p = password
        if (u.isNullOrBlank() || p.isNullOrBlank()) {
            Log.w("WebtopPlatform", "Cannot refresh cookies: credentials missing")
            loggedIn = false
            return false
        }

        return try {
            val loginData = JSONObject()
                .put("Data", encrypt(username + "0"))
                .put("username", username)
                .put("Password", password)
                .put("deviceDataJson", "{\"isMobile\":true,\"os\":\"Android\",\"browser\":\"Chrome\",\"cookies\":true}")

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/user/LoginByUserNameAndPassword")
                .post(loginData.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful || body.isEmpty()) {
                    Log.e("WebtopPlatform", "Login failed: HTTP ${response.code}, body=$body")
                    return false
                }
                val jsonResponse = JSONObject(body)
                val data = jsonResponse.optJSONObject("data") ?: return false

                studentId = data.getString("userId")
                studentClass = data.getString("classCode") + "|" + data.get("classNumber")
                studentInstitution = data.getString("institutionCode")
                studentName = "${data.getString("firstName")} ${data.getString("lastName")}"
                userStudentId = data.optString("studentId").ifEmpty { null }
                userType = if (data.has("userType") && !data.isNull("userType")) data.optInt("userType") else null
                schoolName = data.optString("institutionName").ifEmpty { null }
                loginMethod = "password"
                _cookies = response.headers("Set-Cookie").joinToString("; ")
                true
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Login exception", e)
            false
        }
    }

    override fun getName(): String = studentName ?: ""
    override fun getUsername(): String = username ?: ""
    override fun getPassword(): String = password ?: ""

    override fun toString(): String =
        "WebtopPlatform(name=$studentName, institution=$studentInstitution, loggedIn=$loggedIn)"

    private fun encrypt(data: String): String? {
        val key = "01234567890000000150778345678901"
        return try {
            val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val iv = ByteArray(16).apply { SecureRandom().nextBytes(this) }

            val spec = PBEKeySpec(key.toCharArray(), salt, 100, 256)
            val secretKey = SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec).encoded, "AES"
            )

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
            val encrypted = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))

            val combined = salt + iv + encrypted
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Encryption failed", e)
            null
        }
    }

    /** Return the single Webtop course, fetch grades only once */
    override fun getCourses(): ArrayList<JSONObject> {
        if (_courses.isEmpty()) {
            _courses.add(
                JSONObject()
                    .put("name", "Webtop")
                    .put("courseKey", "Webtop")
                    .put("platformId", id)
                    .put("index", 0)
                    .put("semester", getCurrentSemester())
                    .put("semesterPicker", true)
                    .put("year",
                        if (getCurrentSemester().equals("a", ignoreCase = true))
                            Year.now().value + 1
                        else
                            Year.now().value
                    )
            )
        }

        // Attach grades if not already present
        val course = _courses[0]
        if (!course.has("grades")) {
            course.put("grades", getGrades("webtop", null, null))
        }
        return _courses
    }


    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        Log.d("WebtopPlatform", "override getGrades(course: $course, year: $year, semester: $semester) called.")

        val currentYear = Year.now().value
        val resolvedSemester = semester?.lowercase() ?: getCurrentSemester()

        val correctYear = currentYear + if (resolvedSemester.equals("a", true)) 1 else 0
        val resolvedYear = year ?: correctYear

        return getGrades(year = resolvedYear, semester = resolvedSemester, retry = true)
    }


    private fun getGrades(year: Int, semester: String, retry: Boolean = true): JSONArray {
        Log.d("WebtopPlatform", "private getGrades(year: $year, semester: $semester, retry: $retry) called.")

        val grades = JSONArray()
        if (studentId == null) {
            grades.put(JSONObject().put("error", "login_failed"))
            return grades
        }

        try {
            val periodId = when (semester.lowercase()) {
                "a" -> 1103
                "b" -> 1102
                "ab" -> 0
                else -> throw IllegalArgumentException("Invalid semester")
            }

            val requestJson = JSONObject()
                .put("studyYear", year)
                .put("moduleID", 1)
                .put("periodID", periodId)
                .put("studentID", studentId)

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/PupilCard/GetPupilGrades")
                .addHeader("Cookie", _cookies ?: "")
                .post(
                    requestJson.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()

            _client.newCall(request).execute().use { response ->
                val body = response.body.string()

                // Unauthorized — try refresh once
                if ((response.code == 401 || response.code == 403)) {
                    if (retry && refreshCookies()) {
                        Log.d("WebtopPlatform", "Retrying grades after cookie refresh")
                        return getGrades(year, semester, retry = false)
                    }
                    Log.w("WebtopPlatform", "Login/session expired; returning login_failed error")
                    return JSONArray().put(JSONObject().put("error", "login_failed"))
                }

                // Unsuccessful or empty body
                if (!response.isSuccessful || body.isEmpty()) {
                    Log.w("WebtopPlatform", "Server unreachable or bad response: ${response.code}")
                    return JSONArray().put(JSONObject().put("error", "server_unreachable"))
                }

                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data == null) {
                    Log.w("WebtopPlatform", "No data array in response body")
                    return JSONArray().put(JSONObject().put("error", "unknown_error"))
                }

                for (i in 0 until data.length()) {
                    val g = data.optJSONObject(i) ?: continue
                    grades.put(
                        JSONObject()
                            .put("subject", g.optString("subject", "Unknown Subject"))
                            .put("name", g.optString("title", "Untitled"))
                            .put("grade", g.optString("grade", "N/A"))
                            .put("date", g.optString("date", "N/A"))
                    )
                }
            }

        } catch (io: IOException) {
            Log.e("WebtopPlatform", "Server unreachable (IOException)", io)
            return JSONArray().put(JSONObject().put("error", "server_unreachable"))
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch grades (Exception)", e)
            return JSONArray().put(JSONObject().put("error", "unknown_error"))
        }

        Log.i("WebtopPlatform", "Grades loaded successfully (${grades.length()} items)")
        return grades
    }




    override fun getSubjectList(): List<String> {
        Log.d("WebtopPlatform", "override getSubjectList() called.")

        val subjects = mutableSetOf<String>()

        try {
            // Prepare request payload (same as getSchedule)
            val payload = JSONObject()
                .put("institutionCode", studentInstitution)
                .put("selectedValue", studentClass)
                .put("typeView", 1)

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/shotef/ShotefSchedualeData")
                .addHeader("Cookie", _cookies ?: "")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful || body.isEmpty()) {
                    Log.w("WebtopPlatform", "Failed to load full schedule for subject list")
                    return subjects.toList()
                }

                val json = JSONObject(body)
                val days = json.optJSONArray("data") ?: JSONArray()

                // Iterate over all days in the schedule
                for (i in 0 until days.length()) {
                    val day = days.optJSONObject(i) ?: continue
                    val hoursData = day.optJSONArray("hoursData") ?: continue

                    // Each hour may contain a list of scheduled lessons
                    for (j in 0 until hoursData.length()) {
                        val hour = hoursData.optJSONObject(j) ?: continue

                        // "scheduale" array sometimes spelled incorrectly in the API
                        val lessons = when {
                            hour.has("scheduale") -> hour.optJSONArray("scheduale")
                            hour.has("schedule") -> hour.optJSONArray("schedule")
                            else -> null
                        } ?: continue

                        for (k in 0 until lessons.length()) {
                            val lesson = lessons.optJSONObject(k) ?: continue
                            val subject = cleanSubject(lesson.optString("subject", ""))
                            if (subject.isNotBlank()) {
                                subjects.add(subject)
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch subject list", e)
        }

        return subjects.sorted()
    }


    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
        Log.d("WebtopPlatform", "override getSchedule(dayIndex: $dayIndex, institutionCode $institutionCode, selectedValue $selectedValue) called.")

        val institution = institutionCode ?: studentInstitution
        val classCode = selectedValue?.takeIf { it.isNotBlank() } ?: studentClass

        val schedule = JSONObject()
        if (dayIndex < 0) return schedule

        try {
            val cacheKey = "$institution|$classCode"
            val now = System.currentTimeMillis()
            val days: JSONArray = if (cachedShotefKey == cacheKey &&
                now - cachedShotefTime < 60_000 &&
                cachedShotefDays != null
            ) {
                cachedShotefDays!!
            } else {
                val payload = JSONObject()
                    .put("institutionCode", institution)
                    .put("selectedValue", classCode)
                    .put("typeView", 1)

                Log.d("WebtopPlatform", "Requesting schedule for class=$classCode, inst=$institution, day=$dayIndex")

                val request = Request.Builder()
                    .url("https://webtopserver.smartschool.co.il/server/api/shotef/ShotefSchedualeData")
                    .addHeader("Cookie", _cookies ?: "")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                _client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    Log.v("WebtopPlatform", "Schedule body (len=${body.length}): $body")

                    if (response.code == 401 || response.code == 403 || body.isEmpty()) {
                        Log.d("WebtopPlatform", "Schedule fetch unauthorized/empty → refreshing cookies")
                        if (refreshCookies()) {
                            // Retry once with same parameters after refreshing cookies
                            return getSchedule(dayIndex, institutionCode, classCode)
                        }
                        return JSONObject().put("error", "login_failed")
                    }

                    if (!response.isSuccessful) {
                        return JSONObject().put("error", "server_unreachable")
                    }

                    val json = JSONObject(body)
                    val fetchedDays = json.optJSONArray("data") ?: JSONArray()
                    cachedShotefKey = cacheKey
                    cachedShotefDays = fetchedDays
                    cachedShotefTime = now
                    fetchedDays
                }
            }

            var day: JSONObject? = null
            for (i in 0 until days.length()) {
                val d = days.optJSONObject(i) ?: continue
                val dIndex = if (d.has("dayIndex")) d.optInt("dayIndex", -1) else d.optInt("day", d.optInt("dayId", -1))
                if (dIndex == dayIndex + 1) {
                    day = d
                    break
                }
            }
            if (day == null) return schedule
            Log.v("WebtopPlatform", "Schedule day: $day")

            val hoursRaw = day.optJSONArray("hoursData") ?: JSONArray()

            // --- STEP 1: Build original schedule ---
            val hoursOriginal = JSONObject()
            for (i in 0 until hoursRaw.length()) {
                val hour = hoursRaw.optJSONObject(i) ?: continue
                val lessons = hour.optJSONArray("scheduale") ?: continue
                if (lessons.length() > 0) {
                    processScheduleOriginal(hour, hoursOriginal)
                }
            }

            // --- STEP 2: Build updated schedule ---
            val hours = JSONObject()
            for (i in 0 until hoursRaw.length()) {
                val hour = hoursRaw.optJSONObject(i) ?: continue
                val lessons = hour.optJSONArray("scheduale") ?: JSONArray()
                val changes = hour.optJSONArray("changes") ?: JSONArray()
                if (lessons.length() > 0 || changes.length() > 0) {
                    processScheduleUpdated(hour, hours, hoursOriginal)
                }
            }

            return hours
        } catch (e: IOException) {
            Log.e("WebtopPlatform", "Server unreachable", e)
            return JSONObject().put("error", "server_unreachable")
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch schedule", e)
            return JSONObject().put("error", "unknown_error")
        }
    }

    private fun parseHourTime(hourName: String?): String {
        if (hourName.isNullOrBlank()) return ""
        val matches = Regex("""\d{1,2}:\d{2}""").findAll(hourName).map { it.value }.toList()
        if (matches.size >= 2) {
            val t1 = matches[0]
            val t2 = matches[1]
            fun toMins(t: String): Int {
                val parts = t.split(":")
                return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            return if (toMins(t1) <= toMins(t2)) "$t1 - $t2" else "$t2 - $t1"
        }
        return matches.firstOrNull() ?: ""
    }

    @Throws(Exception::class)
    private fun processScheduleOriginal(hourRaw: JSONObject, hours: JSONObject) {
        val scheduleArray = hourRaw.optJSONArray("scheduale") ?: return
        val hourNum = hourRaw.optInt("hour", -1)
        val time = parseHourTime(hourRaw.optString("hourName"))

        for (k in 0 until scheduleArray.length()) {
            val scheduleItem = scheduleArray.optJSONObject(k) ?: continue
            val subject = cleanSubject(scheduleItem.optString("subject", "לא זמין"))
            val teacher = (scheduleItem.optString("teacherPrivateName", "") + " " +
                    scheduleItem.optString("teacherLastName", "")).trim().ifEmpty { "לא זמין" }
            val itemHourNum = scheduleItem.optInt("hour", hourNum)
            val colorClass = findColorClass(subject)
            val room = scheduleItem.optString("room", "").takeIf { it != "null" } ?: ""
            val subjectLevel = scheduleItem.optString("subjectLevel", "").takeIf { it != "null" } ?: ""

            val hour = JSONObject()
                .put("num", itemHourNum)
                .put("hour", itemHourNum)
                .put("subject", subject)
                .put("subjectLevel", subjectLevel)
                .put("teacher", teacher)
                .put("room", room)
                .put("time", time)
                .put("colorClass", colorClass)
                .put("changes", "")
                .put("exams", "")
            hours.put("${itemHourNum}_$k", hour)
        }
    }

    @Throws(Exception::class)
    private fun processScheduleUpdated(hourRaw: JSONObject, hours: JSONObject, hoursOriginal: JSONObject) {
        val scheduleArray = hourRaw.optJSONArray("scheduale") ?: JSONArray()
        val hourNum = hourRaw.optInt("hour", -1)
        val time = parseHourTime(hourRaw.optString("hourName"))

        // --- Exams handling ---
        var examTitle = ""
        if (hourRaw.has("exams")) {
            val examsArray = hourRaw.optJSONArray("exams")
            if (examsArray != null && examsArray.length() > 0) {
                examTitle = examsArray.optJSONObject(0)?.optString("title", "") ?: ""
            }
        }

        // --- Changes handling ---
        val hourChanges = hourRaw.optJSONArray("changes") ?: JSONArray()

        // Handle addition in an empty hour
        if (scheduleArray.length() == 0 && hourChanges.length() > 0) {
            for (j in 0 until hourChanges.length()) {
                val itemObj = hourChanges.optJSONObject(j) ?: continue
                if (itemObj.optBoolean("isAddition") ||
                    itemObj.optString("definition").contains("תוספת שיעור") ||
                    itemObj.optString("type") == "תוספת שיעור"
                ) {
                    val addTeacher = (itemObj.optString("privateName", "") + " " +
                            itemObj.optString("lastName", "")).trim()
                    var addSubject = "תוספת שיעור"
                    for (key in hoursOriginal.keys()) {
                        val existing = hoursOriginal.optJSONObject(key) ?: continue
                        if (existing.optString("teacher") == addTeacher) {
                            addSubject = existing.optString("subject")
                            break
                        }
                    }
                    val hour = JSONObject()
                        .put("num", hourNum)
                        .put("hour", hourNum)
                        .put("subject", addSubject)
                        .put("subjectLevel", "")
                        .put("teacher", addTeacher)
                        .put("room", itemObj.optString("room", "").takeIf { it != "null" } ?: "")
                        .put("time", time)
                        .put("colorClass", "yellow-cell")
                        .put("changes", "תוספת שיעור")
                        .put("exams", examTitle)
                    hours.put("${hourNum}_add_$j", hour)
                }
            }
            return
        }

        for (k in 0 until scheduleArray.length()) {
            val scheduleItem = scheduleArray.optJSONObject(k) ?: continue
            val subject = cleanSubject(scheduleItem.optString("subject", "לא זמין"))
            val teacher = (scheduleItem.optString("teacherPrivateName", "") + " " +
                    scheduleItem.optString("teacherLastName", "")).trim().ifEmpty { "לא זמין" }
            val itemHourNum = scheduleItem.optInt("hour", hourNum)
            val colorClass = findColorClass(subject)
            val room = scheduleItem.optString("room", "").takeIf { it != "null" } ?: ""
            val subjectLevel = scheduleItem.optString("subjectLevel", "").takeIf { it != "null" } ?: ""

            val hour = JSONObject()
                .put("num", itemHourNum)
                .put("hour", itemHourNum)
                .put("subject", subject)
                .put("subjectLevel", subjectLevel)
                .put("teacher", teacher)
                .put("room", room)
                .put("time", time)
                .put("colorClass", colorClass)
                .put("changes", "")
                .put("exams", examTitle)

            // Combine changes: hour-level changes and item-level changes
            val changesArray = JSONArray().apply {
                for (i in 0 until hourChanges.length()) {
                    hourChanges.optJSONObject(i)?.let { put(it) }
                }
                val itemChanges = scheduleItem.optJSONArray("changes")
                if (itemChanges != null) {
                    for (i in 0 until itemChanges.length()) {
                        itemChanges.optJSONObject(i)?.let { put(it) }
                    }
                }
            }
            var cancel = false

            for (j in 0 until changesArray.length()) {
                val itemObj = changesArray.optJSONObject(j) ?: continue
                val def = itemObj.optString("definition", "")
                val type = itemObj.optString("type", "")

                if (itemObj.optBoolean("isAddition") ||
                    def.contains("תוספת שיעור") ||
                    type == "תוספת שיעור"
                ) {
                    val addTeacher = (itemObj.optString("privateName", "") + " " +
                            itemObj.optString("lastName", "")).trim()
                    var addSubject = "תוספת שיעור"
                    for (key in hoursOriginal.keys()) {
                        val existing = hoursOriginal.optJSONObject(key) ?: continue
                        if (existing.optString("teacher") == addTeacher) {
                            addSubject = existing.optString("subject")
                            break
                        }
                    }
                    hour.put("subject", addSubject)
                    hour.put("teacher", addTeacher)
                    hour.put("colorClass", "yellow-cell")
                    hour.put("changes", "תוספת שיעור")
                    break
                }

                // ביטול שיעור
                if (def == "ביטול שיעור" || itemObj.optBoolean("isClassCancel", false) ||
                    (def == "ביטול שיעור" && (itemObj.optInt("original_hour", -1) == -1 || itemObj.optInt("original_hour", -1) == itemHourNum))) {
                    cancel = true
                }

                // הזזת שיעור
                if (def == "הזזת שיעור" || itemObj.optBoolean("isClassMove", false)) {
                    val fillTeacher = (itemObj.optString("privateName", "") + " " +
                            itemObj.optString("lastName", "")).trim()
                    var found = false
                    for (key in hoursOriginal.keys()) {
                        val existing = hoursOriginal.optJSONObject(key) ?: continue
                        if (existing.optString("teacher") == fillTeacher) {
                            hour.put("subject", existing.optString("subject"))
                            hour.put("teacher", existing.optString("teacher"))
                            hour.put("colorClass", existing.optString("colorClass"))
                            found = true
                            break
                        }
                    }
                    if (!found && fillTeacher.isNotBlank()) {
                        val prev = hour.optString("changes")
                        hour.put("changes", (if (prev.isNotEmpty()) "$prev\n" else "") + "מילוי מקום של $fillTeacher")
                    }
                }

                // מילוי מקום
                if (def == "מילוי מקום" || itemObj.optBoolean("isFillUp", false)) {
                    val fillTeacher = (itemObj.optString("privateName", "") + " " +
                            itemObj.optString("lastName", "")).trim()
                    if (fillTeacher.isNotBlank()) {
                        hour.put("teacher", fillTeacher)
                        hour.put("subject", "${hour.optString("subject")} / מילוי מקום")
                    }
                    for (key in hoursOriginal.keys()) {
                        val existing = hoursOriginal.optJSONObject(key) ?: continue
                        if (existing.optString("teacher") == fillTeacher) {
                            hour.put("subject", existing.optString("subject"))
                            hour.put("teacher", existing.optString("teacher"))
                            hour.put("colorClass", existing.optString("colorClass"))
                            break
                        }
                    }
                }
            }

            // Events handling
            if (hourRaw.has("events")) {
                val events = hourRaw.optJSONArray("events")
                if (events != null && events.length() > 0) {
                    val event = events.optJSONObject(0)
                    if (event != null) {
                        val title = event.optString("title", "")
                        val accompaniers = event.optString("accompaniers", "").replace(Regex(",\\s*$"), "")
                        if (accompaniers.isNotBlank() && accompaniers != ",") {
                            hour.put("teacher", accompaniers)
                        }
                        if (title.isNotBlank()) {
                            hour.put("subject", title)
                            hour.put("changes", "")
                        }
                    }
                }
            }

            if (cancel) {
                hour.put("colorClass", "cancel-cell")
                hour.put("changes", "ביטול שיעור")
                hour.put("exams", "")
            }

            hours.put("${itemHourNum}_$k", hour)
        }
    }


    private fun cleanSubject(subject: String?): String =
        subject?.replace("\"", "")?.trim() ?: "לא זמין"

    private fun findColorClass(subject: String): String {
        val map = mapOf(
            "מתמטיקה האצה" to "lightgreen-cell",
            "מדעים" to "lightyellow-cell",
            "של`ח" to "lightgreen-cell",
            "חינוך" to "pink-cell",
            "ערבית" to "lightblue-cell",
            "היסטוריה" to "lightred-cell",
            "עברית" to "lightpurple-cell",
            "חינוך גופני" to "lightorange-cell",
            "נחשון" to "lightyellow-cell",
            "אנגלית" to "lime-cell",
            "ספרות" to "blue-cell",
            "תנך" to "lightgrey-cell",
            "תנ`ך" to "lightgrey-cell",
            "cancel" to "cancel-cell"
        )
        return map[subject] ?: "custom-pink-cell"
    }



    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
        val schedule = JSONObject()
        if (dayIndex < 0) return schedule
        val institution = institutionCode ?: studentInstitution
        val classCode = selectedValue?.takeIf { it.isNotBlank() } ?: studentClass

        try {
            val cacheKey = "$institution|$classCode"
            val now = System.currentTimeMillis()
            val days: JSONArray = if (cachedShotefKey == cacheKey &&
                now - cachedShotefTime < 60_000 &&
                cachedShotefDays != null
            ) {
                cachedShotefDays!!
            } else {
                val payload = JSONObject()
                    .put("institutionCode", institution)
                    .put("selectedValue", classCode)
                    .put("typeView", 1)

                val request = Request.Builder()
                    .url("https://webtopserver.smartschool.co.il/server/api/shotef/ShotefSchedualeData")
                    .addHeader("Cookie", _cookies ?: "")
                    .post(
                        payload.toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()

                _client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (response.code == 401 || response.code == 403 || body.isEmpty()) {
                        Log.d("WebtopPlatform", "Original schedule fetch unauthorized/empty → refreshing cookies")
                        if (refreshCookies()) {
                            return getOriginalSchedule(dayIndex, institutionCode, classCode)
                        }
                        return JSONObject().put("error", "login_failed")
                    }

                    if (!response.isSuccessful) {
                        return JSONObject().put("error", "server_unreachable")
                    }

                    val json = JSONObject(body)
                    val fetchedDays = json.optJSONArray("data") ?: JSONArray()
                    cachedShotefKey = cacheKey
                    cachedShotefDays = fetchedDays
                    cachedShotefTime = now
                    fetchedDays
                }
            }

            var day: JSONObject? = null
            for (i in 0 until days.length()) {
                val d = days.optJSONObject(i) ?: continue
                val dIndex = if (d.has("dayIndex")) d.optInt("dayIndex", -1) else d.optInt("day", d.optInt("dayId", -1))
                if (dIndex == dayIndex + 1) {
                    day = d
                    break
                }
            }
            if (day == null) return schedule

            val hoursRaw = day.optJSONArray("hoursData") ?: JSONArray()

            val hoursOriginal = JSONObject()
            for (i in 0 until hoursRaw.length()) {
                val hour = hoursRaw.optJSONObject(i) ?: continue
                val lessons = hour.optJSONArray("scheduale") ?: continue
                if (lessons.length() > 0) {
                    processScheduleOriginal(hour, hoursOriginal)
                }
            }
            return hoursOriginal
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch original schedule", e)
            return JSONObject().put("error", "server_unreachable")
        }
    }

    override fun getScheduleIndexes(): JSONArray = JSONArray()

    override fun isLoggedIn(): Boolean = loggedIn

    override fun refreshCookies(): Boolean {
        if (isMoe()) {
            Log.d("WebtopPlatform", "Refreshing MOE Webtop cookies...")
            val u = username
            val p = password
            if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
                try {
                    val session = WebtopMoeLogin(_client).login(u, p)
                    val newCookies = session.cookieHeader
                    if (newCookies.isNotBlank()) {
                        _cookies = newCookies
                        cachedShotefKey = null
                        cachedShotefDays = null
                        loggedIn = true
                        Log.i("WebtopPlatform", "MOE Webtop cookies refreshed successfully via headless flow")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w("WebtopPlatform", "Headless MOE cookie refresh failed: ${e.message}")
                }
            }
            loggedIn = false
            return false
        }

        return try {
            val loginData = JSONObject()
                .put("Data", encrypt(username + "0"))
                .put("username", username)
                .put("Password", password)
                .put("deviceDataJson", "{\"isMobile\":true,\"os\":\"Android\",\"browser\":\"Chrome\",\"cookies\":true}")

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/user/LoginByUserNameAndPassword")
                .post(loginData.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                val jsonResponse = JSONObject(response.body.string())
                val data = jsonResponse.optJSONObject("data") ?: return false
                studentId = data.getString("userId")
                studentClass = data.getString("classCode") + "|" + data.get("classNumber")
                studentInstitution = data.getString("institutionCode")
                userStudentId = data.optString("studentId").ifEmpty { null }
                userType = if (data.has("userType") && !data.isNull("userType")) data.optInt("userType") else null
                schoolName = data.optString("institutionName").ifEmpty { null }
                _cookies = response.headers("Set-Cookie").joinToString("; ")
                cachedShotefKey = null
                cachedShotefDays = null
                loggedIn = true
                true
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to refresh cookies", e)
            false
        }
    }

    override fun toJson(): JSONObject {
        val course = if (_courses.isNotEmpty()) _courses[0] else null
        return JSONObject()
            .put("class", javaClass.name)
            .put("id", id)
            .put("name", studentName)
            .put("institution", studentInstitution)
            .put("studentId", studentId)
            .put("classCode", studentClass)
            .put("username", username)
            .put("password", password)
            .put("cookies", _cookies)
            .put("loggedIn", loggedIn)
            .put("courses", JSONArray().apply { course?.let { put(it) } })
            .put("platformDisplayName", platformDisplayName)
            .put("userStudentId", userStudentId)
            .put("userType", userType ?: JSONObject.NULL)
            .put("schoolName", schoolName)
            .put("loginMethod", loginMethod)
    }
    @Throws(JSONException::class, IOException::class)
    override fun getAttendanceEvents(period: String): JSONObject {

        if (period !in listOf("a", "b", "ab")) {
            throw IllegalArgumentException("Period must be a, b, or ab")
        }
        val periodId = when (period) {
            "a" -> 1103
            "b" -> 1102
            "ab" -> 0
            else -> throw IllegalArgumentException("Invalid period")
        }

        val result = JSONObject()

        try {
            val requestJson = JSONObject()
                .put("studentID", studentId)
                .put("moduleID", 11)
                .put("periodID", periodId)

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/PupilCard/GetPupilDiciplineEvents")
                .addHeader("Cookie", _cookies ?: "")
                .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("WebtopPlatform", "Failed to fetch discipline events: ${response.code}")
                    return result
                }

                val body = response.body.string()
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: return result
                val eventsArray = data.optJSONArray("disciplineEvents") ?: JSONArray()

                // Transform into grouped events by type
                val eventsByType = JSONObject()
                for (i in 0 until eventsArray.length()) {
                    val item = eventsArray.getJSONObject(i)
                    var isJustified = item.optBoolean("isJustified", false)
                    var justifiedReason = item.optString("justifiedReason", "")
                    val autoJustifyInEvents = item.optBoolean("autoJustifyInEvents", false)
                    if (autoJustifyInEvents) {
                        isJustified = true
                        justifiedReason = justifiedReason.ifBlank { "Auto Justified" } ?: "Auto Justified"
                    }
                    val rawDate = item.optString("eventDate", "")
                    val formattedDate = try {
                        LocalDate.parse(rawDate, inputFormatter).format(outputFormatter)
                    } catch (_: Exception) {
                        rawDate
                    }

                    val type = item.optString("eventType", "לא ידוע")
                    val eventInfo = JSONObject()
                        .put("type", type)
                        .put("date", formattedDate)
                        .put("subject", item.optString("subjectName", ""))
                        .put("teacher", item.optString("teacherName", ""))
                        .put("enableJustified", item.optBoolean("enableJustified", true))
                        .put("isJustified", isJustified)
                        .put("justifiedReason", justifiedReason)
                        .put("remark", item.optString("remark").takeIf { it.isNotBlank() && it.lowercase() != "null" } ?: "") //notes

                    if (!eventsByType.has(type)) {
                        eventsByType.put(type, JSONArray())
                    }
                    eventsByType.getJSONArray(type).put(eventInfo)
                }

                result.put("events", eventsByType)
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch discipline events", e)
        }
        return result
    }
    @Throws(JSONException::class, IOException::class)
    override fun getAttendanceEvents(year: Int, period: String): JSONObject {
        if (period !in listOf("a", "b", "ab")) {
            throw IllegalArgumentException("Period must be a, b, or ab")
        }

        val periodId = when (period) {
            "a" -> 1103
            "b" -> 1102
            "ab" -> 0
            else -> throw IllegalArgumentException("Invalid period")
        }

        val result = JSONObject()

        try {
            val requestJson = JSONObject()
                .put("studentID", studentId)
                .put("moduleID", 4)
                .put("periodID", periodId)
                .put("studyYear", year)

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/PupilCard/GetPupilDiciplineEvents")
                .addHeader("Cookie", _cookies ?: "")
                .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("WebtopPlatform", "Failed to fetch discipline events: ${response.code}")
                    return result
                }

                val body = response.body.string()
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: return result
                val eventsArray = data.optJSONArray("diciplineEvents") ?: JSONArray()

                // Transform into grouped events by type
                val eventsByType = JSONObject()
                for (i in 0 until eventsArray.length()) {
                    val item = eventsArray.getJSONObject(i)
                    var isJustified = item.optBoolean("isJustified", false)
                    var justifiedReason = item.optString("justifiedReason", "")
                    val autoJustifyInEvents = item.optBoolean("autoJustifyInEvents", false)
                    if (autoJustifyInEvents) {
                        isJustified = true
                        justifiedReason = justifiedReason.ifBlank { "Auto Justified" } ?: "Auto Justified"
                    }
                    val rawDate = item.optString("eventDate", "")
                    val formattedDate = try {
                        LocalDate.parse(rawDate, inputFormatter).format(outputFormatter)
                    } catch (_: Exception) {
                        rawDate
                    }

                    val type = item.optString("eventType", "לא ידוע")
                    val eventInfo = JSONObject()
                        .put("type", type)
                        .put("date", formattedDate)
                        .put("subject", item.optString("subjectName", ""))
                        .put("teacher", item.optString("teacherName", ""))
                        .put("enableJustified", item.optBoolean("enableJustified", true))
                        .put("isJustified", isJustified)
                        .put("justifiedReason", justifiedReason)
                        .put("remark", item.optString("remark").takeIf { it.isNotBlank() && it.lowercase() != "null" } ?: "") //notes

                    if (!eventsByType.has(type)) {
                        eventsByType.put(type, JSONArray())
                    }
                    eventsByType.getJSONArray(type).put(eventInfo)
                }

                result.put("events", eventsByType)
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch discipline events", e)
        }

        return result
    }
    override fun isEditing(): Boolean = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun setName(name: String) { this.studentName = name }
    override fun setUsername(username: String) { this.username = username }
    override fun setPassword(password: String) { this.password = password }
    override fun getInfo(): JSONObject {
        return JSONObject()
            .put("name", "Webtop")
            .put("supportsEndpoints", JSONArray(listOf("grades", "schedule", "disciplineEvents")))
            .put("loginVariables", JSONArray(listOf("username", "password")))
            .put("supportsSchedule", true)
            .put("supportsGrades", true)
            .put("supportsAttendance", true)
            .put("scheduleSelection", studentClass)
            .put("supportsOriginalSchedule", true)
            .put("supportsScheduleSelection", true)
            .put("scheduleKind", "weekly")
    }


    override fun getLoginFields(): LoginFields = loginFields

    companion object : Platform.Companion {

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        override fun fromJson(obj: JSONObject): WebtopPlatform {
            val p = WebtopPlatform()
            p.id = obj.optString("id", "").ifEmpty { generateId() }
            p.setUsername(obj.optString("username", ""))
            p.setPassword(obj.optString("password", ""))
            p.studentName = obj.optString("name", "").ifEmpty { null }
            p.studentInstitution = obj.optString("institution", "").ifEmpty { null }
            p.studentId = obj.optString("studentId", "").ifEmpty { null }
            p.studentClass = obj.optString("classCode", "").ifEmpty { null }
            p._cookies = obj.optString("cookies", "").ifEmpty { null }
            p.loggedIn = obj.optBoolean("loggedIn", false)
            p.platformDisplayName = obj.optString("platformDisplayName")
            p.userStudentId = obj.optString("userStudentId", "").ifEmpty { null }
            p.userType = if (obj.has("userType") && !obj.isNull("userType")) obj.optInt("userType") else null
            p.schoolName = obj.optString("schoolName", "").ifEmpty { null }
            p.loginMethod = obj.optString("loginMethod", "").ifEmpty { null }

            Log.d("WebtopPlatform", "fromJson: ${p.username} ${p.password}")
            Log.d("WebtopPlatform", "fromJson obj: $obj")

            // Always guarantee one tab
            val course = JSONObject()
                .put("name", "Webtop")
                .put("courseKey", "Webtop")
                .put("platformId", p.id)
                .put("index", 0)
                .put("semester", getCurrentSemester())
                .put("semesterPicker", true)
                .put("year", Year.now().value)

            // Restore grades if present
            val coursesArray = obj.optJSONArray("courses")
            if (coursesArray != null && coursesArray.length() > 0) {
                val saved = coursesArray.optJSONObject(0)
                if (saved != null && saved.has("grades")) {
                    course.put("grades", saved.getJSONArray("grades"))
                }
            }

            p._courses.clear()
            p._courses.add(course)

            return p
        }

        fun getCurrentSemester(): String {
            val month = Calendar.getInstance().get(Calendar.MONTH)
            return if (month >= Calendar.SEPTEMBER || month <= Calendar.JANUARY) "a" else "b"
        }

        @JvmStatic
        fun loginWithMoe(key: String, user: String, pass: String): WebtopPlatform {
            val p = WebtopPlatform()
            val payload = JSONObject()
                .put("rememberMe", false)
                .put("key", key)
                .put("UniqueId", java.util.UUID.randomUUID().toString())
                .put("deviceDataJson", "{\"isMobile\":false,\"isTablet\":false,\"isDesktop\":true}")

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/user/LoginMoe")
                .header("Origin", "https://webtop.smartschool.co.il")
                .header("Referer", "https://webtop.smartschool.co.il/")
                .header("language", "he")
                .header("rememberMe", "0")
                .header("X-XSRF-TOKEN", "")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            p._client.newCall(request).execute().use { response ->
                val body = response.body.string()
                val jsonResponse = JSONObject(body)
                if (!jsonResponse.optBoolean("status", false)) {
                    throw IOException("Webtop rejected the MOE sign-in")
                }
                val data = jsonResponse.optJSONObject("data") ?: throw IOException("No data in Webtop response")
                p.studentId = data.optString("userId")
                p.studentClass = "${data.optString("classCode")}|${data.opt("classNumber")}"
                p.studentInstitution = data.optString("institutionCode")
                p.studentName = "${data.optString("firstName")} ${data.optString("lastName")}".trim()
                p.userStudentId = data.optString("studentId").ifEmpty { null }
                p.userType = if (data.has("userType") && !data.isNull("userType")) data.optInt("userType") else null
                p.schoolName = data.optString("institutionName").ifEmpty { null }
                p.setUsername(user)
                p.setPassword(pass)
                p.loginMethod = "moe"
                p._cookies = response.headers("Set-Cookie").joinToString("; ")
                p.loggedIn = true
            }
            return p
        }

        override fun checkCredentials(loginFields: LoginFields): Boolean {
            return try {
                val p = WebtopPlatform(loginFields)
                p.isLoggedIn()
            } catch (e: Exception) {
                Log.e("WebtopPlatform", "checkCredentials failed", e)
                false
            }
        }
    }

    @Throws(JSONException::class, IOException::class)
    override fun getMessages(page: Int): JSONArray {
        val result = JSONArray()

        try {
            val payload = JSONObject()
                .put("PageId", page)
                .put("LabelId", 0)
                .put("HasRead", JSONObject.NULL)
                .put("SearchQuery", "")

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/messageBox/GetMessagesInbox")
                .addHeader("Cookie", _cookies ?: "")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.code == 401 || response.code == 403) {
                    if (refreshCookies()) {
                        return getMessages(page) // retry once
                    }
                    return result
                }

                if (!response.isSuccessful || body.isEmpty()) return result

                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return result

                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val message = JSONObject()
                        .put("subject", item.optString("subject"))
                        .put("from", "${item.optString("student_F_name", "")} ${item.optString("student_L_name", "")}".trim())
                        .put("date", item.optString("sendingDate"))
                        .put("hasRead", item.optInt("hasRead", 0))
                        .put("filesAttached", item.optInt("filesWereAttached", 0) == 1)
                        .put("messageId", item.optString("messageId"))

                    result.put(message)
                }
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch inbox messages", e)
        }

        return result
    }

    @Throws(JSONException::class, IOException::class)
    override fun getMessageDetails(messageId: String): JSONObject {
        val result = JSONObject()

        try {
            val payload = JSONObject()
                .put("MessageId", messageId)
                .put("FilterId", 0)
                .put("IsInbox", true)
                .put("hasRead", JSONObject.NULL)

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/messageBox/GetMessagesInboxData")
                .addHeader("Cookie", _cookies ?: "")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.code == 401 || response.code == 403) {
                    if (refreshCookies()) {
                        return getMessageDetails(messageId)
                    }
                    return result
                }

                if (!response.isSuccessful || body.isEmpty()) return result

                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: return result
                val messageData = data.optJSONObject("messageData") ?: return result

                // Extract key info
                val content = messageData.optString("messageContent", "")
                val subject = messageData.optString("subject", "")
                val sender = "${messageData.optString("privateName", "")} ${messageData.optString("lastName", "")}".trim()
                val sentAt = messageData.optString("sendingDate", "")

                val attachments = JSONArray()
                val filesList = messageData.optJSONArray("filesList") ?: JSONArray()
                for (i in 0 until filesList.length()) {
                    val fileObj = filesList.getJSONObject(i)
                    attachments.put(
                        JSONObject()
                            .put("name", fileObj.optString("fileName"))
                            .put("url", fileObj.optString("fileUrl"))
                            .put("size", fileObj.optJSONObject("fileSize")?.optDouble("size"))
                            .put("sizeUnit", fileObj.optJSONObject("fileSize")?.optString("sizeName"))
                    )
                }

                result.put("subject", subject)
                    .put("from", sender)
                    .put("date", sentAt)
                    .put("contentHtml", content)
                    .put("attachments", attachments)
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch message details", e)
        }

        return result
    }
    override suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean {
        val url = attachment.optString("url", "")
        val name = attachment.optString("name", "attachment.bin")
        if (url.isEmpty()) return false

        return try {
            val request = Request.Builder()
                .url(url)
                // add any headers or cookies if needed:
                // .addHeader("Authorization", "Bearer $token")
                .build()

            _client.newCall(request).execute().use { response ->

                if (!response.isSuccessful) {
                    Log.e("Download", "Failed: ${response.code}")
                    return false
                }

                val body = response.body
                val bytes = body.bytes()
                if (bytes.isEmpty()) return false

                // Save to public Downloads folder
                val downloadsDir = Environment
                    .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()

                val targetFile = java.io.File(downloadsDir, name)
                targetFile.outputStream().use { it.write(bytes) }

                // Detect MIME type
                val mimeType = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(targetFile.extension.lowercase())
                    ?: "application/octet-stream"

                // Open file securely
                withContext(Dispatchers.Main) {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            context.packageName + ".provider",
                            targetFile
                        )

                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        context.startActivity(
                            createChooser(intent, "פתח באמצעות")
                        )

                    } catch (e: Exception) {
                        e.printStackTrace()
                        android.widget.Toast.makeText(
                            context,
                            "הקובץ נשמר ב-${targetFile.absolutePath}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }

                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


}
