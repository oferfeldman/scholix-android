"""Scholix provider/account dispatch for Feldman Home's existing OAuth MCP hub."""
import asyncio
import copy
import json
import os
import re
import secrets
import time
from pathlib import Path
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

from jsonschema import Draft202012Validator

from gateway.openu import OpenUService, OPERATIONS as OPEN_U_OPERATIONS


PROVIDERS = {
    "openu": {"title": "Open University", "login": "existing_openu", "grades": True, "schedule": True, "attendance": False, "messages": True},
    "webtop": {"title": "Webtop", "login": "password/moe/browser", "grades": True, "schedule": True, "attendance": True, "messages": True},
    "mashov": {"title": "Mashov", "login": "password/sms", "grades": True, "schedule": True, "attendance": True, "messages": False},
    "bar-ilan": {"title": "Bar-Ilan (Math/Michlol)", "login": "password", "grades": True, "schedule": False, "attendance": False, "messages": False},
    "inbar": {"title": "Inbar (Bar-Ilan)", "login": "sms", "grades": True, "schedule": True, "attendance": False, "messages": False},
    "education": {"title": "Education Portal", "login": "browser", "grades": True, "schedule": False, "attendance": False, "messages": False},
    "shahaf": {"title": "Shahaf", "login": "password/moe/sms/browser", "grades": False, "schedule": True, "attendance": False, "messages": False},
    "classroom": {"title": "Google Classroom", "login": "google_oauth", "grades": True, "schedule": False, "attendance": False, "messages": True},
    "lemida": {"title": "Lemida", "login": "browser", "grades": True, "schedule": False, "attendance": False, "messages": False},
    "drive": {"title": "Google Drive", "login": "google_oauth", "grades": False, "schedule": False, "attendance": False, "messages": False},
    "demo": {"title": "Demo", "login": "demo", "grades": True, "schedule": True, "attendance": True, "messages": True},
}
STR = {"type": "string", "minLength": 1}
INT = {"type": "integer"}
BOOL = {"type": "boolean"}
OBJECT = {"type": "object"}
ACCOUNT = {"connection_id": STR}
FOLDER = {"enum": ["INBOX", "SENT", "DRAFTS", "TRASH"]}
OPERATIONS = {}


def operation(name, description, properties=None, required=(), read=True, account=True):
    props = {**(ACCOUNT if account else {}), **(properties or {})}
    OPERATIONS[name] = {"description": description,
        "inputSchema": {"type": "object", "properties": props, "required": (["connection_id"] if account else []) + list(required), "additionalProperties": False},
        "readOnly": read, "destructive": not read, "verification": "reuses Scholix provider implementation; live availability depends on the connected account"}


