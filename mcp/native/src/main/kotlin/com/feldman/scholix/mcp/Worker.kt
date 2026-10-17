package com.feldman.scholix.mcp

import com.feldman.scholix.api.*
import com.feldman.scholix.api.platforms.*
import com.feldman.app.api.BarIlanPlatform
import com.feldman.scholix.classroom.ClassroomApi
import com.feldman.scholix.classroom.ClassroomMapping
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Base64
import java.io.File

private val accounts = mutableMapOf<String, Platform>()
private val shahafChallenges = mutableMapOf<String, Pair<ShahafMoeLogin, ShahafMoeLogin.SmsChallenge>>()

private fun restore(provider: String, state: JSONObject): Platform = when (provider) {
    "webtop" -> WebtopPlatform.fromJson(state)
    "mashov" -> MashovPlatform.fromJson(state)
    "bar-ilan" -> BarIlanPlatform.fromJson(state)
    "inbar" -> InbarPlatform.fromJson(state)
    "education" -> StudentsPortalPlatform.fromJson(state)
    "shahaf" -> ShahafPlatform.fromJson(state)
    "demo" -> DemoPlatform.fromJson(state) ?: DemoPlatform()
    else -> error("Unsupported native provider")
}

private fun fields(args: JSONObject): LoginFields = LoginFields().apply {
    addField("username", Type.Username, args.optString("username"))
    addField("Username", Type.Username, args.optString("username"))
    addField("id", Type.Id, args.optString("username"))
    addField("password", Type.Password, args.optString("password"))
    addField("Password", Type.Password, args.optString("password"))
    addField("School code", Type.Custom("schoolCode"), args.optString("school_code"))
    addField("School year", Type.Custom("schoolYear"), args.optString("school_year"))
    addField("mobile", Type.Custom("mobile"), args.optString("mobile"))
    addField("Mobile", Type.Custom("mobile"), args.optString("mobile"))
}

private fun login(provider: String, a: JSONObject): Platform = when (provider) {
    "webtop" -> if (a.optString("method") == "moe") WebtopPlatform().apply {
        setUsername(a.getString("username")); setPassword(a.getString("password")); loginMethod = "moe"; refreshCookies()
    } else WebtopPlatform(fields(a))
    "mashov" -> if (a.optString("method") == "sms") MashovPlatform().apply {
        applySmsLoginFields(fields(a)); requestSms(a.getString("mobile"))
    } else MashovPlatform(fields(a))
    "bar-ilan" -> BarIlanPlatform(fields(a))
    "inbar" -> InbarPlatform(fields(a)).apply { requestSms(a.getString("username"), a.getString("mobile")) }
    "shahaf" -> if (a.optString("method") == "moe") ShahafPlatform().apply {
        setUsername(a.getString("username")); setPassword(a.getString("password"))
        adoptAuthResponse(ShahafMoeLogin().loginWithPassword(ShahafApi.SIGN_IN_PAGE, getUsername(), getPassword()))
    } else ShahafPlatform.login(a.getString("username"), a.getString("password"))
    "demo" -> DemoPlatform().apply { loggedIn = true }
    else -> error("This provider requires its interactive browser/OAuth sign-in or a saved-session import")
}

private fun optionalInt(a: JSONObject, key: String): Int? = if (a.has(key) && !a.isNull(key)) a.getInt(key) else null
private fun strings(a: JSONObject, key: String): List<String> = a.getJSONArray(key).let { xs -> (0 until xs.length()).map { xs.getString(it) } }
private fun folder(a: JSONObject) = MailboxFolder.valueOf(a.optString("folder", "INBOX"))
private fun map(a: JSONObject): Map<String, String> = a.keys().asSequence().associateWith { a.getString(it) }

