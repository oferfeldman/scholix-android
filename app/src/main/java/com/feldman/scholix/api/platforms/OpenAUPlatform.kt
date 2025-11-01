package com.feldman.scholix.api.platforms

import android.util.Log
import com.feldman.scholix.api.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.nio.charset.Charset
import java.util.*

class OpenAUPlatform() : Platform {

    // region --- Login Fields ---
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
        .addField(
            id = "id",
            type = Type.Id,
            getter = { (it as? OpenAUPlatform)?._studentId },
            setter = { platform, value -> (platform as? OpenAUPlatform)?._studentId = value }
        )
    // endregion

    // region --- Internal Fields ---
    override var displayName: String? = null
    private var _studentId: String? = null
    override var _username: String? = null
    override var _password: String? = null
    private var _cookies: String? = null
    private val _client: OkHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient()
    override var editing: Boolean = false
    override var loggedIn: Boolean = false
    private val _courses: ArrayList<JSONObject> = ArrayList()
    override var id: String = WebtopPlatform.generateId()
        private set

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = false
    override val supportsAttendance: Boolean = false
    // endregion

    init {
        if (id.isBlank()) id = WebtopPlatform.generateId()
    }

    // region --- Constructors ---
    constructor(loginFields: LoginFields) : this() {
        val username = loginFields.getValueByType(Type.Username)
        val password = loginFields.getValueByType(Type.Password)
        val studentId = loginFields.getValueByType(Type.Id)

        Log.d("OpenAUPlatform", "Constructing OpenAUPlatform: $username / $studentId")

        if (username != null && password != null && studentId != null && login(username, password, studentId)) {
            _username = username
            _password = password
            _studentId = studentId
            loggedIn = true


        } else {
            Log.e("OpenAUPlatform", "Missing username, password, or ID in LoginFields")
        }
    }
    // endregion