operation("providers", "List Scholix providers, their capabilities and provider-specific tool names. OpenU retains all its specialized actions.", account=False)
operation("accounts", "List connected Scholix accounts and IDs. Choose a connection_id explicitly; OpenU's existing account is 'openu'. Never request passwords/tokens in AI chat.", account=False)
operation("courses", "Read the selected provider's courses.")
operation("grades", "Read grades using the app's provider parser. Not supported by Shahaf.", {"course": STR, "year": INT, "semester": STR})
operation("subjects", "Read available subjects.")
SCHEDULE = {"day_index": {"type": "integer", "minimum": 0, "maximum": 6}, "institution_code": INT, "selected_value": STR}
operation("schedule", "Read the current/updated schedule for day_index (0=Sunday). For Shahaf use shahaf_schedule; for OpenU use openu_calendar.", SCHEDULE, ("day_index",))
operation("original_schedule", "Read the regular schedule before substitutions/cancellations, where the provider supports it.", SCHEDULE, ("day_index",))
operation("schedule_indexes", "Read provider schedule selection options.")
operation("calendar", "Read calendar data: Webtop's student weekly schedule and Events & Tests board; OpenU's meetings/exams; school providers' seven-day timetable. date is YYYY-MM-DD.", {"date": STR, "week_index": INT, "year": INT, "view": INT})
operation("attendance", "Read attendance events using the provider's year/period semantics. Unsupported providers return an explicit error.", {"year": INT, "period": {"enum": ["a", "b", "ab"]}})
operation("messages", "Read messages/announcements. For Webtop's full mailbox including sent/drafts/trash/search use webtop_mail_messages.", {"page": INT})
operation("message_details", "Read message details.", {"message_id": STR}, ("message_id",))
operation("account_info", "Read provider profile/capabilities without credentials.")
operation("refresh_session", "Refresh the selected provider session using its saved login. Does not automatically resend SMS or retry account mutations.", read=False)
operation("mashov_schools", "List Mashov school names/codes for connection setup.", account=False)
operation("shahaf_schools", "Read the selected Shahaf account's schools.")
operation("shahaf_select_school", "Select an available Shahaf school, using the object returned by shahaf_schools.", {"school": OBJECT}, ("school",), False)
operation("shahaf_select_class", "Choose the Shahaf class for subsequent published schedule views.", {"class_id": STR}, ("class_id",), False)
operation("shahaf_schedule", "Read Shahaf's full published timetable/appdata. Preserve its published view instead of inventing normalized lessons.", {"class_id": STR, "parameters": {"type": "object", "additionalProperties": {"type": "string"}}})
operation("shahaf_parameters", "Read the published schedule's classes, dates and filter parameters.", {"class_id": STR})
operation("webtop_calendar_schedule", "Read Webtop's student-specific calendar/week timetable (GetPupilScheduale). Includes the actual student groups; week_index selects the displayed week.", {"week_index": INT, "year": INT})
operation("webtop_calendar", "Read Webtop's Events & Tests calendar board for a selected date and view (EventsTestsBoard). Includes filters and calendar/schedule events.", {"date": STR, "view": INT, "year": INT, "load_filters": BOOL})
operation("webtop_pupil_settings", "Read available pupil-card divisions and academic year.", {"year": INT}, ("year",))
for name, description in {
    "permissions": "Read mailbox permissions and available actions.", "directory": "Read recipient-directory data.",
    "signature": "Read the current mail signature/preferences.",
}.items():
    operation("webtop_mail_" + name, description)
operation("webtop_mail_messages", "Read Inbox, Sent, Drafts or Trash with pagination, search, folder label and optional read/unread filtering.", {"folder": FOLDER, "page": INT, "query": {"type": "string"}, "label": INT, "has_read": BOOL})
MESSAGE = {"message_id": STR, "folder": FOLDER, "from_inbox": INT}
operation("webtop_mail_details", "Read a complete message including body and attachment metadata.", MESSAGE, ("message_id",))
operation("webtop_mail_recipients", "Read a message's full recipient list.", {"message_id": STR, "folder_id": INT}, ("message_id",))
operation("webtop_mail_search_people", "Search recipients by name (at least two characters), optionally using advanced search.", {"query": STR, "advanced": BOOL}, ("query",))
operation("webtop_mail_teachers", "List available teachers/recipient contexts.", {"view": STR})
operation("webtop_mail_prepare_reply", "Prepare reply, reply-all or forward data without sending it (reply_type=1 reply, 2 all, 0 forward).", {**MESSAGE, "reply_type": {"enum": [0, 1, 2]}, "label": INT}, ("message_id",))
operation("webtop_mail_load_draft", "Read a saved draft in the app's MailDraft format.", {"message_id": STR}, ("message_id",))
DRAFT = {"type": "object", "description": "Scholix MailDraft: subject, html, recipients (directory objects), attachments ({name,uri,key,mimeType}), draftId, replyType, senderId, hideRecipients, blockReplies, scheduledAt (dd/MM/yyyy HH:mm:ss), senderName, sendToMail, mailFrom, mailReplyTo, typeId. For new files use uri='mcp:<id>' and supply matching files map entries as base64."}
for name, description in {"send": "Send a REAL message/reply/forward, optionally scheduled and with attachments. Requires an explicit user request to send. Never retry an ambiguous result automatically.", "save_draft": "Save/update a draft on Webtop without sending it."}.items():
    operation("webtop_mail_" + name, description, {"draft": DRAFT, "files": {"type": "object", "additionalProperties": STR}}, ("draft",), False)
