package com.feldman.scholix.openu

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

internal data class OpenUAssignment(
    val courseId: String, val courseName: String, val semester: String, val moodleId: String,
    val number: String, val title: String, val url: String, val module: String, val cmid: String,
    val due: String = "", val opens: String = "", val status: String = "", val statusCode: String = "",
    val grade: String = "", val weight: String = "", val examDate: String = "",
    val questionnaire: Boolean = false, val notebook: Boolean = false,
) {
    val key get() = "$courseId:$semester:$module:$cmid"
    val courseKey get() = "$courseId:$semester"
    val label get() = listOf(title, number).filter(String::isNotBlank).joinToString(" ")
    fun json() = JSONObject().apply {
        listOf("courseId" to courseId, "courseName" to courseName, "semester" to semester, "moodleId" to moodleId,
            "number" to number, "title" to title, "url" to url, "module" to module, "cmid" to cmid,
            "due" to due, "opens" to opens, "status" to status, "statusCode" to statusCode,
            "grade" to grade, "weight" to weight, "examDate" to examDate).forEach { (k, v) -> put(k, v) }
        put("questionnaire", questionnaire); put("notebook", notebook)
    }
    companion object {
        fun from(j: JSONObject) = OpenUAssignment(j.getString("courseId"), j.getString("courseName"), j.getString("semester"),
            j.optString("moodleId"), j.optString("number"), j.getString("title"), j.optString("url"),
            j.getString("module"), j.getString("cmid"), j.optString("due"), j.optString("opens"), j.optString("status"),
            j.optString("statusCode"), j.optString("grade"), j.optString("weight"), j.optString("examDate"),
            j.optBoolean("questionnaire"), j.optBoolean("notebook"))
    }
}

internal data class OpenUAssignmentFile(val name: String, val url: String, val group: String) {
    fun json() = JSONObject().put("name", name).put("url", url).put("group", group)
}

internal data class OpenUAssignmentDetail(
    val fields: List<Pair<String, String>>, val submission: List<Pair<String, String>>,
    val instructions: String, val files: List<OpenUAssignmentFile>, val notices: List<String>,
) {
    fun json() = JSONObject().put("fields", JSONArray(fields.map { JSONArray(listOf(it.first, it.second)) }))
        .put("submission", JSONArray(submission.map { JSONArray(listOf(it.first, it.second)) }))
        .put("instructions", instructions).put("files", JSONArray(files.map { it.json() })).put("notices", JSONArray(notices))
    companion object {
        fun from(j: JSONObject): OpenUAssignmentDetail {
            fun pairs(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map {
                a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) }
            } }
            return OpenUAssignmentDetail(pairs("fields"), pairs("submission"), j.optString("instructions"),
                j.getJSONArray("files").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { f ->
                    OpenUAssignmentFile(f.getString("name"), f.getString("url"), f.getString("group"))
                } } }, j.getJSONArray("notices").let { a -> (0 until a.length()).map { a.getString(it) } })
        }
    }
}

/** Selectors and course entry contract shared with the OpenU app's api/src/openu.ts. */
internal object OpenUAssignmentParser {
    const val BASE = "https://opal.openu.ac.il"
    fun courseUrl(course: JSONObject) = "$BASE/ouil/course.php?course=c${course.getString("id")}&semester=${course.getString("term")}"
    fun moodleId(html: String): String = Regex("(?:^|\\s)course-(\\d+)(?:\\s|$)")
        .find(Jsoup.parse(html).body().className())?.groupValues?.get(1)
        ?: throw IOException("OpenU did not open this course site. Reconnect the account and try again.")

    fun activityUrl(raw: String): Boolean = runCatching {
        val u = URI(raw)
        secure(u, "opal.openu.ac.il") && u.path in listOf("/mod/ouilassign/view.php", "/mod/quiz/view.php") &&
            Regex("(?:^|&)id=[1-9][0-9]*(?:&|$)").containsMatchIn(u.rawQuery.orEmpty())
    }.getOrDefault(false)

    fun fileUrl(raw: String): Boolean = runCatching {
        val u = URI(raw)
        (secure(u, "opal.openu.ac.il") && listOf("/pluginfile.php/", "/draftfile.php/", "/mod/ouilassign/type/file/viewfile.php/",
            "/mod/ouilassign/view/").any { u.path.startsWith(it) }) ||
            (secure(u, "sheilta.apps.openu.ac.il") && u.path == "/Main/api/coursesApi/downloadScanned")
    }.getOrDefault(false)

    private fun secure(u: URI, host: String) = u.scheme == "https" && u.host.equals(host, true) &&
        u.port in setOf(-1, 443) && u.userInfo == null && '\\' !in u.path.orEmpty() &&
        u.path.orEmpty().split('/').none { it == "." || it == ".." }

