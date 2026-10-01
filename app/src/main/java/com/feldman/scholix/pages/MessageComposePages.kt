package com.feldman.scholix.pages

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feldman.motion.*
import com.feldman.scholix.AppDest
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.R
import com.feldman.scholix.api.platforms.*
import com.feldman.scholix.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageComposePage(destination: AppDest.ComposeMessage, onNavigate: MotionNavigator, onBack: () -> Unit) {
    val vm = requireNotNull(LocalAppState.current.messagesViewModel)
    val context = LocalContext.current
    val draft = vm.draft(destination.draftKey)
    val controller = remember(destination.draftKey) { MessageEditorController() }
    var discard by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.uploadImage(destination.providerId, uri) { url ->
            if (controller.view != null) controller.insertImage(url)
            else vm.draft(destination.draftKey)?.let { vm.updateDraft(destination.draftKey, it.copy(html = it.html + "<p><img src=\"${org.jsoup.nodes.Entities.escape(url)}\"></p>")) }
        }
    }
    val attachmentsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.action("attachments:${destination.draftKey}") {
            val current = vm.draft(destination.draftKey) ?: return@action
            require(current.attachments.size + uris.size <= 5) { "Webtop allows up to five attachments per message." }
            val files = withContext(Dispatchers.IO) { uris.map { uri ->
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                vm.attachment(uri)
            } }
            vm.draft(destination.draftKey)?.let { vm.updateDraft(destination.draftKey, it.copy(attachments = it.attachments + files)) }
        }
    }
    LaunchedEffect(destination.providerId, vm.providerId) { if (vm.providerId.isNotEmpty()) vm.initialize(destination.providerId) }
    fun flush(action: () -> Unit) {
        if (controller.view == null) { action(); return }
        controller.flush { html -> vm.draft(destination.draftKey)?.let { vm.updateDraft(destination.draftKey, it.copy(html = html)) }; action() }
    }
    MotionScaffold(
        modifier = Modifier.imePadding(),
        topBar = { SettingsTopBar("Compose message", { flush(onBack) }) }
    ) {
        if (draft == null) Item { Text("This draft was sent or discarded."); MailAction("Back", onClick = onBack) }
        else {
            Item(modifier = Modifier.padding(vertical = 12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val sending = "send:${destination.draftKey}" in vm.busy
                    MailIconAction(MotionSymbols.ic_send, if (sending) "Working…" else if (draft.scheduledAt.isEmpty()) "Send" else "Schedule send",
                        !sending && draft.subject.isNotBlank() && draft.recipients.isNotEmpty() && vm.permissions[destination.providerId]?.optBoolean("isAllowedToWriteMessages") == true, primary = true) {
                        flush { vm.send(destination.providerId, destination.draftKey, onBack) }
                    }
                    MailIconAction(MotionSymbols.ic_save, "Save draft to Webtop", !sending && draft.subject.isNotBlank()) { flush { vm.saveDraft(destination.providerId, destination.draftKey) } }
                    MailIconAction(MotionSymbols.ic_more_vert, "Message options", !sending) { flush { onNavigate(AppDest.MessageOptions(destination)) } }
                }
            }
            if (vm.error != null || vm.notice != null) Item { MailFeedback(vm) }
            Section {
                PageItem(title = "Recipients (${draft.recipients.size})", description = "Add people",
                    icon = painterResource(R.drawable.ic_account), onClick = { flush { onNavigate(AppDest.MessagePeople(destination)) } })
                if (draft.recipients.isNotEmpty()) Item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        draft.recipients.forEach { person ->
                            InputChip(selected = true, onClick = {
                                vm.updateDraft(destination.draftKey, draft.copy(recipients = draft.recipients.filter { recipientKey(it) != recipientKey(person) }))
                            }, label = { Text("${recipientName(person)} ×") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            }
            Item(modifier = Modifier.padding(vertical = 12.dp)) { OutlinedTextField(value = draft.subject, onValueChange = { vm.updateDraft(destination.draftKey, draft.copy(subject = it)) }, label = { Text("Subject") }, singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) }
            Item {
                key(destination.draftKey) {
                    RichMessageEditor(initialHtml = draft.html, controller = controller,
                        onChange = { html -> vm.draft(destination.draftKey)?.let { vm.updateDraft(destination.draftKey, it.copy(html = html)) } },
                        onPickImage = { imagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth().height(380.dp))
                }
            }
            if ("image:${destination.providerId}" in vm.busy) Item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Uploading image…") }
            Title("Attachments (${draft.attachments.size}/5)")
            Section {
                draft.attachments.forEachIndexed { index, file ->
                    PageItem(key = "$index:${file.name}", title = file.name, description = "Tap to remove", icon = painterResource(R.drawable.ic_docs),
                        onClick = { vm.updateDraft(destination.draftKey, draft.copy(attachments = draft.attachments.filterIndexed { i, _ -> i != index })) })
                }
                PageItem(title = "Add attachments", description = "Up to 50 MB per file", icon = painterResource(R.drawable.ic_add),
                    onClick = { if (draft.attachments.size < 5) attachmentsPicker.launch(arrayOf("*/*")) else vm.error = "Remove an attachment before adding another." })
            }
            Item(modifier = Modifier.padding(vertical = 12.dp)) { Text("Draft saved automatically on this device.", style = MaterialTheme.typography.bodySmall) }
            Item(modifier = Modifier.padding(bottom = 12.dp)) { MailAction("Discard draft", "send:${destination.draftKey}" !in vm.busy) { discard = true } }
            if (draft.scheduledAt.isNotEmpty()) Item { Text("Scheduled for ${draft.scheduledAt}") }
        }
        Item { Spacer(Modifier.height(BottomBarSpacing())) }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard this local draft?") },
        text = { Text("A copy already saved to Webtop will remain in Webtop Drafts.") },
        confirmButton = { TextButton(onClick = { discard = false; vm.discardDraft(destination.draftKey); onBack() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep draft") } })
}

@Composable
fun MessagePeoplePage(composer: AppDest.ComposeMessage, onBack: () -> Unit) {
    val vm = requireNotNull(LocalAppState.current.messagesViewModel)
    val draft = vm.draft(composer.draftKey) ?: return
    val directory = vm.directories[composer.providerId]
    val permissions = directory?.optJSONArray("permissionsList")
    val canTeachers = permissions?.toString()?.contains("teachers") == true
    val canAdvanced = permissions?.toString()?.contains("advancedSearch") == true
    var mode by rememberSaveable { mutableStateOf("Search") }
    var query by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf("professionTeachers") }
    var results by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val views = linkedMapOf("professionTeachers" to "My teachers", "homeroomteachers" to "Homeroom teachers", "coordinators" to "Coordinators",
        "proCoordinators" to "Subject coordinators", "studentCounselors" to "Counselors", "studentPrinicipals" to "Principals", "studentSubPrinicipals" to "Deputy principals")
    LaunchedEffect(mode, query, advanced, view) {
        loading = true; error = null
        try {
            if (mode == "Search" && query.length >= 2) delay(300)
            results = withContext(Dispatchers.IO) {
                if (mode == "Teachers") vm.mailbox(composer.providerId).teachers(view)
                else vm.mailbox(composer.providerId).searchPeople(query, advanced)
            }.flatMap { person -> listOf(person) + person.optJSONArray("relatedContactsDto")?.objects().orEmpty() }
                .filter { it.text("itemId").isNotEmpty() }.distinctBy(::recipientKey)
        } catch (cancel: CancellationException) { throw cancel
        } catch (e: Exception) { error = e.message; results = emptyList()
        } finally { loading = false }
    }
    fun select(person: JSONObject, checked: Boolean) {
        val current = vm.draft(composer.draftKey) ?: return
        val people = if (checked) (current.recipients + person).distinctBy(::recipientKey) else current.recipients.filter { recipientKey(it) != recipientKey(person) }
        vm.updateDraft(composer.draftKey, current.copy(recipients = people))
    }
    MotionScaffold(
        topBar = { SettingsTopBar("Choose recipients", onBack) },
        fitContentHeight = true,
        contentWindowInsets = WindowInsets(0)
    ) {
        Item(modifier = Modifier.padding(vertical = 12.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${draft.recipients.size} selected", modifier = Modifier.weight(1f)); MailIconAction(MotionSymbols.ic_check, "Done", onClick = onBack)
        } }
        if (canTeachers) Item { ChipPicker(options = listOf("Search", "Teachers"), selected = mode, onSelectedChange = { mode = it }, label = "Directory") }
        if (mode == "Teachers") Item(modifier = Modifier.padding(top = 8.dp)) { ChipPicker(options = views.values.toList(), selected = views.getValue(view), onSelectedChange = { label -> view = views.entries.first { it.value == label }.key }, label = "Teachers") }
        Item(modifier = Modifier.padding(vertical = 12.dp)) { OutlinedTextField(query, { query = it }, label = { Text(if (mode == "Teachers") "Filter teachers" else "Search people") }, singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) }
        if (canAdvanced && mode == "Search") Section { SwitchItem(title = "Advanced search", checked = advanced, onCheckedChange = { advanced = it }) }
        if (loading) Item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) Item { Text(error!!, color = MaterialTheme.colorScheme.error) }
        val visible = if (mode == "Teachers") results.filter { recipientName(it).contains(query, ignoreCase = true) || it.text("subject").contains(query, ignoreCase = true) } else results
        if (visible.isNotEmpty()) {
            Item { MailAction("Select all results") {
                val current = vm.draft(composer.draftKey) ?: return@MailAction
                vm.updateDraft(composer.draftKey, current.copy(recipients = (current.recipients + visible).distinctBy(::recipientKey)))
            } }
            Section { visible.forEach { person ->
                SwitchItem(key = recipientKey(person), title = recipientName(person), description = person.text("subject").ifBlank { person.text("type") },
                    icon = painterResource(R.drawable.ic_account), checked = draft.recipients.any { recipientKey(it) == recipientKey(person) }, onCheckedChange = { select(person, it) })
            } }
        } else if (!loading && error == null) Item { Text(if (mode == "Search" && query.length < 2) "Enter at least two characters to search." else "No matching people.") }
        Item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun MessageOptionsPage(composer: AppDest.ComposeMessage, onNavigate: MotionNavigator, onBack: () -> Unit) {
    val vm = requireNotNull(LocalAppState.current.messagesViewModel)
    val context = LocalContext.current
    val draft = vm.draft(composer.draftKey) ?: return
    val permissions = vm.permissions[composer.providerId]
    MotionScaffold(
        topBar = { SettingsTopBar("Message options", onBack) },
        fitContentHeight = true,
        contentWindowInsets = WindowInsets(0)
    ) {
        Section {
            SwitchItem(title = "Hide recipient list", checked = draft.hideRecipients, onCheckedChange = { vm.updateDraft(composer.draftKey, draft.copy(hideRecipients = it)) })
            SwitchItem(title = "Disable replies", checked = draft.blockReplies, onCheckedChange = { vm.updateDraft(composer.draftKey, draft.copy(blockReplies = it)) })
            PageItem(title = "Schedule sending", description = draft.scheduledAt.ifBlank { "Send immediately" }, onClick = {
                val now = LocalDateTime.now()
                DatePickerDialog(context, { _, year, month, day ->
                    TimePickerDialog(context, { _, hour, minute ->
                        val selected = LocalDateTime.of(year, month + 1, day, hour, minute)
                        if (selected.isBefore(LocalDateTime.now())) vm.error = "Choose a future time."
                        else vm.draft(composer.draftKey)?.let { vm.updateDraft(composer.draftKey, it.copy(scheduledAt = selected.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:00")))) }
                    }, now.hour, now.minute, true).show()
                }, now.year, now.monthValue - 1, now.dayOfMonth).show()
            })
            if (draft.scheduledAt.isNotBlank()) PageItem(title = "Clear scheduled time", onClick = { vm.updateDraft(composer.draftKey, draft.copy(scheduledAt = "")) })
            PageItem(title = "Personal signature", onClick = { onNavigate(AppDest.MessageSignature(composer.providerId)) })
        }
        if (permissions?.optBoolean("isAllowedToChangeSender") == true) Item {
            OutlinedTextField(draft.senderName, { vm.updateDraft(composer.draftKey, draft.copy(senderName = it)) }, label = { Text("Sender display name") }, modifier = Modifier.fillMaxWidth())
        }
        if (permissions?.optBoolean("allowSendEmail") == true) {
            Section { SwitchItem(title = "Also send by email", checked = draft.sendToMail, onCheckedChange = { vm.updateDraft(composer.draftKey, draft.copy(sendToMail = it)) }) }
            if (draft.sendToMail) {
                Item { OutlinedTextField(draft.mailFrom, { vm.updateDraft(composer.draftKey, draft.copy(mailFrom = it)) }, label = { Text("Email sender name") }, modifier = Modifier.fillMaxWidth()) }
                Item { OutlinedTextField(draft.mailReplyTo, { vm.updateDraft(composer.draftKey, draft.copy(mailReplyTo = it)) }, label = { Text("Reply-to email") }, modifier = Modifier.fillMaxWidth()) }
            }
        }
        if (vm.error != null) Item { MailFeedback(vm) }
        Item(modifier = Modifier.padding(vertical = 12.dp)) { MailAction("Done", onClick = onBack) }
        Item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun MessageSignaturePage(providerId: String, onBack: () -> Unit) {
    val vm = requireNotNull(LocalAppState.current.messagesViewModel)
    val original = vm.signatures[providerId]
    val controller = remember(providerId) { MessageEditorController() }
    var html by rememberSaveable(original) { mutableStateOf(original?.text("signature").orEmpty()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.uploadImage(providerId, uri) { controller.insertImage(it) }
    }
    LaunchedEffect(providerId, vm.providerId) { if (vm.providerId.isNotEmpty()) vm.initialize(providerId) }
    MotionScaffold(
        topBar = { SettingsTopBar("Personal signature", onBack) },
        modifier = Modifier.imePadding()
    ) {
        if (vm.error != null || vm.notice != null) Item { MailFeedback(vm) }
        if (original == null) Item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else {
            Item { Text("Added to new messages. Existing drafts keep their current content.") }
            Item { RichMessageEditor(html, controller, { html = it }, { picker.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth().height(380.dp)) }
            Item { MailAction("Save signature", "signature:$providerId" !in vm.busy) { controller.flush { content ->
                vm.action("signature:$providerId") {
                    withContext(Dispatchers.IO) { vm.mailbox(providerId).updateSignature(original, content) }
                    vm.signatures[providerId] = JSONObject(original.toString()).put("signature", content)
                    vm.notice = "Signature saved"
                    onBack()
                }
            } } }
        }
        Item { Spacer(Modifier.height(BottomBarSpacing())) }
    }
}
