package com.feldman.scholix.studies

import org.json.JSONObject
import java.util.UUID

object DocumentsStudyContract {
    const val ACTION = "com.feldman.documents.action.STUDY"
    const val CONTENT_ACTION = "com.feldman.documents.action.STUDY_CONTENT"
    const val REMOTE_CONTENT_ACTION = "com.feldman.documents.action.REMOTE_STUDY_CONTENT"
    const val EXTRA = "com.feldman.documents.STUDY_HANDOFF"

    fun request(assignment: StudyAssignment?, reference: StudyReference? = null, choose: Boolean = false): String =
        JSONObject().put("version", 1).put("requestId", UUID.randomUUID().toString())
            .put("title", (assignment?.title ?: reference?.title ?: "Study set").take(500))
            .put("assignmentKey", assignment?.key ?: reference?.assignmentKey)
            .put("catalogId", reference?.id)
            .put("studyId", reference?.studyId).put("choose", choose)
            .put("course", assignment?.course.orEmpty()).put("status", assignment?.status.orEmpty())
            .put("checkedAt", assignment?.checkedAt ?: 0).toString()

    fun receipt(raw: String, request: String, documentsPackage: String): StudyReference {
        require(raw.length <= 4096)
        val json = JSONObject(raw)
        val original = JSONObject(request)
        require(json.getInt("version") == 1 && json.getString("requestId") == original.getString("requestId")) {
            "Documents returned a different study request."
        }
        val id = json.getString("studyId")
        val title = json.getString("title")
        val revision = json.getLong("revision")
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")) && title.isNotBlank() && title.length <= 500 && revision >= 0)
        val assignmentKey = if (original.isNull("assignmentKey")) null else original.optString("assignmentKey").takeIf { it.isNotBlank() }
        val catalogId = original.optString("catalogId").takeIf { it.isNotBlank() }
            ?: "${assignmentKey ?: "general"}:$documentsPackage:$id"
        return StudyReference(catalogId, title, assignmentKey,
            documentsPackage, id, revision)
    }
}