private fun webtop(p: WebtopPlatform, operation: String, a: JSONObject): Any? {
    val mail = p.mailbox
    fun body(key: String, mime: String) = Base64.getDecoder().decode(key).also { require(it.size <= 10 * 1024 * 1024) }.toRequestBody(mime.toMediaType())
    fun attachment(file: MailAttachment) = body(a.getJSONObject("files").getString(file.uri), file.mimeType)
    return when (operation) {
        "webtop_calendar_schedule" -> p.getPupilScheduleWeek(a.optInt("week_index"), optionalInt(a, "year"))
        "webtop_calendar" -> p.getEventsTestsBoard(LocalDate.parse(a.optString("date", LocalDate.now().toString())), a.optInt("view", 3), a.optBoolean("load_filters", true), optionalInt(a, "year"))
        "webtop_pupil_settings" -> p.fetchPupilCardSettings(a.getInt("year"))?.let { JSONObject().put("divisions", JSONObject(it.divisions)).put("currentStudyYear", it.currentStudyYear) }
        "webtop_mail_permissions" -> mail.permissions()
        "webtop_mail_directory" -> mail.directory()
        "webtop_mail_signature" -> mail.signature()
        "webtop_mail_messages" -> mail.messages(folder(a), a.optInt("page", 1), a.optString("query"), a.optInt("label"), if (a.has("has_read")) a.getBoolean("has_read") else null)
        "webtop_mail_details" -> mail.details(a.getString("message_id"), folder(a), a.optInt("from_inbox", 1))
        "webtop_mail_recipients" -> mail.recipients(a.getString("message_id"), a.optInt("folder_id"))
        "webtop_mail_search_people" -> mail.searchPeople(a.getString("query"), a.optBoolean("advanced"))
        "webtop_mail_teachers" -> mail.teachers(a.optString("view", "professionTeachers"))
        "webtop_mail_prepare_reply" -> mail.prepareReply(a.getString("message_id"), folder(a), a.optInt("reply_type", 1), a.optInt("label"), a.optInt("from_inbox", 1))
        "webtop_mail_load_draft" -> mail.loadDraft(a.getString("message_id")).toJson()
        "webtop_mail_send" -> mail.send(MailDraft.fromJson(a.getJSONObject("draft")), ::attachment)
        "webtop_mail_save_draft" -> mail.saveDraft(MailDraft.fromJson(a.getJSONObject("draft")), ::attachment)
        "webtop_mail_folders" -> mail.folders(folder(a))
        "webtop_mail_mark_read" -> mail.markRead(strings(a, "message_ids"), a.getBoolean("read"))
        "webtop_mail_delete" -> mail.delete(strings(a, "message_ids"), folder(a))
        "webtop_mail_recover" -> mail.recover(a.getString("message_id"), a.optInt("from_inbox", 1))
        "webtop_mail_move" -> mail.move(strings(a, "message_ids"), folder(a), a.getInt("label"))
        "webtop_mail_create_folder" -> mail.createFolder(folder(a), a.getString("title"))
        "webtop_mail_rename_folder" -> mail.renameFolder(folder(a), a.getInt("folder_id"), a.getString("title"))
        "webtop_mail_delete_folder" -> mail.deleteFolder(folder(a), a.getInt("folder_id"))
        "webtop_mail_upload_image" -> mail.uploadImage(a.getString("filename"), body(a.getString("base64"), a.getString("mime_type")))
        "webtop_mail_update_signature" -> mail.updateSignature(mail.signature(), a.getString("html"))
        "webtop_notifications_settings" -> mail.notifications()
        "webtop_children" -> mail.children()
        "webtop_notifications" -> mail.notifications(a.getString("child_id"))
        "webtop_mark_notifications" -> mail.markNotifications(strings(a, "notification_ids"), a.getBoolean("read"))
        "webtop_delete_notifications" -> mail.deleteNotifications(strings(a, "notification_ids"))
        "webtop_download_attachment" -> runBlocking {
            val directory = kotlin.io.path.createTempDirectory("scholix-attachment-").toFile()
            try {
                val item = p.messageAttachments.download(directory, p.id, a.getString("url"), a.getString("filename"))
                require(item.file.length() <= 10 * 1024 * 1024) { "File exceeds 10 MiB" }
                JSONObject().put("filename", item.file.name).put("mime_type", item.mimeType).put("base64", Base64.getEncoder().encodeToString(item.file.readBytes()))
            } finally { directory.deleteRecursively() }
        }
        else -> error("Unknown Webtop operation")
    }
}