    fun assignments(html: String, course: JSONObject, moodleId: String): List<OpenUAssignment> {
        val doc = Jsoup.parse(html, BASE)
        if (doc.getElementById("ouilassign_index_list_container") == null)
            throw IOException("OpenU did not return the assignment list. The previous list has been kept.")
        val scores = course.optJSONArray("assignments") ?: JSONArray()
        val gradeRows = (0 until scores.length()).map { scores.getJSONObject(it) }
        fun matching(number: String) = gradeRows.firstOrNull { it.optString("number").trimStart('0') == number.trimStart('0') }
        val rows = doc.select("#ouilassign_index_list_container > li, #ouilassign_index_list_container > span > li").mapNotNull { item ->
            val link = item.selectFirst("[index-item=name] a") ?: return@mapNotNull null
            val url = link.absUrl("href")
            if (!activityUrl(url)) return@mapNotNull null
            val number = link.selectFirst("span")?.text().orEmpty()
            val title = link.clone().apply { selectFirst("span")?.remove() }.text().ifBlank { "Assignment" }
            fun field(key: String) = item.selectFirst("[index-item=$key]")?.clone()?.apply {
                select(".ouilassign_index_list_title").remove()
            }?.text().orEmpty()
            val score = matching(number)
            val grade = field("grade").takeIf { it.isNotBlank() && it != "-" } ?: score?.optString("grade").orEmpty()
            OpenUAssignment(course.getString("id"), course.getString("name"), course.getString("term"), moodleId,
                number, title, url, URI(url).path.split('/')[2], URI(url).rawQuery.split('&').first { it.startsWith("id=") }.substringAfter('='),
                field("duedate"), field("timeopen"), field("status"),
                item.selectFirst("[ouilassign-status]")?.attr("ouilassign-status").orEmpty(),
                grade, score?.optString("weight").orEmpty())
        }
        return rows + gradeRows.filter { s -> rows.none { it.number.trimStart('0') == s.optString("number").trimStart('0') } }.map { s ->
            OpenUAssignment(course.getString("id"), course.getString("name"), course.getString("term"), moodleId,
                s.optString("number"), s.optString("type", "Assessment"), "", "assessment", s.optString("number"),
                status = s.optString("status"), statusCode = s.optString("statusCode"), grade = s.optString("grade"),
                weight = s.optString("weight"), examDate = s.optString("examDate"),
                questionnaire = s.optBoolean("hasQuestionnaire"), notebook = s.optBoolean("hasExamFileId"))
        }
    }

    fun detail(html: String, item: OpenUAssignment): OpenUAssignmentDetail {
        if (moodleId(html) != item.moodleId) throw IOException("OpenU returned a different course activity.")
        val doc = Jsoup.parse(html, BASE)
        val root = doc.selectFirst("#region-main") ?: throw IOException("OpenU activity details are missing.")
        fun fields(selector: String, labelSelector: String): List<Pair<String, String>> = root.select(selector).flatMap { row ->
            val parts = if (row.select(labelSelector).size > 1) row.children().toList() else listOf(row)
            parts.mapNotNull { part ->
                val label = part.selectFirst(labelSelector)?.text().orEmpty()
                val value = part.clone().apply { select(labelSelector).remove() }.text()
                if (label.isBlank() || value.isBlank()) null else label to value
            }
        }
        fun files(selector: String, group: String) = root.select(selector)
            .select("a[href]:not([aria-hidden=true]), iframe[src], object[data], embed[src]").mapNotNull { element ->
            val attribute = when (element.tagName()) { "a" -> "href"; "object" -> "data"; else -> "src" }
            val url = element.absUrl(attribute).replace(" ", "%20")
            if (!fileUrl(url)) return@mapNotNull null
            val name = element.text().ifBlank { element.attr("title") }
                .ifBlank { URI(url).path.substringAfterLast('/') }.ifBlank { "Attachment" }
            OpenUAssignmentFile(name, url, group)
        }.distinctBy { it.url }
        val quizFields = if (item.module == "quiz") root.select("table.generaltable tr, .quizinfo tr").mapNotNull { row ->
            val cells = row.select("th,td")
            if (cells.size >= 2) cells.first()!!.text() to cells.drop(1).joinToString(" ") { it.text() } else null
        } else emptyList()
        val submission = fields("#ouilassign_view_submission_content .ouilassign_submission_info_row", ".ouilassign_section_row_title")
            .ifEmpty { root.selectFirst("#ouilassign_view_submission_content")?.ownText()?.trim()
                ?.takeIf(String::isNotBlank)?.let { listOf("Status" to it) }.orEmpty() }
        return OpenUAssignmentDetail(fields("#ouilassign_view_section_details [index-item]", ".ouilassign_inner_title"),
            submission + quizFields,
            root.select("#ouilassign_view_section_guide .ouilassign_section_content, #intro, .quizintro").joinToString("\n\n") { it.wholeText().trim() },
            files("#ouilassign_view_section_assignment, #ouilassign_view_section_guide, #intro, .quizintro", "Original files") +
                files("#ouilassign_view_submission_content", "Submitted files") +
                files("[id^=ouilassigntype_file_feedbacks_list], .ouilassign_view_section_feedback", "Checked files"),
            root.select(".alert, .notifyproblem").map(Element::text).filter(String::isNotBlank))
    }
}
