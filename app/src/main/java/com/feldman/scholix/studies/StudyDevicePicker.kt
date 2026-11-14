package com.feldman.scholix.studies

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seamless.link.api.SeamlessLink
import com.seamless.link.protocol.LinkErrors
import com.feldman.motion.MotionDialog
import com.feldman.motion.MotionScaffold
import org.json.JSONObject
import kotlinx.coroutines.launch

private const val REMOTE_STUDY = "com.feldman.documents.action.REMOTE_STUDY"

@Composable
internal fun StudyDevicePicker(payload: String, localPackage: String?, prepareRemote: suspend () -> String = { payload }, onLocal: (ComponentName) -> Unit,
                               onSent: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasContent = remember(payload) { JSONObject(payload).optBoolean("content") }
    val localAction = if (hasContent) DocumentsStudyContract.CONTENT_ACTION else DocumentsStudyContract.ACTION
    val remoteAction = if (hasContent) DocumentsStudyContract.REMOTE_CONTENT_ACTION else REMOTE_STUDY
    var devices by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var approval by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    val local = remember(localPackage, localAction) {
        context.packageManager.queryIntentActivities(Intent(localAction), PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && (localPackage == null || it.activityInfo.packageName == localPackage) }
    }
    val link = remember { SeamlessLink(context) { state ->
        connected = state.optBoolean("connected")
        if (state.optString("errorCode") == LinkErrors.APPROVAL_REQUIRED) { approval = true; loading = false }
    } }
    fun load() {
        loading = true; error = null; approval = false
        link.appDevices(remoteAction) { result ->
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
        if (it.resultCode == Activity.RESULT_OK) link.request("watch")
    }
    DisposableEffect(link) {
        if (!link.connect()) { loading = false; error = "Install or connect Seamless to open on another device." }
        onDispose { link.close() }
    }
    LaunchedEffect(connected) { if (connected) load() }
    MotionDialog(onDismissRequest = { if (!sending) onDismiss() }, title = "Open in Documents",
        confirmText = if (connected && !loading && !sending) "Refresh" else null,
        onConfirm = { load() }, dismissText = "Cancel", onDismiss = { if (!sending) onDismiss() }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading || sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (local.isNotEmpty() || devices.any { it.optBoolean("supported") })
            MotionScaffold(modifier = Modifier.heightIn(max = 400.dp), contentPadding = PaddingValues(0.dp), fitContentHeight = true) {
                Section {
                    local.forEach { target ->
                        PageItem(title = if (local.size == 1) "This device" else "This device · ${target.loadLabel(context.packageManager)}", icon = rememberVectorPainter(Icons.Outlined.Smartphone), onClick = {
                            if (!sending)
                            onLocal(ComponentName(target.activityInfo.packageName, target.activityInfo.name))
                        })
                    }
                    devices.filter { it.optBoolean("supported") }.forEach { device ->
                        val components = device.getJSONArray("components")
                        (0 until components.length()).forEach { index ->
                            val component = components.getString(index)
                            PageItem(title = device.getString("name") + if (components.length() > 1) " · ${component.substringBefore('/')}" else "", icon = rememberVectorPainter(Icons.Outlined.Devices), onClick = {
                                if (!sending && !loading) {
                                    sending = true; error = null
                                    // A remote study is resolved on that device by assignment identity, never a phone-local study ID.
                                    scope.launch {
                                        try {
                                            val request = JSONObject(prepareRemote()).apply { remove("studyId") }.toString()
                                            link.launchApp(device.getString("id"), component, remoteAction, request) { result ->
                                                sending = false
                                                if (result.containsKey(SeamlessLink.ERROR)) error = "Could not open Documents. Refresh devices and try again."
                                                else onSent(device.getString("name"))
                                            }
                                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                                        } catch (e: Exception) { sending = false; error = e.message ?: "Could not transfer study files." }
                                    }
                                }
                            })
                        }
                    }
                }
            }
            if (!loading && devices.none { it.optBoolean("supported") } && error == null && !approval)
                Text(if (devices.any { !it.optBoolean("checked") }) "No supported devices confirmed. Check the connection in Seamless."
                    else "No supported devices found. Update Documents on your other device.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (approval) TextButton(onClick = { runCatching { access.launch(link.accessIntent()) }
                .onFailure { error = "Update Seamless to allow app access." } }) { Text("Connect Seamless") }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}
