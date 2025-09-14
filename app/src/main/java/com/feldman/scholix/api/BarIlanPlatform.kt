package com.feldman.app.api

import android.util.Log
import com.feldman.scholix.api.Platform
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.*

class BarIlanPlatform() : Platform {

    override var displayName: String? = null
    var _studentId: String? = null
    override var _password: String? = null
    var token: String? = null
    override var _username: String? = null

    private val courses: ArrayList<JSONObject> = ArrayList()
    private val client: OkHttpClient = OkHttpClient()

    private var grades: JSONArray? = null
    override var loggedIn: Boolean = false
    override var editing: Boolean = false

    constructor(studentId: String, password: String) : this() {
        this._studentId = studentId
        this._password = password
        this._username = studentId

        val loginJson = JSONObject(
            client.newCall(
                        Request.Builder()
                            .url("https://biumath.michlol4.co.il/api/Login/Login")
                            .post(
                                JSONObject()
                                    .put("captchaToken", JSONObject.NULL)
                                    .put("loginType", "student")
                                    .put("password", password)
                                    .put("zht", studentId)
                                    .put("deviceDataJson", "{\"isMobile\":true,\"os\":\"Android\",\"browser\":\"Chrome\",\"cookies\":true}")
                                    .toString()
                                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                            )
                            .build()
                    ).execute().body.string()
        )

        if (!loginJson.optBoolean("success", false)) {
            loggedIn = false
            return
        }
        token = loginJson.getString("token")

        val infoJson = JSONObject(
            client.newCall(
                        Request.Builder()
                            .url("https://biumath.michlol4.co.il/api/Account/UserInfo")
                            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .header("Authorization", "Bearer $token")
                            .build()
                    ).execute().body.string()
        )
        val user = infoJson.getJSONObject("userInfo")
        displayName = "${user.getString("smp")} ${user.getString("smm")}"

        val coursesJson = JSONObject(
            client.newCall(
                        Request.Builder()
                            .url("https://biumath.michlol4.co.il/api/StudentCourses/Data")
                            .post(
                                JSONObject().put("urlParameters", JSONObject()).toString()
                                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                            )
                            .header("Authorization", "Bearer $token")
                            .build()
                    ).execute().body.string()
        )

        val data = coursesJson.getJSONObject("courses").getJSONArray("clientData")
        for (i in 0 until data.length()) {
            val src = data.getJSONObject(i)
            val parts = src.getString("all_pms_shm").trim().split("\\s+".toRegex()).toMutableList()
            parts.reverse()
            courses.add(
                JSONObject()
                    .put("name", src.getString("krs_shm"))
                    .put("year", src.getString("krs_snl"))
                    .put("semester", src.getString("krs_sms_select"))
                    .put("teacher", parts.joinToString(" "))
            )
        }

        loggedIn = true

        // Load grades async
        Thread {
            try {
                grades = getGrades("all")
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }.start()
    }

    override fun getCourses(): ArrayList<JSONObject> = courses

    override fun getGrades(): JSONArray = getGrades("all")

    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, semester: String): JSONArray {
        return getGrades(course) // ignores semester
    }

    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, year: Int, semester: String): JSONArray {
        return getGrades(course) // ignores semester
    }


    override fun getGrades(course: String): JSONArray {
        if (grades == null) {
            Log.w("BarIlanPlatform", "Grades were null, loading...")
            grades = getGrades1("all")
        }
        if (course == "all") return grades ?: JSONArray()

        val filtered = JSONArray()
        for (i in 0 until (grades?.length() ?: 0)) {
            val grade = grades!!.getJSONObject(i)

            val subject = grade.optString("subject")
            if (subject.equals(course, ignoreCase = true) ||
                subject.contains(course, ignoreCase = true) ||
                course.contains(subject, ignoreCase = true)) {
                filtered.put(grade)
            }
        }
        return filtered
    }

    fun getGrades1(course: String): JSONArray {
        val loginData = JSONObject().put("urlParameters", JSONArray())

        val request = Request.Builder()
            .url("https://biumath.michlol4.co.il/api/Grades/Data")
            .post(loginData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            val pageData = response.header("pagedata")
            val responseBody = response.body.string()
            val jsonResponse = JSONObject(responseBody)

            val courses = jsonResponse.getJSONObject("collapsedCourses").getJSONArray("clientData")
            val grades = JSONArray()

            for (i in 0 until courses.length()) {
                val currentCourse = courses.getJSONObject(i)
                if (course == "all" || currentCourse.getString("krs_shm") == course) {
                    val gradesRaw = currentCourse.getJSONArray("__body")
                    for (m in 0 until gradesRaw.length()) {
                        val gradeRaw = gradesRaw.getJSONObject(m)
                        val grade = JSONObject()
                            .put("name", gradeRaw.getString("krs_shm"))
                            .put("subject", currentCourse.getString("krs_shm"))
                            .put("date", gradeRaw.getString("krs_snl"))
                            .put("grade", gradeRaw.getString("bhnzin"))

                        val submissions = JSONArray()
                        val data = gradeRaw.getJSONArray("__body")
                        for (j in 0 until data.length()) {
                            val moedRaw = data.getJSONObject(j)
                            val moed = JSONObject()
                                .put("type", moedRaw.getString("zin_sug"))
                                .put("grade", moedRaw.getString("moed_1_zin"))
                                .put("date", moedRaw.getString("krs_snl"))

                            val buttons = moedRaw.getJSONArray("__buttons")
                            val downloadData = JSONObject()
                            for (k in 0 until buttons.length()) {
                                val button = buttons.getJSONObject(k)
                                if (button.getString("description") == "בחינה סרוקה") {
                                    val routeData = button.getJSONObject("routeData")
                                    downloadData
                                        .put("scanLocation", routeData.getString("scan_location"))
                                        .put("scanFileName", routeData.getString("scanfilename"))
                                        .put("hash", routeData.getString("__hash"))
                                        .put("scanPt", routeData.getString("scan_pt"))
                                        .put("rowKey", routeData.getString("rowkey"))
                                        .put("pageData", pageData)
                                }
                            }
                            moed.put("downloadData", downloadData)
                            submissions.put(moed)
                        }
                        grade.put("submissions", submissions)
                        grades.put(grade)
                    }
                }
            }
            Log.d("BarIlanPlatform", "grades refreshed!")
            return grades
        }
    }


    override fun getDisciplineEvents(period: String): JSONObject {
        // Fake demo events for Bar Ilan
        val result = JSONObject()
        val eventsByType = JSONObject()

        val absence = JSONObject()
            .put("type", "חיסור")
            .put("date", "2025-09-01T00:00:00")
            .put("subject", "אלגברה לינארית")
            .put("teacher", "פרופ׳ כהן")
            .put("remark", "החסיר שיעור ללא הודעה")

        val positive = JSONObject()
            .put("type", "חיזוק חיובי")
            .put("date", "2025-09-05T00:00:00")
            .put("subject", "מבוא למדעי המחשב")
            .put("teacher", "ד״ר לוי")
            .put("remark", "תרם רבות לשיעור")

        eventsByType.put("חיסור", JSONArray().put(absence))
        eventsByType.put("חיזוק חיובי", JSONArray().put(positive))

        result.put("events", eventsByType)
        return result
    }

    override fun getDisciplineEvents(year: Int, period: String): JSONObject {
        return getDisciplineEvents(period)
    }
    override fun getSchedule(dayIndex: Int): JSONObject = JSONObject()
    override fun getOriginalSchedule(dayIndex: Int): JSONObject = getSchedule(dayIndex)
    override fun getScheduleIndexes(): JSONArray = JSONArray()
    override fun getName(): String = displayName ?: ""
    override fun getUsername(): String = _username ?: ""
    override fun getPassword(): String = _password ?: ""
    fun getStudentId(): String? = _studentId

    override fun isLoggedIn(): Boolean = loggedIn

    override fun refreshCookies(): Boolean {
        val loginData = JSONObject()
            .put("captchaToken", null)
            .put("loginType", "student")
            .put("password", this._password)
            .put("zht", this._studentId)
            .put("deviceDataJson", "{\"isMobile\":true,\"os\":\"Android\",\"browser\":\"Chrome\",\"cookies\":true}")

        val request = Request.Builder()
            .url("https://biumath.michlol4.co.il/api/Login/Login")
            .post(loginData.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.isNull("success") || !jsonResponse.getBoolean("success")) {
                Log.e("BarIlanPlatform", "Login error: ${jsonResponse.optString("errorDescription", "Unknown error")}")
                loggedIn = false
                return false
            }
            token = jsonResponse.getString("token")
            loggedIn = true
            grades = getGrades1("all")
            return true
        }
    }

    override fun toJson(): JSONObject {
        val root = JSONObject()
            .put("class", javaClass.name)
            .put("name", displayName)
            .put("studentId", _studentId)
            .put("password", _password)
            .put("token", token)
            .put("loggedIn", loggedIn)
            .put("grades", grades)

        val arr = JSONArray()
        for (course in courses) arr.put(course)
        root.put("courses", arr)

        return root
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
        fun fromJson(obj: JSONObject): Platform {
            val p = BarIlanPlatform()
            p.displayName = obj.optString("name", p.displayName ?: "name")
            p.token = obj.optString("token", p.token ?: "")
            p.loggedIn = obj.optBoolean("loggedIn", p.loggedIn)
            p._studentId = obj.optString("studentId", p._studentId ?: "001")
            p._password = obj.optString("password", p._password ?: "***REMOVED***")
            p._username = obj.optString("studentId", p._username ?: "username")
            p.grades = obj.optJSONArray("grades")

            p.courses.clear()
            obj.optJSONArray("courses")?.let { arr ->
                for (i in 0 until arr.length()) {
                    p.courses.add(arr.getJSONObject(i))
                }
            }
            return p
        }
    }
}
