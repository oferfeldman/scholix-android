package com.feldman.scholix.studies

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** What a prepared study holds, said before it is sent: "6 questions from Ex6.pdf (2 in תרגילי בונוס)". */
internal object StudyBundleSummary {
    fun describe(bundle: File): String? = runCatching {
        val study = ZipFile(bundle).use { zip ->
            zip.getInputStream(zip.getEntry("study.json")).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        }.getJSONObject("study")
        describe(study)
    }.getOrNull()

    fun describe(study: JSONObject): String {
        val questions = study.optJSONArray("questions") ?: return "No questions"
        val all = (0 until questions.length()).map { questions.getJSONObject(it) }
        val sources = study.optJSONArray("sources")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.filter { !it.optBoolean("solutionOnly") }.map { it.optString("title") }
        }.orEmpty()
        val pages = all.count { it.optString("number").startsWith("Page ") }
        val from = if (sources.isEmpty()) "" else " from " + sources.joinToString(", ")
        if (pages == all.size && pages > 0)
            return "No question headings were found" + (if (sources.isEmpty()) "" else " in " + sources.joinToString(", ")) +
                ", so each of its ${all.size} pages is a question"
        val folders = study.optJSONArray("folders")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()
        val parts = folders.mapNotNull { folder ->
            val count = all.count { it.optString("folderId") == folder.optString("id") }
            if (count == 0) null else "$count in ${folder.optString("title")}"
        }
        val count = if (all.size == 1) "1 question" else "${all.size} questions"
        return count + from + if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }
}
