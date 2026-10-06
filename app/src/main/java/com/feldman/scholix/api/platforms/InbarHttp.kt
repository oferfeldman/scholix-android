package com.feldman.scholix.api.platforms

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class InbarSessionExpired : IOException("Inbar session expired. Sign in again with an SMS code.")
internal class InbarSmsCodeRejected : IOException("The SMS code was not accepted. Please sign in again.")
internal class InbarSmsRestricted : IOException("Inbar has temporarily blocked sending SMS codes and verifying them. Please try again later.")
internal class InbarGradeLayoutChanged(val year: Int, val years: List<Int>, val layout: String) :
    IOException("Signed in to Inbar, but its grade table could not be read.")

internal class InbarCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
    @Synchronized fun toJson(): JSONArray = JSONArray(cookies.map { it.toString() })
    @Synchronized fun restore(source: JSONArray) {
        cookies.clear()
        for (i in 0 until source.length()) {
            Cookie.parse(InbarHttp.BASE.toHttpUrl(), source.getString(i))?.let {
                if (it.domain == "inbar.biu.ac.il") cookies += it
            }
        }
    }
}

internal data class InbarPage(val url: HttpUrl, val html: String) {
    fun document(): Document = Jsoup.parse(html, url.toString())
}

/** Direct Web Forms requests, using normal TLS validation and fresh form state. */
internal class InbarHttp(
    val cookieJar: InbarCookieJar = InbarCookieJar(),
    transport: OkHttpClient? = null
) {
    private val client = (transport ?: SHARED_CLIENT).newBuilder()
        .cookieJar(cookieJar).followRedirects(false).followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS).build()
    private var challenge: InbarPage? = null
    private var gradePage: InbarPage? = null
    private var gradeYear: Int? = null
    private var smsTime: Long = 0
    private var schedulePage: InbarSchedulePage? = null
    private var scheduleFetchedAt: Long = 0

    private fun request(initial: Request, allowAuthenticationRedirect: Boolean = true): InbarPage {
        var request = initial
        repeat(10) {
            if (request.url.scheme != "https" || request.url.host != "inbar.biu.ac.il" || request.url.port != 443) {
                throw IOException("Inbar redirected outside its portal. Use the portal's browser sign-in.")
            }
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code !in listOf(301, 302, 303, 307, 308)) {
                    throw IOException("Inbar returned HTTP ${response.code}")
                }
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: throw IOException("Inbar redirect missing Location")
                    val destination = request.url.resolve(location) ?: throw IOException("Invalid Inbar redirect")
                    if (!allowAuthenticationRedirect &&
                        listOf("/Live/Login.aspx", "/Live/Authenticate.aspx").any { destination.encodedPath.equals(it, ignoreCase = true) }) {
                        // Reading grades must not visit an endpoint that can create another SMS challenge.
                        throw InbarSessionExpired()
                    }
                    val builder = request.newBuilder().url(destination)
                    if (response.code == 303 || (response.code in listOf(301, 302) && request.method == "POST")) builder.get()
                    request = builder.build()
                } else {
                    return InbarPage(response.request.url, response.body.string())
                }
            }
        }
        throw IOException("Too many Inbar redirects")
    }

    private fun get(url: String): InbarPage = request(Request.Builder().url(url).build(),
        allowAuthenticationRedirect = !isDataPath(url.toHttpUrl().encodedPath))

    private fun post(page: InbarPage, overrides: Map<String, String>): InbarPage {
        val form = page.document().selectFirst("form#form1") ?: throw IOException("Inbar form is missing")
        val body = FormBody.Builder()
        for ((key, value) in successfulControls(form)) if (key !in overrides) body.add(key, value)
        overrides.forEach { (key, value) -> body.add(key, value) }
        val action = page.url.resolve(form.attr("action")) ?: throw IOException("Invalid Inbar form action")
        return request(Request.Builder().url(action).header("Referer", page.url.toString())
            .header("Origin", BASE).post(body.build()).build(),
            allowAuthenticationRedirect = !isDataPath(page.url.encodedPath))
    }

    @Synchronized fun requestSms(identity: String, phone: String) {
        require(identity.isNotBlank() && phone.isNotBlank()) { "Enter ID/passport and registered mobile" }
        val page = get(LOGIN)
        val doc = page.document()
        if (doc.selectFirst("input[name=edtUsername]") == null || doc.selectFirst("input[name=edtMobile]") == null) {
            throw IOException("Inbar login has changed")
        }
        val button = doc.selectFirst("input[name=btnLogin]") ?: throw IOException("Inbar login button missing")
        val next = post(page, mapOf("edtUsername" to identity, "edtMobile" to phone, "btnLogin" to button.attr("value")))
        checkSmsRestriction(next)
        if (next.document().selectFirst("input[name=edtCode]") == null) throw IOException("Inbar did not accept the login. Check ID and registered mobile.")
        challenge = next
        smsTime = System.nanoTime()
    }

    @Synchronized fun resendSms() {
        val page = challenge ?: throw IOException("Request an SMS first")
        if (System.nanoTime() - smsTime < TimeUnit.SECONDS.toNanos(45)) throw IOException("Wait 45 seconds before requesting another SMS")
        val button = page.document().selectFirst("input[name=btnSendSmsCode]") ?: throw IOException("Inbar resend button missing")
        val next = post(page, mapOf("btnSendSmsCode" to button.attr("value")))
        checkSmsRestriction(next)
        challenge = next
        smsTime = System.nanoTime()
    }

    @Synchronized fun verifySms(code: String): InbarGradePage {
        require(code.matches(Regex("[0-9]{4,10}"))) { "Enter the verification code" }
        val page = challenge ?: throw IOException("Request an SMS first")
        val button = page.document().selectFirst("input[name=btnVerify]") ?: throw IOException("Inbar verify button missing")
        val next = post(page, mapOf("edtCode" to code, "btnVerify" to button.attr("value")))
        checkSmsRestriction(next)
        if (next.document().selectFirst("input[name=edtCode]") != null) {
            challenge = next // Keep fresh state for a manually corrected code.
            throw InbarSmsCodeRejected()
        }
        val grades = try { if (next.url.encodedPath == "/Live/StudentGradesList.aspx") {
            readGradePages(next, InbarGrades.parse(next.html, combineGroups = false))
        } else grades() } catch (e: InbarGradeLayoutChanged) {
            challenge = null // The authenticated grades page proves that SMS verification succeeded.
            throw e
        }
        challenge = null
        return grades
    }

    private fun checkSmsRestriction(page: InbarPage) {
        // Orbit reports the restriction in a JavaScript alert while still rendering edtCode.
        // Treat it as a server refusal, rather than waiting for an SMS or trying more codes.
        val restricted = page.document().select("script").any {
            it.data().replace(Regex("\\s+"), " ")
                .contains("לא ניתן לשלוח עוד הודעות/לאמת קוד בשלב זה")
        }
        if (restricted) {
            challenge = null
            throw InbarSmsRestricted()
        }
    }

    @Synchronized fun grades(year: Int? = null): InbarGradePage {
        // A year switch posts the latest form directly. Refreshing the same year still GETs it.
        var page = gradePage?.takeIf { year != null && gradeYear != year } ?: get(GRADES)
        var result = InbarGrades.parse(page.html, combineGroups = false)
        if (year != null && result.year != year) {
            if (year !in result.years) throw IOException("Academic year $year is not available in Inbar")
            val selector = page.document().selectFirst("select#cmbActiveYear") ?: throw IOException("Inbar year selector missing")
            val name = selector.attr("name")
            page = post(page, mapOf(name to year.toString(), "__EVENTTARGET" to name, "__EVENTARGUMENT" to ""))
            result = InbarGrades.parse(page.html, combineGroups = false)
            if (result.year != year) throw IOException("Inbar did not select year $year")
        }
        return readGradePages(page, result)
    }

    private fun readGradePages(initial: InbarPage, first: InbarGradePage): InbarGradePage {
        var page = initial
        var parsed = first
        val courses = first.courses.toMutableList()
        val visited = mutableSetOf(first.page)
        while (parsed.next != null) {
            if (visited.size >= 100) throw IOException("Inbar grade pagination did not finish. Previous grades preserved.")
            val next = parsed.next!!
            page = post(page, mapOf("__EVENTTARGET" to next.target, "__EVENTARGUMENT" to next.argument))
            val loaded = InbarGrades.parse(page.html, combineGroups = false)
            if (loaded.year != first.year || loaded.page != parsed.page + 1 || !visited.add(loaded.page))
                throw IOException("Inbar did not advance the grades page. Previous grades preserved.")
            courses += loaded.courses
            parsed = loaded
        }
        gradePage = page
        gradeYear = first.year
        return first.copy(courses = InbarGrades.combineTeachingGroups(courses), next = null)
    }

    /** One timetable request serves every weekday; filters use the latest returned form state. */
    @Synchronized fun schedule(year: Int? = null, period: String? = null): InbarSchedulePage {
        schedulePage?.takeIf { (year == null || it.year == year) && (period == null || it.period == period) &&
            System.nanoTime() - scheduleFetchedAt < TimeUnit.MINUTES.toNanos(5) }?.let { return it }
        var page = get(SCHEDULE)
        var result = InbarSchedule.parse(page.html)
        if (year != null && year != result.year) {
            if (year !in result.years) throw IOException("Academic year $year is not available in Inbar")
            val name = page.document().selectFirst("select#cmbActiveYear")!!.attr("name")
            page = post(page, mapOf(name to year.toString(), "__EVENTTARGET" to name, "__EVENTARGUMENT" to ""))
            result = InbarSchedule.parse(page.html)
            if (result.year != year) throw IOException("Inbar did not select schedule year $year")
        }
        if (period != null && period != result.period) {
            if (period !in result.periods) throw IOException("Semester is not available in Inbar")
            val name = page.document().selectFirst("select[id$=ddlPeriodTypeFilter2]")!!.attr("name")
            page = post(page, mapOf(name to period, "__EVENTTARGET" to name, "__EVENTARGUMENT" to ""))
            result = InbarSchedule.parse(page.html)
            if (result.period != period) throw IOException("Inbar did not select schedule semester")
        }
        schedulePage = result
        scheduleFetchedAt = System.nanoTime()
        return result
    }

    companion object {
        // Restored providers keep their own cookies while reusing HTTPS connections.
        private val SHARED_CLIENT = OkHttpClient()
        const val BASE = "https://inbar.biu.ac.il"
        const val LOGIN = "$BASE/Live/Login.aspx?ReturnUrl=%2fLive%2fStudentGradesList.aspx"
        const val GRADES = "$BASE/Live/StudentGradesList.aspx"
        const val SCHEDULE = "$BASE/Live/StudentPeriodSchedule.aspx"
        private fun isDataPath(path: String) = listOf("/Live/StudentGradesList.aspx", "/Live/StudentPeriodSchedule.aspx")
            .any { path.equals(it, ignoreCase = true) }

        fun successfulControls(form: Element): List<Pair<String, String>> = buildList {
            for (element in form.select("input[name], select[name], textarea[name]")) {
                if (element.hasAttr("disabled")) continue
                val type = element.attr("type").lowercase()
                if (type in listOf("submit", "button", "image", "reset", "file")) continue
                if (type in listOf("radio", "checkbox") && !element.hasAttr("checked")) continue
                val name = element.attr("name")
                when (element.tagName()) {
                    "select" -> {
                        val selected = element.select("option[selected]").ifEmpty {
                            if (element.hasAttr("multiple")) emptyList() else element.select("option").take(1)
                        }
                        selected.forEach { add(name to if (it.hasAttr("value")) it.attr("value") else it.text()) }
                    }
                    "textarea" -> add(name to element.wholeText())
                    else -> add(name to if (element.hasAttr("value")) element.attr("value") else if (type in listOf("checkbox", "radio")) "on" else "")
                }
            }
        }
    }
}

