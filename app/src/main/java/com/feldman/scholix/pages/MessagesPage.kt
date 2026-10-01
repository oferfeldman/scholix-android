package com.feldman.scholix.pages

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.webkit.WebView
import android.text.BidiFormatter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.feldman.motion.*
import com.feldman.scholix.AppDest
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.TopBarSpacing
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.R
import com.feldman.scholix.api.platforms.*
import com.feldman.scholix.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun MailAction(text: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    MotionButton(
        text = text,
        contentPadding = PaddingValues(horizontal = 14.dp),
        enabled = enabled,
        onClick = onClick,
        sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp),
        states = MotionButtonDefaults.states(default = if (primary) null else MotionButtonState(backgroundColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer))
    )
}

@Composable
internal fun MailIconAction(icon: String, label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    MotionButton(
        icon = icon,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
        sizes = MotionButtonDefaults.sizes(width = 48.dp, height = 48.dp, iconSize = 24.dp),
        states = MotionButtonDefaults.states(default = if (primary) null else MotionButtonState(backgroundColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer))
    )
}

@Composable
internal fun MailFeedback(vm: MessagesViewModel) {
    val text = vm.error ?: vm.notice ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = if (vm.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        MailAction("Dismiss") { vm.error = null; vm.notice = null }
    }
}

