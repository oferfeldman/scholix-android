package com.feldman.scholix.studies

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class StudyAssignment(val key: String, val title: String, val course: String = "",
    val status: String = "", val checkedAt: Long = 0) {
    companion object {
        fun create(provider: String, account: String, courseId: Any, activityKind: String,
            activityId: String, title: String, course: String = "", status: String = "", checkedAt: Long = 0): StudyAssignment {
            require(listOf(provider, account, courseId.toString(), activityKind, activityId, title).all { it.isNotBlank() })
            val identity = JSONArray(listOf(provider, account, courseId, activityKind, activityId))
                .toString().replace("\\/", "/")
            val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return StudyAssignment(key, title, course, status, checkedAt)
        }
    }
}

/** A publication is a future server revision, separate from the user's editable local copy. */
data class StudyPublication(val id: String, val revision: String, val downloadUrl: String) {
    fun json() = JSONObject().put("id", id).put("revision", revision).put("downloadUrl", downloadUrl)
}

data class StudyReference(val id: String, val title: String, val assignmentKey: String? = null,
    val documentsPackage: String? = null, val studyId: String? = null, val localRevision: Long = 0,
    val publication: StudyPublication? = null, val importedPublicationRevision: String? = null) {
    val isLocal get() = documentsPackage != null && studyId != null
    fun json() = JSONObject().put("id", id).put("title", title).put("assignmentKey", assignmentKey)
        .put("documentsPackage", documentsPackage).put("studyId", studyId).put("localRevision", localRevision)
        .put("publication", publication?.json())
        .put("importedPublicationRevision", importedPublicationRevision)

    companion object {
        fun fromJson(json: JSONObject): StudyReference {
            fun optional(key: String) = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
            val publication = json.optJSONObject("publication")?.let {
                StudyPublication(it.getString("id"), it.getString("revision"), it.getString("downloadUrl"))
            }
            return StudyReference(json.getString("id"), json.getString("title"), optional("assignmentKey"),
                optional("documentsPackage"), optional("studyId"), json.optLong("localRevision"), publication,
                optional("importedPublicationRevision"))
        }
    }
}

class StudyCatalog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("study-catalog-v1", Context.MODE_PRIVATE)
    fun entries(): List<StudyReference> = prefs.all.values.filterIsInstance<String>()
        .map { StudyReference.fromJson(JSONObject(it)) }.sortedBy { it.title }

    fun save(entry: StudyReference) = synchronized(lock) {
        require(entry.id.isNotBlank() && entry.title.isNotBlank())
        val previous = prefs.getString(entry.id, null)?.let { StudyReference.fromJson(JSONObject(it)) }
        require(previous == null || previous.assignmentKey == entry.assignmentKey) { "Study assignment changed." }
        require(previous == null || !previous.isLocal ||
            (previous.documentsPackage == entry.documentsPackage && previous.studyId == entry.studyId &&
                entry.localRevision >= previous.localRevision)) { "A newer local study reference is already saved." }
        val saved = entry.copy(publication = entry.publication ?: previous?.publication,
            importedPublicationRevision = entry.importedPublicationRevision ?: previous?.importedPublicationRevision)
        check(prefs.edit().putString(entry.id, saved.json().toString()).commit()) { "Could not save the study reference." }
    }

    companion object { private val lock = Any() }
}
