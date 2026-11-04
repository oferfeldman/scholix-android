package com.feldman.scholix.openu

import android.content.Context
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopDownloadedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class OpenUAssignmentsRepository(context: Context, val provider: OpenAUPlatform) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("openu_assignments_${provider.id}", Context.MODE_PRIVATE)
    private fun state(): JSONObject = runCatching { JSONObject(prefs.getString("snapshot", "{}")!!) }.getOrDefault(JSONObject())
        .takeIf { it.optString("account") == provider.getUsername() } ?: JSONObject()
    private fun save(change: (JSONObject) -> Unit) {
        val state = state().put("account", provider.getUsername())
        change(state)
        prefs.edit().putString("snapshot", state.toString()).apply()
    }
    private fun persistSession() {
        // A foreground read must not recreate a provider that was removed while it was loading.
        if (PlatformStorage.loadPlatforms(context).any { it.id == provider.id && it.getUsername() == provider.getUsername() })
            PlatformStorage.addPlatform(context, provider)
    }

    fun cachedCourses(): List<JSONObject> = state().optJSONArray("courses")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }.orEmpty()
    fun cachedAssignments(): List<OpenUAssignment> = state().optJSONObject("coursesData")?.let { data ->
        data.keys().asSequence().flatMap { key -> data.getJSONObject(key).getJSONArray("items").let { a ->
            (0 until a.length()).asSequence().map { OpenUAssignment.from(a.getJSONObject(it)) }
        } }.toList()
    }.orEmpty()
    fun checkedAt(item: OpenUAssignment): Long = state().optJSONObject("coursesData")?.optJSONObject(item.courseKey)?.optLong("checkedAt") ?: 0
    fun detailCheckedAt(item: OpenUAssignment): Long = state().optJSONObject("details")?.optJSONObject(item.key)?.optLong("checkedAt") ?: 0
    fun cachedDetail(item: OpenUAssignment): OpenUAssignmentDetail? = state().optJSONObject("details")?.optJSONObject(item.key)
        ?.optJSONObject("data")?.let(OpenUAssignmentDetail::from)

    fun cachedDrafts(item: OpenUAssignment): List<OpenUDraftSnapshot> = state().optJSONObject("drafts")?.optJSONObject(item.key)?.let { data ->
        data.keys().asSequence().mapNotNull { key ->
            val json = data.getJSONObject(key)
            val reference = OpenUDraftReference.parse(json.getString("reference")) ?: return@mapNotNull null
            val files = json.getJSONArray("files")
            OpenUDraftSnapshot(reference, (0 until files.length()).map { files.getJSONObject(it).let { f ->
                OpenUAssignmentFile(f.getString("name"), f.getString("url"), f.getString("group"))
            } }, json.optLong("checkedAt"))
        }.toList()
    }.orEmpty()

    suspend fun refreshCourses(): List<JSONObject> = withContext(Dispatchers.IO) {
        val courses = provider.assignmentCourses().sortedWith(compareBy<JSONObject> { it.optInt("courseStatusRank", 1) }
            .thenByDescending { it.optString("term") }.thenBy { it.optString("name") })
        save { it.put("courses", JSONArray(courses)) }
        persistSession()
        courses
    }

    suspend fun refresh(course: JSONObject): List<OpenUAssignment> = withContext(Dispatchers.IO) {
        val moodleId = OpenUAssignmentParser.moodleId(provider.assignmentPage(OpenUAssignmentParser.courseUrl(course)))
        val html = provider.assignmentPage("${OpenUAssignmentParser.BASE}/mod/ouilassign/index.php?id=$moodleId&quicklinks=1")
        val items = OpenUAssignmentParser.assignments(html, course, moodleId)
        save { state ->
            val data = state.optJSONObject("coursesData") ?: JSONObject()
            data.put(course.getString("courseKey"), JSONObject().put("checkedAt", System.currentTimeMillis())
                .put("items", JSONArray(items.map { it.json() })))
            state.put("coursesData", data)
        }
        persistSession()
        items
    }

    suspend fun detail(item: OpenUAssignment): OpenUAssignmentDetail = withContext(Dispatchers.IO) {
        val data = if (item.module == "assessment") {
            val files = mutableListOf<OpenUAssignmentFile>()
            fun scan(artifact: String): String = "https://sheilta.apps.openu.ac.il/Main/api/coursesApi/downloadScanned?" +
                "course=${item.courseId}&semester=${item.semester.replace("a", "א").replace("b", "ב").replace("c", "ג")}" +
                "&assignmentNo=${item.number}&assignmentSemester=${item.semester.replace("a", "א").replace("b", "ב").replace("c", "ג")}" +
                "&questionnaire=${artifact == "questionnaire"}&questionnaireScanned=false&examScanned=${artifact == "notebook"}"
            if (item.questionnaire) files += OpenUAssignmentFile("Questionnaire.pdf", scan("questionnaire"), "Original files")
            if (item.notebook) files += OpenUAssignmentFile("Checked notebook.pdf", scan("notebook"), "Checked files")
            OpenUAssignmentDetail(listOf("Grade" to item.grade, "Weight" to item.weight, "Exam date" to item.examDate)
                .filter { it.second.isNotBlank() }, emptyList(), "", files, emptyList())
        } else OpenUAssignmentParser.detail(provider.assignmentPage(item.url), item)
        save { state ->
            val details = state.optJSONObject("details") ?: JSONObject()
            details.put(item.key, JSONObject().put("checkedAt", if (item.module == "assessment") checkedAt(item) else System.currentTimeMillis())
                .put("data", data.json()))
            state.put("details", details)
        }
        persistSession()
        data
    }

    suspend fun draft(item: OpenUAssignment, reference: OpenUDraftReference): List<OpenUAssignmentFile> = withContext(Dispatchers.IO) {
        require(item.module == "ouilassign" && item.moodleId == reference.course && item.cmid == reference.cmid)
        val page = provider.assignmentPage(item.url)
        require(OpenUAssignmentParser.moodleId(page) == reference.course)
        val assign = Regex("[\"']assign[\"']\\s*:\\s*[\"']?(\\d+)").find(page)?.groupValues?.get(1)
        require(assign == reference.assign) { "The website draft belongs to a different assignment." }
        val response = JSONObject(provider.assignmentDraftFiles(item.url, reference.itemId, reference.clientId))
        val files = response.optJSONArray("list") ?: throw IOException("The website draft is unavailable.")
        (0 until files.length()).mapNotNull { i ->
            val f = files.getJSONObject(i)
            val url = f.optString("url")
            if (OpenUAssignmentParser.fileUrl(url)) OpenUAssignmentFile(f.optString("filename", "Draft file"), url, "Unsubmitted website draft") else null
        }.also { result ->
            save { state ->
                val drafts = state.optJSONObject("drafts") ?: JSONObject()
                val linked = drafts.optJSONObject(item.key) ?: JSONObject()
                linked.put(reference.itemId, JSONObject().put("reference", reference.link()).put("files", JSONArray(result.map { it.json() }))
                    .put("checkedAt", System.currentTimeMillis()))
                state.put("drafts", drafts.put(item.key, linked))
            }
            persistSession()
        }
    }

    suspend fun openFile(file: OpenUAssignmentFile): WebtopDownloadedAttachment = withContext(Dispatchers.IO) {
        require(OpenUAssignmentParser.fileUrl(file.url))
        val folder = File(context.cacheDir, "message_attachments/openu-${provider.id}").apply { mkdirs() }
        val name = file.name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").take(140).ifBlank { "attachment" }
        val work = File.createTempFile("download-", ".part", folder)
        try {
            val mime = provider.assignmentFile(file.url, work)
            val complete = File(File(folder, java.util.UUID.randomUUID().toString()).apply { mkdirs() }, name)
            if (!work.renameTo(complete)) throw IOException("Could not save the attachment.")
            persistSession()
            WebtopDownloadedAttachment(complete, mime)
        } finally { work.delete() }
    }
}

