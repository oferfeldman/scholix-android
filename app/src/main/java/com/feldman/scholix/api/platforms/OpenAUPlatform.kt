package com.feldman.scholix.api.platforms

import android.content.Context
import android.util.Log
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.Type
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.ktor.client.call.body
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parseClientCookiesHeader
import io.ktor.http.renderCookieHeader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val OPEN_U_COURSES_PAGE = "https://sheilta.apps.openu.ac.il/Main/Courses"
private const val OPEN_U_COURSES_API = "https://sheilta.apps.openu.ac.il/Main/api/coursesApi/get"
private const val OPEN_U_AUTH_API = "https://sheilta.apps.openu.ac.il/Main/api/homeApi/isAuthenticated"
private const val OPEN_U_MESSAGES_SEARCH = "https://sheilta.apps.openu.ac.il/Main/Messages/MessagesSearchResult"
private const val OPEN_U_MESSAGE_CONTENT = "https://sheilta.apps.openu.ac.il/Main/Messages/MessageContent"
private const val OPEN_U_SSO_TARGET = "https://sheilta.apps.openu.ac.il/pls/dmyopt2/sheilta.main"
private const val OPEN_U_SSO_PROCESS = "https://sso.apps.openu.ac.il/process"
private const val OPEN_U_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

private class OpenUSessionExpiredException : IOException()

class OpenAUPlatform() : Platform {
    override var platformDisplayName: String = "Open University"

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

    var displayName: String? = null
    private var _studentId: String? = null
    private var _username: String? = null
    private var _password: String? = null

    // Kept for backwards-compatible persistence. Requests use only Ktor's cookie jar.
    private var _cookies: String? = null
    private var persistedCookiesRestored = false
    private val cookieStorage = AcceptAllCookiesStorage()
    private val sessionMutex = Mutex()
    private var lastRefreshError: String? = null
    private var lastSuccessfulRefreshMillis: Long = 0

