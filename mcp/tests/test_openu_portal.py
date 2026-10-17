import asyncio
from unittest.mock import AsyncMock

from gateway import openu_portal
from gateway.openu import OPERATIONS


def test_request_submission_uses_discovered_hierarchy_once(monkeypatch):
    rows = [{"code": "parent", "description": "Parent", "subTypes": [{"code": "leaf", "parentCode": "parent", "description": "Leaf", "idToCreate": 123, "subTypes": []}]}]
    monkeypatch.setattr(openu_portal, "types", AsyncMock(return_value=rows))
    monkeypatch.setattr(openu_portal, "token", AsyncMock(return_value={"X-RequestVerificationToken": "fixture"}))
    send = AsyncMock(return_value={"results": {"requestId": "fixture"}})
    monkeypatch.setattr(openu_portal, "request", send)
    result = asyncio.run(openu_portal.invoke(object(), "service_request_create", {"request_type": "leaf", "description": "Synthetic fixture"}))
    assert result["results"]["requestId"] == "fixture"
    send.assert_awaited_once()
    call = send.call_args
    assert call.args[1:3] == ("/Main/api/generalApi/add/", "POST")
    assert call.kwargs["data"]["Level1Code"] == "parent"
    assert call.kwargs["data"]["Level2Code"] == "leaf"
    assert call.kwargs["data"]["idToCreate"] == "123"
    assert "X-RequestVerificationToken" not in str(result)


def test_portal_reads_and_writes_have_distinct_scopes():
    for name in ("service_requests", "service_request_details", "service_request_catalog", "service_request_form", "exam_portal", "course_offerings", "degree_details"):
        assert OPERATIONS[name]["readOnly"]
    for name in ("service_request_create", "service_request_upload", "service_request_delete_file"):
        assert not OPERATIONS[name]["readOnly"]
        assert OPERATIONS[name]["destructive"]


def test_private_document_link_download_and_expiry(tmp_path):
    import base64
    import time
    from types import SimpleNamespace
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from gateway.store import Store
    from gateway.openu_downloads import document_link, install

    store = Store(tmp_path)
    service = SimpleNamespace(store=store)
    app = FastAPI()
    install(app, service, "/tools", "https://testserver/tools")
    pdf = b"%PDF-1.4\nsynthetic fixture"
    result = document_link(service, {"base64": base64.b64encode(pdf).decode(), "mime_type": "application/pdf"},
                           {"course_id": "20474", "semester": "2026b", "assignment_no": "84", "artifact": "notebook"})
    assert "base64" not in result
    assert result["size_bytes"] == len(pdf)
    with TestClient(app) as client:
        response = client.get(result["download_url"])
        assert response.status_code == 200
        assert response.content == pdf
        assert response.headers["content-disposition"].startswith("attachment;")
        assert response.headers["cache-control"] == "no-store"
        assert client.get("/tools/files/openu/invalid").status_code == 404
        token = result["download_url"].rsplit("/", 1)[-1]
        store.put("openu_download", token, store.get("openu_download", token), time.time() - 1)
        assert client.get(result["download_url"]).status_code == 404
    store.db.close()


def test_course_search_matches_website_defaults_and_pagination(monkeypatch):
    from urllib.parse import urlsplit, parse_qs
    view = AsyncMock(return_value={})
    monkeypatch.setattr(openu_portal, "page", view)
    asyncio.run(openu_portal.invoke(object(), "course_offerings", {"semester": "2027ב", "course_id": "20109", "page": 2}))
    query = parse_qs(urlsplit(view.call_args.args[1]).query)
    assert query["Semester"] == ["2027ב"]
    assert query["CourseIDs"] == [",20109,"]
    assert query["GeographicalArea"] == [",01,02,03,04,05,06,07,09,"]
    assert query["Days"] == ["111111"]
    assert query["InstructionHours"] == ["00:01"]
    assert query["Degree"] == ["0"]
    assert query["Page"] == ["2"]


def test_study_program_uses_preview_for_catalog_and_display_for_saved_program(monkeypatch):
    from urllib.parse import urlsplit, parse_qs
    get = AsyncMock(return_value={})
    monkeypatch.setattr(openu_portal, "request", get)
    for args, status in [({"program_code": "AF"}, "1"), ({"program_code": "AF", "program_id": "fixture"}, "3")]:
        asyncio.run(openu_portal.invoke(object(), "study_program_details", args))
        assert parse_qs(urlsplit(get.call_args.args[1]).query)["status"] == [status]
