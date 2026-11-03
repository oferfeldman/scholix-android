package com.feldman.scholix.openu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionNavFlow
import com.feldman.motion.MotionNavigator
import com.feldman.motion.MotionScaffold
import com.feldman.scholix.AppDest
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.SettingsTopBar
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Reads a reference from the exact open website form; it has no submission/upload actions. */
class OpenUDraftActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val reference = intent.dataString.orEmpty()
        setContent {
            MotionNavFlow(startDestination = AppDest.OpenUWebsiteDraft(reference),
                destinations = listOf(AppDest.OpenUWebsiteDraft(""), AppDest.OpenUAttachment("", "", "")),
                navigation = null, onRootBack = ::finish)
        }
    }
}

@Composable
internal fun OpenUWebsiteDraftPage(raw: String, onNavigate: MotionNavigator, onBack: () -> Unit) {
    val context = LocalContext.current
    val reference = remember(raw) { OpenUDraftReference.parse(raw) }
    val providers = remember { PlatformStorage.loadPlatforms(context).filterIsInstance<OpenAUPlatform>() }
    var providerId by rememberSaveable { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
    val provider = providers.firstOrNull { it.id == providerId }
    val repo = remember(provider) { provider?.let { OpenUAssignmentsRepository(context, it) } }
    var courses by remember(repo) { mutableStateOf(repo?.cachedCourses().orEmpty()) }
    var courseKey by rememberSaveable(providerId) { mutableStateOf(repo?.cachedAssignments()
        ?.firstOrNull { it.moodleId == reference?.course }?.courseKey.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var files by remember(repo, courseKey) { mutableStateOf<List<OpenUAssignmentFile>>(emptyList()) }
    var assignment by remember(repo, courseKey) { mutableStateOf<OpenUAssignment?>(null) }
    var checkedAt by remember(repo, courseKey) { mutableLongStateOf(0) }
    LaunchedEffect(repo, courseKey, reference, retry) {
        if (repo == null || reference == null) return@LaunchedEffect
        loading = true; error = null; files = emptyList()
        try {
            courses = repo.refreshCourses()
            val course = courses.firstOrNull { it.optString("courseKey") == courseKey }
            if (course != null) {
                val item = repo.refresh(course).firstOrNull { it.cmid == reference.cmid && it.moodleId == reference.course }
                    ?: error("This website draft does not belong to the selected course/account.")
                assignment = item
                files = repo.draft(item, reference)
                checkedAt = System.currentTimeMillis()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not read this website draft." }
        finally { loading = false }
    }
    MotionScaffold(topBar = { SettingsTopBar("Unsubmitted website draft", onBack, actions = {
        IconButton(onClick = { retry++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh website draft") }
    }) }) {
        Item { Text("Choose the OpenU account and course.") }
        if (reference == null) Item { Text("This website draft link is incomplete or invalid.") }
        else if (provider == null || repo == null) Item { Text("Connect your OpenU account in Scholix, then open this draft link again.") }
        else {
            Item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val accounts = providers.map { "OpenU · ${it.getUsername()}" }
                ChipPicker("Account", accounts, accounts[providers.indexOf(provider)], onSelectedChange = {
                    providerId = providers[accounts.indexOf(it)].id
                })
                val names = listOf("Choose course") + courses.map { "${it.optString("name")} · ${it.optString("term")}" }
                ChipPicker("Course", names, names[courses.indexOfFirst { it.optString("courseKey") == courseKey } + 1], onSelectedChange = {
                    courseKey = courses.getOrNull(names.indexOf(it) - 1)?.optString("courseKey").orEmpty()
                }, menuAnimation = com.feldman.motion.MotionDropdownAnimation.Morph)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                assignment?.let { Text("${it.label} · Accepted status: ${it.status.ifBlank { "unavailable" }}") }
            } }
            if (checkedAt > 0 && files.isEmpty() && error == null && !loading) Item { Text("This draft currently has no files.") }
            Section { files.forEach { f -> PageItem(title = f.name, description = "Unsubmitted file · open attachment",
                onClick = { onNavigate(AppDest.OpenUAttachment(provider.id, f.name, f.url)) }) } }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
}
