package com.feldman.scholix.api.platforms

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun jsonObject(vararg values: Pair<String, Any?>) = JSONObject().apply {
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}

enum class MailboxFolder(val title: String, val apiName: String) {
    INBOX("Inbox", "Inbox"), SENT("Sent", "Outbox"), DRAFTS("Drafts", "Drafts"), TRASH("Trash", "Deleted")
}

data class MailAttachment(
    val name: String,
    val uri: String = "",
    val key: String = "",
    val url: String = "",
    val mimeType: String = "application/octet-stream",
    val size: Long = 0
) {
    fun toJson() = jsonObject("name" to name, "uri" to uri, "key" to key, "url" to url, "mimeType" to mimeType, "size" to size)
    companion object {
        fun fromJson(it: JSONObject) = MailAttachment(it.text("name"), it.text("uri"), it.text("key"), it.text("url"), it.text("mimeType"), it.optLong("size"))
        fun fromServer(it: JSONObject) = MailAttachment(
            name = it.text("fileName"), key = it.text("fileKey"), url = it.text("fileUrl")
        )
    }
}

data class MailDraft(
    val subject: String = "",
    val html: String = "",
    val recipients: List<JSONObject> = emptyList(),
    val attachments: List<MailAttachment> = emptyList(),
    val draftId: String = "",
    val replyType: Int = -1,
    val senderId: String = "",
    val hideRecipients: Boolean = false,
    val blockReplies: Boolean = false,
    val scheduledAt: String = "",
    val senderName: String = "",
    val sendToMail: Boolean = false,
    val mailFrom: String = "",
    val mailReplyTo: String = "",
    val typeId: Int = 1
) {
    fun toJson() = jsonObject(
        "subject" to subject, "html" to html, "recipients" to JSONArray(recipients),
        "attachments" to JSONArray(attachments.map { it.toJson() }), "draftId" to draftId,
        "replyType" to replyType, "senderId" to senderId, "hideRecipients" to hideRecipients,
        "blockReplies" to blockReplies, "scheduledAt" to scheduledAt, "senderName" to senderName,
        "sendToMail" to sendToMail, "mailFrom" to mailFrom, "mailReplyTo" to mailReplyTo, "typeId" to typeId
    )
    companion object {
        fun fromJson(it: JSONObject) = MailDraft(
            subject = it.text("subject"), html = it.text("html"),
            recipients = it.optJSONArray("recipients")?.objects().orEmpty(),
            attachments = it.optJSONArray("attachments")?.objects().orEmpty().map(MailAttachment::fromJson),
            draftId = it.text("draftId"), replyType = it.optInt("replyType", -1), senderId = it.text("senderId"),
            hideRecipients = it.optBoolean("hideRecipients"), blockReplies = it.optBoolean("blockReplies"),
            scheduledAt = it.text("scheduledAt"), senderName = it.text("senderName"), sendToMail = it.optBoolean("sendToMail"),
            mailFrom = it.text("mailFrom"), mailReplyTo = it.text("mailReplyTo"), typeId = it.optInt("typeId", 1)
        )
    }
}