/** Token-free reference to the exact active form, not a newly allocated edit draft. */
internal data class OpenUDraftReference(val course: String, val cmid: String, val assign: String, val itemId: String, val clientId: String) {
    fun link() = "scholix-openu://draft?course=$course&cmid=$cmid&assign=$assign&itemid=$itemId&client=$clientId"
    companion object {
        fun parse(raw: String): OpenUDraftReference? = runCatching {
            val uri = java.net.URI(raw)
            require(uri.scheme == "scholix-openu" && uri.host == "draft")
            val parts = uri.rawQuery.orEmpty().split('&')
            val parameters = parts.associate { it.substringBefore('=') to it.substringAfter('=', "") }
            require(parts.size == 5 && parameters.keys == setOf("course", "cmid", "assign", "itemid", "client"))
            fun id(key: String) = requireNotNull(parameters[key]).also { require(it.matches(Regex("[1-9][0-9]{0,14}"))) }
            OpenUDraftReference(id("course"), id("cmid"), id("assign"), id("itemid"),
                requireNotNull(parameters["client"]).also { require(it.matches(Regex("[A-Za-z0-9_-]{1,100}"))) })
        }.getOrNull()
    }
}

internal data class OpenUDraftSnapshot(val reference: OpenUDraftReference, val files: List<OpenUAssignmentFile>, val checkedAt: Long)
