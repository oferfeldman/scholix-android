package com.feldman.scholix.classroom

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ClassroomSignInRequired : IOException("Reconnect Google Classroom in Settings to continue.")

/** Read-only Classroom REST client. A snapshot is only replaced after all pages succeed. */
internal class ClassroomApi(private val token: () -> String, private val client: OkHttpClient = OkHttpClient(),
    private val renewToken: () -> Unit = { throw ClassroomSignInRequired() }) {
    fun get(path: String, parameters: Map<String, String> = emptyMap(), retry: Boolean = true): JSONObject {
        val url = (if (path.startsWith("http://") || path.startsWith("https://")) {
            path.toHttpUrl()
        } else {
            "https://classroom.googleapis.com/v1/$path".toHttpUrl()
        }).newBuilder()
        parameters.forEach { (key, value) ->
            (if (key.endsWith("States")) value.split(',') else listOf(value)).forEach { url.addQueryParameter(key, it) }
        }
        return client.newCall(Request.Builder().url(url.build()).header("Authorization", "Bearer ${token()}").build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401) {
                if (!retry) throw ClassroomSignInRequired()
                renewToken()
                return get(path, parameters, retry = false)
            }
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException(message ?: "Google Classroom request failed (${response.code}).")
            }
            JSONObject(body)
        }
    }

    fun list(path: String, field: String, parameters: Map<String, String> = emptyMap()): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var pageToken = ""
        val seen = mutableSetOf<String>()
        do {
            val page = get(path, parameters + mapOf("pageSize" to "100") +
                if (pageToken.isEmpty()) emptyMap() else mapOf("pageToken" to pageToken))
            items += page.optJSONArray(field).objects()
            pageToken = page.optString("nextPageToken")
            if (pageToken.isNotEmpty() && !seen.add(pageToken)) throw IOException("Classroom returned a repeated page token.")
        } while (pageToken.isNotEmpty())
        return items
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList()
    else (0 until length()).map { getJSONObject(it) }

internal object ClassroomMapping {
    fun grade(work: JSONObject, submission: JSONObject, courseName: String): JSONObject? {
        if (!submission.has("assignedGrade") || submission.isNull("assignedGrade")) return null
        val points = submission.getDouble("assignedGrade")
        val maximum = work.optDouble("maxPoints", 0.0)
        val pointsStr = if (points % 1.0 == 0.0) points.toLong().toString() else points.toString()
        val maxStr = if (maximum % 1.0 == 0.0) maximum.toLong().toString() else maximum.toString()
        // The shared grade UI/average uses percentages. Preserve the original points too.
        return JSONObject().put("id", "${work.getString("courseId")}:${work.getString("id")}")
            .put("subject", courseName).put("name", work.optString("title") + if (maximum > 0) " · $pointsStr/$maxStr" else " · $pointsStr points")
            .put("grade", if (maximum > 0) points * 100 / maximum else JSONObject.NULL)
            .put("points", points).put("maxPoints", maximum)
            .put("date", submission.optString("updateTime").substringBefore('T'))
            .put("url", work.optString("alternateLink"))
    }

    fun due(work: JSONObject): String {
        val date = work.optJSONObject("dueDate") ?: return "No due date"
        val time = work.optJSONObject("dueTime") ?: JSONObject()
        return LocalDateTime.of(date.getInt("year"), date.getInt("month"), date.getInt("day"),
            time.optInt("hours"), time.optInt("minutes"), time.optInt("seconds"))
            .atOffset(ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM))
    }

    fun status(submission: JSONObject?): String {
        if (submission == null) return "Not submitted"
        val state = when (submission.optString("state")) {
            "TURNED_IN" -> "Turned in"
            "RETURNED" -> "Returned"
            "RECLAIMED_BY_STUDENT" -> "Reclaimed"
            else -> "Not submitted"
        }
        return state + if (submission.optBoolean("late")) " · Late" else ""
    }

    fun materials(post: JSONObject): List<JSONObject> = post.optJSONArray("materials").objects().mapNotNull { material ->
        val file = material.optJSONObject("driveFile")?.let { it.optJSONObject("driveFile") ?: it }
            ?: material.optJSONObject("youtubeVideo") ?: material.optJSONObject("link") ?: material.optJSONObject("form")
            ?: return@mapNotNull null
        val url = file.optString("alternateLink").ifBlank { file.optString("url").ifBlank { file.optString("formUrl") } }
        if (url.isBlank()) null else JSONObject().put("title", file.optString("title").ifBlank { url }).put("url", url)
    }
}
