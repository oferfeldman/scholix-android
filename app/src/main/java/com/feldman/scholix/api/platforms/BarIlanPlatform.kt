package com.feldman.app.api

import android.content.Context
import android.util.Log
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.UnsafeOkHttpClient
import com.feldman.scholix.api.Type
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.*

class BarIlanPlatform() : Platform {
    override var platformDisplayName: String = "BarIlanPlatform"

    private val loginFields = LoginFields()
        .addField(
            id = "id",
            type = Type.Id,
            getter = { it.getUsername() },
            setter = { platform, value -> platform.setUsername(value ?: "") }
        )
        .addField(
            id = "password",
            type = Type.Password,
            getter = { it.getPassword() },
            setter = { platform, value -> platform.setPassword(value ?: "") }
        )

    var displayName: String? = null
    var _studentId: String? = null
    var _password: String? = null
    var token: String? = null
    var _username: String? = null

    private val courses: ArrayList<JSONObject> = ArrayList()
    private val client: OkHttpClient = OkHttpClient()

    private var grades: JSONArray? = null
    override var loggedIn: Boolean = false
    override var editing: Boolean = false
    override var id: String = generateId()
        private set

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = false
    override val supportsAttendance: Boolean = false

    init {
        if (id.isBlank()) {
            id = generateId()
        }
    }



    constructor(loginFields: LoginFields) : this() {
        Log.d("BarIlanPlatform", "BarIlanPlatform constructor called")
        this._studentId = loginFields.getValueByType(Type.Id)
        this._password = loginFields.getValueByType(Type.Password)

        val loginJson = JSONObject(
            client.newCall(
                        Request.Builder()
                            .url("https://biumath.michlol4.co.il/api/Login/Login")
                            .post(
                                JSONObject()
                                    .put("captchaToken", JSONObject.NULL)
                                    .put("loginType", "student")
                                    .put("password", _password)
                                    .put("zht", _studentId)
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

    fun getGrades(): JSONArray = getGrades("all")


    //year and semester will always be ignored
    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        return getGrades(course)
    }

    fun getGrades(course: String): JSONArray {
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
    override fun getSubjectList(): List<String> {
        val subjects = mutableListOf<String>()

        try {
            for (course in courses) {
                val name = course.optString("name", "")
                if (name.isNotBlank() && !subjects.contains(name)) {
                    subjects.add(name)
                }
            }
        } catch (e: Exception) {
            Log.e("BarIlanPlatform", "Failed to build subject list", e)
        }

        return subjects.sorted()
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


    // Bar-Ilan publishes no attendance data through this API. It used to return
    // two invented events, which read as real ones -- better to report nothing.
    override fun getAttendanceEvents(period: String): JSONObject = JSONObject()

    override fun getAttendanceEvents(year: Int, period: String): JSONObject =
        getAttendanceEvents(period)

    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = JSONObject()
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = getSchedule(dayIndex, 5374, "")

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
            .put("id", id)
            .put("name", displayName)
            .put("studentId", _studentId)
            .put("password", _password)
            .put("token", token)
            .put("loggedIn", loggedIn)
            .put("grades", grades)
            .put("platformDisplayName", platformDisplayName)

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


    override fun getInfo(): JSONObject {
        return JSONObject()
            .put("name", "Webtop")
            .put("supportsEndpoints", JSONArray(listOf("grades", "schedule", "disciplineEvents")))
            .put("loginVariables", JSONArray(listOf("username", "password")))
            .put("supportsSchedule", true)
            .put("supportsGrades", true)
            .put("supportsAttendance", false)
    }
    companion object : Platform.Companion {

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        override fun fromJson(obj: JSONObject): Platform {
            val p = BarIlanPlatform()
            p.id = obj.optString("id", "").ifEmpty { generateId() }
            p.displayName = obj.optString("name", p.displayName)
            p.token = obj.optString("token")
            p.loggedIn = obj.optBoolean("loggedIn", false)
            p._studentId = obj.optString("studentId")
            p._password = obj.optString("password")
            p._username = obj.optString("studentId")
            p.grades = obj.optJSONArray("grades")
            p.platformDisplayName = obj.optString("platformDisplayName")

            p.courses.clear()
            obj.optJSONArray("courses")?.let { arr ->
                for (i in 0 until arr.length()) {
                    p.courses.add(arr.getJSONObject(i))
                }
            }

            return p
        }
    }


    @Throws(JSONException::class, IOException::class)
    // No messages endpoint is implemented for Bar-Ilan. These used to return
    // invented messages, an invented body, and a placeholder file written into
    // the user's Downloads folder.
    override fun getMessages(page: Int): JSONArray = JSONArray()

    override fun getMessageDetails(messageId: String): JSONObject = JSONObject()

    override suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean = false

    override fun getLoginFields(): LoginFields = loginFields
}