internal data class InbarGradePostback(val target: String, val argument: String)
internal data class InbarGradePage(val year: Int, val years: List<Int>, val courses: List<JSONObject>,
    val page: Int = 1, val next: InbarGradePostback? = null)

internal object InbarGrades {
    fun parse(html: String, combineGroups: Boolean = true): InbarGradePage {
        val doc = Jsoup.parse(html)
        val table = doc.selectFirst("table#ContentPlaceHolder1_gvGradesList") ?: throw InbarSessionExpired()
        val selector = doc.selectFirst("select#cmbActiveYear") ?: throw IOException("Inbar year selector missing")
        val selected = selector.selectFirst("option[selected]") ?: selector.selectFirst("option")
        val year = selected?.attr("value")?.toIntOrNull() ?: throw IOException("Invalid Inbar academic year")
        val years = selector.select("option").mapNotNull { it.attr("value").toIntOrNull() }
        val rows = table.select("tr").filter { it.parents().firstOrNull { parent -> parent.tagName() == "table" } === table }
        fun label(value: String) = value.replace(Regex("[\\s\\u00a0]+"), " ").trim()
        val headers = rows.firstOrNull { row -> row.children().any { it.tagName() == "th" } }
            ?.children()?.filter { it.tagName() == "th" }?.map { label(it.text()) }.orEmpty()
        val courses = mutableListOf<JSONObject>()
        var currentPage = 1
        var nextPage: InbarGradePostback? = null
        for (row in rows) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.isEmpty()) continue
            fun changed(): Nothing = throw InbarGradeLayoutChanged(year, years,
                "headers=${headers.joinToString("|")}; cells=${cells.size}; spans=${cells.map { it.attr("colspan") }}; " +
                    "singleCellLabel=${if(cells.size==1)label(cells.single().text()).take(120) else ""}")
            val pageLinks = row.select("a[href]").mapNotNull { link ->
                Regex("__doPostBack\\('([^']*gvGradesList)',\\s*'(Page\\$(?:[0-9]+|Next|Previous|First|Last))'\\)")
                    .find(link.attr("href"))?.let { InbarGradePostback(it.groupValues[1], it.groupValues[2]) }
            }
            if (cells.size == 1 && (cells.single().attr("colspan").toIntOrNull() ?: 1) > 1 && pageLinks.isNotEmpty()) {
                currentPage = row.select("span").mapNotNull { it.text().trim().toIntOrNull() }.distinct().singleOrNull() ?: changed()
                nextPage = pageLinks.firstOrNull { it.argument == "Page\$Next" }
                    ?: pageLinks.firstOrNull { it.argument == "Page\$${currentPage + 1}" }
                if (nextPage == null && pageLinks.any { (it.argument.substringAfter('$').toIntOrNull() ?: 0) > currentPage }) changed()
                continue
            }
            // Web Forms can render an empty year as one spanning cell, with no headers.
            if (cells.all { it.text().isBlank() } ||
                (label(cells.first().text()) in listOf("אין נתונים", "No data", "No records") &&
                    cells.drop(1).all { it.text().isBlank() })) continue
            if (cells.size != headers.size || cells.any { (it.attr("colspan").toIntOrNull() ?: 1) != 1 })
                changed()
            fun cell(hebrew: String, english: String, required: Boolean = false): Element? {
                val index = headers.indexOfFirst { it == hebrew || it.equals(english, ignoreCase = true) }
                if (required && index < 0) changed()
                return cells.getOrNull(index)
            }
            val code = cell("קוד קבוצת קורס", "Code", true)!!.text()
            val name = cell("שם", "Name", true)!!.text()
            if (code.isBlank() || name.isBlank()) throw IOException("Inbar grade course is missing")
            val date = cell("ת.עדכון", "Updated")?.text().orEmpty()
            val key = "$year:$code"
            val grades = JSONArray()
            val assignmentTable = cell("מטלות", "Assignments")?.selectFirst("table")
            var assignmentIndex = 0
            var current: JSONObject? = null
            assignmentTable?.select("tr")?.forEach { detail ->
                val values = detail.children().filter { it.tagName() in listOf("td", "th") }.map { it.text() }
                if (detail.hasClass("AssignmentText") && values.size == 3) {
                    current = JSONObject().put("id", "$key:assignment:${assignmentIndex++}")
                        .put("subject", name).put("name", values[0]).put("grade", values[2].ifBlank { JSONObject.NULL })
                        .put("weight", values[1]).put("date", date).put("submissions", JSONArray())
                    grades.put(current)
                } else if (current != null && !detail.hasClass("AssignmentHeader") && values.size == 2) {
                    current!!.getJSONArray("submissions").put(JSONObject().put("type", values[0]).put("date", values[0]).put("grade", values[1]))
                    current!!.put("date", values[0].substringBefore(" "))
                }
            }
            val final = cell("ציון סופי", "Final grade", true)!!.selectFirst("span[id*=lblRowFinalGrade]")?.text().orEmpty()
            if (final.isNotBlank()) grades.put(JSONObject().put("id", "$key:final").put("subject", name)
                .put("name", "Final grade").put("type", "final").put("grade", final).put("date", date))
            val period = cell("תקופה", "Period", true)!!.text()
            val semester = when {
                period.contains("קיץ") -> "c"
                Regex("[אב][׳'\"]?").find(period.substringAfter("סמסטר", ""))?.value?.startsWith("א") == true -> "a"
                period.contains("סמסטר") && period.contains("ב") -> "b"
                else -> ""
            }
            courses += JSONObject().put("id", code).put("courseKey", key).put("name", name).put("year", year)
                .put("semester", semester).put("semesterPicker", false).put("teacher", cell("מרצה", "Lecturer")?.text().orEmpty())
                .put("period", period).put("credits", cell("נ\"ז", "Credits")?.text().orEmpty())
                .put("passingGrade", cell("ציון עובר", "Passing grade")?.text().orEmpty())
                .put("remark", cell("הערה", "Remark")?.text().orEmpty())
                .put("passRequestRemark", cell("בקשה לציון עובר", "Pass request")?.text().orEmpty()).put("grades", grades)
        }
        return InbarGradePage(year, years, if(combineGroups)combineTeachingGroups(courses) else courses, currentPage, nextPage)
    }

    /** The portal often lists a lecture and its ungraded tutorial as separate rows. */
    fun combineTeachingGroups(courses: List<JSONObject>): List<JSONObject> {
        val families = courses.groupBy { course ->
            listOf(course.optInt("year").toString(), course.optString("name"), course.optString("period"),
                course.optString("id").substringBeforeLast("-", course.optString("id")))
        }
        return families.values.flatMap { group ->
            val graded = group.filter { it.getJSONArray("grades").length() > 0 }
            // Preserve multiple independently graded offerings. Pending companion groups
            // remain metadata rather than extra empty course tabs.
            val displayed = graded.ifEmpty {
                listOf(group.maxBy { it.optString("credits").toDoubleOrNull() ?: 0.0 })
            }
            displayed.map { course ->
                if (group.size > 1) course.put("relatedGroups", JSONArray(group.filter { it !== course }.map { other ->
                    JSONObject().put("id", other.optString("id")).put("teacher", other.optString("teacher"))
                        .put("credits", other.optString("credits"))
                }))
                course
            }
        }
    }
}