/** Webtop's authenticated mailbox protocol. The editor never receives session cookies. */
class WebtopMailbox(
    private val cookie: () -> String,
    private val refreshSession: () -> Boolean,
    client: OkHttpClient = OkHttpClient()
) {
    private val client = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun request(endpoint: String, body: RequestBody) = Request.Builder()
        .url("$API$endpoint")
        .header("Cookie", cookie())
        .header("language", "he")
        .header("rememberMe", "0")
        .post(body).build()

    private fun execute(endpoint: String, body: RequestBody, readOnly: Boolean, retry: Boolean = true): String {
        client.newCall(request(endpoint, body)).execute().use { response ->
            if (response.code == 401 && readOnly && retry && refreshSession()) {
                return execute(endpoint, body, readOnly = true, retry = false)
            }
            if (response.code == 401) throw IOException("Your Webtop session expired. Reconnect the provider in Settings.")
            if (!response.isSuccessful) throw IOException("Webtop returned HTTP ${response.code}. Please try again.")
            return response.body.string()
        }
    }

    private fun data(response: String): Any {
        val json = try { JSONObject(response) } catch (_: Exception) {
            throw IOException("Webtop returned an unexpected response. Reconnect the provider and try again.")
        }
        if (!json.optBoolean("status")) throw IOException("Webtop could not complete this action. Check your account permissions.")
        return json.opt("data") ?: JSONObject.NULL
    }

    internal fun post(endpoint: String, payload: JSONObject = JSONObject(), readOnly: Boolean = true): Any =
        data(execute(endpoint, payload.toString().toRequestBody(JSON), readOnly))

    fun permissions(): JSONObject = post("messageBox/initMessagesData") as? JSONObject ?: JSONObject()
    fun directory(): JSONObject = (post("messageBox/InitData") as? JSONObject)?.optJSONObject("recipientsListData") ?: JSONObject()
    fun signature(): JSONObject = post("messageBox/GetSignature") as? JSONObject ?: JSONObject()

    fun messages(folder: MailboxFolder, page: Int, query: String = "", label: Int = 0, hasRead: Boolean? = null): List<JSONObject> {
        val endpoint = when (folder) {
            MailboxFolder.INBOX -> "GetMessagesInbox"
            MailboxFolder.SENT -> "GetMessagesOutbox"
            MailboxFolder.DRAFTS -> "GetMessagesDraft"
            MailboxFolder.TRASH -> "GetMessagesDeleted"
        }
        return (post("messageBox/$endpoint", jsonObject("PageId" to page, "LabelId" to label, "HasRead" to hasRead, "SearchQuery" to query)) as? JSONArray)?.objects().orEmpty()
    }

    fun details(id: String, folder: MailboxFolder, fromInbox: Int = 1): JSONObject {
        val inbox = folder != MailboxFolder.SENT && !(folder == MailboxFolder.TRASH && fromInbox == 0)
        return post("messageBox/GetMessages${if (inbox) "Inbox" else "Outbox"}Data", jsonObject(
            "MessageId" to id, "FilterId" to 0, "IsInbox" to (folder != MailboxFolder.SENT), "hasRead" to null
        )) as? JSONObject ?: throw IOException("This message is no longer available.")
    }

    fun recipients(id: String, folderId: Int): Any = post("messageBox/GetAllRecipients", jsonObject("MessageId" to id, "FolderId" to folderId))

    fun searchPeople(query: String, advanced: Boolean): List<JSONObject> {
        if (query.trim().length < 2) return emptyList()
        val today = LocalDate.now()
        val payload = jsonObject("SearchQuery" to query.trim(), "IsManual" to advanced)
        if (advanced) payload.put("StudyYear", today.year + if (today.monthValue >= 9) 1 else 0)
        return (post("messageBox/RecipientsAutoComplete", payload) as? JSONArray)?.objects().orEmpty()
    }

    fun teachers(view: String = "professionTeachers"): List<JSONObject> =
        (post("messageBox/changeTeachersView", jsonObject("TeacherView" to view, "ClassCode" to 0, "ClassNum" to 0, "studentId" to "0")) as? JSONArray)?.objects().orEmpty().map {
            jsonObject("mailType" to "Teacher", "type" to "Teacher", "itemId" to it.text("itemId"),
                "firstName" to it.text("private_name"), "lastName" to it.text("family_name"),
                "userImageToken" to it.text("userImageToken"), "contextOrigin" to if (view == "professionTeachers") "" else view,
                "subject" to it.text("subject"))
        }

    fun prepareReply(id: String, folder: MailboxFolder, type: Int, label: Int, fromInbox: Int): JSONObject =
        post("messageBox/ReplyAndForwardMessage", jsonObject("MessageId" to id, "MessageType" to folder.apiName,
            "IsReplay" to (type > 0), "ToAll" to (type == 2), "FolderId" to label, "fromInbox" to if (folder == MailboxFolder.TRASH) fromInbox else null)) as? JSONObject
            ?: throw IOException("Webtop could not prepare this reply.")

    fun loadDraft(id: String): MailDraft {
        val d = post("messageBox/LoadDraftData", jsonObject("Id" to id)) as? JSONObject ?: throw IOException("Draft not found.")
        return MailDraft(
            subject = d.text("subject"), html = d.text("messageContent"), draftId = d.text("msgId").ifEmpty { id },
            hideRecipients = d.optInt("hiddenRecipients") == 1, blockReplies = d.optBoolean("replyDisabled"),
            recipients = d.optJSONArray("recipientsList")?.objects().orEmpty().map { r ->
                jsonObject("mailType" to r.text("userType"), "type" to r.text("userType"), "itemId" to r.text("recipientId"),
                    "firstName" to r.text("firstName"), "lastName" to r.text("lastName"), "title" to r.text("title"), "contextOrigin" to r.text("contextOrigin"))
            },
            attachments = d.optJSONArray("filesList")?.objects().orEmpty().map(MailAttachment::fromServer)
        )
    }

    fun send(draft: MailDraft, attachmentBody: (MailAttachment) -> RequestBody) {
        require(draft.subject.isNotBlank()) { "Enter a subject." }
        require(draft.recipients.isNotEmpty()) { "Choose at least one recipient." }
        if (draft.scheduledAt.isNotBlank()) require(LocalDateTime.parse(draft.scheduledAt, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")).isAfter(LocalDateTime.now())) { "Choose a future sending time." }
        // Neither HTTP failures nor ambiguous timeouts are ever automatically retried.
        val result = data(execute("messageBox/SendMessage", composeBody(draft, attachmentBody), readOnly = false))
        if (result == JSONObject.NULL || result == false || result.toString() == "0") throw IOException("Webtop did not accept the message.")
    }

    fun saveDraft(draft: MailDraft, attachmentBody: (MailAttachment) -> RequestBody): String {
        require(draft.subject.isNotBlank()) { "Enter a subject before saving to Webtop." }
        val result = data(execute("messageBox/SaveDraft", composeBody(draft, attachmentBody, savingDraft = true), readOnly = false))
        if (result == JSONObject.NULL || result == false || result.toString() == "0") throw IOException("Webtop did not save the draft.")
        return result.toString()
    }

    fun folders(folder: MailboxFolder): List<JSONObject> = (post("messageBox/GetMessageFolders", jsonObject("Id" to folder.apiName.lowercase())) as? JSONArray)?.objects().orEmpty()
    fun markRead(ids: List<String>, read: Boolean) = change("MarkMessagesAsRead", jsonObject("ChangeType" to if (read) "read" else "unread", "MessageList" to JSONArray(ids)))
    fun delete(ids: List<String>, folder: MailboxFolder) = change("DeleteMessages", jsonObject("ChangeType" to folder.apiName.lowercase(), "MessageList" to JSONArray(ids)))
    fun recover(id: String, fromInbox: Int) = change("MessageRecovery", jsonObject("id" to id, "param1" to fromInbox.toString()))
    fun move(ids: List<String>, folder: MailboxFolder, label: Int) = change("MoveToFolder", jsonObject("ChangeType" to folder.apiName, "MessageList" to JSONArray(ids), "folderId" to label))
    fun editFolder(folder: MailboxFolder, id: Int, title: String) = change("InserEditFolder", jsonObject("folderId" to id, "name" to title, "viewBox" to folder.apiName))
    fun deleteFolder(folder: MailboxFolder, id: Int) = change("DeleteFolder", jsonObject("folderId" to id, "viewBox" to folder.apiName))
    fun createFolder(folder: MailboxFolder, title: String) = editFolder(folder, 0, title)
    fun renameFolder(folder: MailboxFolder, id: Int, title: String) = editFolder(folder, id, title)

    private fun change(endpoint: String, payload: JSONObject) {
        val result = post("messageBox/$endpoint", payload, readOnly = false)
        if (result == false || result == JSONObject.NULL || result.toString() == "0") throw IOException("Webtop could not save this change.")
    }

    fun uploadImage(name: String, body: RequestBody): String {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("filetype", "image").addFormDataPart("fileName", name)
            .addFormDataPart("file", name, body).build()
        val url = execute("stream/UploadFile", multipart, readOnly = false).trim().trim('"')
        if (!url.startsWith("https://")) throw IOException("Webtop could not upload this image.")
        return url
    }

    fun notifications(): JSONObject = post("Notification/GetNotificationsSettings", jsonObject("id" to null)) as? JSONObject ?: JSONObject()
    fun children(): List<JSONObject> = (post("Notification/GetChildrenList") as? JSONArray)?.objects()
        ?: (post("Notification/GetNotificationsSettings", jsonObject("id" to null)) as? JSONObject)?.optJSONArray("children")?.objects().orEmpty()
    fun notifications(child: String): List<JSONObject> = (post("Notification/GetNotificationList", jsonObject("id" to child)) as? JSONArray)?.objects().orEmpty()
    fun markNotifications(ids: List<String>, read: Boolean) { post("Notification/UpdateNotificationReadStatus", jsonObject("isRead" to read, "notifiactionsList" to JSONArray(ids)), readOnly = false) }
    fun deleteNotifications(ids: List<String>) { post("Notification/DeleteNotifications", jsonObject("notifiactionsList" to JSONArray(ids)), readOnly = false) }

    fun updateSignature(previous: JSONObject, html: String) {
        post("messageBox/UpdateSignature", jsonObject(
            "Signature" to html, "PupilsCanViewMyReadStatus" to previous.opt("pupilsCanViewMyReadStatus"),
            "ParentsCanViewMyReadStatus" to previous.opt("parentsCanViewMyReadStatus"),
            "PupilsCanSendMessages" to previous.opt("pupilsCanSendMessages"), "ParentsCanSendMessages" to previous.opt("parentsCanSendMessages"),
            "DefaultFont" to previous.opt("defaultFont"), "DefaultFontSize" to previous.opt("defaultFontSize"),
            "DefaultColor" to previous.opt("defaultColor"), "DefaultFontWeight" to previous.opt("defaultFontWeight"),
            "vacationFromDate" to previous.opt("vacationFromDate"), "vacationToDate" to previous.opt("vacationToDate"),
            "vacationReplyContent" to previous.opt("vacationReplyContent"),
            "sendNotifyEmail" to previous.optJSONObject("userEmailData")?.opt("messagesEmailNotification"),
            "email" to previous.optJSONObject("userEmailData")?.opt("email")
        ), readOnly = false)
    }

    companion object {
        const val API = "https://webtopserver.smartschool.co.il/server/api/"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun composeBody(draft: MailDraft, attachmentBody: (MailAttachment) -> RequestBody, savingDraft: Boolean = false): MultipartBody {
            require(draft.attachments.size <= 5) { "Webtop allows up to five attachments." }
            val recipients = draft.recipients.sortedBy { if (it.text("mailType") == "MailingGroup") 0 else 1 }
                .map { JSONObject(it.toString()).apply {
                    if (text("mailType").isEmpty()) put("mailType", text("type"))
                    put("item", JSONObject.NULL)
                } }
            val fields = linkedMapOf(
                "Subject" to draft.subject, "content" to draft.html, "draftMessageId" to draft.draftId.ifEmpty { "undefined" },
                "hiddenRecipients" to if (draft.hideRecipients) "1" else "0", "newSenderName" to draft.senderName,
                "recipients" to Base64.getEncoder().encodeToString(JSONArray(recipients).toString().toByteArray(Charsets.UTF_8)),
                "sceduleDate" to draft.scheduledAt, "typeId" to draft.typeId.toString(),
                "IsReplay" to if (draft.replyType in 1..2) "1" else "0", "IsForward" to if (draft.replyType == 0) "1" else "0",
                "SenderId" to draft.senderId.ifEmpty { "undefined" }, "SendToMail" to if (draft.sendToMail) "1" else "0",
                "SendToMailFrom" to draft.mailFrom, "SendToMailReplay" to draft.mailReplyTo, "SendToMailDelayTime" to "15",
                "blockMessage" to if (draft.blockReplies) "1" else "0", "isPopup" to "0", "signData" to "{}"
            )
            return MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (key, value) -> addFormDataPart(key, value) }
                draft.attachments.forEachIndexed { index, file ->
                    require(file.uri.isNotEmpty() || file.key.isNotEmpty()) { "Reattach ${file.name} before sending; Webtop did not provide its attachment key." }
                    if (file.uri.isNotEmpty()) addFormDataPart("file${index + 1}", file.name, attachmentBody(file))
                    else if (file.key.isNotEmpty()) addFormDataPart("file${index + 1}", if (savingDraft) file.name else file.key)
                }
            }.build()
        }
    }
}
