package com.feldman.scholix.lemida

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SavedHomeworkExportTest {
    private val item = Homework("998877:assign:445566", 998877, "Synthetic mechanics", "Synthetic homework", "assign",
        "${LemidaParser.BASE}/mod/assign/view.php?id=445566", "Due: original source wording")
    private fun completed(): MutableMap<String, Any> = mutableMapOf(
        "user_id" to "synthetic-transfer-account", "last_sync" to 1700000000000L, "needs_login" to false,
        "courses" to LemidaCourse.encode(listOf(LemidaCourse(998877, "Synthetic mechanics"))),
        "homework" to LemidaParser.encode(listOf(item)),
        "detail:synthetic-transfer-account:${item.id}" to HomeworkDetail("Synthetic vectors", "Unchanged date text", emptyList(), "Synthetic source text").json().toString(),
        "user_agent" to "SYNTHETIC-PRIVATE-STATE", "sesskey" to "SYNTHETIC-PRIVATE-STATE",
        "detail:another-account:${item.id}" to HomeworkDetail("SYNTHETIC-PRIVATE-STATE", "", emptyList(), "").json().toString(),
    )

    @Test fun exportsCompleteSnapshotWithoutSessionStateOrMutatingSavedData() {
        val values = completed()
        val before = values.toMap()
        val export = SavedHomeworkExport.fromValues(values)
        val json = JSONObject(export.json)
        assertEquals(before, values)
        assertEquals(1, export.courseCount)
        assertEquals(1, export.homeworkCount)
        assertEquals(1700000000000L, export.savedAt)
        assertFalse(export.json.contains("SYNTHETIC-PRIVATE-STATE"))
        assertTrue(json.getBoolean("complete"))
        assertEquals("scholix-homework", json.getString("format"))
        assertEquals("synthetic-transfer-account", json.getString("scope"))
        val exported = json.getJSONObject("payload").getJSONArray("homework").getJSONObject(0)
        assertEquals(item.id, exported.getString("id"))
        assertEquals(item.dates, exported.getString("dates"))
        assertEquals("Synthetic vectors", exported.getJSONObject("detail").getString("description"))
        assertEquals("05fefc148117645a6f7d6c3c2d57b47875d1663ac1e5fb130d99991e6351ab05", item.studyAssignment("synthetic-transfer-account").key)
    }

    @Test fun rejectsPartialExpiredAndMismatchedSnapshots() {
        val invalid = listOf(
            completed().apply { remove("last_sync") },
            completed().apply { this["needs_login"] = true },
            completed().apply { remove("user_id") },
            completed().apply { remove("courses") },
            completed().apply { this["courses"] = "[]" },
            completed().apply { this["homework"] = LemidaParser.encode(listOf(item.copy(courseId = 1))) },
            completed().apply { this["homework"] = LemidaParser.encode(listOf(item.copy(url = item.url + "&sesskey=synthetic"))) },
            completed().apply { this["homework"] = LemidaParser.encode(listOf(item, item)) },
        )
        invalid.forEach { assertTrue(runCatching { SavedHomeworkExport.fromValues(it) }.isFailure) }
        assertEquals(0, SavedHomeworkExport.fromValues(completed().apply { this["homework"] = "[]" }).homeworkCount)
    }
}
