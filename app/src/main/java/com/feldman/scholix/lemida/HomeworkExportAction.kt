package com.feldman.scholix.lemida

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** User-selected local file handoff; no network sync or account/session export. */
@Composable
fun rememberHomeworkExportAction(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prepared by remember { mutableStateOf<SavedHomeworkExport?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val path = pendingPath
        pendingPath = null
        prepared = null
        scope.launch {
            exporting = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = path?.let(::File)
                    if (uri != null) {
                        check(file != null && file.isFile) { "Open Save homework for PC again to prepare the file." }
                        check(file.canonicalFile.parentFile == context.cacheDir.canonicalFile && file.name.startsWith("hub-homework-"))
                        val bytes = file.readBytes()
                        check(bytes.size <= 8 * 1024 * 1024)
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "The selected file could not be opened." }
                            .use { it.write(bytes); it.flush() }
                    }
                    if (file?.canonicalFile?.parentFile == context.cacheDir.canonicalFile && file.name.startsWith("hub-homework-")) file.delete()
                }
            }
            failure = result.isFailure
            message = result.exceptionOrNull()?.message ?: if (uri != null) "Open this homework file in Feldman Hub on your PC. Studies and handwriting use the library's separate study export." else null
            exporting = false
        }
    }
    prepared?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { prepared = null },
            title = { Text("Save homework for PC") },
            text = { Text("${snapshot.courseCount} courses · ${snapshot.homeworkCount} homework items\nLast homework refresh: ${DateFormat.getDateTimeInstance().format(Date(snapshot.savedAt))}\n\nIncludes saved descriptions, dates and source links. Your sign-in session stays on this device.") },
            confirmButton = { TextButton(enabled = !exporting, onClick = {
                scope.launch {
                    exporting = true
                    runCatching {
                        pendingPath = withContext(Dispatchers.IO) {
                            File.createTempFile("hub-homework-", ".json", context.cacheDir).also { it.writeText(snapshot.json, Charsets.UTF_8) }.absolutePath
                        }
                        picker.launch("Feldman-homework.json")
                    }.onFailure { failure = true; message = it.message }
                    exporting = false
                }
            }) { Text("Choose where to save") } },
            dismissButton = { TextButton(enabled = !exporting, onClick = { prepared = null }) { Text("Cancel") } },
        )
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text(if (failure) "Could not save homework" else "Homework saved") },
            text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
    return {
        if (!exporting) scope.launch {
            exporting = true
            runCatching { withContext(Dispatchers.IO) { SavedHomeworkExport.read(context) } }
                .onSuccess { prepared = it }.onFailure { failure = true; message = it.message ?: "Refresh Lemida before saving homework." }
            exporting = false
        }
    }
}
