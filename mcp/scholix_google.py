"""Owner-console Google OAuth and account-bound Classroom/Drive requests."""
import base64
import hashlib
import json
import secrets
import time
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

SCOPES = {
    "classroom": ["classroom.courses.readonly", "classroom.coursework.me.readonly", "classroom.courseworkmaterials.readonly", "classroom.announcements.readonly", "classroom.profile.emails", "classroom.rosters.readonly", "userinfo.email"],
    "drive": ["drive.readonly", "drive.file", "userinfo.email"],
}


def configure(service, raw):
    value = json.loads(raw)
    config = value.get("web", value)
    if not config.get("client_id") or not config.get("client_secret"):
        raise ValueError("Use a Google Web application OAuth client JSON")
    service.store.put("scholix", "google_oauth", {"client_id": config["client_id"], "client_secret": config["client_secret"]})


def start(service, provider, public_url, owner_session):
    config = service.store.get("scholix", "google_oauth")
    if not config: raise ValueError("Configure a Google Web OAuth client in Scholix settings first")
    state, verifier = secrets.token_urlsafe(32), secrets.token_urlsafe(48)
    redirect = public_url + "/admin/scholix/google/callback"
    service.store.put("scholix_oauth", state, {"provider": provider, "verifier": verifier, "redirect": redirect, "owner_session": owner_session}, time.time() + 600)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    return "https://accounts.google.com/o/oauth2/v2/auth?" + urlencode({"client_id": config["client_id"], "redirect_uri": redirect,
        "response_type": "code", "scope": " ".join("https://www.googleapis.com/auth/" + scope for scope in SCOPES[provider]),
        "access_type": "offline", "prompt": "consent", "state": state, "code_challenge": challenge, "code_challenge_method": "S256"})


def token_request(config, fields):
    try:
        with urlopen(Request("https://oauth2.googleapis.com/token", urlencode({**fields, **config}).encode(), {"Content-Type": "application/x-www-form-urlencoded"}), timeout=30) as response:
            return json.load(response)
    except HTTPError:
        raise ValueError("Google authorization failed; reconnect in the owner console") from None


def complete(service, state, code, owner_session):
    pending = service.store.take("scholix_oauth", state)
    if not pending or not secrets.compare_digest(pending["owner_session"], owner_session):
        raise ValueError("Google sign-in expired or belongs to another owner session")
    config = service.store.get("scholix", "google_oauth")
    token = token_request(config, {"grant_type": "authorization_code", "code": code, "redirect_uri": pending["redirect"], "code_verifier": pending["verifier"]})
    if not token.get("refresh_token"): raise ValueError("Google did not provide an offline session; reconnect and approve access")
    key = secrets.token_urlsafe(12)
    service.connections[key] = {"provider": pending["provider"], "title": "Google " + ("Classroom" if pending["provider"] == "classroom" else "Drive"), "verified": True,
        "state": {**token, "expires_at": time.time() + token.get("expires_in", 3600)}}
    service.persist()
    return key


def ensure_token(service, account):
    token = account["state"]
    if token.get("expires_at", 0) < time.time() + 60:
        refreshed = token_request(service.store.get("scholix", "google_oauth"), {"grant_type": "refresh_token", "refresh_token": token["refresh_token"]})
        token.update(refreshed, expires_at=time.time() + refreshed.get("expires_in", 3600))
        service.persist()
    return token


def google_request(service, account, operation, args):
    token = ensure_token(service, account)
    body, headers = None, {"Authorization": "Bearer " + token["access_token"]}
    if operation.startswith("classroom_") or account["provider"] == "classroom":
        from gateway.scholix import OPERATIONS
        aliases = {"courses": "classroom_courses", "messages": "classroom_announcements"}
        operation = aliases.get(operation, operation)
        if operation == "account_info":
            return {"provider": "classroom", "connected": True, "scopes": token.get("scope", "").split()}
        if operation == "message_details":
            course_id, message_id = args["message_id"].split(":", 1)
            path = "courses/" + quote(course_id, safe="") + "/announcements/" + quote(message_id, safe="")
        elif operation not in OPERATIONS or "path" not in OPERATIONS[operation]:
            raise ValueError("Use Classroom's specialized course, coursework, submission, announcement and material tools")
        else:
            path = OPERATIONS[operation]["path"]
        for name in ("course_id", "work_id"):
            path = path.replace("{" + name + "}", quote(args.get(name, "-"), safe=""))
        query = {"pageSize": args.get("page_size", 100)}
        if "page_token" in args: query["pageToken"] = args["page_token"]
        if operation == "classroom_courses": query["studentId"] = "me"
        if operation == "classroom_submissions": query["userId"] = "me"
        url = "https://classroom.googleapis.com/v1/" + path + "?" + urlencode(query)
    elif operation in {"courses", "drive_files"}:
        query = {"pageSize": args.get("page_size", 100), "fields": "nextPageToken,files(id,name,mimeType,parents,size,modifiedTime,webViewLink,shortcutDetails,resourceKey)", "supportsAllDrives": "true", "includeItemsFromAllDrives": "true"}
        query["q"] = args.get("query", "trashed=false")
        if "page_token" in args: query["pageToken"] = args["page_token"]
        url = "https://www.googleapis.com/drive/v3/files?" + urlencode(query)
    elif operation == "drive_file":
        url = "https://www.googleapis.com/drive/v3/files/" + quote(args["file_id"], safe="") + "?fields=*&supportsAllDrives=true"
    elif operation == "drive_download":
        url = "https://www.googleapis.com/drive/v3/files/" + quote(args["file_id"], safe="")
        url += "/export?" + urlencode({"mimeType": args["export_mime"]}) if args.get("export_mime") else "?alt=media&supportsAllDrives=true"
    elif operation == "drive_create":
        metadata = json.dumps(args["metadata"], ensure_ascii=False)
        if "content" in args:
            boundary = "scholix-" + secrets.token_hex(16)
            body = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + metadata + "\r\n--" + boundary + "\r\nContent-Type: " + args["metadata"].get("mimeType", "text/plain") + "; charset=UTF-8\r\n\r\n" + args["content"] + "\r\n--" + boundary + "--\r\n").encode()
            headers["Content-Type"] = "multipart/related; boundary=" + boundary
            url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,webViewLink"
        else:
            body = metadata.encode()
            headers["Content-Type"] = "application/json"
            url = "https://www.googleapis.com/drive/v3/files?fields=id,name,mimeType,webViewLink"
    elif operation == "account_info":
        url = "https://www.googleapis.com/oauth2/v2/userinfo"
    else:
        raise ValueError("This Google provider does not support the requested action")
    try:
        with urlopen(Request(url, data=body, headers=headers), timeout=60) as response:
            if operation == "drive_download":
                data = response.read(10 * 1024 * 1024 + 1)
                if len(data) > 10 * 1024 * 1024: raise ValueError("File exceeds 10 MiB")
                return {"mime_type": response.headers.get("content-type"), "base64": base64.b64encode(data).decode()}
            return json.load(response)
    except HTTPError as error:
        raise ValueError("Google rejected this action (HTTP " + str(error.code) + "); check the account permissions and granted scopes") from None