    // region --- Login Logic ---
    private fun login(username: String, password: String, id: String): Boolean {
        Log.d("OpenAU", "Attempting login for $username ($id)")

        try {
            // Step 1: Initial GET to get cookies
            val loginPage =
                "https://sso.apps.openu.ac.il/login?T_PLACE=https://sheilta.apps.openu.ac.il/pls/mtl/student.first?v_kurs="

            val getReq = Request.Builder()
                .url(loginPage)
                .get()
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                )
                .build()

            _client.newCall(getReq).execute().use { resp0 ->
                if (!resp0.isSuccessful) {
                    Log.e("OpenAU", "Initial GET failed: ${resp0.code}")
                    return false
                }
                _cookies = resp0.headers("Set-Cookie").joinToString("; ")
                Log.d("OpenAU", "Initial cookies: $_cookies")
            }

            // Step 2: Login POST
            val loginUrl = "https://sso.apps.openu.ac.il/process"
            val formPayload =
                "p_user=$username&p_sisma=$password&p_mis_student=$id&T_PLACE=https://sheilta.apps.openu.ac.il/pls/mtl/student.first?v_kurs="

            val headers = Headers.Builder()
                .add(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                )
                .add("Referer", loginPage)
                .add("Content-Type", "application/x-www-form-urlencoded")
                .add("Cookie", _cookies ?: "")
                .build()

            val request = Request.Builder()
                .url(loginUrl)
                .headers(headers)
                .post(formPayload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            _client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("OpenAU", "Login failed: ${resp.code}")
                    return false
                }

                val newCookies = resp.headers("Set-Cookie").joinToString("; ")
                _cookies = listOfNotNull(_cookies, newCookies).joinToString("; ")

                displayName = "OpenU: $username"
                loggedIn = true
                Log.d("OpenAU", "Login success! User=$username")
                return true
            }

        } catch (e: Exception) {
            Log.e("OpenAU", "Login exception", e)
            return false
        }
    }
    // endregion

    // region --- Courses & Grades ---
    override fun getCourses(): ArrayList<JSONObject> {
        Log.d("OpenAU", "Fetching courses")

        if (_courses.isEmpty()) {
            val courses = fetchCourses()
            Log.d("OpenAU", "update courses: $courses")

            for (c in courses) {
                _courses.add(JSONObject().put("name", c))
            }
        }
        println(_courses)
        Log.d("OpenAU", "getCourses: $_courses")
        return _courses
    }

    override fun getSubjectList(): List<String> = fetchCourses()

    private fun fetchCourses(): List<String> {
        val result = mutableListOf<String>()

        // --- Ensure valid session ---
//        if (!loggedIn || _cookies.isNullOrBlank()) {
//            Log.w("OpenAU", "Not logged in or cookies missing. Attempting re-login...")
//            if (!refreshCookies()) {
//                Log.e("OpenAU", "Re-login failed. Cannot fetch courses.")
//                return result
//            }
//            Log.d("OpenAU", "Re-login succeeded.")
//        }
        refreshCookies()
        val firstUrl = "https://sheilta.apps.openu.ac.il/pls/mtl/student.first"
        val fullUrl = "$firstUrl?v_kurs="

        val req = Request.Builder()
            .url(fullUrl)
            .addHeader("Cookie", _cookies!!)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
            )
            .get()
            .build()

        try {
            _client.newCall(req).execute().use { resp ->
//                Log.d("HTTP_DEBUG", "➡️ student.first: ${resp.code}")
//                Log.d("HTTP_DEBUG", "Message: ${resp.message}")
//                Log.d("HTTP_DEBUG", "URL: ${resp.request.url}")
//                Log.d("HTTP_DEBUG", "Headers:\n${resp.headers}")

                // Handle expired session (OpenU redirects to login or returns 302/401)
                if (resp.code in listOf(302, 401, 403)) {
                    Log.w("OpenAU", "Session expired (code=${resp.code}). Refreshing cookies...")
                    if (!refreshCookies()) {
                        Log.e("OpenAU", "Re-login after expiration failed.")
                        return result
                    }
                    return fetchCourses() // retry once after re-login
                }

                if (!resp.isSuccessful) {
                    Log.e("OpenAU", "Fetch failed with code ${resp.code}")
                    return result
                }

                // --- Read body once ---
                val bytes = resp.body.bytes()
                val charset = resp.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val html = bytes.toString(charset)
                Log.d("HTTP_DEBUG", "Body:\n$html")

                // --- Parse courses ---
                val doc = Jsoup.parse(html)
                val options = doc.select("select[name=in_kurs] option")

                for (opt in options) {
                    val id = opt.attr("value").trim()
                    val text = opt.text().trim()
                    if (id.isNotEmpty()) {
                        // Just return text as required
                        result.add(text)
                    }
                }

                Log.d("HTTP_DEBUG", "Final courses: $result")
            }
        } catch (e: Exception) {
            Log.e("OpenAU", "fetchCourses failed", e)
        }

        return result
    }



    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray = getGrades()

    fun getGrades(): JSONArray {
        val grades = JSONArray()
        if (!loggedIn || _cookies.isNullOrBlank()) return grades

        val sikumUrl = "https://sheilta.apps.openu.ac.il/pls/mtl/student.sikum?v_pro=1"
        val request = Request.Builder()
            .url(sikumUrl)
            .addHeader("Cookie", _cookies!!)
            .get()
            .build()

        try {
            _client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return grades

                val bytes = resp.body.bytes()
                val html = String(bytes, Charset.forName("windows-1255"))
                val doc = Jsoup.parse(html)

                val rows = doc.select("tr[bgcolor=White]")
                for (row in rows) {
                    val cells = row.select("> td")
                    if (cells.isEmpty()) continue

                    val assignment = cells[0].text().trim()
                    if (!assignment.matches(Regex("\\d+"))) continue

                    val gradeCell = cells.getOrNull(7)
                    val grade = gradeCell?.select("a")?.text()?.trim().takeIf { !it.isNullOrEmpty() }
                        ?: gradeCell?.text()?.trim().takeIf { !it.isNullOrEmpty() }
                        ?: "-"

                    grades.put(
                        JSONObject()
                            .put("assignment", assignment)
                            .put("grade", grade)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("OpenAU", "Error parsing grades", e)
        }

        return grades
    }
    // endregion

    // region --- Boilerplate ---
    override fun getName(): String = displayName ?: ""
    override fun getUsername(): String = _username ?: ""
    override fun getPassword(): String = _password ?: ""
    override fun getLoginFields(): LoginFields = loginFields

    override fun isLoggedIn(): Boolean = loggedIn
    override fun refreshCookies(): Boolean =
        login(_username ?: "", _password ?: "", _studentId ?: "")

    override fun isEditing(): Boolean = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun setName(name: String) { displayName = name }
    override fun setUsername(username: String) { _username = username }
    override fun setPassword(password: String) { _password = password }

    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = JSONObject()
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = JSONObject()
    override fun getScheduleIndexes(): JSONArray = JSONArray()
    override fun getAttendanceEvents(period: String): JSONObject = JSONObject()
    override fun getAttendanceEvents(year: Int, period: String): JSONObject = JSONObject()
    override fun getMessages(page: Int): JSONArray = JSONArray()
    override fun getMessageDetails(messageId: String): JSONObject = JSONObject()
    override suspend fun downloadAttachment(context: android.content.Context, attachment: JSONObject): Boolean = false
    // endregion

    // region --- Metadata ---

    override fun getInfo(): JSONObject = JSONObject()
        .put("name", "OpenAU")
        .put("supportsGrades", true)
        .put("supportsSchedule", false)
        .put("supportsAttendance", false)
        .put("loginVariables", JSONArray(listOf("username", "password", "id")))

    override fun toJson(): JSONObject = JSONObject()
        .put("class", javaClass.name)
        .put("id", id)
        .put("displayName", displayName)
        .put("username", _username)
        .put("password", _password)
        .put("studentId", _studentId)
        .put("cookies", _cookies)
        .put("loggedIn", loggedIn)
        .put("editing", editing)
        .put("supportsGrades", suportsGrades)
        .put("supportsSchedule", supportsSchedule)
        .put("supportsAttendance", supportsAttendance)
        .put("courses", JSONArray(_courses))
    // endregion

    companion object : Platform.Companion {
        override val client: OkHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient()

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        override fun fromJson(obj: JSONObject): OpenAUPlatform {
            val p = OpenAUPlatform()

            p.id = obj.optString("id", "").ifEmpty { WebtopPlatform.generateId() }
            p.displayName = obj.optString("displayName", null)
            p._username = obj.optString("username", null)
            p._password = obj.optString("password", null)
            p._studentId = obj.optString("studentId", null)
            p._cookies = obj.optString("cookies", null)
            p.loggedIn = obj.optBoolean("loggedIn", false)
            p.editing = obj.optBoolean("editing", false)

            // Optional: restore courses if saved
            val coursesArray = obj.optJSONArray("courses")
            p._courses.clear()
            if (coursesArray != null) {
                for (i in 0 until coursesArray.length()) {
                    val c = coursesArray.optJSONObject(i)
                    if (c != null) p._courses.add(c)
                }
            } else {
                // fallback: add placeholder course
                val course = JSONObject()
                    .put("name", "OpenU")
                    .put("index", 0)
                    .put("semesterPicker", false)
                    .put("year", Calendar.getInstance().get(Calendar.YEAR))
                p._courses.add(course)
            }

            return p
        }

        override fun checkCredentials(loginFields: LoginFields): Boolean {
            return try {
                val p = OpenAUPlatform(loginFields)
                p.isLoggedIn()
            } catch (e: Exception) {
                Log.e("OpenAUPlatform", "checkCredentials failed", e)
                false
            }
        }
    }
}