operation("webtop_mail_folders", "List custom folders/labels for a mailbox view.", {"folder": FOLDER})
IDS = {"type": "array", "items": STR, "minItems": 1}
operation("webtop_mail_mark_read", "Mark messages read or unread.", {"message_ids": IDS, "read": BOOL}, ("message_ids", "read"), False)
operation("webtop_mail_delete", "Delete selected messages from the specified mailbox view. Can be destructive, especially in Trash.", {"message_ids": IDS, "folder": FOLDER}, ("message_ids",), False)
operation("webtop_mail_recover", "Recover a deleted message.", {"message_id": STR, "from_inbox": INT}, ("message_id",), False)
operation("webtop_mail_move", "Move messages into a folder/label.", {"message_ids": IDS, "folder": FOLDER, "label": INT}, ("message_ids", "label"), False)
operation("webtop_mail_create_folder", "Create a mailbox folder.", {"folder": FOLDER, "title": STR}, ("title",), False)
operation("webtop_mail_rename_folder", "Rename a mailbox folder.", {"folder": FOLDER, "folder_id": INT, "title": STR}, ("folder_id", "title"), False)
operation("webtop_mail_delete_folder", "Delete a mailbox folder.", {"folder": FOLDER, "folder_id": INT}, ("folder_id",), False)
operation("webtop_mail_upload_image", "Upload an inline message image, maximum 10 MiB base64.", {"filename": STR, "mime_type": STR, "base64": STR}, ("filename", "mime_type", "base64"), False)
operation("webtop_mail_update_signature", "Update the signature while preserving the existing notification/preferences fields.", {"html": {"type": "string"}}, ("html",), False)
operation("webtop_download_attachment", "Download a message attachment using the app's streamed account-specific downloader. Returns filename, MIME and base64, maximum 10 MiB.", {"url": STR, "filename": STR}, ("url", "filename"))
operation("webtop_notifications_settings", "Read notification settings.")
operation("webtop_children", "Read the account's available children.")
operation("webtop_notifications", "Read notifications for an available child.", {"child_id": STR}, ("child_id",))
operation("webtop_mark_notifications", "Mark notifications read/unread.", {"notification_ids": IDS, "read": BOOL}, ("notification_ids", "read"), False)
operation("webtop_delete_notifications", "Delete selected notifications.", {"notification_ids": IDS}, ("notification_ids",), False)
for name, path in {"courses": "courses", "coursework": "courses/{course_id}/courseWork", "materials": "courses/{course_id}/courseWorkMaterials", "announcements": "courses/{course_id}/announcements", "submissions": "courses/{course_id}/courseWork/{work_id}/studentSubmissions", "teachers": "courses/{course_id}/teachers", "students": "courses/{course_id}/students"}.items():
    properties = {"page_token": STR, "page_size": {"type": "integer", "minimum": 1, "maximum": 100}, "course_id": STR}
    if name == "submissions": properties["work_id"] = STR
    operation("classroom_" + name, "Read Google Classroom " + name + ". Uses the app's ClassroomApi; follows Google's account/project permissions. Returns nextPageToken when paginated.", properties, (() if name == "courses" else ("course_id",)), True)
    OPERATIONS["classroom_" + name]["path"] = path
operation("drive_files", "List/search Google Drive files visible to the connected account. Returns nextPageToken; permissions/scopes apply.", {"query": STR, "page_token": STR, "page_size": {"type": "integer", "minimum": 1, "maximum": 100}})
operation("drive_file", "Read Google Drive file metadata.", {"file_id": STR}, ("file_id",))
operation("drive_download", "Download an accessible binary Drive file or export a Google document (supply export_mime), maximum 10 MiB.", {"file_id": STR, "export_mime": STR}, ("file_id",))
operation("drive_create", "Create a Drive folder or text/JSON file using the app's create-file workflow (including synced StarNote edit JSON). Requires drive.file consent. Does not change a phone's unsynced local drafts.", {"metadata": {"type": "object", "properties": {"name": STR, "mimeType": STR, "parents": {"type": "array", "items": STR}, "description": {"type": "string"}, "appProperties": {"type": "object", "additionalProperties": {"type": "string"}}}, "required": ["name"], "additionalProperties": False}, "content": {"type": "string"}}, ("metadata",), False)
operation("classroom_homework", "Read coursework with the app's due-date and submission-state mapping; course_id optionally limits it to one class.", {"course_id": STR})
operation("lemida_course", "Read a Lemida course's activities, homework, dates and files using the app's Python exporter parser.", {"course_id": {"type": "integer", "minimum": 1}}, ("course_id",))
operation("lemida_grades", "Read Lemida course grade-report tables.", {"course_id": {"type": "integer", "minimum": 1}}, ("course_id",))
operation("lemida_activity", "Read a discovered Lemida assignment/quiz/resource detail, status tables and file links.", {"url": STR}, ("url",))

