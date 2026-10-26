package com.feldman.scholix.api.platforms

import org.json.JSONArray
import org.json.JSONObject

internal fun schoolyGrades(card: JSONObject, studentId: String): JSONArray = JSONArray().apply {
    card.optJSONArray("assessmentDetails").schoolyObjects().forEach { detail ->
        val event = detail.optJSONObject("event") ?: return@forEach
        val owner = detail.opt("studentId") ?: detail.opt("student_login_id")
        if (owner != null && owner != JSONObject.NULL && owner.toString() != studentId) return@forEach
        if (event.optBoolean("_deleted") || event.optBoolean("draft") || !event.optBoolean("published", true)) return@forEach
        val value = detail.opt("value")?.takeUnless { it == JSONObject.NULL }
        val text = detail.optString("text").ifBlank { detail.optString("freeText") }
        if (value == null && text.isBlank()) return@forEach
        val rawDate = event.optString("dt")
        val date = if (rawDate.matches(Regex("\\d{8}"))) "${rawDate.takeLast(2)}/${rawDate.substring(4, 6)}/${rawDate.take(4)}" else rawDate.take(10)
        put(JSONObject().put("id", detail.optString("_id"))
            .put("subject", event.optString("subjectName").ifBlank { event.optString("name") })
            .put("name", event.optString("name"))
            .put("grade", value?.toString() ?: text).put("date", date)
            .put("type", event.optString("typeName", "Grade"))
            .put("weight", event.opt("weight") ?: 0)
            .put("maxGrade", event.optInt("maxValue", 100)).put("notes", text))
    }
}

internal fun schoolySchedule(response: JSONObject, day: Int, original: Boolean, lessonHours: JSONArray? = null): JSONObject {
    val result = JSONObject()
    val data = response.schoolyData()
    val blocks = (response.optJSONArray("data").schoolyObjects().ifEmpty { data.optJSONArray("byDate").schoolyObjects() }).ifEmpty { listOf(data) }
    val metadata = listOf(data) + blocks
    val subjectNames = metadata.flatMap { it.optJSONArray("subjects").schoolyObjects() }.associate {
        it.optString("_id") to it.optString("shortName").ifBlank { it.optString("name") }
    }
    val groups = metadata.flatMap { it.optJSONArray("groups").schoolyObjects() }.associateBy { it.optString("_id") }
    val teachers = metadata.flatMap { it.optJSONArray("teachers").schoolyObjects() }.associate {
        it.optString("_id") to it.optString("name").ifBlank { "${it.optString("student_f_name")} ${it.optString("student_l_name")}".trim() }
    }
    val hourSet = lessonHours.schoolyObjects().firstOrNull { it.optJSONArray("class_ids")?.length().let { count -> count == null || count == 0 } }
        ?: lessonHours?.optJSONObject(0)
    val times = hourSet?.optJSONArray("periods").schoolyObjects().mapIndexed { index, period ->
        index to "${period.optString("start")} - ${period.optString("end")}"
    }.toMap()
    blocks.forEach { block ->
        val updated = block.optJSONArray("ttSubjects").schoolyObjects()
        val lessons = if (original || updated.isEmpty()) block.optJSONArray("ttGroups").schoolyObjects() else updated
        lessons.filter { it.optInt("day", -1) == day }.forEach { lesson ->
            if (!original && lesson.opt("movedTo").let { it != null && it != JSONObject.NULL && it != false && it.toString().isNotBlank() }) return@forEach
            val group = groups[lesson.optString("group_id")]
            val subject = lesson.optString("group_name").ifBlank {
                lesson.optString("subjectName").ifBlank { subjectNames[lesson.optString("subject")].orEmpty() }
            }
            val slot = lesson.optInt("slot", -1)
            val firstHour = data.optInt("firstHour", data.optJSONObject("scope")?.let {
                it.optInt("firstHour", it.optJSONObject("blob")?.optInt("firstHour", 0) ?: 0)
            } ?: block.optInt("firstHour", 0))
            val cancelled = lesson.optBoolean("isVoided") || lesson.opt("voidReason").let { it != null && it != JSONObject.NULL && it != false && it.toString().isNotBlank() }
            val names = lesson.optString("teacherNames").ifBlank {
                group?.optJSONArray("teachers")?.let { ids -> (0 until ids.length()).mapNotNull { teachers[ids.optString(it)] }.joinToString(", ") }.orEmpty()
            }
            val id = lesson.optString("_id").ifBlank { "${lesson.optString("group_id")}:$day:$slot" }
            result.put(id, JSONObject().put("hour", slot + firstHour).put("num", slot + firstHour).put("subject", subject)
                .put("teacher", names).put("room", lesson.optString("effectiveRoom_name"))
                .put("time", times[slot].orEmpty()).put("changes", lesson.optString("note"))
                .put("colorClass", if (cancelled && !original) "cancel-cell" else "")
                .put("subjectLevel", group?.opt("CODE_RAMAT_KVUTSA")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()))
        }
    }
    return result
}
