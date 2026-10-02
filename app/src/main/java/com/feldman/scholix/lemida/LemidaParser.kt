package com.feldman.scholix.lemida

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

data class Homework(val id: String, val courseId: Int, val course: String,
                    val title: String, val type: String, val url: String, val dates: String) {
    fun json() = JSONObject().put("id", id).put("courseId", courseId).put("course", course)
        .put("title", title).put("type", type).put("url", url).put("dates", dates)
    companion object {
        fun fromJson(j: JSONObject) = Homework(j.getString("id"), j.getInt("courseId"),
            j.getString("course"), j.getString("title"), j.getString("type"),
            j.getString("url"), j.optString("dates"))
    }
}

data class HomeworkDetail(val description: String, val dates: String, val tables: List<List<List<String>>>, val text: String) {
    fun json() = JSONObject().put("description", description).put("dates", dates).put("text", text)
        .put("tables", JSONArray(tables.map { table -> JSONArray(table.map { JSONArray(it) }) }))
    companion object {
        fun fromJson(raw: String): HomeworkDetail {
            val j = JSONObject(raw)
            val tables = j.getJSONArray("tables")
            return HomeworkDetail(j.optString("description"), j.optString("dates"),
                (0 until tables.length()).map { t ->
                    val rows = tables.getJSONArray(t)
                    (0 until rows.length()).map { r ->
                        val cells = rows.getJSONArray(r)
                        (0 until cells.length()).map { cells.getString(it) }
                    }
                }, j.optString("text"))
        }
    }
}

object LemidaParser {
    const val BASE = "https://lemida.biu.ac.il"
    fun authenticated(html: String): Boolean {
        val doc = Jsoup.parse(html)
        return doc.body()?.hasClass("notloggedin") == false &&
            doc.selectFirst("a[href*=/login/logout.php]") != null
    }
    fun config(html: String, name: String): String? =
        Regex("\"${Regex.escape(name)}\"\\s*:\\s*(?:\"([^\"]+)\"|(\\d+))")
            .find(html)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    fun homework(html: String, courseId: Int, courseName: String): List<Homework> {
        val doc = Jsoup.parse(html, BASE)
        check(doc.body().id().startsWith("page-course-view")) { "Unexpected course page" }
        return doc.select(".activity").mapNotNull { item ->
            val a = item.selectFirst(".activityname a[href], a[href*=/mod/]") ?: return@mapNotNull null
            val url = a.absUrl("href")
            val match = Regex("^https://lemida\\.biu\\.ac\\.il/mod/(assign|quiz|workshop)/view\\.php\\?id=(\\d+)").find(url)
                ?: return@mapNotNull null
            val type = match.groupValues[1]
            val id = match.groupValues[2]
            val title = item.selectFirst("[data-activityname]")?.attr("data-activityname")
                ?.takeIf { it.isNotBlank() } ?: a.text()
            val dates = item.select("[data-region=activity-dates], .activity-dates").text()
            Homework("$courseId:$type:$id", courseId, courseName, title, type, url.substringBefore('#'), dates)
        }.distinctBy { it.id }
    }

    fun stateHomework(raw: String, courseId: Int, courseName: String): List<Homework> {
        val modules = JSONObject(raw).getJSONArray("cm")
        return (0 until modules.length()).mapNotNull { index ->
            val module = modules.getJSONObject(index)
            if (!module.optBoolean("uservisible", true) || !module.optBoolean("accessvisible", true)) return@mapNotNull null
            val url = module.optString("url")
            val match = Regex("^https://lemida\\.biu\\.ac\\.il/mod/(assign|quiz|workshop)/view\\.php\\?id=(\\d+)")
                .find(url) ?: return@mapNotNull null
            val type = match.groupValues[1]
            val id = match.groupValues[2]
            Homework("$courseId:$type:$id", courseId, courseName,
                Jsoup.parse(module.getString("name")).text(), type, url.substringBefore('#'), "")
        }.distinctBy { it.id }
    }

    fun decode(raw: String): List<Homework> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { Homework.fromJson(array.getJSONObject(it)) }
    }
    fun detail(html: String): HomeworkDetail {
        val doc = Jsoup.parse(html)
        val main = doc.selectFirst("#region-main") ?: throw IllegalStateException("Homework page content is missing")
        main.select("script, style, noscript, form, button, nav").remove()
        val tables = main.select("table").map { table -> table.select("tr").map { row ->
            row.select("th, td").map { it.text() }
        }.filter { it.isNotEmpty() } }
        val descriptions = main.select(".activity-description, #intro, .generalbox")
        val topLevelDescriptions = descriptions.filter { element -> element.parents().none { it in descriptions } }
        return HomeworkDetail(topLevelDescriptions.joinToString("\n\n") { readableText(it) },
            main.select("[data-region=activity-dates], .activity-dates").text(), tables, main.text())
    }
    private fun readableText(element: Element): String {
        val content = element.clone()
        content.select("br").forEach { it.before("\n"); it.remove() }
        content.select("li").forEach { it.prependText("• ") }
        content.select("p, div, li, h1, h2, h3, tr").forEach { it.appendText("\n") }
        return content.wholeText().lineSequence().map { it.trim().replace(Regex("[\\t ]+"), " ") }
            .filter { it.isNotBlank() }.joinToString("\n")
    }
    fun encode(items: List<Homework>) = JSONArray().apply { items.forEach { put(it.json()) } }.toString()
    // Keep the union: an activity temporarily hidden and later visible isn't new again.
    fun newItems(items: List<Homework>, seen: Set<String>?) =
        if (seen == null) emptyList() else items.filter { it.id !in seen }
    // Refresh queued titles/dates from the complete snapshot; don't announce withdrawn activities.
    fun pendingAlerts(items: List<Homework>, seen: Set<String>?, pending: List<Homework>): List<Homework> {
        val current = items.associateBy { it.id }
        return (pending + newItems(items, seen)).mapNotNull { current[it.id] }.distinctBy { it.id }
    }
}