internal fun recipientName(person: JSONObject) = listOf(person.text("firstName"), person.text("lastName")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { person.text("title") }
internal fun recipientKey(person: JSONObject) = "${person.text("mailType").ifEmpty { person.text("type") }}:${person.text("itemId") }"
private fun messageSender(message: JSONObject, folder: MailboxFolder): String = if (folder == MailboxFolder.SENT) {
    message.text("recipientsList").ifBlank { "${message.text("firstRecipientFirstName")} ${message.text("firstRecipientLastName")}".trim() }
} else "${message.text("student_F_name")} ${message.text("student_L_name")}".trim()
internal fun mailDate(value: String) = value.replace('T', ' ').take(16)


@Composable
private fun MessageCard(
    sender: String,
    title: String,
    date: String,
    unread: Boolean,
    hasAttachments: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = if (selecting) { { onSelectedChange(!selected) } } else onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChange,
                modifier = Modifier.padding(end = 12.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (unread) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (sender.isNotBlank()) {
                    Text(
                        text = sender.trim().take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = MotionFonts.feldman(weight = 600),
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (unread) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        painter = rememberSymbolPainter(
                            if (unread) MotionSymbols.ic_mark_email_unread else MotionSymbols.ic_mark_email_read
                        ),
                        contentDescription = null,
                        tint = if (unread) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sender.ifBlank { title },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = MotionFonts.feldman(weight = if (unread) 600 else 500),
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (date.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (sender.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal
                    ),
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (hasAttachments || unread) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (unread) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    if (hasAttachments) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                painter = rememberSymbolPainter(MotionSymbols.ic_attach_file),
                                contentDescription = "Attachment",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Attachment",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessagesScreen(onNavigate: MotionNavigator, onBack: () -> Unit) {
    val state = LocalAppState.current
    val vm = requireNotNull(state.messagesViewModel)
    val providers = state.platforms.filterIsInstance<WebtopPlatform>()
    val selecting = vm.selecting
    var providerExpanded by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(vm.providerId, vm.folder, vm.labelId, vm.query, vm.unreadOnly) {
        if (vm.query.isNotEmpty()) delay(350)
        selected = emptyList()
        vm.initialize()
        vm.refresh()
    }
    LaunchedEffect(selecting) { selected = emptyList() }
    MotionScaffold(
        contentWindowInsets = WindowInsets(0)
    ) {
        Item { Spacer(Modifier.height(TopBarSpacing())) }
        if (providers.isEmpty()) {
            Section {
                PageItem(title = "Connect Webtop", description = "Add a Webtop account to read and send messages.", icon = painterResource(R.drawable.ic_webtop), onClick = { onNavigate(AppDest.Platforms) })
            }
        } else {
            if (providers.size > 1) Item(modifier = Modifier.padding(bottom = 8.dp)) {
                ProviderPickerBar(providers = providers, selectedIndex = providers.indexOfFirst { it.id == vm.providerId }.coerceAtLeast(0),
                    onSelected = { vm.selectProvider(providers[it].id) }, expanded = providerExpanded, onExpandedChange = { providerExpanded = it })
            }
            Item(modifier = Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ChipPicker(label = "Mailbox", options = MailboxFolder.entries.map { it.title }, selected = vm.folder.title,
                            onSelectedChange = { title -> vm.selectFolder(MailboxFolder.entries.first { it.title == title }) })
                    }
                    MailIconAction(MotionSymbols.ic_edit, "New message", vm.permissions[vm.providerId]?.optBoolean("isAllowedToWriteMessages") == true, primary = true) {
                        onNavigate(AppDest.ComposeMessage(vm.providerId, vm.newDraft(vm.providerId)))
                    }
                    MailIconAction(MotionSymbols.ic_search, "Search messages") { searchVisible = !searchVisible; if (!searchVisible) vm.query = "" }
                    MailIconAction(MotionSymbols.ic_more_vert, "Mailbox options") { onNavigate(AppDest.MessageTools(vm.providerId)) }
                }
            }
            if (searchVisible || vm.query.isNotBlank()) Item(modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
                    label = { Text("Search messages") }, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
            }
            if (vm.unreadOnly || vm.labelId != 0) Item {
                Text(listOfNotNull(if (vm.unreadOnly) "Unread only" else null, vm.folders.firstOrNull { it.optInt("id") == vm.labelId }?.text("title")).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (vm.error != null || vm.notice != null) Item { MailFeedback(vm) }
            if (vm.folder == MailboxFolder.DRAFTS) {
                val local = vm.localDrafts(vm.providerId)
                if (local.isNotEmpty()) {
                    Title("On this device")
                    Section { local.forEach { (key, draft) ->
                        val rawSubject = draft.subject.ifBlank { "Untitled draft" }
                        val isRtl = isRtlText(rawSubject)
                        val bidi = BidiFormatter.getInstance()
                        val subject = bidi.unicodeWrap(rawSubject)
                        Item(key = key) {
                            CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                MotionPageSettingsItem(
                                    title = subject,
                                    description = "Saved automatically on this device",
                                    icon = rememberSymbolPainter(MotionSymbols.ic_draft),
                                    onClick = { onNavigate(AppDest.ComposeMessage(vm.providerId, key)) }
                                )
                            }
                        }
                    } }
                    Title("On Webtop")
                }
            }
            if (vm.messages.isNotEmpty()) {
                if (selecting) Item(modifier = Modifier.padding(vertical = 8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MailIconAction(MotionSymbols.ic_close, "Done selecting") { vm.selecting = false; selected = emptyList() }
                        MailIconAction(MotionSymbols.ic_select_all, "Select all") { selected = vm.messages.map { it.text("messageId") } }
                        MailIconAction(MotionSymbols.ic_delete, "Delete selected messages", selected.isNotEmpty()) { confirmDelete = true }
                        if (vm.folder == MailboxFolder.INBOX) {
                            listOf(true, false).forEach { read -> MailIconAction(if (read) MotionSymbols.ic_mark_email_read else MotionSymbols.ic_mark_email_unread, if (read) "Mark read" else "Mark unread", selected.isNotEmpty()) {
                                vm.action("mark") { withContext(Dispatchers.IO) { vm.mailbox().markRead(selected, read) }; selected = emptyList(); vm.refresh() }
                            } }
                        }
                        if (vm.folder in listOf(MailboxFolder.INBOX, MailboxFolder.SENT)) MailIconAction(MotionSymbols.ic_folder, "Move selected messages", selected.isNotEmpty()) {
                            onNavigate(AppDest.MessageFolders(vm.providerId, vm.folder.name, selected))
                        }
                    }
                }
                Section {
                    vm.messages.forEach { message ->
                        val id = message.text("messageId")
                        val unread = vm.folder == MailboxFolder.INBOX && message.optInt("hasRead") == 0
                        val destination = AppDest.MessageDetail(vm.providerId, id, vm.folder.name, message.optInt("fromInbox", 1))
                        val rawTitle = message.text("subject").ifBlank { "No subject" }
                        val rawSender = messageSender(message, vm.folder)
                        val isRtl = isRtlText(if (rawSender.isNotBlank()) rawSender else rawTitle)
                        val bidi = BidiFormatter.getInstance()
                        val title = bidi.unicodeWrap(rawTitle)
                        val sender = if (rawSender.isNotBlank()) bidi.unicodeWrap(rawSender) else ""
                        val date = mailDate(message.text("sendingDate").ifBlank { message.text("createdOn") })
                        val hasAttachments = message.optInt("filesWereAttached") > 0
                        val isSelected = id in selected
                        val containerColor = when {
                            selecting && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            unread -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
                            else -> null
                        }

                        Item(key = id, containerColor = containerColor) {
                            CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                MessageCard(
                                    sender = sender,
                                    title = title,
                                    date = date,
                                    unread = unread,
                                    hasAttachments = hasAttachments,
                                    selecting = selecting,
                                    selected = isSelected,
                                    onSelectedChange = { checked ->
                                        selected = if (checked) selected + id else selected - id
                                    },
                                    onClick = {
                                        if (vm.folder == MailboxFolder.DRAFTS) {
                                            vm.openServerDraft(vm.providerId, id) { key ->
                                                onNavigate(AppDest.ComposeMessage(vm.providerId, key))
                                            }
                                        } else {
                                            onNavigate(destination)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            } else if (!vm.loading && vm.error == null) Item { Text("No messages in this view.") }
            if (vm.loading) Item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (vm.hasMore && !vm.loading) Item { MailAction("Load more") { vm.refresh(more = true) } }
        }
        Item { Spacer(Modifier.height(BottomBarSpacing())) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete messages?") },
        text = { Text(if (vm.folder == MailboxFolder.TRASH) "These messages will be permanently deleted." else "Move the selected messages to Trash?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.action("delete") { withContext(Dispatchers.IO) { vm.mailbox().delete(selected, vm.folder) }; selected = emptyList(); vm.refresh() } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
fun MessageToolsPage(providerId: String, onNavigate: MotionNavigator, onBack: () -> Unit) {
    val vm = requireNotNull(LocalAppState.current.messagesViewModel)
    MotionScaffold(
        fitContentHeight = true,
        contentWindowInsets = WindowInsets(0)
    ) {
        Title("Mailbox options")
        Section {
            PageItem(title = "Refresh", icon = rememberSymbolPainter(MotionSymbols.ic_refresh), onClick = { vm.refresh(); vm.initialize(); onBack() })
            if (vm.messages.isNotEmpty()) PageItem(title = "Select messages", icon = rememberSymbolPainter(MotionSymbols.ic_checklist), onClick = { vm.selecting = true; onBack() })
            if (vm.folder == MailboxFolder.INBOX) SwitchItem(title = "Unread only", checked = vm.unreadOnly, onCheckedChange = { vm.unreadOnly = it })
            if (vm.folder in listOf(MailboxFolder.INBOX, MailboxFolder.SENT)) PageItem(title = "Folders", icon = rememberSymbolPainter(MotionSymbols.ic_folder),
                onClick = { onBack(); onNavigate(AppDest.MessageFolders(providerId, vm.folder.name)) })
            PageItem(title = "Notifications", icon = rememberSymbolPainter(MotionSymbols.ic_notifications), onClick = { onBack(); onNavigate(AppDest.Notifications(providerId)) })
            PageItem(title = "Signature", icon = rememberSymbolPainter(MotionSymbols.ic_edit), onClick = { onBack(); onNavigate(AppDest.MessageSignature(providerId)) })
        }
        Item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageDetailPage(destination: AppDest.MessageDetail, onNavigate: MotionNavigator, onBack: () -> Unit) {
    val vm = requireNotNull(LocalAppState.current.messagesViewModel)
    val context = LocalContext.current
    val folder = MailboxFolder.valueOf(destination.folder)
    val key = "${destination.providerId}:${destination.messageId}"
    val envelope = vm.details[key]
    val message = envelope?.optJSONObject("messageData")
    var reader by remember { mutableStateOf<WebView?>(null) }
    var recipients by rememberSaveable(destination) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(destination, vm.providerId) {
        if (vm.providerId.isNotEmpty()) {
            vm.initialize(destination.providerId)
            vm.loadMessage(destination.providerId, destination.messageId, folder, destination.fromInbox)
        }
    }
    MotionScaffold(
        topBar = { SettingsTopBar("Message", onBack) }
    ) {
        if (vm.error != null || vm.notice != null) Item { MailFeedback(vm) }
        if (message == null) Item {
            if (key in vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else MailAction("Retry") { vm.loadMessage(destination.providerId, destination.messageId, folder, destination.fromInbox, true) }
        } else {
            Section { Item {
                val rawSubject = message.text("subject")
                val rawSender = "${message.text("privateName")} ${message.text("lastName")}".trim()
                val isRtl = isRtlText("$rawSender $rawSubject")
                val bidi = BidiFormatter.getInstance()
                val subject = bidi.unicodeWrap(rawSubject)
                val sender = bidi.unicodeWrap(rawSender)
                CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(subject, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(sender, style = MaterialTheme.typography.titleMedium)
                        Text(mailDate(message.text("sendingDate")), style = MaterialTheme.typography.bodySmall)
                        Text(recipients ?: message.text("recipientsName"), style = MaterialTheme.typography.bodyMedium)
                        if (message.optInt("totalRecipientsCount") > 3) MailAction("All recipients") {
                            vm.action("recipients:$key") { recipients = withContext(Dispatchers.IO) { vm.mailbox(destination.providerId).recipients(destination.messageId, vm.labelId).toString() } }
                        }
                    }
                }
            } }
            Item(modifier = Modifier.padding(vertical = 12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val canWrite = vm.permissions[destination.providerId]?.optBoolean("isAllowedToWriteMessages") == true
                    val canReply = canWrite && !message.optBoolean("replyDisabled") && !message.optBoolean("isSystemMessage")
                    listOf(1 to "Reply", 2 to "Reply all", 0 to "Forward").forEach { (type, title) ->
                        MailIconAction(when (type) { 1 -> MotionSymbols.ic_reply; 2 -> MotionSymbols.ic_reply_all; else -> MotionSymbols.ic_forward }, title,
                            (if (type == 0) canWrite else canReply) && "reply:${destination.messageId}" !in vm.busy) {
                            vm.reply(destination.providerId, destination.messageId, folder, destination.fromInbox, type) {
                                onNavigate(AppDest.ComposeMessage(destination.providerId, it, destination))
                            }
                        }
                    }
                    MailIconAction(MotionSymbols.ic_print, "Print", reader != null) { reader?.let { printMessage(context, it) } }
                    if (folder == MailboxFolder.TRASH) MailIconAction(MotionSymbols.ic_restore, "Restore") {
                        vm.action("restore:$key") { withContext(Dispatchers.IO) { vm.mailbox(destination.providerId).recover(destination.messageId, destination.fromInbox) }; vm.refresh(); onBack() }
                    } else MailIconAction(MotionSymbols.ic_folder, "Move") { onNavigate(AppDest.MessageFolders(destination.providerId, destination.folder, listOf(destination.messageId))) }
                    MailIconAction(MotionSymbols.ic_delete, "Delete") { confirmDelete = true }
                }
            }
            Item { RichMessageBody(message.text("messageContent"), Modifier.fillMaxWidth().height(460.dp), onView = { reader = it }) }
            val attachments = message.optJSONArray("filesList")?.objects().orEmpty()
            if (attachments.isNotEmpty()) {
                Title("Attachments")
                Section { attachments.forEach { file ->
                    PageItem(title = file.text("fileName"), icon = painterResource(R.drawable.ic_docs), onClick = {
                        vm.action("download:${file.text("fileName")}") {
                            val url = file.text("fileUrl").toUri()
                            require(url.scheme == "https") { "Webtop did not provide a download link." }
                            val request = DownloadManager.Request(url).setTitle(file.text("fileName"))
                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.text("fileName").substringAfterLast('/').substringAfterLast('\\'))
                            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                            vm.notice = "Download started"
                        }
                    })
                } }
            }
            if (message.optBoolean("replyDisabled")) Item { Text("The sender disabled replies to this message.") }
        }
        Item { Spacer(Modifier.height(BottomBarSpacing())) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete message?") },
        text = { Text(if (folder == MailboxFolder.TRASH) "This will permanently delete the message." else "The message will move to Trash.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.action("delete:$key") {
            withContext(Dispatchers.IO) { vm.mailbox(destination.providerId).delete(listOf(destination.messageId), folder) }; vm.details.remove(key); vm.refresh(); onBack()
        } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
