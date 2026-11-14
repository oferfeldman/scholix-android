package com.feldman.scholix.studies

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.lemida.LemidaRepository
import com.feldman.scholix.lemida.LemidaSnapshot
import com.feldman.scholix.lemida.studyAssignment
import com.feldman.scholix.openu.OpenUAssignmentsRepository
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonDefaults
import com.feldman.motion.MotionIcons
import com.feldman.motion.MotionScaffold
import com.feldman.scholix.ui.components.SettingsTopBar

/** Used by assignments and the general catalog; no upload or provider submission is performed. */
@Composable
fun StudyActions(assignment: StudyAssignment? = null, courseProviderId: String? = null, courseKey: String? = null,
                 courseAssignmentKeys: Set<String> = emptySet(), prepareContent: (suspend () -> java.io.File)? = null) {
    val context = LocalContext.current
    val catalog = remember(context) { StudyCatalog(context) }
    var entries by remember(assignment?.key, courseProviderId, courseKey) { mutableStateOf(catalog.entries()) }
    val scope = rememberCoroutineScope()
    var contentPath by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<StudyContentTransfer?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var targetPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var localPackage by remember { mutableStateOf<String?>(null) }
    fun open(reference: StudyReference) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("feldman-documents://study/${reference.studyId}"))
            .setPackage(reference.documentsPackage))
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending
        val owner = targetPackage
        pending = null
        targetPackage = null
        if (result.resultCode == Activity.RESULT_OK && request != null && owner != null) {
            runCatching {
                val reference = DocumentsStudyContract.receipt(requireNotNull(result.data?.getStringExtra(DocumentsStudyContract.EXTRA)), request, owner)
                catalog.save(reference.copy(courseProviderId = courseProviderId, courseKey = courseKey))
                entries = catalog.entries()
                open(reference)
            }.onFailure { error = it.message ?: "Could not open this study." }
        }
    }
    fun launch(component: ComponentName) {
        targetPackage = component.packageName
        runCatching { launcher.launch(Intent(if (contentPath != null) DocumentsStudyContract.CONTENT_ACTION else DocumentsStudyContract.ACTION).setComponent(component)
            .putExtra(DocumentsStudyContract.EXTRA, pending).apply {
                contentPath?.let { path ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", java.io.File(path))
                    clipData = android.content.ClipData.newRawUri("Assignment study", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("com.feldman.documents.STUDY_CONTENT", uri.toString())
                }
            }) }
            .onFailure { pending = null; targetPackage = null; error = "Update Documents to use study links." }
    }
    fun request(reference: StudyReference? = null, choose: Boolean = true) {
        error = null
        message = null
        pending = DocumentsStudyContract.request(assignment, reference, choose)
        localPackage = reference?.documentsPackage
        preparing = true
        scope.launch {
            try {
                contentPath = prepareContent?.invoke()?.absolutePath
                if (contentPath != null) pending = JSONObject(requireNotNull(pending)).put("content", true).toString()
                picker = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { pending = null; error = e.message ?: "Could not prepare assignment files."
            } finally { preparing = false }
        }
    }
    if (picker && pending != null) StudyDevicePicker(pending!!, localPackage,
        prepareRemote = {
            val request = requireNotNull(pending)
            val json = JSONObject(request)
            contentPath?.let { path ->
                transfer?.close()
                val next = StudyContentTransfer(context, java.io.File(path)) { raw ->
                    runCatching {
                        val receipt = DocumentsStudyContract.receipt(raw, JSONObject(request).apply { remove("catalogId") }.toString(), "remote")
                        catalog.save(receipt.copy(documentsPackage = null, courseProviderId = courseProviderId, courseKey = courseKey))
                        entries = catalog.entries()
                    }.onFailure { error = it.message ?: "Could not save the remote study link." }
                }
                transfer = next
                val source = next.publish()
                source.keys().forEach { key -> json.put(key, source.get(key)) }
            }
            json.toString()
        },
        onLocal = { picker = false; launch(it) },
        onSent = { picker = false; pending = null; message = "Opened Documents on $it" },
        onDismiss = { picker = false; pending = null })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.filter { reference ->
            assignment == null &&
                (courseProviderId == null || (reference.courseProviderId == courseProviderId && reference.courseKey == courseKey) ||
                    reference.assignmentKey in courseAssignmentKeys)
        }.forEach { reference ->
            MotionButton(text = "Open ${reference.title}", enabled = pending == null,
                modifier = Modifier.fillMaxWidth(),
                sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp, iconSize = 20.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                onClick = { request(reference) })
            if (!reference.isLocal && reference.publication != null) Text("This published study has not been downloaded. Download support is not available yet.")
        }
        MotionButton(text = "Open in Documents", icon = MotionIcons.Outlined.School,
            modifier = Modifier.fillMaxWidth(), enabled = pending == null,
            sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp, iconSize = 20.dp),
            contentPadding = PaddingValues(horizontal = 20.dp), onClick = {
            request(assignment?.let { item -> entries.firstOrNull { it.assignmentKey == item.key } })
        })
        if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun StudiesPage(onBack: () -> Unit, providerId: String? = null, courseKey: String? = null) {
    val context = LocalContext.current
    val providers = LocalAppState.current.platforms
    val repository = remember(context) { LemidaRepository(context) }
    val lemida by repository.snapshots.collectAsStateWithLifecycle(initialValue = LemidaSnapshot())
    val provider = providers.firstOrNull { it.id == providerId }
    val assignmentKeys = remember(providerId, courseKey, lemida, providers) {
        when {
            providerId == "lemida:${lemida.account}" && lemida.account.isNotBlank() ->
                lemida.homework.filter { it.courseId.toString() == courseKey }.map { it.studyAssignment(lemida.account).key }
            provider is OpenAUPlatform -> OpenUAssignmentsRepository(context, provider).cachedAssignments()
                .filter { it.courseKey == courseKey }.map {
                    StudyAssignment.create("https://opal.openu.ac.il", provider.id, it.courseKey,
                        it.module, it.cmid, it.label, it.courseName).key
                }
            provider is GoogleClassroomPlatform -> provider.cachedHomework()
                .filter { it.optString("courseId") == courseKey && it.optString("kind") == "courseWork" }.map {
                    StudyAssignment.create("https://classroom.googleapis.com", provider.getUsername().trim().lowercase(java.util.Locale.ROOT),
                        it.getString("courseId"), "courseWork", it.getString("id"), it.getString("title"), it.optString("courseName")).key
                }
            else -> emptyList()
        }.toSet()
    }
    MotionScaffold(topBar = { SettingsTopBar("Studies", onBack) }) {
        Item { Text("Your linked studies open in Documents. Studies can be linked to an assignment or kept general.") }
        Item { StudyActions(courseProviderId = providerId, courseKey = courseKey, courseAssignmentKeys = assignmentKeys) }
        BottomBarSpacer(minMargin = 24.dp)
    }
}
