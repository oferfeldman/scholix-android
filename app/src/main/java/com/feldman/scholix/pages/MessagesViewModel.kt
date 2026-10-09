package com.feldman.scholix.pages

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.platforms.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class MessagesViewModel(application: Application) : AndroidViewModel(application) {
    private var providers = emptyList<Platform>()
    fun classroom(id: String = providerId) = providers.firstOrNull { it.id == id } as? GoogleClassroomPlatform
    private val storage = application.getSharedPreferences("message_drafts", Context.MODE_PRIVATE)
    private var listJob: Job? = null
    var providerId by mutableStateOf(""); private set
    var folder by mutableStateOf(MailboxFolder.INBOX); private set
    var query by mutableStateOf("")
    var unreadOnly by mutableStateOf(false)
    var selecting by mutableStateOf(false)
    var labelId by mutableStateOf(0); private set
    var messages by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var folders by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var hasMore by mutableStateOf(false); private set
    private var page = 1
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    val permissions = mutableStateMapOf<String, JSONObject>()
    val directories = mutableStateMapOf<String, JSONObject>()
    val signatures = mutableStateMapOf<String, JSONObject>()
    val details = mutableStateMapOf<String, JSONObject>()
    val drafts = mutableStateMapOf<String, MailDraft>()
    val busy = mutableStateListOf<String>()

    fun configure(platforms: List<Platform>) {
        providers = platforms.filter { it is WebtopPlatform || it is GoogleClassroomPlatform }
        if (providers.none { it.id == providerId }) {
            providerId = providers.firstOrNull()?.id.orEmpty()
            messages = emptyList()
        }
    }

    fun mailbox(id: String = providerId): WebtopMailbox = (providers.firstOrNull { it.id == id } as? WebtopPlatform)?.mailbox
        ?: throw IOException("Connect a Webtop provider in Settings to use messages.")

    fun selectProvider(id: String) {
        if (id == providerId) return
        providerId = id
        labelId = 0
        folder = MailboxFolder.INBOX
        unreadOnly = false
        selecting = false
        messages = emptyList()
        initialize(id)
    }

    fun initialize(id: String = providerId) {
        if (classroom(id) != null) return
        if (id.isEmpty() || (id in permissions && id in directories && id in signatures) || "init:$id" in busy) return
        action("init:$id") {
            permissions[id] = withContext(Dispatchers.IO) { mailbox(id).permissions() }
            directories[id] = withContext(Dispatchers.IO) { mailbox(id).directory() }
            signatures[id] = withContext(Dispatchers.IO) { mailbox(id).signature() }
        }
    }

    fun selectFolder(value: MailboxFolder, label: Int = 0) {
        folder = value
        labelId = label
    }

    fun refresh(more: Boolean = false) {
        if (providerId.isEmpty() || (more && loading)) return
        listJob?.cancel()
        val id = providerId
        val selectedFolder = folder
        val selectedLabel = labelId
        val search = query
        val unread = unreadOnly
        val next = if (more) page + 1 else 1
        if (!more) messages = emptyList()
        listJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                val classroom = classroom(id)
                val result = withContext(Dispatchers.IO) {
                    if (classroom != null) classroom.getMessages(next).objects().filter {
                        "${it.text("subject")} ${it.text("text")} ${it.text("privateName")}".contains(search, ignoreCase = true)
                    } else mailbox(id).messages(selectedFolder, next, search, selectedLabel, if (unread) false else null)
                }
                messages = (if (more) messages + result else result).distinctBy { it.text("messageId") }
                page = next
                hasMore = classroom == null && result.isNotEmpty()
                if (!more && classroom == null && selectedFolder in listOf(MailboxFolder.INBOX, MailboxFolder.SENT)) {
                    folders = withContext(Dispatchers.IO) { mailbox(id).folders(selectedFolder) }
                } else if (!more) folders = emptyList()
            } catch (cancel: CancellationException) { throw cancel
            } catch (e: Exception) { error = e.message ?: "Could not load messages."
            } finally { loading = false }
        }
    }

    fun loadMessage(id: String, messageId: String, folder: MailboxFolder, fromInbox: Int, force: Boolean = false) {
        val key = "$id:$messageId"
        if (!force && key in details) return
        action(key) {
            details[key] = withContext(Dispatchers.IO) { mailbox(id).details(messageId, folder, fromInbox) }
            if (providerId == id) messages = messages.map {
                if (it.text("messageId") == messageId) JSONObject(it.toString()).put("hasRead", 1) else it
            }
        }
    }

    fun draft(key: String): MailDraft? = drafts[key] ?: storage.getString(key, null)?.let {
        runCatching { MailDraft.fromJson(JSONObject(it)) }.getOrNull()?.also { value -> drafts[key] = value }
    }

    fun newDraft(id: String, value: MailDraft? = null): String {
        val key = "$id:${UUID.randomUUID()}"
        updateDraft(key, value ?: MailDraft(
            html = signatures[id]?.text("signature").orEmpty(),
            hideRecipients = permissions[id]?.optBoolean("hideRecipientsInDefault") == true,
            mailFrom = permissions[id]?.text("userName").orEmpty(), mailReplyTo = permissions[id]?.text("userEmail").orEmpty()
        ))
        return key
    }

    fun updateDraft(key: String, value: MailDraft) {
        drafts[key] = value
        storage.edit().putString(key, value.toJson().toString()).apply()
    }

    fun localDrafts(id: String): List<Pair<String, MailDraft>> {
        // Reading the observable map makes newly created/deleted drafts update the list.
        drafts.size
        return storage.all.keys.filter { it.startsWith("$id:") }.mapNotNull { key -> draft(key)?.let { key to it } }
    }

    fun discardDraft(key: String) {
        drafts.remove(key)
        storage.edit().remove(key).apply()
    }

    fun openServerDraft(id: String, draftId: String, onReady: (String) -> Unit) = action("draft:$draftId") {
        val value = withContext(Dispatchers.IO) { mailbox(id).loadDraft(draftId) }
        onReady(newDraft(id, value))
    }

    fun reply(id: String, messageId: String, folder: MailboxFolder, fromInbox: Int, type: Int, onReady: (String) -> Unit) = action("reply:$messageId") {
        val data = details["$id:$messageId"]?.optJSONObject("messageData") ?: return@action
        if (type > 0 && data.optBoolean("replyDisabled")) throw IOException("The sender disabled replies to this message.")
        val prepared = withContext(Dispatchers.IO) { mailbox(id).prepareReply(messageId, folder, type, labelId, fromInbox) }
        if (type > 0 && prepared.has("isAllowdToReplay") && !prepared.optBoolean("isAllowdToReplay")) throw IOException("Webtop does not allow replies to this message.")
        val original = prepared.optJSONObject("messageData") ?: data
        val escapedSubject = org.jsoup.nodes.TextNode(original.text("subject")).outerHtml()
        val author = org.jsoup.nodes.TextNode("${original.text("privateName")} ${original.text("lastName")}".trim()).outerHtml()
        val quote = "<p><br></p><hr><p><b>$escapedSubject</b><br>$author<br>${original.text("sendingDate")}</p><blockquote>${original.text("messageContent")}</blockquote>"
        onReady(newDraft(id, MailDraft(
            subject = "${if (type == 0) "Fwd" else "Re"}: ${original.text("subject")}",
            html = quote, recipients = if (type == 0) emptyList() else prepared.optJSONArray("recipients")?.objects().orEmpty(),
            attachments = if (type == 0) data.optJSONArray("filesList")?.objects().orEmpty().map(MailAttachment::fromServer) else emptyList(),
            replyType = type, senderId = data.text("senderId")
        )))
    }

    fun send(id: String, key: String, onSent: () -> Unit) = action("send:$key") {
        val value = draft(key) ?: return@action
        if (permissions[id]?.optBoolean("isAllowedToWriteMessages") != true) throw IOException("This account cannot send messages.")
        try {
            withContext(Dispatchers.IO) { mailbox(id).send(value, ::attachmentBody) }
        } catch (e: IOException) {
            throw IOException("${e.message}\nYour draft is kept. Check Sent before trying again; Webtop may have received the message.")
        }
        discardDraft(key)
        notice = "Message sent"
        refresh()
        onSent()
    }

    fun saveDraft(id: String, key: String) = action("send:$key") {
        val value = draft(key) ?: return@action
        val serverId = withContext(Dispatchers.IO) { mailbox(id).saveDraft(value, ::attachmentBody) }
        updateDraft(key, value.copy(draftId = serverId))
        notice = "Draft saved to Webtop"
    }

    fun attachment(uri: Uri): MailAttachment {
        val resolver = getApplication<Application>().contentResolver
        var name = "attachment"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { name = it.getString(0) ?: name; size = it.getLong(1) }
        }
        require(size <= 50L * 1024 * 1024) { "Each attachment must be 50 MB or smaller." }
        return MailAttachment(name = name, uri = uri.toString(), size = size, mimeType = resolver.getType(uri) ?: "application/octet-stream")
    }

    fun attachmentBody(file: MailAttachment): RequestBody = object : RequestBody() {
        override fun contentType() = file.mimeType.toMediaTypeOrNull()
        override fun contentLength() = if (file.size > 0) file.size else -1
        override fun writeTo(sink: BufferedSink) {
            getApplication<Application>().contentResolver.openInputStream(Uri.parse(file.uri))?.use { input ->
                input.source().use { sink.writeAll(it) }
            } ?: throw IOException("Cannot read ${file.name}. Select the attachment again.")
        }
    }

    fun uploadImage(id: String, uri: Uri, onUploaded: (String) -> Unit) = action("image:$id") {
        val url = withContext(Dispatchers.IO) {
            val file = attachment(uri)
            require(file.mimeType.startsWith("image/")) { "Choose an image." }
            mailbox(id).uploadImage(file.name, attachmentBody(file))
        }
        onUploaded(url)
    }

    fun action(key: String, block: suspend () -> Unit) {
        if (key in busy) return
        busy.add(key)
        viewModelScope.launch {
            error = null
            try { block()
            } catch (cancel: CancellationException) { throw cancel
            } catch (e: Exception) { error = e.message ?: "Could not complete this action."
            } finally { busy.remove(key) }
        }
    }
}
