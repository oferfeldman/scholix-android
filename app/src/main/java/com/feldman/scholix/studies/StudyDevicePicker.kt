package com.feldman.scholix.studies

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seamless.link.api.SeamlessLink
import com.seamless.link.protocol.LinkErrors
import org.json.JSONObject

private const val REMOTE_STUDY = "com.feldman.documents.action.REMOTE_STUDY"

@Composable
internal fun StudyDevicePicker(payload: String, localPackage: String?, onLocal: (ComponentName) -> Unit,
                               onSent: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var approval by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    val local = remember(localPackage) {
        context.packageManager.queryIntentActivities(Intent(DocumentsStudyContract.ACTION), PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && (localPackage == null || it.activityInfo.packageName == localPackage) }
    }
    val link = remember { SeamlessLink(context) { state ->
        connected = state.optBoolean("connected")
        if (state.optString("errorCode") == LinkErrors.APPROVAL_REQUIRED) { approval = true; loading = false }
    } }
    fun load() {
        loading = true; error = null; approval = false
        link.appDevices(REMOTE_STUDY) { result ->
            loading = false
            if (result.getString(SeamlessLink.ERROR_CODE) == LinkErrors.APPROVAL_REQUIRED) approval = true
            else if (result.containsKey(SeamlessLink.ERROR)) error = "Connect or update Seamless to find your devices."
            else runCatching {
                val rows = JSONObject(result.getString(SeamlessLink.JSON)!!).getJSONArray("devices")
                devices = (0 until rows.length()).map { rows.getJSONObject(it) }
            }.onFailure { error = "Update Seamless to find supported devices." }
        }
    }
    val access = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) load()
    }
    DisposableEffect(link) {
        if (!link.connect()) { loading = false; error = "Install or connect Seamless to open on another device." }
        onDispose { link.close() }
    }
    LaunchedEffect(connected) { if (connected) load() }
    AlertDialog(onDismissRequest = { if (!sending) onDismiss() }, title = { Text("Open fstudy") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            local.forEach { target ->
                TextButton(enabled = !sending, onClick = {
                    onLocal(ComponentName(target.activityInfo.packageName, target.activityInfo.name))
                }) { Text(if (local.size == 1) "This device" else "This device · ${target.loadLabel(context.packageManager)}") }
            }
            if (loading || sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            devices.filter { it.optBoolean("supported") }.forEach { device ->
                val components = device.getJSONArray("components")
                (0 until components.length()).forEach { index ->
                    val component = components.getString(index)
                    TextButton(enabled = !sending && !loading, onClick = {
                        sending = true; error = null
                        // A remote study is resolved on that device by assignment identity, never a phone-local study ID.
                        val request = JSONObject(payload).apply { remove("studyId"); put("choose", false) }.toString()
                        link.launchApp(device.getString("id"), component, REMOTE_STUDY, request) { result ->
                            sending = false
                            if (result.containsKey(SeamlessLink.ERROR)) error = "Could not open Documents. Refresh devices and try again."
                            else onSent(device.getString("name"))
                        }
                    }) { Text(device.getString("name") + if (components.length() > 1) " · ${component.substringBefore('/')}" else "") }
                }
            }
            if (!loading && devices.none { it.optBoolean("supported") } && error == null && !approval)
                Text(if (devices.any { !it.optBoolean("checked") }) "No supported devices confirmed. Check the connection in Seamless."
                    else "No supported devices found. Update Documents on your other device.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (approval) TextButton(onClick = { runCatching { access.launch(link.accessIntent()) }
                .onFailure { error = "Update Seamless to allow app access." } }) { Text("Connect Seamless") }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = connected && !loading && !sending, onClick = { load() }) { Text("Refresh") } },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text("Cancel") } })
}
