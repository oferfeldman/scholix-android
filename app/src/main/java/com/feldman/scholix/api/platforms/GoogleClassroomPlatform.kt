package com.feldman.scholix.api.platforms

import android.accounts.Account
import android.content.Context
import com.feldman.scholix.R
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.classroom.ClassroomApi
import com.feldman.scholix.classroom.ClassroomMapping
import com.feldman.scholix.classroom.ClassroomSignInRequired
import com.feldman.scholix.classroom.objects
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections

private fun generatePlatformId(): String {
    val chars = ('A'..'Z') + ('0'..'9')
    return (1..8).map { chars.random() }.joinToString("")
}

class GoogleClassroomPlatform(override val id: String = generatePlatformId()) : Platform {
    override var platformDisplayName: String = "Google Classroom"
    override var editing: Boolean = false
    override var loggedIn: Boolean = false

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = false
    override val supportsAttendance: Boolean = false

    var displayName: String = "Google Classroom"
    var email: String = ""

    val coursesList: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    val cachedHomeworkList: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    val cachedGradesList: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    val cachedAnnouncementsList: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())

    @Transient
    var currentToken: String? = null

    @Transient
    private var contextRef: WeakReference<Context>? = null

    fun attach(context: Context) {
        contextRef = WeakReference(context.applicationContext)
    }

    override fun getName(): String = displayName
    override fun getUsername(): String = email
    override fun getPassword(): String = ""

    override fun setName(name: String) { this.displayName = name }
    override fun setUsername(username: String) { this.email = username }
    override fun setPassword(password: String) {}

    override fun isEditing(): Boolean = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun isLoggedIn(): Boolean = loggedIn
    override fun refreshCookies(): Boolean = true

    override fun getCourses(): MutableList<JSONObject> = coursesList
    override fun getSubjectList(): List<String> = coursesList.map { it.optString("name") }.filter { it.isNotBlank() }.distinct()

    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        if (cachedGradesList.isEmpty()) {
            runCatching { refreshHomework() }
        }
        val filtered = if (course.isBlank()) cachedGradesList else cachedGradesList.filter { it.optString("subject") == course }
        return JSONArray(filtered)
    }

    override fun getAttendanceEvents(year: Int, period: String): JSONObject = JSONObject()
    override fun getAttendanceEvents(period: String): JSONObject = JSONObject()
    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = JSONObject()
    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = JSONObject()
    override fun getScheduleIndexes(): JSONArray = JSONArray()

    override fun getMessages(page: Int): JSONArray {
        if (cachedAnnouncementsList.isEmpty()) {
            runCatching { refreshAnnouncements() }
        }
        return JSONArray(cachedAnnouncementsList)
    }

    override fun getMessageDetails(messageId: String): JSONObject {
        return cachedAnnouncementsList.firstOrNull { it.optString("id") == messageId }
            ?: JSONObject().put("id", messageId).put("text", "")
    }

    override suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean = false

    override fun getInfo(): JSONObject = JSONObject().apply {
        put("name", "Google Classroom")
        put("supportsGrades", true)
        put("supportsSchedule", false)
        put("supportsAttendance", false)
    }

    override fun getLoginFields(): LoginFields = LoginFields()

    override fun toJson(): JSONObject = JSONObject().apply {
        put("class", GoogleClassroomPlatform::class.java.name)
        put("id", id)
        put("name", displayName)
        put("platformDisplayName", platformDisplayName)
        put("email", email)
        put("loggedIn", loggedIn)
        put("courses", JSONArray(coursesList))
        put("cachedHomework", JSONArray(cachedHomeworkList))
        put("cachedGrades", JSONArray(cachedGradesList))
        put("cachedAnnouncements", JSONArray(cachedAnnouncementsList))
    }

    fun cachedHomework(): List<JSONObject> = cachedHomeworkList

    fun refreshCourses(): List<JSONObject> {
        val client = getApiClient()
        val fetched = client.list("courses", "courses", mapOf("studentId" to "me", "courseStates" to "ACTIVE,ARCHIVED"))
        coursesList.clear()
        coursesList.addAll(fetched)
        return coursesList
    }

    fun refreshHomework(): List<JSONObject> {
        val client = getApiClient()
        val allHomework = mutableListOf<JSONObject>()
        val allGrades = mutableListOf<JSONObject>()
        val currentCourses = if (coursesList.isEmpty()) refreshCourses() else coursesList

        for (c in currentCourses) {
            val courseId = c.optString("id")
            val courseName = c.optString("name")
            if (courseId.isBlank()) continue

            // 1. Coursework
            try {
                val cwList = client.list("courses/$courseId/courseWork", "courseWork")
                val subList = runCatching {
                    client.list("courses/$courseId/courseWork/-/studentSubmissions", "studentSubmissions", mapOf("userId" to "me"))
                }.getOrNull().orEmpty()
                val subMap = subList.associateBy { it.optString("courseWorkId") }

                for (cw in cwList) {
                    val cwId = cw.optString("id")
                    val sub = subMap[cwId]
                    val item = JSONObject(cw.toString()).apply {
                        put("kind", "courseWork")
                        put("courseName", courseName)
                        if (sub != null) put("submission", sub)
                    }
                    allHomework.add(item)

                    if (sub != null) {
                        ClassroomMapping.grade(cw, sub, courseName)?.let { allGrades.add(it) }
                    }
                }
            } catch (e: Exception) {
                // Ignore course fetch error
            }

            // 2. Course Materials
            try {
                val matList = client.list("courses/$courseId/courseWorkMaterials", "courseWorkMaterials")
                for (mat in matList) {
                    val item = JSONObject(mat.toString()).apply {
                        put("kind", "material")
                        put("courseName", courseName)
                    }
                    allHomework.add(item)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        cachedHomeworkList.clear()
        cachedHomeworkList.addAll(allHomework)
        cachedGradesList.clear()
        cachedGradesList.addAll(allGrades)
        return cachedHomeworkList
    }

    fun refreshAnnouncements(): List<JSONObject> {
        val client = getApiClient()
        val allAnnouncements = mutableListOf<JSONObject>()
        val currentCourses = if (coursesList.isEmpty()) refreshCourses() else coursesList

        for (c in currentCourses) {
            val courseId = c.optString("id")
            val courseName = c.optString("name")
            if (courseId.isBlank()) continue

            try {
                val annList = client.list("courses/$courseId/announcements", "announcements")
                for (ann in annList) {
                    val item = JSONObject(ann.toString()).apply {
                        put("kind", "announcement")
                        put("courseName", courseName)
                        put("date", optString("updateTime").substringBefore('T'))
                        put("title", courseName)
                        put("subject", optString("text").take(60))
                        put("id", "${courseId}:${optString("id")}")
                    }
                    allAnnouncements.add(item)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        cachedAnnouncementsList.clear()
        cachedAnnouncementsList.addAll(allAnnouncements)
        return cachedAnnouncementsList
    }

    private fun getApiClient(): ClassroomApi {
        return ClassroomApi(
            token = {
                currentToken ?: fetchFreshToken() ?: throw ClassroomSignInRequired()
            },
            renewToken = {
                currentToken = fetchFreshToken() ?: throw ClassroomSignInRequired()
            }
        )
    }

    private fun fetchFreshToken(): String? {
        val ctx = contextRef?.get() ?: return null
        return try {
            val authClient = Identity.getAuthorizationClient(ctx)
            val authResult = Tasks.await(authClient.authorize(request(email)))
            val token = authResult.accessToken
            if (!token.isNullOrBlank()) {
                currentToken = token
                token
            } else null
        } catch (e: Exception) {
            null
        }
    }

    companion object : Platform.Companion {
        val scopes = listOf(
            "classroom.courses.readonly",
            "classroom.coursework.me.readonly",
            "classroom.courseworkmaterials.readonly",
            "classroom.announcements.readonly",
            "classroom.profile.emails",
            "userinfo.email"
        ).map { if (it.startsWith("http")) it else "https://www.googleapis.com/auth/$it" }

        fun request(email: String? = null): AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(scopes.map(::Scope)).apply {
                if (!email.isNullOrBlank()) setAccount(Account(email, "com.google"))
                else setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            }.build()

        suspend fun connect(context: Context, token: String, email: String?): GoogleClassroomPlatform {
            val profile = ClassroomApi(token = { token }).get("https://www.googleapis.com/oauth2/v3/userinfo")
            val accountEmail = profile.getString("email")
            require(email.isNullOrBlank() || accountEmail.equals(email, ignoreCase = true)) {
                "Google authorized a different account. Choose the account you want to connect."
            }
            val provider = PlatformStorage.loadPlatforms(context).filterIsInstance<GoogleClassroomPlatform>()
                .firstOrNull { it.email.equals(accountEmail, ignoreCase = true) } ?: GoogleClassroomPlatform()
            provider.email = accountEmail
            provider.attach(context)
            provider.currentToken = token
            provider.refreshCourses()
            runCatching {
                provider.refreshHomework()
                provider.refreshAnnouncements()
            }
            provider.loggedIn = true
            return provider
        }

        @JvmStatic
        override fun fromJson(obj: JSONObject): Platform {
            val p = GoogleClassroomPlatform(obj.optString("id").ifBlank { generatePlatformId() })
            p.displayName = obj.optString("name", "Google Classroom")
            p.platformDisplayName = obj.optString("platformDisplayName", "Google Classroom")
            p.email = obj.optString("email", "")
            p.loggedIn = obj.optBoolean("loggedIn", false)
            obj.optJSONArray("courses")?.let { arr ->
                p.coursesList.clear()
                p.coursesList.addAll(arr.objects())
            }
            obj.optJSONArray("cachedHomework")?.let { arr ->
                p.cachedHomeworkList.clear()
                p.cachedHomeworkList.addAll(arr.objects())
            }
            obj.optJSONArray("cachedGrades")?.let { arr ->
                p.cachedGradesList.clear()
                p.cachedGradesList.addAll(arr.objects())
            }
            obj.optJSONArray("cachedAnnouncements")?.let { arr ->
                p.cachedAnnouncementsList.clear()
                p.cachedAnnouncementsList.addAll(arr.objects())
            }
            return p
        }
    }
}
