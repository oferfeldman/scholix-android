package com.feldman.scholix.openu

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
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
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.feldman.motion.MotionFonts
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonDefaults
import com.feldman.motion.MotionButtonState
import com.feldman.motion.MotionIcons
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
import com.feldman.scholix.browser.ScholixBrowserActivity
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
                                  onCoursesChanged: (List<JSONObject>) -> Unit, includeTopInset: Boolean = true) {
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
        if (includeTopInset) Spacer(Modifier.height(TopBarSpacing()))
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
    var data by remember(repo, assignmentKey) { mutableStateOf(item?.let { repo?.cachedDetail(it) }) }
    var drafts by remember(repo, assignmentKey) { mutableStateOf(item?.let { repo?.cachedDrafts(it) }.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val uri = LocalUriHandler.current
    var openingUpload by remember { mutableStateOf(false) }
    var returningFromSubmission by remember { mutableStateOf(false) }
    val submitAssignment = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        returningFromSubmission = true
        retry++
    }
    var sendingFiles by remember(assignmentKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repo, assignmentKey, retry) {
        val selected = item
        if (repo == null || selected == null) return@LaunchedEffect
        loading = true; error = null
        try {
            val current = selected
            data = repo.detail(current)
            if (returningFromSubmission) {
                returningFromSubmission = false
                repo.cachedCourses().firstOrNull { it.optString("courseKey") == current.courseKey }?.let { course ->
                    item = repo.refresh(course).firstOrNull { it.key == current.key } ?: current
                }
            }
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
    MotionScaffold(
        scrollable = false,
        contentPadding = PaddingValues(0.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SettingsTopBar(item?.courseName.orEmpty(), onBack) {
                IconButton(onClick = { retry++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh assignment") }
            }
        },
    ) {
    Item {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = 16.dp)) {
        val assignment = item
        if (assignment == null || repo == null || provider == null) {
            Text("Assignment unavailable.", Modifier.padding(vertical = 24.dp))
        } else MotionLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                CompositionLocalProvider(LocalLayoutDirection provides if (isRtlText(assignment.label)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SubjectIcon(subject = assignment.courseName, assignment = assignment.label, size = 48.dp, iconSize = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(assignment.label, style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = MotionFonts.feldman(weight = 600), fontWeight = FontWeight.SemiBold))
                            if (assignment.status.isNotBlank()) Text(assignment.status, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (assignment.due.isNotBlank()) Text("Due ${assignment.due}", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp)) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(16.dp))
                StudyActions(StudyAssignment.create("https://opal.openu.ac.il", provider.id, assignment.courseKey,
                    assignment.module, assignment.cmid, assignment.label, assignment.courseName, assignment.status, repo.checkedAt(assignment)),
                    prepareContent = {
                        val detail = data ?: repo.detail(assignment)
                        val sources = detail.files.filter { it.group == "Original files" }.map { file ->
                            com.feldman.scholix.studies.AssignmentStudySource(file.name, repo.openFile(file).file,
                                Regex("solution|answer|פתרון|פתרונות", RegexOption.IGNORE_CASE).containsMatchIn(file.name))
                        }
                        com.feldman.scholix.studies.AssignmentStudyBundle.build(context,
                            StudyAssignment.create("https://opal.openu.ac.il", provider.id, assignment.courseKey,
                                assignment.module, assignment.cmid, assignment.label, assignment.courseName), sources, detail.instructions)
                    })
                if (assignment.module == "ouilassign") {
                    Spacer(Modifier.height(2.dp))
                    MotionButton(text = if (openingUpload) "Opening…" else "Submit assignment", icon = MotionIcons.Outlined.Upload,
                        modifier = Modifier.fillMaxWidth(), enabled = !openingUpload,
                        sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp, iconSize = 20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        states = MotionButtonDefaults.states(default = MotionButtonState(
                            backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer)), onClick = {
                        openingUpload = true
                        scope.launch {
                            try {
                                data = repo.detail(assignment)
                                val cookies = withContext(Dispatchers.IO) { provider.assignmentBrowserCookies(assignment.url) }
                                if (cookies.isEmpty()) throw java.io.IOException("Reconnect OpenU before submitting this assignment.")
                                val profile = "openu-submissions-${provider.id}"
                                val manager = ScholixBrowserActivity.cookieManager(profile)
                                for (cookie in cookies) kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                                    manager.setCookie(assignment.url, cookie) { accepted ->
                                        if (continuation.isActive) continuation.resumeWith(if (accepted) Result.success(Unit)
                                            else Result.failure(java.io.IOException("Could not open the OpenU session.")))
                                    }
                                }
                                submitAssignment.launch(ScholixBrowserActivity.intent(context, assignment.url, "Submit assignment", profile))
                            } catch (e: CancellationException) { throw e
                            } catch (e: Exception) { error = e.message ?: "Could not open submission."
                            } finally { openingUpload = false }
                        }
                    })
                } else TextButton(onClick = { uri.openUri(assignment.url.ifBlank { "https://sheilta.apps.openu.ac.il/Main/Courses" }) }) {
                    Text("Open in OpenU")
                }
            }
            val originalFiles = data?.files.orEmpty().filter { it.group == "Original files" }
            if (originalFiles.isNotEmpty()) item {
                MotionButton(
                    text = if (sendingFiles) "Preparing files…" else "Send assignment files",
                    enabled = !sendingFiles,
                    onClick = {
                        sendingFiles = true
                        error = null
                        scope.launch {
                            try {
                                val files = originalFiles.map { repo.openFile(it) }
                                val uris = ArrayList(files.map {
                                    FileProvider.getUriForFile(context, "${context.packageName}.provider", it.file)
                                })
                                val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
                                    .setPackage("com.seamless")
                                    .setType(files.map { it.mimeType }.distinct().singleOrNull() ?: "*/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.single())
                                else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                intent.clipData = ClipData.newRawUri(files.first().file.name, uris.first()).apply {
                                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                                }
                                context.startActivity(intent)
                            } catch (e: CancellationException) { throw e
                            } catch (_: ActivityNotFoundException) {
                                error = "Install Seamless to send files to another device."
                            } catch (e: Exception) {
                                error = e.message ?: "Could not send the assignment files."
                            } finally { sendingFiles = false }
                        }
                    }
                )
            }
            val fileGroups = data?.files.orEmpty().groupBy { it.group }.toList() +
                drafts.mapIndexed { index, draft -> "Unsubmitted website draft ${index + 1}" to draft.files }
            fileGroups.filter { it.second.isNotEmpty() }.forEach { (group, files) ->
                Title(group)
                section(items = files, key = { "$group:${it.url}" }, contentType = { "attachment" }) { file ->
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${file.name}") {
                        onNavigate(AppDest.OpenUAttachment(provider.id, file.name, file.url))
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(rememberSymbolPainter(MotionSymbols.ic_description), null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(rememberSymbolPainter(MotionSymbols.ic_open_in_new), null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            data?.instructions?.takeIf { it.isNotBlank() }?.let { instructions ->
                Title("Instructions")
                item {
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtlText(instructions)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Text(instructions, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                    }
                }
            }
            val detailGroups = listOf("Assignment details" to data?.fields.orEmpty(),
                "Submission details" to data?.submission.orEmpty())
            detailGroups.forEach { (title, values) ->
                val fields = values.filter { it.second.isNotBlank() }.distinct()
                if (fields.isNotEmpty()) {
                    Title(title)
                    section(items = fields.withIndex().toList(), key = { "$title:${it.index}" }) { field ->
                        val (label, value) = field.value
                        CompositionLocalProvider(LocalLayoutDirection provides if (isRtlText(label)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            if (assignment.opens.isNotBlank()) item { Text("Available from ${assignment.opens}") }
            data?.notices.orEmpty().filter(String::isNotBlank).forEachIndexed { index, notice ->
                item(key = "notice:$index") { Text(notice) }
            }

        }
    }
    }
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