private fun invoke(p: Platform, operation: String, a: JSONObject): Any? {
    if (operation.startsWith("webtop_")) return webtop(p as? WebtopPlatform ?: error("Select a Webtop account"), operation, a)
    return when (operation) {
        "courses" -> p.getCourses()
        "grades" -> { require(p.suportsGrades) { "This provider does not support grades" }; p.getGrades(a.optString("course", "all"), optionalInt(a, "year"), a.optString("semester").ifBlank { null }) }
        "subjects" -> p.getSubjectList()
        "schedule" -> { require(p.supportsSchedule); p.getSchedule(a.getInt("day_index"), optionalInt(a, "institution_code"), a.optString("selected_value").ifBlank { null }) }
        "original_schedule" -> { require(p.supportsSchedule); p.getOriginalSchedule(a.getInt("day_index"), optionalInt(a, "institution_code"), a.optString("selected_value").ifBlank { null }) }
        "schedule_indexes" -> p.getScheduleIndexes()
        "attendance" -> { require(p.supportsAttendance); optionalInt(a, "year")?.let { p.getAttendanceEvents(it, a.optString("period", "b")) } ?: p.getAttendanceEvents(a.optString("period", "b")) }
        "messages" -> { require(p is WebtopPlatform || p is DemoPlatform) { "This provider has no message API" }; p.getMessages(a.optInt("page", 1)) }
        "message_details" -> { require(p is WebtopPlatform || p is DemoPlatform); p.getMessageDetails(a.getString("message_id")) }
        "account_info" -> p.getInfo().put("name", p.getName()).put("logged_in", p.isLoggedIn())
        "refresh_session" -> JSONObject().put("logged_in", p.refreshCookies())
        "verify_sms" -> when (p) { is InbarPlatform -> p.verifySms(a.getString("code")); is MashovPlatform -> p.verifySms(a.getString("code")); else -> error("No pending SMS login") }
        "resend_sms" -> when (p) { is InbarPlatform -> p.resendSms(); is MashovPlatform -> p.requestSms(); else -> error("SMS is not supported for this account") }
        "shahaf_schools" -> (p as ShahafPlatform).getSchools()
        "shahaf_select_school" -> (p as ShahafPlatform).selectSchool(a.getJSONObject("school"))
        "shahaf_select_class" -> (p as ShahafPlatform).selectClass(a.getString("class_id"))
        "shahaf_schedule" -> (p as ShahafPlatform).getAppDataForClassId(a.optString("class_id", p.selectedClassId), map(a.optJSONObject("parameters") ?: JSONObject()))
        "shahaf_parameters" -> (p as ShahafPlatform).getAppParametersForClassId(a.optString("class_id", p.selectedClassId))
        else -> error("Unknown Scholix action")
    }
}

private fun json(value: Any?): Any = when (value) {
    null, Unit -> JSONObject().put("completed", true)
    is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
    is Collection<*> -> JSONArray(value.map(::json))
    else -> value.toString()
}

