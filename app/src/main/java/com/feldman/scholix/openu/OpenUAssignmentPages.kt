package com.feldman.scholix.openu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionNavigator
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionSymbols
import com.feldman.motion.rememberSymbolPainter
import com.feldman.scholix.AppDest
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopDownloadedAttachment
import com.feldman.scholix.drive.DrivePdfReader
import com.feldman.scholix.studies.StudyActions
import com.feldman.scholix.studies.StudyAssignment
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.openMessageAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
internal fun OpenUAssignmentsPage(provider: OpenAUPlatform, searchQuery: String, onNavigate: MotionNavigator,
                                  selectedCourse: String?, coursePicker: @Composable () -> Unit,
                                  onCoursesChanged: (List<JSONObject>) -> Unit) {
    val context = LocalContext.current
    val repo = remember(provider.id, provider.getUsername()) { OpenUAssignmentsRepository(context, provider) }
    var courses by remember(repo) { mutableStateOf(repo.cachedCourses()) }
    var items by remember(repo) { mutableStateOf(repo.cachedAssignments()) }
    var stateFilter by rememberSaveable(provider.id) { mutableStateOf("All statuses") }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember(repo) { mutableStateOf(false) }
    var errors by remember(repo) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(repo, selectedCourse, retry) {
        loading = true; errors = emptyList()
        try {
            courses = repo.refreshCourses()
            onCoursesChanged(courses)
            val selected = if (selectedCourse == null) courses.filter { it.optInt("courseStatusRank", 1) == 0 }
                else courses.filter { it.optString("courseKey") == selectedCourse }
            for (course in selected) {
                try { repo.refresh(course); items = repo.cachedAssignments() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors = errors + "${course.optString("name")}: ${e.message ?: "Could not update assignments."}" }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { errors = listOf(e.message ?: "Could not update OpenU assignments.") }
        finally { loading = false }
    }
    val selectedKeys = if (selectedCourse == null) courses.filter { it.optInt("courseStatusRank", 1) == 0 }.map { it.optString("courseKey") }
        else listOf(selectedCourse)
    val visible = items.filter { item -> item.courseKey in selectedKeys &&
        (stateFilter == "All statuses" || if (stateFilter == "Not submitted") item.statusCode == "notsubmitted"
            else if (stateFilter == "Submitted") item.statusCode in listOf("submitted", "wait") else item.statusCode == "checked") &&
        searchQuery.trim().split(Regex("\\s+")).all {
            "${item.label} ${item.courseName} ${item.semester} ${item.status}".contains(it, true)
        }
    }
    MotionScaffold(topBar = { SettingsTopBar("Homework", actions = {
        IconButton(onClick = { retry++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh OpenU assignments") }
    }) }) {
        Item { coursePicker() }
        Item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipPicker("Status", listOf("All statuses", "Not submitted", "Submitted", "Checked"), stateFilter, onSelectedChange = { stateFilter = it })
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            if (errors.isNotEmpty() && visible.isNotEmpty()) Text("Showing last-known assignments. Their status may have changed.")
        } }
        if (visible.isEmpty() && !loading) Item { Text(if (searchQuery.isNotBlank()) "No matching assignments."
            else if (errors.isNotEmpty()) "No saved assignment list is available. Refresh or reconnect OpenU."
            else "No assignments in the selected courses and status.") }
        visible.groupBy { it.courseKey }.forEach { (_, assignments) ->
            Title("${assignments.first().courseName} · ${assignments.first().semester}")
            Section {
                assignments.forEach { item ->
                    PageItem(key = item.key, title = item.label, icon = rememberSymbolPainter(MotionSymbols.ic_assignment),
                        description = listOf(item.status.ifBlank { "Status unavailable" },
                        item.due.takeIf(String::isNotBlank)?.let { "Due $it" },
                        item.grade.takeIf { it.isNotBlank() && it != "-" }?.let { "Grade $it" }).filterNotNull().joinToString(" · "),
                        onClick = { onNavigate(AppDest.OpenUAssignment(provider.id, item.key)) })
                }
            }
            Item { Text("Last checked ${checkedTime(repo.checkedAt(assignments.first()))}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
}

@Composable
internal fun OpenUAssignmentPage(providerId: String, assignmentKey: String, onNavigate: MotionNavigator, onBack: () -> Unit) {
    val context = LocalContext.current
    val provider = remember(providerId) { PlatformStorage.loadPlatforms(context).filterIsInstance<OpenAUPlatform>().firstOrNull { it.id == providerId } }
    val repo = remember(provider) { provider?.let { OpenUAssignmentsRepository(context, it) } }
    var item by remember(repo, assignmentKey) { mutableStateOf(repo?.cachedAssignments()?.firstOrNull { it.key == assignmentKey }) }
    var data by remember(item) { mutableStateOf(item?.let { repo?.cachedDetail(it) }) }
    var drafts by remember(repo, assignmentKey) { mutableStateOf(item?.let { repo?.cachedDrafts(it) }.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val uri = LocalUriHandler.current
    LaunchedEffect(repo, assignmentKey, retry) {
        val selected = item
        if (repo == null || selected == null) return@LaunchedEffect
        loading = true; error = null
        try {
            val course = repo.cachedCourses().firstOrNull { it.optString("courseKey") == selected.courseKey }
            val current = course?.let { repo.refresh(it).firstOrNull { row -> row.key == assignmentKey } }
                ?: throw java.io.IOException("This assignment is no longer present in the refreshed course list.")
            item = current
            data = repo.detail(current)
            for (draft in repo.cachedDrafts(current)) {
                try { repo.draft(current, draft.reference) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not refresh a linked website draft." }
                drafts = repo.cachedDrafts(current)
            }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not update this assignment." }
        finally { loading = false }
    }
    MotionScaffold(topBar = { SettingsTopBar(item?.label ?: "Assignment", onBack, actions = {
        IconButton(onClick = { retry++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh assignment details") }
    }) }) {
        val item = item
        if (item == null || repo == null || provider == null) {
            Item { Text("This assignment or account is no longer available. Return to Homework and refresh.") }
        } else {
            Item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.courseName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${item.semester} · ${item.status.ifBlank { "Status unavailable" }}")
                if (item.due.isNotBlank()) Text("Due: ${item.due}")
                if (item.opens.isNotBlank()) Text("Available from: ${item.opens}")
                if (item.grade.isNotBlank() && item.grade != "-") Text("Grade: ${item.grade}")
                Text("Status last checked ${checkedTime(repo.checkedAt(item))}", style = MaterialTheme.typography.labelSmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error)
                    if (data != null) Text("Showing saved details and file links. Refresh before relying on the current submission status.") }
                TextButton(onClick = { uri.openUri(item.url.ifBlank { "https://sheilta.apps.openu.ac.il/Main/Courses" }) }) { Text("Open in OpenU") }
                if (item.module == "ouilassign") TextButton(onClick = { onNavigate(AppDest.OpenUDraftHelp) }) { Text("Connect a website draft") }
            } }
            Item { StudyActions(StudyAssignment.create("https://opal.openu.ac.il", provider.id, item.courseKey,
                item.module, item.cmid, item.label, item.courseName, item.status, repo.checkedAt(item))) }
            data?.let { detail ->
                if (detail.fields.isNotEmpty()) { Title("Assignment details"); Section { detail.fields.forEach { (k, v) -> Item { Text("$k: $v") } } } }
                if (detail.submission.isNotEmpty()) { Title("Submission and grading"); Section {
                    detail.submission.forEach { (k, v) -> Item { Text("$k: $v") } }
                } }
                if (detail.instructions.isNotBlank()) { Title("Instructions"); Item { Text(detail.instructions) } }
                detail.notices.forEach { Item { Text(it) } }
                detail.files.groupBy { it.group }.forEach { (group, files) ->
                    Title(group)
                    Section { files.forEach { file -> PageItem(title = file.name, description = "Open attachment",
                        onClick = { onNavigate(AppDest.OpenUAttachment(provider.id, file.name, file.url)) }) } }
                }
                Item { Text("Details last checked ${checkedTime(repo.detailCheckedAt(item))}", style = MaterialTheme.typography.labelSmall) }
            }
            drafts.forEachIndexed { index, draft ->
                Title("Unsubmitted website draft ${index + 1}")
                Item { Text("Last checked ${checkedTime(draft.checkedAt)}. Draft files can remain after canceling an edit; these are separate from your accepted submission.") }
                Section { draft.files.forEach { f -> PageItem(title = f.name, description = "Open draft file",
                    onClick = { onNavigate(AppDest.OpenUAttachment(provider.id, f.name, f.url)) }) } }
                if (draft.files.isEmpty()) Item { Text("No files in this linked draft.") }
            }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
}

@Composable
internal fun OpenUAttachmentPage(providerId: String, name: String, url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var file by remember(providerId, url) { mutableStateOf<WebtopDownloadedAttachment?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file?.mimeType ?: "application/octet-stream")) { target ->
        val downloaded = file
        if (target != null && downloaded != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                (context.contentResolver.openOutputStream(target) ?: error("Could not open the save location.")).use { output ->
                    downloaded.file.inputStream().use { it.copyTo(output) }
                }
            } } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not save the attachment." }
        }
    }
    LaunchedEffect(providerId, url, retry) {
        error = null
        try {
            val provider = withContext(Dispatchers.IO) { PlatformStorage.loadPlatforms(context).filterIsInstance<OpenAUPlatform>()
                .firstOrNull { it.id == providerId } } ?: error("The OpenU account was removed.")
            file = OpenUAssignmentsRepository(context, provider).openFile(OpenUAssignmentFile(name, url, ""))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not open the attachment." }
    }
    MotionScaffold(scrollable = false, topBar = { SettingsTopBar(name, onBack) }) {
        Item { Column(Modifier.fillMaxSize()) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("Retry") } }
            val downloaded = file
            if (downloaded == null && error == null) CircularProgressIndicator()
            if (downloaded != null) {
                Row {
                    TextButton(onClick = { save.launch(downloaded.file.name) }) { Text("Save a copy") }
                    TextButton(onClick = { runCatching { openMessageAttachment(context, downloaded) }.onFailure { error = it.message } }) { Text("Open with…") }
                }
                if (downloaded.mimeType == "application/pdf" || downloaded.file.inputStream().use { input ->
                        val header = ByteArray(5); input.read(header) == 5 && header.contentEquals("%PDF-".toByteArray()) }) {
                    DrivePdfReader(downloaded.file, Modifier.weight(1f),
                        errorMessage = "This PDF could not be displayed. Save a copy or open it in another app.")
                } else Text("${downloaded.mimeType} · ${downloaded.file.length()} bytes. Open with a compatible app or save a copy.")
            }
        } }
    }
}

internal fun checkedTime(time: Long): String = if (time <= 0) "unavailable" else
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

@Composable
internal fun OpenUDraftHelpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    MotionScaffold(topBar = { SettingsTopBar("Connect a website draft", onBack) }) {
        Item { Text("Use this when files are staged in Opal's upload/update form, including in another browser. Connecting reads that exact draft without submitting or changing it.") }
        Section {
            Item { Text("1. Copy the bookmark code below and save it as a bookmark's URL in your Opal browser.") }
            Item { Text("2. In Opal, leave the assignment's upload/update form open with your files added.") }
            Item { Text("3. Run the bookmark. Scholix opens; select the same OpenU account and course to view the draft. It will also appear on the assignment's detail page.") }
        }
        Item { TextButton(onClick = {
            val source = context.assets.open("openu-draft-handoff.js").bufferedReader().use { it.readText() }
            val code = source.substringAfter("*/").lineSequence().joinToString(" ") { it.trim() }
            clipboard.setText(AnnotatedString("javascript:$code")); copied = true
        }) { Text(if (copied) "Bookmark code copied" else "Copy browser bookmark code") } }
        Item { Text("The bookmark shares only a draft reference. Your password and session tokens stay in their respective apps. Opening a fresh edit form does not recover another browser's unsent draft.") }
        BottomBarSpacer(minMargin = 24.dp)
    }
}
