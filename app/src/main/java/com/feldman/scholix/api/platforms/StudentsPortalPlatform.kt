package com.feldman.scholix.api.platforms

import android.content.Context
import android.util.Log
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.Type
import com.feldman.scholix.api.UnsafeOkHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Year
import java.util.*

/**
 * Ministry-of-Education "Students & Graduates Portal"
 * (פורטל תלמידים ובוגרים, https://students.education.gov.il) as a Scholix
 * provider. Surfaces the matriculation (bagrut) grades.
 *
 * Auth model (verified against a captured browser session)
 * --------------------------------------------------------
 * Sign-in relays the user's typed credentials through an off-screen browser
 * engine ([com.feldman.scholix.ui.HiddenMoeLogin]) -- no browser UI is shown --
 * and everything afterwards is plain OkHttp. That split is forced by the IdP: lgn.edu.gov.il sits behind F5 Distributed Cloud Bot Defense
 * (`server: volt-adc`). Its login POST requires an `x-security-csrf-token`
 * header plus a `csrt` query token, both produced by obfuscated `/TSbd/`
 * browser JS and present in neither the served HTML nor any cookie. A headless
 * client gets the password validated (a wrong password still returns
 * `isError:true`) but the authenticated session is never honoured.
 *
 * The DATA endpoints require none of that — only:
 *   1. the education.gov.il session cookie, and
 *   2. the `csrt` query token (without it the API 404s).
 *
 *   GET /api/public/getuserinfo?csrt=..   -> { TZehut, FirstName, LastName, ... }
 *   GET /api/bgr/fullgradespage?csrt=..   -> { grades[], hasmachot[], miktzoa[], config }
 *   GET /api/bgr/getgrades/<codes>?csrt=. -> per-exam records (TZIYUN_SOFI, ...)
 *
 * Grades come from two calls: read `hasmachot[].CODE_MAARECHET` (excluding 1)
 * from fullgradespage, then drill into getgrades/<codes>. If there are no such
 * codes we fall back to the fullgradespage `miktzoa` subject summary.
 *
 * Only grades are supported (the portal has no schedule/attendance/messages).
 */
class StudentsPortalPlatform() : Platform {
    override var platformDisplayName: String = "StudentsPortalPlatform"

    // Native username/password fields. The credentials are relayed to the MOE
    // login page by an off-screen engine (see HiddenMoeLogin) because the IdP is
    // bot-protected. They are stored like every other provider's, so the session
    // can be re-established when the SSO cookie expires -- without them the
    // provider silently dies the first time the cookie lapses.
    // NOTE: the setters are required. Platform.applyLoginFields() only invokes
    // field.setter, so a field declared with just a getter is silently dropped
    // when the user edits the provider -- which is why the password kept coming
    // back empty here while other providers kept theirs.
    private val loginFields = LoginFields()
        .addField(
            id = "username", type = Type.Username,
            getter = { it.getUsername() },
            setter = { platform, value -> platform.setUsername(value.orEmpty()) },
        )
        .addField(
            id = "password", type = Type.Password,
            getter = { it.getPassword() },
            setter = { platform, value -> platform.setPassword(value.orEmpty()) },
        )

    private var username: String? = null
    private var password: String? = null
    private var displayName: String? = null
    private var studentId: String? = null
    private var _cookies: String? = null
    private var csrt: String? = null
    private val _client: OkHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient()

    override var editing: Boolean = false
    override var loggedIn: Boolean = false
    private val _courses: ArrayList<JSONObject> = ArrayList()

    override var id: String = generateId()
        private set

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = false
    override val supportsAttendance: Boolean = false

    init {
        if (id.isBlank()) id = generateId()
    }

    // -- low-level HTTP -------------------------------------------------------

