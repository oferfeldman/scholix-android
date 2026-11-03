package com.feldman.scholix.openu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionFonts
import com.feldman.motion.MotionBackButton
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionSymbols
import com.feldman.motion.rememberSymbolPainter
import com.feldman.motion.MotionLazyColumn
import com.feldman.motion.MotionNavigator
import com.feldman.motion.MotionScaffold
import com.feldman.scholix.AppDest
import com.feldman.scholix.TopBarSpacing
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopDownloadedAttachment
import com.feldman.scholix.drive.DrivePdfReader
import com.feldman.scholix.studies.StudyActions
import com.feldman.scholix.studies.StudyAssignment
import com.feldman.scholix.pages.gradeColor
import com.feldman.scholix.pages.GradesLoadingIndicator
import com.feldman.scholix.pages.isRtlText
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.SubjectIcon
import com.feldman.scholix.ui.components.openMessageAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
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
    var refreshing by remember { mutableStateOf(false) }
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
        finally { loading = false; refreshing = false }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(TopBarSpacing()))
        coursePicker()
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            ChipPicker("Status", listOf("All statuses", "Not submitted", "Submitted", "Checked"), stateFilter, onSelectedChange = { stateFilter = it })
            IconButton(onClick = { refreshing = true; retry++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh OpenU assignments") }
        }
        Spacer(Modifier.height(12.dp))
        val pullRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(state = pullRefreshState, isRefreshing = refreshing,
            onRefresh = { if (!loading) { refreshing = true; retry++ } },
            indicator = {
                Indicator(modifier = Modifier.align(Alignment.TopCenter), isRefreshing = refreshing,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, state = pullRefreshState)
            }, modifier = Modifier.weight(1f)) {
            if (loading) GradesLoadingIndicator(Modifier.fillMaxSize())
            else MotionLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (errors.isNotEmpty() && visible.isNotEmpty()) Text("Showing saved assignments.")
                    if (visible.isEmpty() && !loading) Text(if (searchQuery.isNotBlank()) "No matching assignments."
                        else if (errors.isNotEmpty()) "No saved assignment list is available. Refresh or reconnect OpenU."
                        else "No assignments in the selected courses and status.")
                }
                section(items = visible, key = { it.key }, contentType = { "assignment" }, contentPadding = 0.dp) { assignment ->
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtlText(assignment.label)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${assignment.label}") {
                            onNavigate(AppDest.OpenUAssignment(provider.id, assignment.key))
                        }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SubjectIcon(subject = assignment.courseName, assignment = assignment.label)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(assignment.label, style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = MotionFonts.feldman(weight = 500), fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(assignment.status.ifBlank { "Status unavailable" }, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (assignment.due.isNotBlank()) Text("Due ${assignment.due}", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (assignment.grade.isNotBlank() && assignment.grade != "-") {
                                Spacer(Modifier.width(8.dp))
                                Text(assignment.grade, style = MaterialTheme.typography.headlineLarge.copy(
                                    fontFamily = MotionFonts.feldman(weight = 700, width = 50f), fontWeight = FontWeight.Bold),
                                    color = gradeColor(assignment.grade), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(TopBarSpacing()))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MotionBackButton(onClick = onBack)
            Text(item?.courseName.orEmpty(), Modifier.weight(1f).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { retry++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh assignment") }
        }
        val assignment = item
        if (assignment == null || repo == null || provider == null) {
            Text("Assignment unavailable.", Modifier.padding(vertical = 24.dp))
        } else MotionLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                CompositionLocalProvider(LocalLayoutDirection provides if (isRtlText(assignment.label)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        SubjectIcon(subject = assignment.courseName, assignment = assignment.label)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(assignment.label, style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = MotionFonts.feldman(weight = 600), fontWeight = FontWeight.SemiBold))
                            Text(assignment.semester, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (assignment.grade.isNotBlank() && assignment.grade != "-") {
                            Text(assignment.grade, style = MaterialTheme.typography.displaySmall.copy(
                                fontFamily = MotionFonts.feldman(weight = 700, width = 50f)), color = gradeColor(assignment.grade))
                        }
                    }
                }
                Text(assignment.status.ifBlank { "Status unavailable" }, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (assignment.due.isNotBlank()) Text("Due: ${assignment.due}", Modifier.padding(top = 8.dp))
                if (assignment.opens.isNotBlank()) Text("Available from: ${assignment.opens}", Modifier.padding(top = 4.dp))
                if (loading) GradesLoadingIndicator(Modifier.fillMaxWidth().height(80.dp))
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(16.dp))
                MotionButton(text = "Open in OpenU", onClick = {
                    uri.openUri(assignment.url.ifBlank { "https://sheilta.apps.openu.ac.il/Main/Courses" })
                })
                if (assignment.module == "ouilassign") TextButton(onClick = { onNavigate(AppDest.OpenUDraftHelp) }) {
                    Text("Connect website draft")
                }
            }
            data?.let { detail ->
                if (detail.instructions.isNotBlank()) {
                    Title("Instructions")
                    item { Text(detail.instructions, style = MaterialTheme.typography.bodyLarge) }
                }
                listOf("Assignment details" to detail.fields, "Submission and grading" to detail.submission)
                    .filter { it.second.isNotEmpty() }.forEach { (heading, fields) ->
                        Title(heading)
                        section(items = fields.withIndex().toList(), key = { "$heading:${it.index}" }) { field ->
                            val (label, value) = field.value
                            CompositionLocalProvider(LocalLayoutDirection provides if (isRtlText(label)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value, Modifier.weight(1.5f), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                detail.notices.forEachIndexed { index, notice -> item(key = "notice:$index") { Text(notice) } }
            }
            val fileGroups = data?.files.orEmpty().groupBy { it.group }.toList() +
                drafts.mapIndexed { index, draft -> "Unsubmitted website draft ${index + 1}" to draft.files }
            fileGroups.forEach { (group, files) ->
                Title(group)
                section(items = files, key = { "$group:${it.url}" }, contentType = { "attachment" }) { file ->
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${file.name}") {
                        onNavigate(AppDest.OpenUAttachment(provider.id, file.name, file.url))
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(rememberSymbolPainter(MotionSymbols.ic_description), null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text(file.name, Modifier.weight(1f).padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(rememberSymbolPainter(MotionSymbols.ic_open_in_new), null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (files.isEmpty()) item { Text("No files.") }
            }
            Title("Study")
            item { StudyActions(StudyAssignment.create("https://opal.openu.ac.il", provider.id, assignment.courseKey,
                assignment.module, assignment.cmid, assignment.label, assignment.courseName, assignment.status, repo.checkedAt(assignment))) }
        }
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
