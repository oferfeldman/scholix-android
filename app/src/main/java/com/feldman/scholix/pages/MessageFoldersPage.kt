package com.feldman.scholix.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.feldman.motion.*
import com.feldman.scholix.AppDest
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.api.platforms.*
import com.feldman.scholix.ui.components.SettingsTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun MessageFoldersPage(destination: AppDest.MessageFolders, onBack: () -> Unit) {
    val vm = requireNotNull(LocalAppState.current.messagesViewModel)
    val folder = MailboxFolder.valueOf(destination.folder)
    var folders by remember(destination) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var name by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(0) }
    var deletion by remember { mutableStateOf<JSONObject?>(null) }
    fun reload() = vm.action("folders") { folders = withContext(Dispatchers.IO) { vm.mailbox(destination.providerId).folders(folder) } }
    LaunchedEffect(destination, vm.providerId) { if (vm.providerId.isNotEmpty()) reload() }
    fun choose(id: Int) {
        if (id == 0) return
        val current = folders.firstOrNull { it.optInt("id", -1) == id }
        editing = id
        name = current?.text("name").orEmpty()
    }
    MotionScaffold(
        topBar = { SettingsTopBar("${folder.name.lowercase().replaceFirstChar { it.uppercase() }} folders", onBack) }
    ) {
        if (vm.error != null || vm.notice != null) Item { MailFeedback(vm) }
        Item(modifier = Modifier.padding(vertical = 12.dp)) {
            MotionDropdown(
                options = listOf(0) + folders.map { it.optInt("id", -1) }.filter { it > 0 },
                selected = editing,
                onSelected = ::choose,
                label = "Folder",
                items = MotionDropdownDefaults.items(label = { id -> if (id == 0) "New folder" else folders.firstOrNull { it.optInt("id", -1) == id }?.text("name") ?: id.toString() })
            )
        }
        Item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val current = name.trim()
                    if (current.isEmpty()) return@Button
                    vm.action("folder-save") {
                        val mailbox = vm.mailbox(destination.providerId)
                        withContext(Dispatchers.IO) { if (editing == 0) mailbox.createFolder(folder, current) else mailbox.renameFolder(folder, editing, current) }
                        name = ""
                        editing = 0
                        reload()
                    }
                }) { Text(if (editing == 0) "Create" else "Save") }
                if (editing != 0) {
                    OutlinedButton(onClick = { choose(0) }) { Text("Cancel") }
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = { deletion = folders.firstOrNull { it.optInt("id", -1) == editing } }
                    ) { Text("Delete") }
                }
            }
        }
        if (folders.isNotEmpty()) {
            Section {
                folders.forEach { f ->
                    val id = f.optInt("id", -1)
                    PageItem(
                        key = id,
                        title = f.text("name"),
                        description = "${f.optInt("messagesCount", 0)} messages",
                        onClick = { choose(id) }
                    )
                }
            }
        }
        Item { Spacer(Modifier.height(BottomBarSpacing())) }
    }
    deletion?.let { target ->
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text("Delete folder?") },
            text = { Text("Delete \"${target.text("name")}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.optInt("id", -1)
                    deletion = null
                    vm.action("folder-delete") {
                        withContext(Dispatchers.IO) { vm.mailbox(destination.providerId).deleteFolder(folder, id) }
                        if (editing == id) { editing = 0; name = "" }
                        reload()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationsPage(providerId: String, onNavigate: MotionNavigator, onBack: () -> Unit) {
    val vm = requireNotNull(LocalAppState.current.messagesViewModel)
    var children by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var child by rememberSaveable { mutableStateOf("") }
    var notifications by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var unread by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun refresh() = vm.action("notifications") {
        val mailbox = vm.mailbox(providerId)
        val loadedChildren = withContext(Dispatchers.IO) { mailbox.children() }
        children = loadedChildren
        val effectiveChild = child.ifEmpty { loadedChildren.firstOrNull()?.text("id").orEmpty() }
        child = effectiveChild
        notifications = withContext(Dispatchers.IO) { mailbox.notifications(effectiveChild) }
    }
    LaunchedEffect(providerId, child, vm.providerId) { if (vm.providerId.isNotEmpty()) refresh() }
    MotionScaffold(
        topBar = { SettingsTopBar("Notifications", onBack) }
    ) {
        if (vm.error != null || vm.notice != null) Item { MailFeedback(vm) }
        Item(modifier = Modifier.padding(vertical = 12.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MailIconAction(MotionSymbols.ic_refresh, "Refresh") { refresh() }
            MailIconAction(if (selecting) MotionSymbols.ic_close else MotionSymbols.ic_checklist, if (selecting) "Done selecting" else "Select") { selecting = !selecting; selected = emptyList() }
        } }
        if (children.isNotEmpty()) Item {
            MotionDropdown(
                options = children,
                selected = children.firstOrNull { it.text("id") == child } ?: children.first(),
                onSelected = { child = it.text("id") },
                label = "Student",
                items = MotionDropdownDefaults.items(label = { it.text("name") }, key = { it.text("id") })
            )
        }
        Section { SwitchItem(title = "Unread only", checked = unread, onCheckedChange = { unread = it }) }
        if (selected.isNotEmpty()) Item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MailIconAction(MotionSymbols.ic_mark_email_read, "Mark read") { vm.action("notifications-read") { withContext(Dispatchers.IO) { vm.mailbox(providerId).markNotifications(selected, true) }; selected = emptyList(); refresh() } }
                MailIconAction(MotionSymbols.ic_mark_email_unread, "Mark unread") { vm.action("notifications-unread") { withContext(Dispatchers.IO) { vm.mailbox(providerId).markNotifications(selected, false) }; selected = emptyList(); refresh() } }
                MailIconAction(MotionSymbols.ic_delete, "Delete") { confirmDelete = true }
            }
        }
        val visible = notifications.filter { !unread || it.isNull("read_date") }
        if (visible.isEmpty() && "notifications" !in vm.busy) Item { Text("No notifications in this view.") }
        if ("notifications" in vm.busy) Item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        Section { visible.forEach { notification ->
            val id = notification.text("itemId")
            val title = org.jsoup.Jsoup.parse(notification.text("message")).text()
            val isRtl = isRtlText(title)
            CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                if (selecting) SwitchItem(key = id, title = title, description = mailDate(notification.text("date")), checked = id in selected,
                    onCheckedChange = { checked -> selected = if (checked) selected + id else selected - id })
                else PageItem(key = id, title = title, description = "${notification.text("moduleName")} · ${mailDate(notification.text("date"))}", onClick = {
                    vm.action("notification:$id") {
                        withContext(Dispatchers.IO) { vm.mailbox(providerId).markNotifications(listOf(id), true) }
                        refresh()
                        val route = notification.text("moduleNavigation")
                        val messageId = route.toUri().getQueryParameter("msgId")
                        when {
                            route.contains("Messages", ignoreCase = true) && !messageId.isNullOrEmpty() -> onNavigate(AppDest.MessageDetail(providerId, messageId))
                            route.contains("Messages", ignoreCase = true) -> onNavigate(AppDest.Messages)
                            route.contains("schedule", ignoreCase = true) -> onNavigate(AppDest.Schedule)
                            route.contains("grade", ignoreCase = true) -> onNavigate(AppDest.Grades)
                            route.contains("discipline", ignoreCase = true) -> onNavigate(AppDest.Attendance)
                            else -> vm.notice = title
                        }
                    }
                })
            }
        } }
        Item { Spacer(Modifier.height(BottomBarSpacing())) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete notifications?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.action("notifications-delete") { withContext(Dispatchers.IO) { vm.mailbox(providerId).deleteNotifications(selected) }; selected = emptyList(); refresh() } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