    private fun httpGet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Cookie", _cookies ?: "")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Referer", GRADES_PAGE)
                .get()
                .build()
            _client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    Log.w("StudentsPortal", "GET $url -> HTTP ${response.code}")
                    return null
                }
                body
            }
        } catch (e: Exception) {
            Log.e("StudentsPortal", "GET failed: $url", e)
            null
        }
    }

    /**
     * The csrt token. The portal API 404s without it. It is injected by the
     * site's JS, so the sign-in captures it from the live DOM; if it is missing
     * or stale we re-scrape it from a portal page using the session cookie.
     */
    private fun ensureCsrt(refresh: Boolean = false): String? {
        if (!refresh) csrt?.let { return it }
        for (url in listOf(GRADES_PAGE, "$BASE/")) {
            val html = httpGet(url) ?: continue
            Regex("csrt=([0-9]{6,})").find(html)?.let { csrt = it.groupValues[1]; return csrt }
        }
        if (!refresh) Log.w("StudentsPortal", "no csrt available; API calls will 404")
        return csrt
    }

    /**
     * GET an /api/ endpoint with cookie + csrt. `endpoint` is e.g. "bgr/new".
     * A stale csrt makes the API 404, so retry once with a freshly scraped one
     * before giving up -- that was a silent cause of "grades stopped working".
     */
    private fun apiGet(endpoint: String): String? {
        val sep = if (endpoint.contains("?")) "&" else "?"
        val token = ensureCsrt() ?: return null
        httpGet("$API$endpoint${sep}csrt=$token")?.let { return it }

        val fresh = ensureCsrt(refresh = true) ?: return null
        if (fresh == token) return null
        Log.d("StudentsPortal", "retrying $endpoint with refreshed csrt")
        return httpGet("$API$endpoint${sep}csrt=$fresh")
    }

    // -- auth -----------------------------------------------------------------

    /** Validate a captured cookie via getuserinfo; records identity on success. */
    private fun loginWithCookies(cookieHeader: String): Boolean {
        _cookies = cookieHeader
        return try {
            val body = apiGet("public/getuserinfo") ?: return false
            val info = JSONObject(body)
            // TZehut is the ID number; the SPA treats > -1 as authenticated.
            val tzehut = info.optLong("TZehut", -1)
            if (tzehut <= -1) {
                Log.w("StudentsPortal", "getuserinfo: not authenticated (TZehut=$tzehut)")
                return false
            }
            studentId = tzehut.toString()
            displayName = "${info.optString("FirstName")} ${info.optString("LastName")}"
                .trim().ifBlank { "פורטל תלמידים" }
            loggedIn = true
            true
        } catch (e: Exception) {
            Log.e("StudentsPortal", "loginWithCookies exception", e)
            false
        }
    }

    override fun isLoggedIn(): Boolean = loggedIn

    override fun refreshCookies(): Boolean {
        val c = _cookies ?: return false
        // Re-validate the stored cookie, re-scraping csrt first in case that (and
        // not the session) is what went stale.
        ensureCsrt(refresh = true)
        if (loginWithCookies(c)) return true
        // The SSO session itself has lapsed. We cannot replay the login headlessly
        // (the IdP is bot-protected), so the UI must re-run HiddenMoeLogin with
        // the stored credentials -- see needsInteractiveRelogin.
        loggedIn = false
        Log.w("StudentsPortal", "session expired; interactive re-login required")
        return false
    }

    /**
     * True when the session lapsed but we still hold credentials, so the UI can
     * silently re-run the off-screen sign-in instead of asking the user to retype.
     */
    fun needsInteractiveRelogin(): Boolean =
        !loggedIn && !username.isNullOrBlank() && !password.isNullOrBlank()

    /**
     * Re-establish this provider's session from a cookie captured by a fresh
     * off-screen sign-in, keeping the provider's id (and so its settings and
     * course overrides) intact.
     */
    fun adoptSession(cookieHeader: String, freshCsrt: String?): Boolean {
        freshCsrt?.takeIf { it.isNotBlank() }?.let { csrt = it }
        return loginWithCookies(cookieHeader)
    }

    /**
     * Adopt an existing provider id. Re-signing in builds a fresh instance, and
     * per-provider settings (course order/visibility, free periods) are keyed by
     * id -- so the refreshed session must keep the id it replaces.
     */
    fun withId(existingId: String): StudentsPortalPlatform {
        if (existingId.isNotBlank()) id = existingId
        return this
    }

    // -- grades ---------------------------------------------------------------

    private fun str(g: JSONObject, key: String): String {
        val v = g.opt(key)
        return when {
            v == null || v == JSONObject.NULL -> ""
            v is Number -> if (v.toDouble() % 1.0 == 0.0) v.toInt().toString() else v.toString()
            else -> v.toString().trim()
        }
    }

    /** First meaningful value among the several TZIYUN_* grade fields. */
    private fun gradeValue(g: JSONObject): String {
        for (key in listOf("TZIYUN_SOFI", "TZIYUN_BCHINA", "TZIYUN_T", "TZIYUN_M", "TZIYUN_BEIT_SEFER")) {
            val s = str(g, key)
            if (s.isNotBlank() && s != "0") return s
        }
        return ""
    }

    private fun subjectName(g: JSONObject): String =
        str(g, "SHEM_MIKTZOA").ifBlank { str(g, "SHEM_MIKZOA") }.ifBlank { "בגרות" }

    private fun moedYear(g: JSONObject): String {
        val moed = str(g, "TEUR_CODE_MOED").ifBlank { str(g, "TEUR_MOED") }
        val year = str(g, "TEUR_SHANA").ifBlank { str(g, "SHANA") }
        return listOf(moed, year).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun mapGrade(g: JSONObject): JSONObject {
        val form = str(g, "SHEM_SHEELON")
        val my = moedYear(g)
        val name = listOf(form, my).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "בגרות" }
        return JSONObject()
            .put("subject", subjectName(g))
            .put("name", name)
            .put("grade", gradeValue(g))
            .put("date", my)
    }

    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        val grades = JSONArray()
        if (_cookies == null) return grades.put(JSONObject().put("error", "login_failed"))

        try {
            val pageBody = apiGet("bgr/fullgradespage")
                ?: return JSONArray().put(JSONObject().put("error", "login_failed"))
            // An expired SSO session answers with an HTML login redirect rather
            // than JSON. Report that as a login failure (actionable) instead of
            // letting the JSON parser throw into the generic "unknown error".
            val page = try {
                JSONObject(pageBody)
            } catch (parse: JSONException) {
                Log.w("StudentsPortal", "fullgradespage was not JSON; session expired")
                loggedIn = false
                return JSONArray().put(JSONObject().put("error", "login_failed"))
            }

            // Exam-system codes to drill into (CODE_MAARECHET != 1).
            val codes = LinkedHashSet<String>()
            val hasmachot = page.optJSONArray("hasmachot") ?: JSONArray()
            for (i in 0 until hasmachot.length()) {
                val h = hasmachot.optJSONObject(i) ?: continue
                val cs = str(h, "CODE_MAARECHET")
                if (cs.isNotBlank() && cs != "1") codes.add(cs)
            }

            if (codes.isNotEmpty()) {
                val body = apiGet("bgr/getgrades/" + codes.joinToString("-"))
                // Same here: fall back to the page's own summary rather than
                // failing the whole request if this drill-down misbehaves.
                val arr = body?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { grades.put(mapGrade(it)) }
                }
            }

            // Fallback: the per-subject summary on the grades page itself.
            if (grades.length() == 0) {
                val miktzoa = page.optJSONArray("miktzoa") ?: JSONArray()
                for (i in 0 until miktzoa.length()) {
                    miktzoa.optJSONObject(i)?.let { grades.put(mapGrade(it)) }
                }
            }
        } catch (io: IOException) {
            Log.e("StudentsPortal", "Server unreachable", io)
            return JSONArray().put(JSONObject().put("error", "server_unreachable"))
        } catch (e: Exception) {
            Log.e("StudentsPortal", "Failed to fetch grades", e)
            return JSONArray().put(JSONObject().put("error", "unknown_error"))
        }

        Log.i("StudentsPortal", "Bagrut grades loaded (${grades.length()} items)")
        return grades
    }

    override fun getCourses(): ArrayList<JSONObject> {
        if (_courses.isEmpty()) {
            _courses.add(
                JSONObject()
                    .put("name", "בגרויות")
                    .put("index", 0)
                    .put("year", Year.now().value)
            )
        }
        val course = _courses[0]
        if (!course.has("grades")) {
            course.put("grades", getGrades("בגרויות", null, null))
        }
        return _courses
    }

    override fun getSubjectList(): List<String> {
        val subjects = sortedSetOf<String>()
        try {
            val course = getCourses().firstOrNull() ?: return emptyList()
            val grades = course.optJSONArray("grades") ?: return emptyList()
            for (i in 0 until grades.length()) {
                val s = grades.optJSONObject(i)?.optString("subject", "") ?: ""
                if (s.isNotBlank() && s != "בגרות") subjects.add(s)
            }
        } catch (e: Exception) {
            Log.e("StudentsPortal", "Failed to build subject list", e)
        }
        return subjects.toList()
    }

    // -- unsupported capabilities (portal has grades only) --------------------

    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject =
        JSONObject()

    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject =
        JSONObject()

    override fun getScheduleIndexes(): JSONArray = JSONArray()

    override fun getAttendanceEvents(period: String): JSONObject =
        JSONObject().put("events", JSONObject())

    override fun getAttendanceEvents(year: Int, period: String): JSONObject =
        JSONObject().put("events", JSONObject())

    override fun getMessages(page: Int): JSONArray = JSONArray()

    override fun getMessageDetails(messageId: String): JSONObject = JSONObject()

    override suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean = false

    // -- identity / persistence ----------------------------------------------

    override fun getName(): String = displayName ?: ""
    override fun getUsername(): String = username ?: ""
    override fun getPassword(): String = password ?: ""

    override fun isEditing(): Boolean = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun setName(name: String) { this.displayName = name }
    override fun setUsername(username: String) { this.username = username }
    override fun setPassword(password: String) { this.password = password }

    override fun getLoginFields(): LoginFields = loginFields

    override fun getInfo(): JSONObject =
        JSONObject()
            .put("name", "פורטל תלמידים ובוגרים")
            .put("supportsEndpoints", JSONArray(listOf("grades")))
            .put("loginVariables", JSONArray(listOf("username", "password")))
            .put("supportsSchedule", false)
            .put("supportsGrades", true)
            .put("supportsAttendance", false)

    override fun toString(): String =
        "StudentsPortalPlatform(name=$displayName, loggedIn=$loggedIn)"

    override fun toJson(): JSONObject {
        val course = if (_courses.isNotEmpty()) _courses[0] else null
        return JSONObject()
            .put("class", javaClass.name)
            .put("id", id)
            .put("name", displayName)
            .put("username", username)
            .put("password", password)
            .put("studentId", studentId)
            .put("cookies", _cookies)
            .put("csrt", csrt)
            .put("loggedIn", loggedIn)
            .put("courses", JSONArray().apply { course?.let { put(it) } })
            .put("platformDisplayName", platformDisplayName)
    }

    companion object : Platform.Companion {

        const val BASE = "https://students.education.gov.il"
        const val API = "$BASE/api/"
        const val GRADES_PAGE = "$BASE/matriculation-exams/grades-page"
        // Entry point for the WebView sign-in; ReturnUrl brings the browser back
        // to the portal, which is our signal to capture the cookie.
        const val EDULOGIN_URL =
            "https://apps2.education.gov.il/EduLogin/login.aspx?ReturnUrl=$GRADES_PAGE"

        /**
         * Build a logged-in provider from a WebView-captured education.gov.il
         * cookie plus the `csrt` token the API requires.
         *
         * Sign-in must happen in a WebView: lgn.edu.gov.il is behind F5
         * Distributed Cloud Bot Defense, whose `x-security-csrf-token` / `csrt`
         * tokens are produced by obfuscated browser JS and appear in neither the
         * HTML nor any cookie. Data calls need neither token, so everything after
         * login is plain OkHttp.
         */
        @JvmStatic
        @JvmOverloads
        fun loginWithCookies(
            cookieHeader: String,
            csrt: String? = null,
            username: String? = null,
            password: String? = null,
        ): StudentsPortalPlatform {
            val p = StudentsPortalPlatform()
            p.csrt = csrt
            // Keep the credentials so an expired session can be re-established.
            p.username = username
            p.password = password
            if (!p.loginWithCookies(cookieHeader)) {
                Log.e("StudentsPortal", "loginWithCookies: cookie not authenticated")
            }
            return p
        }

        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        override fun fromJson(obj: JSONObject): StudentsPortalPlatform {
            val p = StudentsPortalPlatform()
            p.id = obj.optString("id", "").ifEmpty { generateId() }
            p.displayName = obj.optString("name", "").ifEmpty { null }
            p.username = obj.optString("username", "").ifEmpty { null }
            p.password = obj.optString("password", "").ifEmpty { null }
            p.studentId = obj.optString("studentId", "").ifEmpty { null }
            p._cookies = obj.optString("cookies", "").ifEmpty { null }
            p.csrt = obj.optString("csrt", "").ifEmpty { null }
            p.loggedIn = obj.optBoolean("loggedIn", false)
            p.platformDisplayName = obj.optString("platformDisplayName")

            val coursesArray = obj.optJSONArray("courses")
            if (coursesArray != null && coursesArray.length() > 0) {
                coursesArray.optJSONObject(0)?.let { p._courses.add(it) }
            }
            return p
        }

        /**
         * Validate MOE credentials WITHOUT a browser.
         *
         * F5 blocks the scripted client from getting a *session*, but it does let
         * the credential POST through and answers truthfully: a wrong password
         * returns isError:true, the right one isError:false. That is exactly what
         * a credential check needs, so editing/verifying a password works
         * headlessly even though signing in does not.
         */
        override fun checkCredentials(loginFields: LoginFields): Boolean {
            val user = loginFields.getValue("username").orEmpty()
            val pass = loginFields.getValue("password").orEmpty()
            if (user.isBlank() || pass.isBlank()) return false
            return validateMoeCredentials(user, pass)
        }

        @JvmStatic
        fun validateMoeCredentials(user: String, pass: String): Boolean {
            return try {
                val jar = SimpleCookieJar()
                val client = UnsafeOkHttpClient.getUnsafeOkHttpClient()
                    .newBuilder().cookieJar(jar).build()

                // The IdP answers with F5 auto-submit interstitials before the
                // real login page; follow them the way a browser would.
                var page = httpText(client, EDULOGIN_URL) ?: return false
                var url = EDULOGIN_URL
                var hops = 0
                while (hops++ < 6 && page.contains("<form", true) &&
                    (page.contains(".submit(", true) || page.length < 4000)
                ) {
                    val form = firstForm(page, url) ?: break
                    url = form.first
                    page = postForm(client, form.first, form.second) ?: break
                }

                val serverUrl = Regex("_serverUrl\\s*=\\s*'([^']+)'").find(page)
                    ?.groupValues?.get(1)
                    ?: "https://lgn.edu.gov.il/nidp/wsfed/ep?sid=0&sid=0"

                val body = postForm(
                    client, serverUrl,
                    mapOf(
                        "option" to "credential",
                        "isAjax" to "true",
                        "HIN_USERID" to user,
                        "Ecom_Password" to pass,
                        "g-recaptcha-response" to "+",
                    )
                ) ?: return false

                val json = JSONObject(body)
                val isError = json.optBoolean("isError", true)
                if (json.optBoolean("isCaptchaNeeded", false)) {
                    // The IdP is demanding a captcha; we cannot judge the password.
                    Log.w("StudentsPortal", "credential check blocked by captcha")
                    return false
                }
                !isError
            } catch (e: Exception) {
                Log.e("StudentsPortal", "credential check failed", e)
                false
            }
        }

        private fun httpText(client: OkHttpClient, url: String): String? = try {
            client.newCall(
                Request.Builder().url(url).addHeader("User-Agent", UA).get().build()
            ).execute().use { it.body.string() }
        } catch (e: Exception) {
            null
        }

        private fun postForm(
            client: OkHttpClient, url: String, fields: Map<String, String>
        ): String? = try {
            val form = okhttp3.FormBody.Builder().apply {
                fields.forEach { (k, v) -> add(k, v) }
            }.build()
            client.newCall(
                Request.Builder().url(url).addHeader("User-Agent", UA)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .post(form).build()
            ).execute().use { it.body.string() }
        } catch (e: Exception) {
            null
        }

        /** (absoluteAction, inputs) of the first <form>, for interstitial hops. */
        private fun firstForm(html: String, baseUrl: String): Pair<String, Map<String, String>>? {
            val tag = Regex("<form\\b[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return null
            val action = Regex("action\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                .find(tag.value)?.groupValues?.get(1) ?: baseUrl
            val absolute = runCatching { java.net.URI(baseUrl).resolve(action).toString() }
                .getOrDefault(action)
            val end = html.indexOf("</form", tag.range.last, ignoreCase = true)
                .let { if (it < 0) html.length else it }
            val fields = LinkedHashMap<String, String>()
            for (m in Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
                .findAll(html.substring(tag.range.first, end))) {
                val name = Regex("name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                    .find(m.value)?.groupValues?.get(1) ?: continue
                val value = Regex("value\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                    .find(m.value)?.groupValues?.get(1) ?: ""
                fields[name] = value
            }
            return Pair(absolute, fields)
        }

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        /** Keeps the IdP's session/anti-bot cookies across the interstitial hops. */
        private class SimpleCookieJar : okhttp3.CookieJar {
            private val store = mutableListOf<okhttp3.Cookie>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                for (c in cookies) {
                    store.removeAll { it.name == c.name && it.domain == c.domain }
                    store.add(c)
                }
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
                store.filter { it.matches(url) }
        }
    }
}
