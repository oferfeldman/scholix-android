package com.feldman.scholix.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WebtopPlatform() : Platform {

    override var displayName: String? = null
    private var _institution: String? = null
    private var _studentId: String? = null
    private var _classCode: String? = null
    override var _username: String? = null
    override var _password: String? = null
    private var _cookies: String? = null

    private val _client: OkHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient()
    override var editing: Boolean = false
    override var loggedIn: Boolean = false

    /** Only one course is ever stored */
    private val _courses: ArrayList<JSONObject> = ArrayList()

    constructor(username: String, password: String) : this() {
        if (login(username, password)) {
            _username = username
            _password = password
            loggedIn = true

            // Add single course entry (grades fetched lazily)
            _courses.add(
                JSONObject()
                    .put("name", "Webtop")
                    .put("index", 0)
                    .put("semester", getCurrentSemester())
                    .put("semesterPicker", true)
                    .put("year", java.time.Year.now().value)
            )
        }
    }

    /** Perform login once, keep cookies */
    private fun login(username: String, password: String): Boolean {
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

                _studentId = data.getString("userId")
                _classCode = data.getString("classCode") + "|" + data.get("classNumber")
                _institution = data.getString("institutionCode")
                displayName = "${data.getString("firstName")} ${data.getString("lastName")}"
                _cookies = response.headers("Set-Cookie").joinToString("; ")
                true
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Login exception", e)
            false
        }
    }

    override fun getName(): String = displayName ?: ""
    override fun getUsername(): String = _username ?: ""
    override fun getPassword(): String = _password ?: ""

    fun getStudentId(): String? = _studentId
    fun getInstitution(): String? = _institution

    override fun toString(): String =
        "WebtopPlatform(name=$displayName, institution=$_institution, loggedIn=$loggedIn)"

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
                    .put("index", 0)
                    .put("semester", getCurrentSemester())
                    .put("semesterPicker", true)
                    .put("year", java.time.Year.now().value+1)
            )
        }

        // Attach grades if not already present
        val course = _courses[0]
        if (!course.has("grades")) {
            course.put("grades", getGrades())
        }
        return _courses
    }

    override fun getGrades(): JSONArray {
        val currentYear = java.time.Year.now().value
        val semester = getCurrentSemester()
        val year = if (semester.equals("A", ignoreCase = true)) {
            currentYear + 1
        } else {
            currentYear
        }
        return getGrades(year, semester)
    }
    override fun getGrades(course: String): JSONArray = getGrades()

    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, semester: String): JSONArray {
        val currentYear = java.time.Year.now().value
        val year = if (semester.equals("a", ignoreCase = true)) {
            currentYear + 1
        } else {
            currentYear
        }
        return getGrades(year, semester.lowercase())
    }

    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, year: Int, semester: String): JSONArray {
        return getGrades(year, semester.lowercase())
    }

    private fun getGrades(year: Int, semester: String): JSONArray {

        val grades = JSONArray()
        if (_studentId == null) return grades

        try {
            val periodId = when (semester) {
                "a" -> 1103
                "b" -> 1102
                "ab" -> 0
                else -> throw IllegalArgumentException("Invalid semester")
            }

            val requestJson = JSONObject()
                .put("studyYear", year)
                .put("moduleID", 1)
                .put("periodID", periodId)
                .put("studentID", _studentId)

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/PupilCard/GetPupilGrades")
                .addHeader("Cookie", _cookies ?: "")
                .post(requestJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return grades
                val data = JSONObject(response.body.string()).optJSONArray("data") ?: return grades
                for (i in 0 until data.length()) {
                    val g = data.getJSONObject(i)
                    grades.put(
                        JSONObject()
                            .put("subject", g.optString("subject", "Unknown Subject"))
                            .put("name", g.optString("title", "Untitled"))
                            .put("grade", g.optString("grade", "N/A"))
                            .put("date", g.optString("date", "N/A"))
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch grades", e)
        }

        Log.w("Webtop", "grades loaded")
        return grades
    }

    override fun getSchedule(dayIndex: Int): JSONObject {
        val schedule = JSONObject()
        if (dayIndex < 0) return schedule

        try {
            // Prepare payload
            val payload = JSONObject()
                .put("institutionCode", _institution)
                .put("selectedValue", _classCode)
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
                val body = response.body?.string().orEmpty()
                val days = JSONObject(body).getJSONArray("data")
                if (dayIndex >= days.length()) return schedule

                val day = days.getJSONObject(dayIndex)
                val hoursRaw = day.getJSONArray("hoursData")

                // --- Build original schedule first ---
                val hoursOriginal = JSONObject()
                for (i in 0 until hoursRaw.length()) {
                    val hour = hoursRaw.getJSONObject(i)
                    if (hour.has("scheduale") && hour.getJSONArray("scheduale").length() > 0) {
                        processScheduleOriginal(hour, hoursOriginal)
                    }
                }

                // --- Now build updated schedule ---
                val hours = JSONObject()
                for (i in 0 until hoursRaw.length()) {
                    val hour = hoursRaw.getJSONObject(i)
                    if (hour.has("scheduale") && hour.getJSONArray("scheduale").length() > 0) {
                        // Pass original reference here
                        processScheduleUpdated(hour, hours, hoursOriginal)
                    }
                }

                println("hours: $hours")
                return hours
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch schedule", e)
        }
        return JSONObject()
    }

    @Throws(Exception::class)
    private fun processScheduleOriginal(hourRaw: JSONObject, hours: JSONObject) {
        val scheduleArray = hourRaw.getJSONArray("scheduale")
        val scheduleItem = scheduleArray.getJSONObject(0)

        val subject = cleanSubject(scheduleItem.optString("subject", "לא זמין"))
        val teacher = scheduleItem.optString("teacherPrivateName", "לא זמין") + " " +
                scheduleItem.optString("teacherLastName", "לא זמין")
        val hourNum = scheduleItem.optInt("hour", -1)
        val colorClass = findColorClass(subject)

        val hour = JSONObject()
            .put("num", hourNum)
            .put("subject", subject)
            .put("teacher", teacher)
            .put("colorClass", colorClass)
            .put("changes", "")
            .put("exams", "")
        hours.put(hourNum.toString(), hour)
    }

    @Throws(Exception::class)
    private fun processScheduleUpdated(hourRaw: JSONObject, hours: JSONObject, hoursOriginal: JSONObject) {
        val scheduleArray = hourRaw.getJSONArray("scheduale")
        val scheduleItem = scheduleArray.getJSONObject(0)

        var subject = cleanSubject(scheduleItem.optString("subject", "לא זמין"))
        var teacher = scheduleItem.optString("teacherPrivateName", "לא זמין") + " " +
                scheduleItem.optString("teacherLastName", "לא זמין")
        val hourNum = scheduleItem.optInt("hour", -1)
        var colorClass = findColorClass(subject)

        val hour = JSONObject()
            .put("num", hourNum)
            .put("subject", subject)
            .put("teacher", teacher)
            .put("colorClass", colorClass)
            .put("changes", "")
            .put("exams", "")

        // --- Exams handling ---
        if (hourRaw.has("exams")) {
            val examsArray = hourRaw.getJSONArray("exams")
            for (j in 0 until examsArray.length()) {
                val examObj = examsArray.getJSONObject(j)
                subject = examObj.optString("title", "מבחן")
                teacher = examObj.optString("supervisors", "לא זמין")
                colorClass = "exam-cell"
                hour.put("exams", examObj)
            }
        }

        // --- Changes handling ---
        val changesArray = scheduleItem.optJSONArray("changes") ?: JSONArray()
        var cancel = false

        for (j in 0 until changesArray.length()) {
            val itemObj = changesArray.getJSONObject(j)

            // ביטול שיעור
            if (itemObj.optString("definition", "לא זמין") == "ביטול שיעור" &&
                (itemObj.optInt("original_hour", -1) == -1 || itemObj.optInt("original_hour", -1) == hourNum)) {
                cancel = true
            }

            // original_hour reference
            if (itemObj.optInt("original_hour", -1) != -1) {
                val originalHour = hoursOriginal.getJSONObject(itemObj.optString("original_hour", "0"))
                cancel = true
            }

            // הזזת שיעור
            if (itemObj.optString("definition", "לא זמין") == "הזזת שיעור") {
                val fillTeacher = itemObj.optString("privateName", "לא זמין") + " " +
                        itemObj.optString("lastName", "לא זמין")

                var found = false
                for (i in 0 until hoursOriginal.length()) {
                    val existing = hoursOriginal.getJSONObject((i).toString())
                    if (existing.getString("teacher") == fillTeacher) {
                        hour.put("subject", existing.getString("subject"))
                        hour.put("teacher", existing.getString("teacher"))
                        hour.put("colorClass", existing.getString("colorClass"))
                        found = true
                        break
                    }
                }
                if (!found) {
                    val changes = hour.optString("changes")
                    hour.put("changes", changes + "מילוי מקום של $fillTeacher\n")
                }
            }

            // מילוי מקום
            if (itemObj.optString("definition", "לא זמין") == "מילוי מקום") {
                val fillTeacher = itemObj.optString("privateName", "לא זמין") + " " +
                        itemObj.optString("lastName", "לא זמין")

                var found = false
                for (i in 0 until hoursOriginal.length()) {
                    val existing = hoursOriginal.getJSONObject((i).toString())
                    if (existing.getString("teacher") == fillTeacher) {
                        hour.put("subject", existing.getString("subject"))
                        hour.put("teacher", existing.getString("teacher"))
                        hour.put("colorClass", existing.getString("colorClass"))
                        found = true
                        break
                    }
                }
                if (!found) {
                    val changes = hour.optString("changes")
                    hour.put("changes", changes + "מילוי מקום של $fillTeacher\n")
                }
            }
        }

        // --- Events handling ---
        if (hourRaw.has("events") && hourRaw.getJSONArray("events").length() > 0) {
            val events = hourRaw.getJSONArray("events")
            val event = events.getJSONObject(0)
            val title = event.getString("title")
            val accompaniers = event.getString("accompaniers").replace(Regex(",\\s*$"), "")

            if (accompaniers != "," && accompaniers != " " && accompaniers.isNotEmpty()) {
                hour.put("teacher", accompaniers)
            }
            hour.put("subject", title)
            hour.put("changes", "")
        }

        // --- Only keep if not canceled ---
        if (!cancel) {
            hours.put(hourNum.toString(), hour)
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



    override fun getOriginalSchedule(dayIndex: Int): JSONObject {
        val schedule = JSONObject()
        if (dayIndex < 0) return schedule

        try {
            val payload = JSONObject()
                .put("institutionCode", _institution)
                .put("selectedValue", _classCode)
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
                val body = response.body?.string().orEmpty()
                val days = JSONObject(body).getJSONArray("data")
                if (dayIndex >= days.length()) return schedule

                val day = days.getJSONObject(dayIndex)
                val hoursRaw = day.getJSONArray("hoursData")

                val hoursOriginal = JSONObject()
                for (i in 0 until hoursRaw.length()) {
                    val hour = hoursRaw.getJSONObject(i)
                    if (hour.has("scheduale") && hour.getJSONArray("scheduale").length() > 0) {
                        processScheduleOriginal(hour, hoursOriginal)
                    }
                }
                return hoursOriginal
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch original schedule", e)
        }
        return JSONObject()
    }

    override fun getScheduleIndexes(): JSONArray = JSONArray()

    override fun isLoggedIn(): Boolean = loggedIn

    override fun refreshCookies(): Boolean {
        return try {
            val loginData = JSONObject()
                .put("Data", encrypt(_username + "0"))
                .put("username", _username)
                .put("Password", _password)
                .put("deviceDataJson", "{\"isMobile\":true,\"os\":\"Android\",\"browser\":\"Chrome\",\"cookies\":true}")

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/user/LoginByUserNameAndPassword")
                .post(loginData.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            _client.newCall(request).execute().use { response ->
                val jsonResponse = JSONObject(response.body.string())
                val data = jsonResponse.optJSONObject("data") ?: return false
                _studentId = data.getString("userId")
                _classCode = data.getString("classCode") + "|" + data.get("classNumber")
                _institution = data.getString("institutionCode")
                _cookies = response.headers("Set-Cookie").joinToString("; ")
                loggedIn = true
                true
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to refresh cookies", e)
            false
        }
    }
    private val inputFormatter = DateTimeFormatter.ofPattern("yyyy-M-d['T'HH:mm:ss]")
    private val outputFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    override fun toJson(): JSONObject {
        val course = if (_courses.isNotEmpty()) _courses[0] else null
        return JSONObject()
            .put("class", javaClass.name)
            .put("name", displayName)
            .put("institution", _institution)
            .put("studentId", _studentId)
            .put("classCode", _classCode)
            .put("username", _username)
            .put("password", _password)
            .put("cookies", _cookies)
            .put("loggedIn", loggedIn)
            .put("courses", JSONArray().apply { course?.let { put(it) } })
    }
    @Throws(JSONException::class, IOException::class)
    override fun getDisciplineEvents(period: String): JSONObject {
        println(12)

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
                .put("studentID", _studentId)
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
                    } catch (e: Exception) {
                        rawDate // fallback to original if parsing fails
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
        println("attendance: $result")
        return result
    }
    @Throws(JSONException::class, IOException::class)
    override fun getDisciplineEvents(year: Int, period: String): JSONObject {
        if (period !in listOf("a", "b", "ab")) {
            throw IllegalArgumentException("Period must be a, b, or ab")
        }
        println(4)
        val periodId = when (period) {
            "a" -> 1103
            "b" -> 1102
            "ab" -> 0
            else -> throw IllegalArgumentException("Invalid period")
        }

        val result = JSONObject()

        try {
            val requestJson = JSONObject()
                .put("studentID", _studentId)
                .put("moduleID", 4)
                .put("periodID", periodId)
                .put("studyYear", year)

            println(requestJson )
            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/PupilCard/GetPupilDiciplineEvents")
                .addHeader("Cookie", _cookies ?: "")
                .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            println(request)
            _client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("WebtopPlatform", "Failed to fetch discipline events: ${response.code}")
                    return result
                }

                val body = response.body.string()
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: return result
                val eventsArray = data.optJSONArray("diciplineEvents") ?: JSONArray()

                println(body)
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
                    } catch (e: Exception) {
                        rawDate // fallback to original if parsing fails
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

                println(7)
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
    override fun setName(name: String) { this.displayName = name }
    override fun setUsername(username: String) { this._username = username }
    override fun setPassword(password: String) { this._password = password }

    companion object {
        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        fun fromJson(obj: JSONObject): WebtopPlatform {
            val p = WebtopPlatform()
            p._username = obj.optString("username", "")
            p._password = obj.optString("password", "")
            p.displayName = obj.optString("name", "").ifEmpty { null }
            p._institution = obj.optString("institution", "").ifEmpty { null }
            p._studentId = obj.optString("studentId", "").ifEmpty { null }
            p._classCode = obj.optString("classCode", "").ifEmpty { null }
            p._cookies = obj.optString("cookies", "").ifEmpty { null }
            p.loggedIn = obj.optBoolean("loggedIn", false)

            // Always guarantee one tab
            val course = JSONObject()
                .put("name", "Webtop")
                .put("index", 0)
                .put("semester", getCurrentSemester())
                .put("semesterPicker", true)
                .put("year", java.time.Year.now().value)

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
    }
}