fun main() {
    System.setProperty("user.timezone", "Asia/Jerusalem")
    generateSequence(::readLine).forEach { line ->
        var platform: Platform? = null
        val output = JSONObject()
        try {
            val request = JSONObject(line)
            val provider = request.getString("provider")
            val key = request.optString("connection_id")
            val operation = request.getString("operation")
            val args = request.optJSONObject("arguments") ?: JSONObject()
            if (operation == "mashov_schools") {
                output.put("result", JSONArray(MashovPlatform.getSchools().map { JSONObject().put("code", it.semel).put("name", it.name) }))
            } else if (operation == "classroom_request") {
                val state = request.getJSONObject("state")
                val api = ClassroomApi({ state.getString("access_token") })
                output.put("result", api.get(args.getString("path"), map(args.optJSONObject("query") ?: JSONObject())))
            } else if (operation == "classroom_snapshot") {
                val api = ClassroomApi({ request.getJSONObject("state").getString("access_token") })
                val courses = api.list("courses", "courses", mapOf("studentId" to "me", "courseStates" to "ACTIVE,ARCHIVED"))
                    .filter { !args.has("course_id") || it.getString("id") == args.getString("course_id") }
                val items = mutableListOf<JSONObject>()
                for (course in courses) {
                    val id = course.getString("id")
                    when (args.getString("kind")) {
                        "grades", "homework" -> {
                            val work = api.list("courses/$id/courseWork", "courseWork")
                            val submissions = api.list("courses/$id/courseWork/-/studentSubmissions", "studentSubmissions", mapOf("userId" to "me")).associateBy { it.optString("courseWorkId") }
                            for (item in work) {
                                val submission = submissions[item.getString("id")]
                                if (args.getString("kind") == "grades") {
                                    submission?.let { ClassroomMapping.grade(item, it, course.optString("name")) }?.let(items::add)
                                } else items += JSONObject(item.toString()).put("course_name", course.optString("name")).put("due", ClassroomMapping.due(item)).put("status", ClassroomMapping.status(submission))
                            }
                        }
                        "messages" -> items += api.list("courses/$id/announcements", "announcements").map { JSONObject(it.toString()).put("course_name", course.optString("name")).put("id", "$id:${it.getString("id")}") }
                    }
                }
                output.put("result", JSONArray(items))
            } else if (operation == "adopt_browser" && provider == "webtop") {
                val data = args.getJSONObject("login_data")
                val state = JSONObject().put("id", key).put("loggedIn", true).put("cookies", args.getString("cookies"))
                    .put("name", data.optString("firstName") + " " + data.optString("lastName"))
                    .put("studentId", data.getString("userId")).put("classCode", "${data.optString("classCode")}|${data.opt("classNumber")}")
                    .put("institution", data.optString("institutionCode")).put("userStudentId", data.optString("studentId"))
                    .put("userType", data.opt("userType")).put("schoolName", data.optString("institutionName"))
                    .put("courses", JSONArray().put(JSONObject().put("name", "Webtop").put("courseKey", "Webtop").put("index", 0)))
                platform = WebtopPlatform.fromJson(state)
                accounts[key] = platform
                output.put("result", JSONObject().put("logged_in", true))
            } else if (operation == "adopt_browser" && provider == "education") {
                platform = StudentsPortalPlatform.loginWithCookies(args.getString("cookies"), args.optString("csrt").ifBlank { null })
                require(platform.isLoggedIn()) { "Browser sign-in has not finished" }
                accounts[key] = platform
                output.put("result", JSONObject().put("logged_in", true))
            } else if (operation == "adopt_browser" && provider == "shahaf") {
                platform = ShahafPlatform().apply { adoptAuthResponse(args.getJSONObject("auth_response")) }
                accounts[key] = platform
                output.put("result", JSONObject().put("logged_in", true))
            } else if (operation == "login" && provider == "shahaf" && args.optString("method") == "sms") {
                val auth = ShahafMoeLogin()
                shahafChallenges[key] = auth to auth.requestSmsCode(ShahafApi.SIGN_IN_PAGE, args.getString("username"), args.getString("mobile"))
                output.put("result", JSONObject().put("sms_required", true))
                output.put("state", JSONObject().put("id", key))
            } else if (operation == "verify_sms" && provider == "shahaf") {
                val (auth, challenge) = shahafChallenges[key] ?: error("Restart the SMS sign-in after a server restart")
                platform = ShahafPlatform().apply { adoptAuthResponse(auth.submitSmsCode(challenge, args.getString("code"))) }
                accounts[key] = platform
                shahafChallenges.remove(key)
                output.put("result", JSONObject().put("logged_in", true))
            } else {
                platform = if (operation == "login") login(provider, args)
                    else accounts[key] ?: restore(provider, request.getJSONObject("state"))
                accounts[key] = platform
                val smsPending = provider == "inbar" || (provider == "mashov" && args.optString("method") == "sms")
                val result = if (operation == "login") JSONObject().put("logged_in", platform.isLoggedIn()).put("sms_required", smsPending)
                    else invoke(platform, operation, args)
                if (operation == "login" && !smsPending) require(platform.isLoggedIn()) { "Provider did not accept the login" }
                output.put("result", json(result))
            }
        } catch (error: Exception) {
            // Keep stack traces, cookies, login payloads and arbitrary upstream responses out of tool output.
            output.put("error", "Provider operation failed (${error.javaClass.simpleName}). Check the account/session and supported operation before retrying.")
        }
        platform?.let { output.put("state", it.toJson()) }
        println(output)
        System.out.flush()
    }
}
