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
import com.google.firebase.messaging.FirebaseMessaging
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
    private var studentName: String? = null
    private var studentId: String? = null
    private var studentClass: String? = null
    private var studentInstitution: String? = null

    private var _cookies: String? = null
    private val _client: OkHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient()
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
                    .put("index", 0)
                    .put("semester", getCurrentSemester())
                    .put("semesterPicker", true)
                    .put("year", Year.now().value)
            )
            
            // Register FCM token after successful login
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w("WebtopPlatform", "Fetching FCM registration token failed", task.exception)
                        return@addOnCompleteListener
                    }
                    
                    // Get new FCM registration token
                    val token = task.result
                    Log.d("WebtopPlatform", "Got FCM token after login: ${token?.take(30)}...")
                    
                    if (token != null) {
                        val success = registerFCMToken(token)
                        Log.d("WebtopPlatform", "FCM token registration after login: $success")
                    }
                }
            } catch (e: Exception) {
                Log.e("WebtopPlatform", "Error registering FCM token after login", e)
            }
        } else {
            Log.e("WebtopPlatform", "Missing username or password in LoginFields")
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

                studentId = data.getString("userId")
                studentClass = data.getString("classCode") + "|" + data.get("classNumber")
                studentInstitution = data.getString("institutionCode")
                studentName = "${data.getString("firstName")} ${data.getString("lastName")}"
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
        val classCode = selectedValue ?: studentClass

        val schedule = JSONObject()
        if (dayIndex < 0) return schedule

        try {
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
                val days = json.optJSONArray("data") ?: JSONArray()
                if (dayIndex >= days.length()) return schedule

                val day = days.getJSONObject(dayIndex)
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
                    val lessons = hour.optJSONArray("scheduale") ?: continue
                    if (lessons.length() > 0) {
                        processScheduleUpdated(hour, hours, hoursOriginal)
                    }
                }

                return hours
            }
        } catch (e: IOException) {
            Log.e("WebtopPlatform", "Server unreachable", e)
            return JSONObject().put("error", "server_unreachable")
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "Failed to fetch schedule", e)
            return JSONObject().put("error", "unknown_error")
        }
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

        // --- Exams handling ---
        if (hourRaw.has("exams")) {
            val examsArray = hourRaw.getJSONArray("exams")
            for (j in 0 until examsArray.length()) {
                val examObj = examsArray.getJSONObject(j)
                hour.put("exams", examObj.getString("title") ?: "idk")
            }
        }

        // --- Changes handling ---
        val changesArray = JSONArray().apply {
            val c1 = hourRaw.optJSONArray("changes")
            val c2 = scheduleItem.optJSONArray("changes")
            if (c1 != null) for (i in 0 until c1.length()) put(c1.getJSONObject(i))
            if (c2 != null) for (i in 0 until c2.length()) put(c2.getJSONObject(i))
        }
        var cancel = false

        for (j in 0 until changesArray.length()) {
            val itemObj = changesArray.getJSONObject(j)
            println(itemObj)
            println("def: ${itemObj.optString("definition")} ${itemObj.optBoolean("isAddition")} ${itemObj.optString("type")}")

            if (
                itemObj.optBoolean("isAddition") ||
                itemObj.optString("definition").contains("תוספת שיעור") ||
                itemObj.optString("type") == "תוספת שיעור"
            ) {
                val addTeacher = itemObj.optString("privateName", "") + " " +
                        itemObj.optString("lastName", "")

                var addSubject = "תוספת שיעור"
                for (key in hoursOriginal.keys()) {
                    val existing = hoursOriginal.getJSONObject(key)
                    if (existing.getString("teacher") == addTeacher) {
                        addSubject = existing.getString("subject")
                        break
                    }
                }

                hour.put("subject", addSubject)
                hour.put("teacher", addTeacher)
                hour.put("colorClass", "yellow-cell")
                hour.put("changes", "תוספת שיעור")
                hour.put("exams", "")

                hours.put(hourNum.toString(), hour)
                return
            }

            // ביטול שיעור
            if (itemObj.optString("definition", "לא זמין") == "ביטול שיעור" &&
                (itemObj.optInt("original_hour", -1) == -1 || itemObj.optInt("original_hour", -1) == hourNum)) {
                cancel = true
            }

            // original_hour reference
            if (itemObj.optInt("original_hour", -1) != -1) {
                cancel = true
            }

            // הזזת שיעור
            if (itemObj.optString("definition", "לא זמין") == "הזזת שיעור") {
                val fillTeacher = itemObj.optString("privateName", "לא זמין") + " " +
                        itemObj.optString("lastName", "לא זמין")

                var found = false
                for (key in hoursOriginal.keys()) {
                    val existing = hoursOriginal.getJSONObject(key)
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

                hour.put("teacher", fillTeacher)
                hour.put("subject", hour.getString("subject") + " / מילוי מקום")

                //בדיקה של הזזת שיעור
                var found = false
                for (key in hoursOriginal.keys()) {
                    val existing = hoursOriginal.getJSONObject(key)
                    if (existing.getString("teacher") == fillTeacher) {
                        hour.put("subject", existing.getString("subject"))
                        hour.put("teacher", existing.getString("teacher"))
                        hour.put("colorClass", existing.getString("colorClass"))
                        found = true
                        break
                    }
                }



                if (!found) {
                    hour.put("teacher", fillTeacher)
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



    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
        val schedule = JSONObject()
        if (dayIndex < 0) return schedule
        val institution = institutionCode ?: studentInstitution
        val classCode = selectedValue ?: studentClass

        try {
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
                _cookies = response.headers("Set-Cookie").joinToString("; ")
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
    }


    override fun getLoginFields(): LoginFields = loginFields

    /**
     * Register FCM token with the webtop server for push notifications
     */
    fun registerFCMToken(fcmToken: String): Boolean {
        if (!isLoggedIn() || _cookies.isNullOrEmpty()) {
            Log.w("WebtopPlatform", "Cannot register FCM token - not logged in or no cookies")
            return false
        }

        return try {
            val payload = JSONObject()
                .put("param1", "android")
                .put("param2", fcmToken)

            val request = Request.Builder()
                .url("https://webtopserver.smartschool.co.il/server/api/user/setRegistrationId")
                .addHeader("Cookie", _cookies ?: "")
                .addHeader("User-Agent", "Android-WebView/1.0")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            Log.d("WebtopPlatform", "Registering FCM token: ${fcmToken.take(30)}...")

            _client.newCall(request).execute().use { response ->
                val body = response.body.string()
                
                Log.d("WebtopPlatform", "FCM Registration Status: ${response.code}")
                Log.d("WebtopPlatform", "FCM Response: $body")

                if (!response.isSuccessful) {
                    Log.e("WebtopPlatform", "FCM registration failed: HTTP ${response.code}")
                    return false
                }

                if (body.isNotEmpty()) {
                    try {
                        val jsonResponse = JSONObject(body)
                        val success = jsonResponse.optBoolean("status", false)
                        if (success) {
                            Log.i("WebtopPlatform", "✅ FCM token registered successfully!")
                            return true
                        } else {
                            Log.w("WebtopPlatform", "❌ FCM token registration failed - server returned false")
                            return false
                        }
                    } catch (e: JSONException) {
                        Log.w("WebtopPlatform", "⚠️ Could not parse FCM response as JSON, assuming success")
                        return response.isSuccessful
                    }
                }

                return response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("WebtopPlatform", "❌ FCM registration error", e)
            false
        }
    }


    companion object : Platform.Companion {

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        override fun fromJson(obj: JSONObject): WebtopPlatform {
            val p = WebtopPlatform()
            p.id = obj.optString("id", "").ifEmpty { generateId() }
            p.username = obj.optString("username", "")
            p.password = obj.optString("password", "")
            p.studentName = obj.optString("name", "").ifEmpty { null }
            p.studentInstitution = obj.optString("institution", "").ifEmpty { null }
            p.studentId = obj.optString("studentId", "").ifEmpty { null }
            p.studentClass = obj.optString("classCode", "").ifEmpty { null }
            p._cookies = obj.optString("cookies", "").ifEmpty { null }
            p.loggedIn = obj.optBoolean("loggedIn", false)
            p.platformDisplayName = obj.optString("platformDisplayName")

            Log.d("WebtopPlatform", "fromJson: ${p.username} ${p.password}")
            Log.d("WebtopPlatform", "fromJson obj: $obj")

            // Always guarantee one tab
            val course = JSONObject()
                .put("name", "Webtop")
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
