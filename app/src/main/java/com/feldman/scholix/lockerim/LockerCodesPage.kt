package com.feldman.scholix.lockerim

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.feldman.motion.*
import com.feldman.scholix.AppDest
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.SettingsTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LockerCodesPage(onNavigate: MotionNavigator) {
    val context = LocalContext.current
    val store = remember(context) { LockerCodesStore(context) }
    val scope = rememberCoroutineScope()
    var codes by remember { mutableStateOf<List<LockerCode>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var delete by remember { mutableStateOf<LockerCode?>(null) }
    val changes by LockerCodesStore.changes.collectAsState()
    LaunchedEffect(store, changes) {
        try { codes = withContext(Dispatchers.IO) { store.load() }; error = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not read saved locker codes." }
        finally { loading = false }
    }
    MotionScaffold(topBar = { SettingsTopBar("Locker") }) {
        Section {
            PageItem(title = "Contents", onClick = { onNavigate(AppDest.LockerContents) })
            PageItem(title = "לוקרים", onClick = { onNavigate(AppDest.Lockerim) })
        }
        Item { MotionButton(text = "Add locker code", enabled = !loading && error == null,
            onClick = { onNavigate(AppDest.LockerCodeEditor()) }) }
        if (loading) Item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> Item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (!loading && error == null && codes.isEmpty()) Item { Text("No saved locker codes.") }
        codes.forEach { code -> Item {
            key(code.id) {
                var visible by remember { mutableStateOf(false) }
                MotionCard(position = MotionItemPosition.Alone, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(code.name, style = MaterialTheme.typography.titleLarge)
                        if (code.location.isNotBlank()) Text(code.location)
                        Text(if (code.dial) "Dial combination" else "Numeric code", style = MaterialTheme.typography.labelLarge)
                        if (!visible) Text("••••")
                        else if (!code.dial) Text(code.pin, style = MaterialTheme.typography.headlineMedium)
                        else code.steps.forEachIndexed { index, step ->
                            Text("${index + 1}. ${if (step.clockwise) "Right ↻" else "Left ↺"} · ${step.turns} ${if (step.turns == 1) "turn" else "turns"} · stop at ${step.number}")
                        }
                        Row {
                            TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") }
                            TextButton(onClick = { onNavigate(AppDest.LockerCodeEditor(code.id)) }) { Text("Edit") }
                            TextButton(onClick = { delete = code }) { Text("Delete") }
                        }
                    }
                }
            }
        } }
    }
    delete?.let { code -> MotionDialog(title = "Delete ${code.name}?", text = "This removes the saved locker code.",
        onDismissRequest = { delete = null }, dismissText = "Cancel", confirmText = "Delete code", onConfirm = {
            scope.launch {
                try { withContext(Dispatchers.IO) { store.delete(code.id) }; delete = null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { delete = null; error = "Could not delete the locker code." }
            }
        }) }
}

/** Keep an unfinished secret in memory across rotation, never in saved-instance state. */
class LockerCodeEditorState : ViewModel() {
    var code by mutableStateOf(LockerCode())
    var loaded by mutableStateOf(false)
}

@Composable
fun LockerCodeEditorPage(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val store = remember(context) { LockerCodesStore(context) }
    val editor: LockerCodeEditorState = viewModel(key = "locker-code-$id")
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (!editor.loaded) try {
            editor.code = if (id.isBlank()) LockerCode() else withContext(Dispatchers.IO) {
                store.load().single { it.id == id }
            }
            editor.loaded = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not read this locker code." }
    }
    val code = editor.code
    fun update(value: LockerCode) { editor.code = value }
    MotionScaffold(topBar = { SettingsTopBar(if (id.isBlank()) "Add locker code" else "Edit locker code", onBack) }) {
        Item {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(code.name, { update(code.copy(name = it)) }, label = { Text("Locker name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(code.location, { update(code.copy(location = it)) }, label = { Text("Location (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                ChipPicker("Lock type", listOf("Numeric", "Dial"), if (code.dial) "Dial" else "Numeric",
                    onSelectedChange = { update(code.copy(dial = it == "Dial", steps = code.steps.ifEmpty { LockerCode().steps })) })
                if (!code.dial) {
                    OutlinedTextField(code.pin, { value -> if (value.all { it in '0'..'9' }) update(code.copy(pin = value)) },
                        label = { Text("Code") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation())
                    TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide code" else "Show code") }
                } else {
                    code.steps.forEachIndexed { index, step ->
                        fun change(value: DialStep) = update(code.copy(steps = code.steps.mapIndexed { i, old -> if (i == index) value else old }))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Step ${index + 1}")
                                TextButton(enabled = !busy, onClick = { update(code.copy(steps = code.steps.filterIndexed { i, _ -> i != index })) }) { Text("Remove") }
                            }
                            ChipPicker("Direction", listOf("Right ↻", "Left ↺"), if (step.clockwise) "Right ↻" else "Left ↺",
                                onSelectedChange = { change(step.copy(clockwise = it.startsWith("Right"))) })
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(step.number, { value -> if (value.all { it in '0'..'9' }) change(step.copy(number = value)) },
                                    label = { Text("Stop at") }, modifier = Modifier.weight(1f), singleLine = true, enabled = !busy,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                OutlinedTextField(if (step.turns == 0) "" else step.turns.toString(), { value ->
                                    if (value.isEmpty() || value.toIntOrNull() != null) change(step.copy(turns = value.toIntOrNull() ?: 0))
                                }, label = { Text("Turns") }, modifier = Modifier.weight(1f), singleLine = true, enabled = !busy,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            }
                        }
                    }
                    MotionButton(text = "Add step", enabled = !busy, onClick = { update(code.copy(steps = code.steps + DialStep())) })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                MotionButton(text = if (busy) "Saving…" else "Save", enabled = editor.loaded && code.valid() && !busy,
                    onClick = { scope.launch {
                        busy = true; error = null
                        try { withContext(Dispatchers.IO) { store.save(code) }; keyboard?.hide(); onBack() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "Could not save this locker code." }
                        finally { busy = false }
                    } })
            }
        }
    }
}
