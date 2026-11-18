package com.feldman.scholix.lemida

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Something a Lemida course posts for reading rather than handing in: lecture slides, recitation
 * sheets (תרגולים), whiteboard scans, links and folders. Kept with the course section it sits in,
 * since lecturers organise a course by those sections ("הרצאות", "תרגולים", "לוח").
 */
data class LemidaMaterial(
    val id: String,
    val courseId: Int,
    val course: String,
    val section: String,
    /** The section's place in the course, so sections keep Lemida's order. */
    val sectionIndex: Int,
    val title: String,
    val type: String,
    val url: String,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("courseId", courseId).put("course", course)
        .put("section", section).put("sectionIndex", sectionIndex).put("title", title).put("type", type).put("url", url)

    fun matchesSearch(vararg queries: String): Boolean = queries.filter { it.isNotBlank() }.all { query ->
        listOf(title, section, course).any { it.contains(query.trim(), ignoreCase = true) }
    }

    companion object {
        /** Module types that are posted material. */
        val types = setOf("resource", "folder", "url", "page", "book")

        fun fromJson(json: JSONObject) = LemidaMaterial(json.getString("id"), json.getInt("courseId"),
            json.getString("course"), json.optString("section"), json.optInt("sectionIndex"),
            json.getString("title"), json.getString("type"), json.getString("url"))

        fun encode(items: List<LemidaMaterial>): String = JSONArray().apply { items.forEach { put(it.json()) } }.toString()

        fun decode(raw: String): List<LemidaMaterial> = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        /**
         * Reads the material in one course's `core_courseformat_get_state` answer, in the order
         * Lemida shows it: by section, then by each section's own list.
         */
        fun fromState(raw: String, courseId: Int, courseName: String): List<LemidaMaterial> {
            val state = JSONObject(raw)
            val sections = state.optJSONArray("section") ?: JSONArray()
            val sectionOf = HashMap<String, Pair<Int, String>>()
            val placeOf = HashMap<String, Int>()
            for (index in 0 until sections.length()) {
                val section = sections.getJSONObject(index)
                val title = Jsoup.parse(section.optString("title")).text()
                sectionOf[section.optString("id")] = index to title
                section.optJSONArray("cmlist")?.let { list ->
                    for (place in 0 until list.length()) placeOf[list.optString(place)] = place
                }
            }
            val modules = state.optJSONArray("cm") ?: JSONArray()
            return (0 until modules.length()).mapNotNull { index ->
                val module = modules.getJSONObject(index)
                if (!module.optBoolean("uservisible", true)) return@mapNotNull null
                val url = module.optString("url")
                val (type, id) = LemidaParser.activity(url) ?: return@mapNotNull null
                if (type !in types) return@mapNotNull null
                val (sectionIndex, section) = sectionOf[module.optString("sectionid")] ?: (Int.MAX_VALUE to "")
                Triple(LemidaMaterial("$courseId:$type:$id", courseId, courseName, section, sectionIndex,
                    Jsoup.parse(module.optString("name")).text().ifBlank { "Untitled" }, type, url.substringBefore('#')),
                    placeOf[module.optString("id")] ?: Int.MAX_VALUE, index)
            }.sortedWith(compareBy({ it.first.sectionIndex }, { it.second }, { it.third }))
                .map { it.first }.distinctBy { it.id }
        }
    }
}