    private val client = HttpClient(OkHttp) {
        followRedirects = true

        install(HttpCookies) {
            storage = cookieStorage
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        install(DefaultRequest) {
            header(HttpHeaders.UserAgent, OPEN_U_USER_AGENT)
        }
    }

    override var editing: Boolean = false
    override var loggedIn: Boolean = false
    private val courses: ArrayList<JSONObject> = ArrayList()
    override var id: String = WebtopPlatform.generateId()
        private set

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = false
    override val supportsAttendance: Boolean = false

    init {
        if (id.isBlank()) id = WebtopPlatform.generateId()
    }

    constructor(loginFields: LoginFields) : this() {
        val username = loginFields.getValueByType(Type.Username)
        val password = loginFields.getValueByType(Type.Password)
        val studentId = loginFields.getValueByType(Type.Id)

        if (username != null && password != null && studentId != null) {
            loggedIn = login(username, password, studentId)
        } else {
            Log.e(TAG, "Missing username, password, or student ID")
        }
    }

    private fun login(username: String, password: String, studentId: String): Boolean = runBlocking {
        sessionMutex.withLock {
            _username = username
            _password = password
            _studentId = studentId

            authenticateLocked() && refreshCoursesLocked(retryAuthentication = false)
        }
    }

    private suspend fun authenticateLocked(): Boolean {
        val username = _username?.takeIf { it.isNotBlank() } ?: return false
        val password = _password?.takeIf { it.isNotBlank() } ?: return false
        val studentId = _studentId?.takeIf { it.isNotBlank() } ?: return false

        return try {
            restorePersistedCookiesLocked()

            val initialResponse = client.get(OPEN_U_COURSES_PAGE)
            if (!initialResponse.status.isSuccess()) return false

            val loginResponse = client.submitForm(
                url = OPEN_U_SSO_PROCESS,
                formParameters = Parameters.build {
                    append("p_user", username)
                    append("p_sisma", password)
                    append("p_mis_student", studentId)
                    append("T_PLACE", OPEN_U_SSO_TARGET)
                },
                encodeInQuery = false
            )
            if (!loginResponse.status.isSuccess()) return false

            val ssoTargetResponse = client.get(OPEN_U_SSO_TARGET)
            val ssoTargetBody = ssoTargetResponse.bodyAsText()
            val authenticated = ssoTargetResponse.status.isSuccess() &&
                !looksLikeLoginResponse(ssoTargetResponse, ssoTargetBody)

            loggedIn = authenticated
            if (authenticated) {
                displayName = username
                snapshotCookiesLocked()
                lastRefreshError = null
            } else {
                lastRefreshError = ERROR_LOGIN_FAILED
            }
            authenticated
        } catch (exception: Exception) {
            Log.w(TAG, "Open University authentication failed: ${exception.javaClass.simpleName}")
            loggedIn = false
            lastRefreshError = ERROR_LOGIN_FAILED
            false
        }
    }

    override fun getCourses(): ArrayList<JSONObject> = runBlocking {
        sessionMutex.withLock {
            if (courses.isEmpty() || refreshedPlatformIds.add(id)) {
                refreshCoursesLocked(retryAuthentication = true)
            }
            ArrayList(courses.sortedWith(
                compareBy<JSONObject> { it.optInt("courseStatusRank", 1) }
                    .thenBy { it.optString("semesterStartDate") }
                    .thenBy { it.optString("name") }
            ))
        }
    }

    override fun getSubjectList(): List<String> =
        getCourses().map { it.optString("name") }.filter { it.isNotBlank() }.distinct()

    private suspend fun refreshCoursesLocked(retryAuthentication: Boolean): Boolean {
        restorePersistedCookiesLocked()

        return try {
            applyCourseSnapshotLocked(fetchCourseSnapshotLocked())
            true
        } catch (_: OpenUSessionExpiredException) {
            if (!retryAuthentication || !authenticateLocked()) {
                loggedIn = false
                lastRefreshError = ERROR_LOGIN_FAILED
                false
            } else {
                try {
                    applyCourseSnapshotLocked(fetchCourseSnapshotLocked())
                    true
                } catch (exception: Exception) {
                    recordRefreshFailure(exception)
                    false
                }
            }
        } catch (exception: Exception) {
            recordRefreshFailure(exception)
            false
        }
    }

    private suspend fun fetchCourseSnapshotLocked(): ArrayList<JSONObject> {
        val response = client.get(OPEN_U_COURSES_API) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        val body = response.bodyAsText()

        if (!response.status.isSuccess()) {
            if (authenticationStateLocked() == false) throw OpenUSessionExpiredException()
            throw IOException("Open University course API returned ${response.status.value}")
        }

        if (looksLikeLoginResponse(response, body)) throw OpenUSessionExpiredException()

        val json = body.trim().removePrefix("\uFEFF")
        if (!json.startsWith("[")) {
            if (authenticationStateLocked() == false) throw OpenUSessionExpiredException()
            throw JSONException("Open University course API returned a non-array response")
        }

        val payload = JSONArray(json)
        val parsed = ArrayList<JSONObject>(payload.length())
        for (index in 0 until payload.length()) {
            val source = payload.optJSONObject(index) ?: continue
            parseCourse(source)?.let(parsed::add)
        }
        return parsed
    }

    private suspend fun applyCourseSnapshotLocked(snapshot: ArrayList<JSONObject>) {
        // Retain a known-good snapshot if an upstream fault is represented as an empty response.
        if (snapshot.isNotEmpty() || courses.isEmpty()) {
            courses.clear()
            courses.addAll(snapshot)
        }
        loggedIn = true
        lastRefreshError = null
        lastSuccessfulRefreshMillis = System.currentTimeMillis()
        snapshotCookiesLocked()
    }

    private suspend fun authenticationStateLocked(): Boolean? {
        return try {
            val response = client.get(OPEN_U_AUTH_API) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
            if (!response.status.isSuccess()) null
            else when (response.bodyAsText().trim().lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun recordRefreshFailure(exception: Exception) {
        if (exception is OpenUSessionExpiredException) {
            loggedIn = false
            lastRefreshError = ERROR_LOGIN_FAILED
        } else {
            lastRefreshError = ERROR_SERVER_UNREACHABLE
        }
        Log.w(TAG, "Course refresh failed; keeping cached courses: ${exception.javaClass.simpleName}")
    }

    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray = runBlocking {
        sessionMutex.withLock {
            val snapshotAge = System.currentTimeMillis() - lastSuccessfulRefreshMillis
            val refreshed = if (courses.isNotEmpty() && snapshotAge in 0 until SNAPSHOT_DEBOUNCE_MILLIS) {
                true
            } else {
                refreshCoursesLocked(retryAuthentication = true)
            }
            val matchedCourse = findCourse(course, year, semester)

            if (matchedCourse == null) {
                if (!refreshed && courses.isEmpty()) {
                    return@withLock errorResult(lastRefreshError ?: ERROR_SERVER_UNREACHABLE)
                }
                return@withLock JSONArray()
            }

            gradesFromCourse(matchedCourse)
        }
    }

    fun getRecentGrades(context: Context): JSONArray = runBlocking {
        sessionMutex.withLock {
            if (courses.isEmpty()) refreshCoursesLocked(retryAuthentication = true)
            val completionDates = fetchAssignmentCompletionDatesLocked(context)
            val recentGrades = JSONArray()

            courses.forEach { course ->
                val courseId = course.optString("id")
                val grades = gradesFromCourse(course)
                for (index in 0 until grades.length()) {
                    val grade = grades.optJSONObject(index) ?: continue
                    val assignmentNumber = grade.optString("id")
                        .substringAfterLast(":assignment:", "")
                        .toAssignmentNumber()
                    val gradeKey = when {
                        grade.optString("id").endsWith(":exam") -> "$courseId:exam"
                        grade.optString("id").endsWith(":final") -> "$courseId:final"
                        else -> "$courseId:$assignmentNumber"
                    }
                    val gradedAt = completionDates[gradeKey] ?: continue
                    recentGrades.put(JSONObject(grade.toString()).put("gradedAt", gradedAt))
                }
            }
            recentGrades
        }
    }

    private suspend fun fetchAssignmentCompletionDatesLocked(context: Context): Map<String, String> {
        val today = LocalDate.now()
        val response = try {
            client.get(OPEN_U_MESSAGES_SEARCH) {
                parameter("fromDate", today.minusYears(2).format(MESSAGE_QUERY_DATE_FORMAT))
                parameter("toDate", today.format(MESSAGE_QUERY_DATE_FORMAT))
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Could not load Open University grade notices: ${exception.javaClass.simpleName}")
            return emptyMap()
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess() || looksLikeLoginResponse(response, body)) return emptyMap()

        val notices = mutableMapOf<String, String>()
        Jsoup.parse(body).select(".messages-list .pagination-item").forEach { message ->
            val title = message.selectFirst(".panel-title > a[messageid]")?.text().orEmpty()
            val kind = title.gradeNoticeKind() ?: return@forEach

            val date = message.selectFirst(".panel-heading > p")?.text()
                ?.trim()
                ?.toMessageDate() ?: return@forEach
            val messageId = message.selectFirst(".panel-title > a[messageid]")
                ?.attr("messageid") ?: return@forEach
            val attachmentUrl = message.selectFirst("a[href*='/Main/Messages/GetFile/']")
                ?.absUrl("href")
                ?.ifBlank { "$OPEN_U_MESSAGE_CONTENT/$messageId" }
                ?: "$OPEN_U_MESSAGE_CONTENT/$messageId"
            val notice = readGradeNotice(context, attachmentUrl, kind) ?: return@forEach
            notices.putIfAbsent(notice.key, date)
        }
        return notices
    }

    private suspend fun readGradeNotice(
        context: Context,
        url: String,
        kind: GradeNoticeKind
    ): GradeNotice? {
        var pdf = try {
            client.get(url).body<ByteArray>()
        } catch (exception: Exception) {
            Log.w(TAG, "Could not load Open University grade notice: ${exception.javaClass.simpleName}")
            return null
        }
        if (!pdf.startsWithPdfHeader()) {
            val attachmentUrl = Jsoup.parse(String(pdf, Charsets.UTF_8))
                .selectFirst("a[href*='/Main/Messages/GetFile/']")
                ?.absUrl("href")
                .orEmpty()
            if (attachmentUrl.isNotBlank()) {
                pdf = runCatching { client.get(attachmentUrl).body<ByteArray>() }.getOrNull() ?: return null
            }
        }
        if (!pdf.startsWithPdfHeader()) return null

        return runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(ByteArrayInputStream(pdf)).use { document ->
                val text = PDFTextStripper().getText(document)
                val courseId = COURSE_ID_PATTERN.find(text)?.groupValues?.get(1) ?: return@use null
                val key = when (kind) {
                    GradeNoticeKind.Assignment -> ASSIGNMENT_NUMBER_PATTERNS.asSequence()
                        .mapNotNull { pattern -> pattern.find(text)?.groupValues?.get(1)?.toAssignmentNumber() }
                        .firstOrNull()
                        ?.let { "$courseId:$it" }
                    GradeNoticeKind.CourseStatus -> text.takeIf { it.containsExamGradeText() }
                        ?.let { "$courseId:exam" }
                    GradeNoticeKind.CourseCompleted -> "$courseId:final"
                } ?: return@use null
                GradeNotice(key)
            }
        }.getOrElse { exception ->
            Log.w(TAG, "Could not read Open University grade notice: ${exception.javaClass.simpleName}")
            null
        }
    }

    private fun findCourse(identifier: String, year: Int?, semester: String?): JSONObject? {
        courses.firstOrNull { it.optString("courseKey") == identifier }?.let { return it }

        val requestedTerm = if (year != null && !semester.isNullOrBlank()) {
            "$year${semester.lowercase(Locale.ROOT).first()}"
        } else {
            null
        }

        return courses.firstOrNull {
            it.optString("name").equals(identifier, ignoreCase = true) &&
                (requestedTerm == null || it.optString("term") == requestedTerm)
        } ?: courses.firstOrNull {
            it.optString("name").equals(identifier, ignoreCase = true)
        }
    }

    private fun gradesFromCourse(course: JSONObject): JSONArray {
        val grades = JSONArray()
        val courseName = course.optString("name")
        val courseKey = course.optString("courseKey")
        val assignments = course.optJSONArray("assignments") ?: JSONArray()
        val finalGrade = course.optString("finalGrade").trim()
        val examGrade = course.optString("examGrade").trim()
        val seenAssignments = mutableSetOf<String>()
        var includesExamGrade = false

        for (index in 0 until assignments.length()) {
            val assignment = assignments.optJSONObject(index) ?: continue
            val grade = assignment.optString("grade").trim()
            if (grade.isBlank() || grade.equals("null", ignoreCase = true)) continue

            val number = assignment.optString("number").trim()
            val type = assignment.optString("type").trim()
            val assignmentKey = number.ifBlank {
                "$type|$grade|${assignment.optString("date").trim()}"
            }
            if (!seenAssignments.add(assignmentKey)) continue

            val name = listOf(type, number).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { "Assignment" }
            val assignmentOrder = Regex("\\d+").find(number)?.value?.toIntOrNull()
            if (isExamAssessment(type) && grade == examGrade) includesExamGrade = true

            grades.put(
                JSONObject()
                    .put("id", "$courseKey:assignment:${number.ifBlank { index.toString() }}")
                    .put("subject", courseName)
                    .put("name", name)
                    .put("grade", grade)
                    .put("weight", assignment.optString("weight"))
                    .put("date", assignment.optString("date"))
                    .put("gradedAt", assignment.optString("gradedAt"))
                    .put("status", assignment.optString("status"))
                    .apply { assignmentOrder?.let { put("assignmentOrder", it) } }
            )
        }

        finalGrade.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.let { grade ->
            grades.put(
                JSONObject()
                    .put("id", "$courseKey:final")
                    .put("subject", courseName)
                    .put("name", "Final grade")
                    .put("grade", grade)
                    .put("type", "final")
            )
        }

        examGrade.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) && !includesExamGrade }?.let { grade ->
            grades.put(
                JSONObject()
                    .put("id", "$courseKey:exam")
                    .put("subject", courseName)
                    .put("name", "Exam grade")
                    .put("grade", grade)
            )
        }

        return grades
    }

    private fun isExamAssessment(type: String): Boolean =
        type.contains("exam", ignoreCase = true) ||
            type.contains("מבחן") ||
            type.contains("בחינה") ||
            type.contains("בחינת")

    private enum class GradeNoticeKind { Assignment, CourseStatus, CourseCompleted }

    private data class GradeNotice(val key: String)

    private fun parseCourse(source: JSONObject): JSONObject? {
        val courseId = firstText(source, "courseId", "id") ?: return null
        val courseName = firstText(source, "courseName", "name") ?: return null
        val rawTerm = firstText(source, "semester") ?: ""
        val term = normalizeTerm(rawTerm)
        val semester = term.lastOrNull { it in 'a'..'c' }?.toString() ?: ""
        val year = term.take(4).toIntOrNull()
        val courseKey = "$courseId:$term"
        val status = firstText(source, "statusDesc", "statusCodeDisp")
        val semesterStartDate = firstText(source, "semesterStartDate")

        return JSONObject()
            .put("id", courseId)
            .put("courseKey", courseKey)
            .put("platformId", id)
            .put("name", courseName)
            .put("rawTerm", rawTerm)
            .put("term", term)
            .put("semester", semester)
            .put("semesterPicker", false)
            .put("courseStatusRank", courseStatusRank(status, semesterStartDate))
            .apply {
                if (year != null) put("year", year)
                putIfText("status", status)
                putIfText("semesterStartDate", semesterStartDate)
                putIfText("finalGrade", firstText(source, "finalGrade"))
                putIfText("examGrade", firstText(source, "examGrade"))
                putIfText("points", firstText(source, "points"))
                put("assignments", sanitizeAssignments(source))
            }
    }

    private fun courseStatusRank(status: String?, semesterStartDate: String?): Int = when {
        status?.contains("בלימוד") == true -> 0
        status?.contains("נרשם") == true && semesterStartDate.orEmpty().take(10) <= java.time.LocalDate.now().toString() -> 0
        status?.contains("נרשם") == true -> 2
        else -> 1
    }

    private fun sanitizeAssignments(course: JSONObject): JSONArray {
        val source = sequenceOf("assigmentScores", "assigmentScoreModel")
            .mapNotNull { course.opt(it) as? JSONArray }
            .firstOrNull() ?: return JSONArray()
        val sanitized = JSONArray()

        for (index in 0 until source.length()) {
            val assignment = source.optJSONObject(index) ?: continue
            if (isExplicitlyFalse(assignment, "isPublic")) continue

            val scoreIsPublic = !isExplicitlyFalse(assignment, "isScorePublic")
            val item = JSONObject()
            item.putIfText("number", firstText(assignment, "assignmentNo"))
            item.putIfText(
                "type",
                firstText(
                    assignment,
                    "assignmentTypeDesc",
                    "assignmentType",
                    "typeDesc",
                    "type"
                )
            )
            if (scoreIsPublic) {
                item.putIfText("grade", firstText(assignment, "scoreValue", "grade", "score"))
            }
            item.putIfText(
                "gradedAt",
                firstText(
                    assignment,
                    "scoreDate",
                    "scoreUpdateDate",
                    "scorePublishedDate",
                    "scorePublicationDate",
                    "gradeDate",
                    "gradeUpdateDate",
                    "updatedAt"
                )
            )
            item.putIfText(
                "date",
                firstText(
                    assignment,
                    "registrationDate",
                    "submitDate",
                    "submissionDate",
                    "examinationDate",
                    "examDate",
                    "assignmentDate"
                )
            )
            item.putIfText("weight", firstText(assignment, "weight", "assignmentWeight"))
            item.putIfText("status", firstText(assignment, "statusDesc", "status"))
            item.putIfText("statusCode", firstText(assignment, "statusCode"))
            item.putIfText("assignmentSemester", firstText(assignment, "assignmentSemester"))
            sanitized.put(item)
        }

        return sanitized
    }

    private suspend fun restorePersistedCookiesLocked() {
        if (persistedCookiesRestored) return
        persistedCookiesRestored = true

        val persisted = _cookies?.takeIf { it.isNotBlank() } ?: return
        val requestUrl = Url(OPEN_U_COURSES_PAGE)
        parseClientCookiesHeader(persisted).forEach { (name, value) ->
            cookieStorage.addCookie(
                requestUrl,
                Cookie(
                    name = name,
                    value = value,
                    encoding = CookieEncoding.RAW,
                    path = "/",
                    secure = true,
                    httpOnly = true
                )
            )
        }
    }

    private suspend fun snapshotCookiesLocked() {
        _cookies = cookieStorage.get(Url(OPEN_U_COURSES_PAGE))
            .joinToString("; ") { renderCookieHeader(it) }
            .ifBlank { null }
    }

    private fun looksLikeLoginResponse(response: HttpResponse, body: String): Boolean {
        val finalUrl = response.call.request.url
        return finalUrl.host.equals("sso.apps.openu.ac.il", ignoreCase = true) ||
            finalUrl.encodedPath.contains("/login", ignoreCase = true) ||
            body.contains("name=\"p_user\"", ignoreCase = true) ||
            body.contains("name=\"p_sisma\"", ignoreCase = true)
    }

    override fun getName(): String = displayName ?: ""
    override fun getUsername(): String = _username ?: ""
    override fun getPassword(): String = _password ?: ""
    override fun getLoginFields(): LoginFields = loginFields

    override fun isLoggedIn(): Boolean = loggedIn
    override fun refreshCookies(): Boolean = runBlocking {
        sessionMutex.withLock {
            refreshCoursesLocked(retryAuthentication = true)
        }
    }

    override fun isEditing(): Boolean = editing
    override fun startEditing() {
        editing = true
    }

    override fun stopEditing() {
        editing = false
    }

    override fun setName(name: String) {
        displayName = name
    }

    override fun setUsername(username: String) {
        _username = username
    }

    override fun setPassword(password: String) {
        _password = password
    }

    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = JSONObject()
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = JSONObject()
    override fun getScheduleIndexes(): JSONArray = JSONArray()
    override fun getAttendanceEvents(period: String): JSONObject = JSONObject()
    override fun getAttendanceEvents(year: Int, period: String): JSONObject = JSONObject()
    override fun getMessages(page: Int): JSONArray = JSONArray()
    override fun getMessageDetails(messageId: String): JSONObject = JSONObject()
    override suspend fun downloadAttachment(
        context: android.content.Context,
        attachment: JSONObject
    ): Boolean = false

    override fun getInfo(): JSONObject = JSONObject()
        .put("name", "Open University")
        .put("supportsGrades", true)
        .put("supportsSchedule", false)
        // Open University teaching is dated sessions rather than a repeating
        // week, so its schedule is browsed by date once the data source lands.
        .put("scheduleKind", "dated")
        .put("supportsOriginalSchedule", false)
        .put("supportsScheduleSelection", false)
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
        .put("lastCourseRefresh", lastSuccessfulRefreshMillis)
        .put("loggedIn", loggedIn)
        .put("editing", editing)
        .put("supportsGrades", suportsGrades)
        .put("supportsSchedule", supportsSchedule)
        .put("supportsAttendance", supportsAttendance)
        .put("courses", JSONArray(courses))
        .put("platformDisplayName", platformDisplayName)

    companion object : Platform.Companion {
        private const val TAG = "OpenAUPlatform"
        private const val ERROR_SERVER_UNREACHABLE = "server_unreachable"
        private const val ERROR_LOGIN_FAILED = "login_failed"
        private const val SNAPSHOT_DEBOUNCE_MILLIS = 15_000L
        private val refreshedPlatformIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val MESSAGE_QUERY_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
        private val MESSAGE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)
        private val COURSE_ID_PATTERN = Regex("""\((\d{5})\)""")
        private val ASSIGNMENT_NUMBER_PATTERNS = listOf(
            Regex("""(?:מטלה|הלטמ)\s*(\d{1,2})"""),
            Regex("""(\d{1,2})\s*(?:מטלה|הלטמ)""")
        )

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        override fun fromJson(obj: JSONObject): OpenAUPlatform {
            val platform = OpenAUPlatform()

            platform.id = obj.optString("id", "").ifEmpty { WebtopPlatform.generateId() }
            platform.displayName = obj.optString("displayName").takeIf { it.isNotBlank() }
            platform._username = obj.optString("username").takeIf { it.isNotBlank() }
            platform._password = obj.optString("password").takeIf { it.isNotBlank() }
            platform._studentId = obj.optString("studentId").takeIf { it.isNotBlank() }
            platform._cookies = obj.optString("cookies").takeIf { it.isNotBlank() }
            platform.lastSuccessfulRefreshMillis = obj.optLong("lastCourseRefresh", 0)
            platform.loggedIn = obj.optBoolean("loggedIn", false)
            platform.editing = obj.optBoolean("editing", false)
            platform.platformDisplayName = obj.optString("platformDisplayName", "Open University")

            val coursesArray = obj.optJSONArray("courses")
            if (coursesArray != null) {
                for (index in 0 until coursesArray.length()) {
                    val c = coursesArray.optJSONObject(index) ?: continue
                    if (!c.has("platformId") || c.optString("platformId").isBlank()) {
                        c.put("platformId", platform.id)
                    }
                    platform.courses.add(c)
                }
            }

            return platform
        }

        override fun checkCredentials(loginFields: LoginFields): Boolean {
            return try {
                OpenAUPlatform(loginFields).isLoggedIn()
            } catch (exception: Exception) {
                Log.e(TAG, "Credential check failed: ${exception.javaClass.simpleName}")
                false
            }
        }

        private fun firstText(source: JSONObject, vararg names: String): String? {
            for (name in names) {
                val value = source.opt(name)
                if (value == null || value == JSONObject.NULL) continue
                val text = value.toString().trim()
                if (text.isNotEmpty() && !text.equals("null", ignoreCase = true)) return text
            }
            return null
        }

        private fun normalizeTerm(rawTerm: String): String = rawTerm
            .trim()
            .lowercase(Locale.ROOT)
            .replace('א', 'a')
            .replace('ב', 'b')
            .replace('ג', 'c')
            .filter { it.isDigit() || it in 'a'..'c' }

        private fun JSONObject.putIfText(name: String, value: String?) {
            if (!value.isNullOrBlank()) put(name, value)
        }

        private fun isExplicitlyFalse(source: JSONObject, name: String): Boolean {
            val value = source.opt(name) ?: return false
            return value == false || value.toString().equals("false", ignoreCase = true) || value.toString() == "0"
        }

        private fun String.gradeNoticeKind(): GradeNoticeKind? = when {
            contains("סיום") && contains("בדיקת") && contains("מטלה") -> GradeNoticeKind.Assignment
            contains("עדכון") && contains("מצב") && contains("לימודים") && contains("בקורס") -> GradeNoticeKind.CourseStatus
            contains("סיום") && contains("לימודים") && contains("בקורס") -> GradeNoticeKind.CourseCompleted
            else -> null
        }

        private fun String.containsExamGradeText(): Boolean =
            contains("בחינת הגמר") || contains("רמגה תניחב")

        private fun String.toMessageDate(): String? = runCatching {
            LocalDate.parse(this, MESSAGE_DATE_FORMAT).toString()
        }.getOrNull()

        private fun String.toAssignmentNumber(): String =
            Regex("\\d+").find(this)?.value?.toIntOrNull()?.toString().orEmpty()

        private fun ByteArray.startsWithPdfHeader(): Boolean =
            size >= 4 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() &&
                this[2] == 'D'.code.toByte() && this[3] == 'F'.code.toByte()

        private fun errorResult(error: String): JSONArray =
            JSONArray().put(JSONObject().put("error", error))
    }
}
