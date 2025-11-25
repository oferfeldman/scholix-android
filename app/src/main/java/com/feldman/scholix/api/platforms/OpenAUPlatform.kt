package com.feldman.scholix.api.platforms

import android.util.Log
import com.feldman.scholix.api.*

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.nio.charset.Charset
import java.util.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

private fun mergeCookieStrings(existing: String?, newCookies: List<String>): String {
    val allCookies = mutableMapOf<String, String>()

    // take existing cookies and split them by ;
    existing?.split(";")?.forEach {
        val parts = it.split("=", limit = 2)
        if (parts.size == 2) allCookies[parts[0].trim()] = parts[1].trim()
    }

    // add/overwrite with new ones
    for (c in newCookies) {
        val parts = c.split(";", limit = 2)[0].split("=", limit = 2)
        if (parts.size == 2) allCookies[parts[0].trim()] = parts[1].trim()
    }

    return allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

class OpenAUPlatform() : Platform {
    override var platformDisplayName: String = "OpenAUPlatform"

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
    var displayName: String? = null
    private var _studentId: String? = null
    var _username: String? = null
    var _password: String? = null
    private var _cookies: String? = null
    private val _client = HttpClient(OkHttp) {
        followRedirects = true

        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }

        install(ContentNegotiation) {
            json()
        }
        install(DefaultRequest) {
            _cookies?.let { headers.append("Cookie", it) }
        }

//        install(Logging) {
//            logger = Logger.SIMPLE
//            level = LogLevel.HEADERS
//        }
    }

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
    private fun login(username: String, password: String, id: String): Boolean = runBlocking {
        try {
            val loginPage = "https://sso.apps.openu.ac.il/login?T_PLACE=https://sheilta.apps.openu.ac.il/pls/dmyopt2/myop.myop_screen"

            // Step 1: GET initial cookies
            val initialResp = _client.get(loginPage)
            // ⬇️ ADD THIS
            val setCookies1 = initialResp.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            _cookies = mergeCookieStrings(_cookies, setCookies1)
            // ⬆️ ADD THIS

            if (!initialResp.status.isSuccess()) return@runBlocking false

            // Step 2: POST login
            val loginUrl = "https://sso.apps.openu.ac.il/process"
            val payload = listOf(
                "p_user" to username,
                "p_sisma" to password,
                "p_mis_student" to id,
                "T_PLACE" to "https://sheilta.apps.openu.ac.il/pls/dmyopt2/myop.myop_screen"
            )

            val loginResp: HttpResponse = _client.submitForm(
                url = loginUrl,
                formParameters = Parameters.build { payload.forEach { (k, v) -> append(k, v) } },
                encodeInQuery = false
            )
            // ⬇️ ADD THIS
            val setCookies2 = loginResp.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            _cookies = mergeCookieStrings(_cookies, setCookies2)
            // ⬆️ ADD THIS

            val loginHtml = loginResp.bodyAsText(Charset.forName("windows-1255"))

            // Step 3: follow hidden form if needed
            val formActionMatch = Regex("""<form[^>]*action="([^"]+)"[^>]*>""").find(loginHtml)
            if (formActionMatch != null) {
                val formUrl = formActionMatch.groupValues[1]
                val hiddenInputs = Regex("""<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"""").findAll(loginHtml)
                val params = Parameters.build {
                    hiddenInputs.forEach {
                        append(it.groupValues[1], it.groupValues[2])
                    }
                }

                val sheiltaResp: HttpResponse = _client.submitForm(
                    url = formUrl,
                    formParameters = params,
                    encodeInQuery = false
                )
                // ⬇️ ADD THIS
                val setCookies3 = sheiltaResp.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                _cookies = mergeCookieStrings(_cookies, setCookies3)
                // ⬆️ ADD THIS
            }

            loggedIn = true
            displayName = "OpenU: $username"
            fetchCourses()

            Log.d("OpenAU", "Login success!")
            return@runBlocking true

        } catch (e: Exception) {
            Log.e("OpenAU", "Login exception", e)
            return@runBlocking false
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
        Log.d("OpenAU", "getCourses: $_courses")
        return _courses
    }

    override fun getSubjectList(): List<String> = fetchCourses()

    private fun fetchCourses(): List<String> = runBlocking {
        val result = mutableListOf<String>()

        try {
            if (!loggedIn) {
                Log.w("OpenAU", "Not logged in. Attempting re-login...")
                if (!refreshCookies()) {
                    Log.e("OpenAU", "Re-login failed. Cannot fetch courses.")
                    return@runBlocking emptyList<String>()
                }
            }

            var currentUrl = "https://sheilta.apps.openu.ac.il/pls/dmyopt2/course_info.courses"
            var html: String
            var loops = 0

            while (true) {
                val response: HttpResponse = _client.get(currentUrl) {
                    headers {
                        append(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                        )
                    }
                    header(HttpHeaders.Cookie, _cookies ?: "")
                }

                html = response.bodyAsText()
                Log.d("OpenAUPlatform", "Fetched URL: $currentUrl (status=${response.status})")

                val redirectMatch = Regex("document\\.location\\.href\\s*=\\s*\"([^\"]+)\"").find(html)
                if (redirectMatch != null && loops < 3) {
                    val newUrl = redirectMatch.groupValues[1]
                    Log.w("OpenAU", "Detected JS redirect → $newUrl")
                    currentUrl = newUrl
                    loops++
                    continue
                }

                if (!response.status.isSuccess()) {
                    Log.e("OpenAU", "Fetch failed with status ${response.status}")
                    return@runBlocking emptyList<String>()
                }
                break
            }

            val doc = Jsoup.parse(html)
            val courseRows = doc.select("table.content_tbl tr:has(td)")

            // Clear previous course list before updating
            _courses.clear()
            println(courseRows)
//            for (row in courseRows) {
//                val cols = row.select("td")
//                if (cols.size >= 9) {
//                    // From inspection:
//                    // index 7 = course name, index 8 = course ID
//                    val name = cols[7].text().trim()
//                    val id = cols[8].text().trim().takeLast(5) // Extract only the numeric ID
//                    if (id.matches(Regex("\\d{5}")) && name.isNotEmpty()) {
//                        val courseObj = JSONObject()
//                            .put("id", id)
//                            .put("name", name)
//                        _courses.add(courseObj)
//                        result.add(name)
//                    }
//                }
//            }
            for (row in courseRows) {
                val cols = row.select("td")
                if (cols.size >= 9) {
                    // Extract the "details" link (the one pointing to course_info.courseinfo)
                    val detailLink = row.selectFirst("a[href*='course_info.courseinfo']")?.attr("href")?.trim()

                    // The course ID and name columns (based on the HTML you pasted)
                    val id = row.select("a[href*='courses/']").firstOrNull()?.text()?.trim()?.takeLast(5) ?: ""
                    val name = cols.getOrNull(7)?.text()?.trim() ?: ""

                    if (id.matches(Regex("\\d{5}")) && name.isNotEmpty()) {
                        val infoUrl = if (detailLink?.startsWith("http") == true) {
                            detailLink
                        } else if (!detailLink.isNullOrEmpty()) {
                            // Normalize relative href to full absolute URL
                            "https://sheilta.apps.openu.ac.il/pls/dmyopt2/${detailLink.removePrefix("/")}"
                        } else null

                        val courseObj = JSONObject()
                            .put("id", id)
                            .put("name", name)
                            .put("infoUrl", infoUrl)

                        _courses.add(courseObj)
                        result.add(name)
                    }
                }
            }


            Log.d("OpenAU", "Fetched ${result.size} courses and updated _courses.")

        } catch (e: Exception) {
            Log.e("OpenAU", "fetchCourses failed", e)
        }

        return@runBlocking result
    }




    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        // Try to find the course object by name
        val matchedCourse = _courses.find {
            it.optString("name").equals(course, ignoreCase = true)
        }

        val courseId = matchedCourse?.optString("id") ?: run {
            Log.w("OpenAU", "Course '$course' not found in list; fetching general grades.")
            return JSONArray()
        }

        Log.d("OpenAU", "Resolved course '$course' → id=$courseId")

        // --- 🔒 Ensure valid session before fetching ---
        if (!ensureSession()) {
            Log.w("OpenAU", "Session invalid; re-login failed — cannot fetch grades.")
            return JSONArray().put(JSONObject().put("error", "login_failed"))
        }

        // --- Proceed with actual fetch ---
        return getGrades(courseId)
    }


    fun getGrades(courseId: String): JSONArray = runBlocking {
        val grades = JSONArray()

        if (!loggedIn) {
            Log.e("OpenAU", "Not logged in, cannot fetch grades.")
            return@runBlocking grades
        }

        try {
            // Find the corresponding course object
            val courseObj = _courses.find { it.optString("id") == courseId }
            val infoUrl = courseObj?.optString("infoUrl")
            if (infoUrl.isNullOrEmpty()) {
                Log.e("OpenAU", "No infoUrl found for course $courseId")
                return@runBlocking grades
            }

            // Construct the grades URL
            val gradesUrl = infoUrl.replace(
                "course_info.courseinfo",
                "course_info_2.ZIUNMATALA"
            )

            Log.d("OpenAU", "Fetching grades from $gradesUrl")

            // Fetch grades page
            val response: HttpResponse = _client.get(gradesUrl) {
                header(HttpHeaders.Cookie, _cookies ?: "")
            }

            if (!response.status.isSuccess()) {
                Log.e("OpenAU", "Failed to fetch grades: ${response.status}")
                return@runBlocking grades
            }

            val html = response.bodyAsText(Charset.forName("windows-1255"))
            val doc = Jsoup.parse(html)

            // Extract final course grade
            val finalGradeMatch = Regex("ציון סופי בקורס:&nbsp;\\s*(\\d+)").find(html)
            val finalGrade = finalGradeMatch?.groupValues?.get(1)

            // Extract all task/exam rows
            val rows = doc.select("tr[valign=top][align=right]:has(td[bgcolor])")
            for (row in rows.drop(1)) { //don't take the first row with headers
                val cells = row.select("td")
                if (cells.size < 8) continue

                val subjectCell = cells[6].clone()
                subjectCell.select("a").remove()
                val subject = subjectCell.text().trim()

                val number = cells[7].text().trim()
                val grade = cells[5].text().trim()
                val weight = cells[4].text().trim()
                val date = cells.getOrNull(3)?.text()?.trim() ?: ""

                if (subject.isEmpty() || grade.isEmpty()) continue

                grades.put(
                    JSONObject()
                        .put("subject", subject)
                        .put("name", number)
                        .put("grade", grade)
                        .put("weight", weight)
                        .put("date", date)
                )
            }

            //Final Grade
            if (!finalGrade.isNullOrEmpty()) {
                grades.put(
                    JSONObject()
                        .put("subject", "Final Grade")
                        .put("name", "Final Grade")
                        .put("type", "final")
                        .put("grade", finalGrade)
                )
            }

            Log.d("OpenAU", "Parsed ${grades.length()} grades for $courseId")

        } catch (e: Exception) {
            Log.e("OpenAU", "Error fetching grades for $courseId", e)
        }

        return@runBlocking grades
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
        .put("platformDisplayName", platformDisplayName)
    // endregion
    private fun ensureSession(): Boolean = runBlocking {
        try {
            // Ping the main page to test current session validity
            val resp = _client.get("https://sheilta.apps.openu.ac.il/pls/mtl/student.first?v_kurs=") {
                header(HttpHeaders.Cookie, _cookies ?: "")

            }

            val html = resp.bodyAsText(Charset.forName("windows-1255"))

            // If HTML contains login fields, session expired
            if (html.contains("p_user") || html.contains("sisma") || html.contains("כניסה")) {
                Log.w("OpenAU", "Session appears expired — attempting re-login.")
                val relog = refreshCookies()
                if (!relog) {
                    Log.e("OpenAU", "Re-login failed.")
                    loggedIn = false
                    return@runBlocking false
                }
                Log.d("OpenAU", "Re-login succeeded.")
            }

            true
        } catch (e: Exception) {
            Log.e("OpenAU", "Session validation failed", e)
            false
        }
    }

    companion object : Platform.Companion {

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
            p.platformDisplayName = obj.optString("platformDisplayName")

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
