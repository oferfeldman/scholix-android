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
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionScaffold
import com.feldman.scholix.ui.components.SettingsTopBar

/** Used by assignments and the general catalog; no upload or provider submission is performed. */
@Composable
fun StudyActions(assignment: StudyAssignment? = null) {
    val context = LocalContext.current
    val catalog = remember(context) { StudyCatalog(context) }
    var entries by remember(assignment?.key) { mutableStateOf(catalog.entries()) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var targetPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<List<ComponentName>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
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
                catalog.save(reference)
                entries = catalog.entries()
                open(reference)
            }.onFailure { error = it.message ?: "Could not open this study." }
        }
    }
    fun launch(component: ComponentName) {
        targetPackage = component.packageName
        runCatching { launcher.launch(Intent(DocumentsStudyContract.ACTION).setComponent(component)
            .putExtra(DocumentsStudyContract.EXTRA, pending)) }
            .onFailure { pending = null; targetPackage = null; error = "Update Documents to use study links." }
    }
    fun request(reference: StudyReference? = null, choose: Boolean = false) {
        error = null
        pending = DocumentsStudyContract.request(assignment, reference, choose)
        val targets = context.packageManager.queryIntentActivities(Intent(DocumentsStudyContract.ACTION), PackageManager.MATCH_DEFAULT_ONLY)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { reference?.documentsPackage == null || it.packageName == reference.documentsPackage }.distinct()
        when (targets.size) {
            0 -> { pending = null; error = "Install or update Documents to create and link studies." }
            1 -> launch(targets.single())
            else -> picker = targets
        }
    }
    if (picker.isNotEmpty()) AlertDialog(onDismissRequest = { picker = emptyList(); pending = null },
        title = { Text("Choose Documents app") }, text = { Column {
            picker.forEach { target -> TextButton(onClick = { picker = emptyList(); launch(target) }) { Text(target.packageName) } }
        } }, confirmButton = {}, dismissButton = {
            TextButton(onClick = { picker = emptyList(); pending = null }) { Text("Cancel") }
        })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.filter { assignment == null || it.assignmentKey == assignment.key }.forEach { reference ->
            MotionButton(text = "Open ${reference.title}", enabled = reference.isLocal && pending == null,
                onClick = { request(reference) })
            if (!reference.isLocal) Text("This published study has not been downloaded. Download support is not available yet.")
        }
        MotionButton(text = if (assignment == null) "Create or link a study" else "Create or link assignment study",
            enabled = pending == null, onClick = { request(choose = true) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun StudiesPage(onBack: () -> Unit) {
    MotionScaffold(topBar = { SettingsTopBar("Studies", onBack) }) {
        Item { Text("Your linked studies open in Documents. Studies can be linked to an assignment or kept general.") }
        Item { StudyActions() }
        BottomBarSpacer(minMargin = 24.dp)
    }
}
