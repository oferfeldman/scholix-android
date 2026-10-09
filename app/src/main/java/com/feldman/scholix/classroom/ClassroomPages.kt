package com.feldman.scholix.classroom

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionScaffold
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.SettingsTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun ClassroomHomeworkPage(provider: GoogleClassroomPlatform, searchQuery: String = "", providerPicker: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    var items by remember(provider.id) { mutableStateOf(provider.cachedHomework()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable(provider.id) { mutableStateOf<String?>(null) }
    var course by rememberSaveable(provider.id) { mutableStateOf("all") }
    var type by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) retry++
    }
    LaunchedEffect(provider.id, retry) {
        loading = true
        error = null
        try {
            items = withContext(Dispatchers.IO) {
                val current = PlatformStorage.loadPlatforms(context).filterIsInstance<GoogleClassroomPlatform>().first { it.id == provider.id }
                current.refreshHomework().also { PlatformStorage.addPlatform(context, current) }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.localizedMessage ?: "Could not update Classroom homework."
        } finally { loading = false }
    }
    val selectedItem = items.firstOrNull { "${it.optString("kind")}:${it.optString("courseId")}:${it.optString("id")}" == selected }
    if (selectedItem != null) {
        BackHandler { selected = null }
        ClassroomPostPage(selectedItem, onBack = { selected = null })
        return
    }
    val courses = items.distinctBy { it.optString("courseId") }
    val courseNames = listOf("All courses") + courses.map { item ->
        item.optString("courseName") + if (courses.count { it.optString("courseName") == item.optString("courseName") } > 1)
            " · ${item.optString("courseId")}" else ""
    }
    val visible = items.filter { item ->
        (course == "all" || course == item.optString("courseId")) &&
            (type == "All" || if (type == "Materials") item.optString("kind") == "material" else item.optString("kind") == "courseWork") &&
            listOf(query, searchQuery).all { search -> search.trim().split(Regex("\\s+")).all {
                "${item.optString("title")} ${item.optString("description")} ${item.optString("courseName")}".contains(it, ignoreCase = true)
            } }
    }
    MotionScaffold(topBar = { SettingsTopBar("Homework") }) {
        if (providerPicker != null) Item { providerPicker() }
        Item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search homework") }, shape = MaterialTheme.shapes.large)
            ChipPicker(label = "Course", options = courseNames,
                selected = courseNames[courses.indexOfFirst { it.optString("courseId") == course } + 1],
                onSelectedChange = { name -> course = courses.getOrNull(courseNames.indexOf(name) - 1)?.optString("courseId") ?: "all" })
            ChipPicker(label = "Type", options = listOf("All", "Coursework", "Materials"), selected = type, onSelectedChange = { type = it })
            MotionButton(text = "Refresh", onClick = { retry++ }, enabled = !loading)
        } }
        if (loading) Item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) Item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            MotionButton(text = "Reconnect Google Classroom", onClick = {
                login.launch(Intent(context, ClassroomLoginActivity::class.java).putExtra("email", provider.getUsername()))
            })
        } }
        if (visible.isEmpty() && !loading && error == null) Item { Text("No Classroom coursework or materials match this view.") }
        visible.groupBy { it.optString("courseId") }.forEach { (_, group) ->
            Title(group.first().optString("courseName"))
            Section { group.sortedByDescending { it.optString("creationTime") }.forEach { item ->
                val key = "${item.optString("kind")}:${item.optString("courseId")}:${item.optString("id")}"
                PageItem(key = key, title = item.optString("title"), description =
                    if (item.optString("kind") == "material") "Class material"
                    else "${ClassroomMapping.due(item)} · ${ClassroomMapping.status(item.optJSONObject("submission"))}",
                    onClick = { selected = key })
            } }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
}

@Composable
fun ClassroomAnnouncementPage(provider: GoogleClassroomPlatform, messageId: String, onBack: () -> Unit) {
    var post by remember(provider.id, messageId) { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(provider.id, messageId, retry) {
        error = null
        try { post = withContext(Dispatchers.IO) { provider.getMessageDetails(messageId).put("kind", "announcement") } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.localizedMessage ?: "Could not load announcement." }
    }
    val item = post
    if (item != null) ClassroomPostPage(item, onBack)
    else MotionScaffold(topBar = { SettingsTopBar("Announcement", onBack) }) {
        Item { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else Column {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            MotionButton(text = "Retry", onClick = { retry++ })
        } }
    }
}

@Composable
private fun ClassroomPostPage(post: JSONObject, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    val announcement = post.optString("kind") == "announcement"
    val coursework = post.optString("kind") == "courseWork"
    MotionScaffold(topBar = { SettingsTopBar(if (announcement) "Announcement" else "Homework", onBack) }) {
        Title(post.optString("courseName"))
        Item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!announcement) Text(post.optString("title"), style = MaterialTheme.typography.headlineSmall)
            Text(post.optString(if (announcement) "text" else "description"))
            if (coursework) {
                Text("Due: ${ClassroomMapping.due(post)}")
                val submission = post.optJSONObject("submission")
                Text(ClassroomMapping.status(submission))
                if (submission?.has("assignedGrade") == true) Text("Grade: ${submission.opt("assignedGrade")} / ${post.opt("maxPoints") ?: "ungraded"}")
                submission?.optJSONObject("shortAnswerSubmission")?.optString("answer")?.takeIf { it.isNotBlank() }?.let { Text("Your answer: $it") }
                submission?.optJSONObject("multipleChoiceSubmission")?.optString("answer")?.takeIf { it.isNotBlank() }?.let { Text("Your answer: $it") }
            }
        } }
        val materials = ClassroomMapping.materials(post)
        if (materials.isNotEmpty()) {
            Title("Attachments")
            Section { materials.forEach { file -> PageItem(title = file.getString("title"), onClick = { uri.openUri(file.getString("url")) }) } }
        }
        val submitted = post.optJSONObject("submission")?.optJSONObject("assignmentSubmission")?.optJSONArray("attachments")
        val submissionFiles = ClassroomMapping.materials(JSONObject().put("materials", submitted))
        if (submissionFiles.isNotEmpty()) {
            Title("Your submission")
            Section { submissionFiles.forEach { file -> PageItem(title = file.getString("title"), onClick = { uri.openUri(file.getString("url")) }) } }
        }
        post.optString("alternateLink").takeIf { it.isNotBlank() }?.let { url ->
            Item { MotionButton(text = "Open in Google Classroom", onClick = { uri.openUri(url) }) }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
}