from gateway.scholix_browser import add_operations
add_operations(OPERATIONS)

# New provider actions live alongside this registry. Existing OpenU tool names remain aliases in the hub.
for name, info in OPEN_U_OPERATIONS.items():
    OPERATIONS["openu_" + name] = copy.deepcopy(info)


def redact(value):
    if isinstance(value, dict):
        return {key: redact(item) for key, item in value.items() if not re.search(r"password|cookie|csrf|token|secret|devicepass|authorization|encryptedSession", key, re.I)}
    if isinstance(value, list):
        return [redact(item) for item in value]
    return value


class NativeWorker:
    def __init__(self):
        self.lock = asyncio.Lock()
        self.process = None

    async def call(self, request):
        async with self.lock:
            if self.process is None or self.process.returncode is not None:
                root = Path(os.environ.get("SCHOLIX_WORKER_DIR", Path(__file__).parent / "scholix-client"))
                self.process = await asyncio.create_subprocess_exec(os.environ.get("SCHOLIX_JAVA", "java"), "-Xmx256m", "-XX:ActiveProcessorCount=2", "-cp", str(root / "lib" / "*"), "com.feldman.scholix.mcp.WorkerKt",
                    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL, limit=20 * 1024 * 1024)
            try:
                self.process.stdin.write((json.dumps(request) + "\n").encode())
                await self.process.stdin.drain()
                result = await asyncio.wait_for(self.process.stdout.readline(), timeout=180)
                if not result: raise ValueError("Scholix provider worker stopped")
                return json.loads(result)
            except BaseException:
                # A timed-out mutation is never replayed in a fresh worker.
                await self.close()
                raise

    async def close(self):
        if self.process and self.process.returncode is None:
            self.process.kill()
            await self.process.wait()
        self.process = None


