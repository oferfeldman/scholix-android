"""Short-lived, unguessable download links for owner-requested OpenU documents."""
import base64
import hashlib
import secrets
import time
from urllib.parse import quote

from fastapi import HTTPException
from fastapi.responses import Response


def document_link(service, document, args):
    token = secrets.token_urlsafe(32)
    expires = int(time.time()) + 900
    data = base64.b64decode(document["base64"], validate=True)
    filename = "OpenU-" + "-".join(args[key] for key in ("course_id", "semester", "assignment_no", "artifact")) + ".pdf"
    service.store.cleanup()
    service.store.put("openu_download", token, {"base64": document["base64"], "mime_type": document["mime_type"], "filename": filename}, expires)
    return {"download_url": service.public_url + "/files/openu/" + token,
            "filename": filename, "mime_type": document["mime_type"], "size_bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(), "expires_at": expires,
            "note": "Private bearer link, valid for 15 minutes. Fetch this URL directly to download/read the PDF; no additional login or browser export is needed."}


def install(app, service, prefix, public_url):
    service.public_url = public_url

    @app.get(prefix + "/files/openu/{download_token}", include_in_schema=False)
    async def download(download_token: str):
        document = service.store.get("openu_download", download_token)
        if not document:
            raise HTTPException(404, "Download link expired or invalid; request a new link through the OpenU tool")
        return Response(base64.b64decode(document["base64"]), media_type=document["mime_type"], headers={
            "Content-Disposition": "attachment; filename*=UTF-8''" + quote(document["filename"], safe=""),
            "Cache-Control": "no-store", "Referrer-Policy": "no-referrer"})
