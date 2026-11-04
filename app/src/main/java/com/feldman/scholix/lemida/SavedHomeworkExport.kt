package com.feldman.scholix.lemida

import android.content.Context
import com.feldman.scholix.lemida.Homework
import com.feldman.scholix.lemida.HomeworkDetail
import com.feldman.scholix.lemida.LemidaCourse
import com.feldman.scholix.lemida.LemidaParser
import org.json.JSONArray
import org.json.JSONObject

/** Read-only handoff from Scholix's atomically committed cache, never its login/session store. */
data class SavedHomeworkExport(
    val json: String,
    val courseCount: Int,
    val homeworkCount: Int,
    val savedAt: Long,
) {
    companion object {
        fun read(context: Context): SavedHomeworkExport = fromValues(
            context.applicationContext.getSharedPreferences("lemida_sync", Context.MODE_PRIVATE).all,
        )

        internal fun fromValues(values: Map<String, *>): SavedHomeworkExport {
            // These are the fields committed together at the end of LemidaRepository.sync.
            // In particular, an inferred course list or an unloaded empty cache is not complete.
            val account = (values["user_id"] as? String)?.takeIf { it.isNotBlank() }
            val updated = values["last_sync"] as? Long ?: 0L
            require(account != null && updated > 0L && values["needs_login"] == false) {
                "Refresh your signed-in Lemida homework before saving it for PC."
            }
            val courses = LemidaCourse.decode(requireNotNull(values["courses"] as? String) {
                "Refresh Lemida to save a complete course list first."
            })
            require(courses.isNotEmpty() && courses.all { it.id > 0 && it.name.isNotBlank() } &&
                courses.distinctBy { it.id }.size == courses.size) { "The saved course list is incomplete. Refresh Lemida first." }
            val courseIds = courses.map { it.id }.toSet()
            val rawHomework = JSONArray(requireNotNull(values["homework"] as? String) {
                "Refresh Lemida to save a complete homework list first."
            })
            val seen = mutableSetOf<String>()
            val homework = JSONArray()
            for (index in 0 until rawHomework.length()) {
                val item = Homework.fromJson(rawHomework.getJSONObject(index))
                require(item.courseId in courseIds && item.title.isNotBlank() && seen.add(item.id)) {
                    "The saved homework list is incomplete. Refresh Lemida first."
                }
                // Use the same provider/account/course/activity identity as native study links.
                val identity = item.id.split(':')
                val url = java.net.URI(item.url)
                require(identity.size == 3 && identity[0].toIntOrNull() == item.courseId &&
                    identity[1] == item.type && item.type in setOf("assign", "quiz", "workshop") &&
                    identity[2].isNotBlank() && identity[2].all(Char::isDigit) &&
                    url.scheme == "https" && url.authority == java.net.URI(LemidaParser.BASE).authority &&
                    url.rawUserInfo == null && url.rawFragment == null &&
                    url.path == "/mod/${item.type}/view.php" && url.rawQuery == "id=${identity[2]}") {
                    "The saved assignment identity is invalid. Refresh Lemida first."
                }
                item.studyAssignment(account).key
                val exported = item.json()
                (values["detail:$account:${item.id}"] as? String)?.let { detail ->
                    exported.put("detail", HomeworkDetail.fromJson(detail).json())
                }
                homework.put(exported)
            }
            val payload = JSONObject().put("loaded", true).put("needsLogin", false)
                .put("updated", updated).put("courses", JSONArray(LemidaCourse.encode(courses)))
                .put("homework", homework)
            val json = JSONObject().put("schemaVersion", 1).put("format", "scholix-homework")
                .put("provider", LemidaParser.BASE).put("scope", account).put("complete", true)
                .put("expectedCourseIds", JSONArray(courses.map { it.id })).put("payload", payload).toString()
            require(json.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "This homework export exceeds the PC file limit." }
            return SavedHomeworkExport(json, courses.size, homework.length(), updated)
        }
    }
}