class ScholixService:
    slug = "scholix"
    title = "Scholix"
    operations = OPERATIONS

    def __init__(self, store, openu):
        self.store, self.openu = store, openu
        self.worker = NativeWorker()
        self.lock = asyncio.Lock()
        self.connections = store.get("scholix", "accounts") or {}
        self.browsers = {}

    def persist(self):
        self.store.put("scholix", "accounts", self.connections)

    def status(self):
        accounts = [{"connection_id": "openu", "provider": "openu", **self.openu.status()}]
        accounts += [{"connection_id": key, "provider": value["provider"], "title": value["title"], "connected": value.get("verified", False), "pending_sms": value.get("pending_sms", False)} for key, value in self.connections.items()]
        return {"accounts": accounts}

    async def native(self, key, operation, arguments, account=None):
        account = account or self.connections[key]
        response = await self.worker.call({"provider": account["provider"], "connection_id": key, "operation": operation, "arguments": arguments, "state": account.get("state", {})})
        if "state" in response:
            account["state"] = response["state"]
            if key in self.connections: self.persist()
        if "error" in response: raise ValueError(response["error"])
        return redact(response.get("result"))

    async def connect(self, provider, title, fields):
        if provider not in PROVIDERS or provider in {"openu", "classroom", "drive", "education", "lemida"}:
            raise ValueError("Use this provider's browser/OAuth connection option")
        async with self.lock:
            key = secrets.token_urlsafe(12)
            account = {"provider": provider, "title": title or PROVIDERS[provider]["title"]}
            result = await self.native(key, "login", fields, account)
            account.update(verified=result.get("logged_in", False), pending_sms=result.get("sms_required", False))
            self.connections[key] = account
            self.persist()
            return key

    async def verify_sms(self, key, code):
        async with self.lock:
            account = self.connections[key]
            await self.native(key, "verify_sms", {"code": code})
            account.update(verified=True, pending_sms=False)
            self.persist()

    async def import_account(self, provider, title, state):
        if provider not in PROVIDERS or provider in {"openu", "classroom", "drive", "lemida"}:
            raise ValueError("This provider requires its connection form")
        async with self.lock:
            key = secrets.token_urlsafe(12)
            account = {"provider": provider, "title": title or PROVIDERS[provider]["title"], "state": state}
            result = await self.native(key, "account_info", {}, account)
            if not result.get("logged_in"): raise ValueError("Imported provider state is not signed in")
            # Validate against the upstream rather than trusting an imported loggedIn flag.
            result = await self.native(key, "refresh_session", {}, account)
            if not result.get("logged_in"): raise ValueError("Imported provider session was not accepted")
            account["verified"] = True
            self.connections[key] = account
            self.persist()
            return key

    async def disconnect(self, key):
        if key == "openu":
            await self.openu.disconnect()
            return
        async with self.lock:
            if key not in self.connections: raise ValueError("Unknown Scholix account")
            if key in self.browsers: await self.browsers.pop(key).close()
            self.connections.pop(key)
            self.persist()

    async def close(self):
        for browser in self.browsers.values(): await browser.close()
        await self.worker.close()

    async def invoke(self, operation, arguments):
        Draft202012Validator(self.operations[operation]["inputSchema"]).validate(arguments)
        if operation.startswith("openu_"):
            return await self.openu.invoke(operation.removeprefix("openu_"), arguments)
        if operation == "providers":
            return {name: {**info, "specialized_tools": ["scholix__" + key for key in OPERATIONS if key.startswith(name.replace("-", "_") + "_")]} for name, info in PROVIDERS.items()}
        if operation == "accounts": return self.status()
        if operation == "mashov_schools":
            response = await self.worker.call({"provider": "mashov", "operation": operation})
            if "error" in response: raise ValueError(response["error"])
            return response["result"]
        args = dict(arguments)
        key = args.pop("connection_id")
        if key == "openu":
            names = {"courses": "courses", "grades": "grades", "account_info": "account_info", "calendar": "calendar"}
            if operation not in names: raise ValueError("Use the specialized scholix__openu_* tools for this OpenU workflow")
            return await self.openu.invoke(names[operation], {})
        if key not in self.connections: raise ValueError("Unknown account; call scholix__accounts first")
        account = self.connections[key]
        if operation.startswith("browser_"):
            from gateway.scholix_browser import browser_for
            if not account.get("browser"): raise ValueError("Connect this account's browser in the owner console first")
            browser = await browser_for(self, key)
            return await browser.invoke(operation.removeprefix("browser_"), args)
        if not account.get("verified"): raise ValueError("Complete the provider sign-in/SMS in the owner console")
        if operation.startswith("webtop_") and account["provider"] != "webtop": raise ValueError("Select a Webtop connection_id")
        if operation.startswith("shahaf_") and account["provider"] != "shahaf": raise ValueError("Select a Shahaf connection_id")
        if account["provider"] in {"classroom", "drive"}: return await self.google(account, operation, args)
        if account["provider"] == "lemida":
            from gateway.scholix_browser import lemida
            if operation == "grades":
                if not args.get("course", "").isdigit(): raise ValueError("Use lemida_grades with a course_id")
                return await lemida(self, key, "lemida_grades", {"course_id": int(args["course"])})
            if operation == "account_info": return {"provider": "lemida", "connected": True}
            if operation not in {"courses", "lemida_course", "lemida_grades", "lemida_activity"}: raise ValueError("Use Lemida's specialized tools or its browser actions")
            return await lemida(self, key, operation, args)
        async with self.lock:
            if operation == "calendar":
                if account["provider"] == "webtop":
                    return {"schedule": await self.native(key, "webtop_calendar_schedule", {k: v for k, v in args.items() if k in {"week_index", "year"}}),
                            "events_tests": await self.native(key, "webtop_calendar", {k: v for k, v in args.items() if k in {"date", "year", "view"}})}
                if account["provider"] == "shahaf": return await self.native(key, "shahaf_schedule", {})
                if not PROVIDERS[account["provider"]]["schedule"]: raise ValueError("This provider does not provide a timetable")
                return {"days": [await self.native(key, "schedule", {"day_index": day}) for day in range(7)]}
            return await self.native(key, operation, args)

    async def google(self, account, operation, args):
        # Filled by the OAuth adapter below; Google tokens never enter the MCP schema.
        from gateway.scholix_google import google_request, ensure_token
        if account["provider"] == "classroom" and operation in {"grades", "messages", "classroom_homework"}:
            await asyncio.to_thread(ensure_token, self, account)
            response = await self.worker.call({"provider": "classroom", "operation": "classroom_snapshot", "arguments": {**args, "kind": "homework" if operation == "classroom_homework" else operation}, "state": account["state"]})
            if "error" in response: raise ValueError(response["error"])
            return response["result"]
        return await asyncio.to_thread(google_request, self, account, operation, args)
