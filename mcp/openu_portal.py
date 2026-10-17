"""Sheilta portal contracts inspected from its own shipped JavaScript (2026-10-17)."""
import base64
import json
import re
from datetime import datetime
from pathlib import Path
from urllib.parse import quote, urlencode, urljoin

from bs4 import BeautifulSoup

ROOT = "https://sheilta.apps.openu.ac.il"
DATA = Path(__file__).parent / "openu-data"
OPERATIONS = set()


def register(operation):
    string = {"type": "string", "minLength": 1}
    def add(name, description, props=None, required=(), read=True):
        operation(name, description, props, required, read)
        OPERATIONS.add(name)
    add("service_requests", "Read all פניות, including historical requests, IDs, application numbers, status, dates, course and semester. Optional filters are applied locally; no requests are submitted.", {"query": string, "status_group": {"enum": ["1", "2", "3"]}})
    add("service_request_details", "Resolve and read an existing פניה's details and available controls. Use application_code and application_number from service_requests. Does not submit a response.", {"application_code": string, "application_number": string}, ("application_code", "application_number"))
    add("service_request_catalog", "Discover available פניות categories, common requests, instructions and action/information links. Supply a category code to see its request types, or query to search.", {"category": string, "query": string})
    add("service_request_types", "Read the complete general-request type/subtype tree, explanations, course requirements and whether creation is enabled. group defaults to the website's general group 44.", {"group": string})
    add("service_request_form", "Read a general פניה form's dynamic parameters, eligible courses, attachment types/constraints and account permissions. No request or draft is created.", {"request_type": string, "group": string}, ("request_type",))
    add("service_request_create", "Submit a REAL general פניה. Inspect service_request_form first. Supply the exact leaf request_type, parameter values and uploaded attachment references. Does not pay associated charges. Never retry an ambiguous result automatically. Legacy/specialized forms use the existing browser tools.", {"request_type": string, "group": string, "description": {"type": "string", "maxLength": 2000}, "service": string, "course_id": string, "semester": string, "params": {"type": "array", "items": {"type": "object", "properties": {"name": string, "value": {}}, "required": ["name", "value"], "additionalProperties": False}}, "files": {"type": "array", "items": {"type": "object", "properties": {"contactId": {"type": "integer"}, "fileId": string, "comment": {"type": "string"}}, "required": ["contactId", "fileId"], "additionalProperties": False}}}, ("request_type",), False)
    add("service_request_upload", "Upload a REAL attachment for a פניה, without submitting the request. Read form attachment constraints first. Returns the uploaded file reference. Maximum 10 MiB.", {"request_type": string, "contact_id": {"type": "integer"}, "filename": string, "mime_type": string, "base64": string}, ("request_type", "contact_id", "filename", "mime_type", "base64"), False)
    add("service_request_files", "Read attachment metadata for an existing פניה.", {"request_id": string}, ("request_id",))
    add("service_request_download", "Download an existing פניה attachment as base64, maximum 10 MiB.", {"request_id": string, "file_id": string}, ("request_id", "file_id"))
    add("service_request_delete_file", "Delete a REAL uploaded פניה attachment. This changes the request's documents; never retry automatically.", {"request_id": string, "file_id": string}, ("request_id", "file_id"), False)
    add("exam_portal", "Read the new בחינות/מטלות system: permissions, pending exams, tasks, submissions, messages and file constraints. Does not start an exam or submit anything.", {"section": {"enum": ["permissions", "exams", "tasks", "exams/submissions", "tasks/submissions", "messages", "files/constraints"]}})
    exam = {"semester": string, "course_id": string, "assignment_id": string, "section": {"enum": ["details", "state", "questionnaires/metadata"]}}
    add("exam_portal_details", "Read a discovered exam's details, state or available questionnaire metadata. Does not start the exam.", exam, ("semester", "course_id", "assignment_id"))
    add("exam_registration", "Read exam registration eligibility, available sittings, deadlines and links in the legacy בחינות screen. Does not register/change/cancel an exam.")
    add("registration_options", "Read all currently offered registration semesters (including upcoming semesters), teaching modes, degree/subject, credit, department, time and location filters. Does not add a course to the basket.")
    filters = {key: string for key in ("semester", "course_id", "query", "degree", "subject", "language", "level", "credits", "department", "area", "center", "hours", "days", "instruction_type")}
    filters["page"] = {"type": "integer", "minimum": 1}
    add("course_offerings", "Search available courses and study groups for an offered semester, including future semesters and annual offerings. Returns the site's table, registration availability, times and course links. No course is added/registered.", filters, ("semester",))
    add("academic_timetable", "Read the published academic timetable: semesters, registration periods, holidays and exam periods.")
    add("personal_timetable", "Read the owner's Sheilta timetable and calendar links. Does not export/synchronize a calendar.")
    add("course_description", "Read the current official course description, prerequisites, credits, level, syllabus and links.", {"course_id": {"type": "string", "pattern": "^[0-9]{5}$"}}, ("course_id",))
    add("study_programs", "Read personal study programs, academic obligations, recognition of previous study, messages and limits from Sheilta. Does not acknowledge notices, save or submit a study program.")
    add("study_program_search", "Read the available official study-program index, or search programs by subject codes returned by the index.", {"subject_codes": string})
    add("study_program_details", "Read an official/personal study program's XML and course/credit requirements in display mode. Does not create/save/submit a program.", {"program_code": string, "program_id": {"type": "string"}, "parent_code": {"type": "string"}}, ("program_code",))
    add("study_program_courses", "Read courses and filters available to the official degree planner. resource selects course search, departments, knowledge branches, subjects, levels or an individual course.", {"resource": {"enum": ["courses", "departments", "branches", "subjects", "levels", "course"]}, **{k: {"type": "string"} for k in ("subject_ids", "level_ids", "query", "knowledge_branch", "subject_id", "course_id")}, "mind_expanding": {"type": "boolean"}}, ("resource",))
    add("degree_index", "Read the same complete degree tree used by your OpenU website's תואר planner, including nested tracks and specializations. This is the app's bundled catalog snapshot.")
    add("degree_details", "Read the same detailed degree requirements used by your OpenU website: mandatory/elective groups, course alternatives, credit/advanced-credit quotas, constraints and notes. Use the degree filename/id from degree_index.", {"degree_id": {"type": "string", "pattern": "^[A-Za-z0-9_-]+$"}}, ("degree_id",))
    add("course_catalog", "Search the same complete course catalog used by your OpenU website. Does not imply current registration availability; use course_offerings for a semester.", {"query": string, "offset": {"type": "integer", "minimum": 0}, "limit": {"type": "integer", "minimum": 1, "maximum": 300}})


async def request(service, path, method="GET", **kwargs):
    url = service.validate_url(urljoin(ROOT, path))
    response = await service.context.request.fetch(url, method=method, max_redirects=0, timeout=60000, **kwargs)
    try:
        if not response.ok:
            raise ValueError(f"OpenU portal returned HTTP {response.status}; reconnect if the session expired")
        if "json" not in response.headers.get("content-type", ""):
            raise ValueError("OpenU did not return portal data; reconnect in the owner console")
        return await response.json()
    finally:
        await response.dispose()


async def token(service):
    response = await service.context.request.get(ROOT + "/Main/GlobalRequest", max_redirects=0)
    try:
        field = BeautifulSoup(await response.text(), "html.parser").find("input", {"name": "__RequestVerificationToken"})
        if not response.ok or not field:
            raise ValueError("Reconnect OpenU in the owner console")
        return {"X-RequestVerificationToken": field["value"]}
    finally:
        await response.dispose()


async def page(service, url):
    # Read tools share one tab; repeated timetable/catalog searches must not exhaust renderers.
    tab = getattr(service, "portal_page", None)
    if tab is None or tab.is_closed() or tab.context != service.context:
        tab = service.portal_page = await service.context.new_page()
    await tab.goto(service.validate_url(urljoin(ROOT, url)), wait_until="domcontentloaded", timeout=60000)
    if await tab.locator("frameset").count():
        # Legacy Sheilta calendars create their child documents after the outer DOM event.
        for element in await tab.locator("frame").all():
            handle = await element.element_handle()
            frame = await handle.content_frame()
            if frame:
                await frame.wait_for_url(lambda value: bool(value) and str(value) != "about:blank", wait_until="domcontentloaded", timeout=30000)
    if "sso.apps.openu.ac.il" in tab.url:
        raise ValueError("OpenU session expired; reconnect in the owner console")
    result = await service._invoke("read_page", {"page_id": service.tabs.index(tab), "limit": 60000})
    if len(tab.frames) > 1:
        result["frame_content"] = []
        for index, frame in enumerate(tab.frames[1:], 1):
            if frame.url.startswith(ROOT):
                result["frame_content"].append(await service._invoke("read_page", {"page_id": service.tabs.index(tab), "frame": index, "limit": 20000}))
    return result | {
        "page_id": service.tabs.index(tab), "url": tab.url,
        "frames": [{"frame": i, "url": frame.url} for i, frame in enumerate(tab.frames)],
        "note": "Portal read tools reuse this tab. Use read_page for remaining text/frames before opening another portal section."}


async def types(service, group):
    return await request(service, "/Main/api/generalApi/getServiceRequestTypes/" + quote(group, safe=""))


def flatten(items):
    for item in items:
        yield item
        yield from flatten(item.get("subTypes") or [])


async def invoke(service, op, args):
    if op == "degree_index": return json.loads((DATA / "degrees_index.json").read_text(encoding="utf-8"))
    if op == "degree_details":
        filename = DATA / "degrees" / (args["degree_id"] + ".json")
        if not filename.is_file(): raise ValueError("Unknown degree; use degree_index")
        return json.loads(filename.read_text(encoding="utf-8"))
    if op == "course_catalog":
        rows = json.loads((DATA / "courses.json").read_text(encoding="utf-8"))
        query = args.get("query", "").casefold()
        rows = [r for r in rows if query in json.dumps(r, ensure_ascii=False).casefold()]
        offset, limit = args.get("offset", 0), args.get("limit", 100)
        return {"total": len(rows), "courses": rows[offset:offset+limit], "offset": offset}
    if op == "service_requests":
        rows = await request(service, "/Main/ServiceRequests/GetApplicationRequestsByUser")
        if "status_group" in args: rows = [r for r in rows if r["StatusGroupId"] == args["status_group"]]
        if "query" in args: rows = [r for r in rows if args["query"].casefold() in json.dumps(r, ensure_ascii=False).casefold()]
        return rows
    if op == "service_request_details":
        info = await request(service, "/Main/ServiceRequests/GetApplicationRequestMoreInfo?" + urlencode({"applicationCode": args["application_code"], "applicationNumber": args["application_number"]}))
        return {"link": info, "page": await page(service, info["Url"])}
    if op == "service_request_catalog":
        headers = await token(service)
        if "query" in args: endpoint = "searchRequests?" + urlencode({"freeText": args["query"]})
        elif "category" in args: endpoint = "getCategoryData?" + urlencode({"category": args["category"], "hotRequest": ""})
        else: endpoint = "getUserData"
        return await request(service, "/Main/api/serviceRequestsApi/" + endpoint, headers=headers)
    if op == "service_request_types": return await types(service, args.get("group", "44"))
    if op in {"service_request_form", "service_request_create"}:
        items = list(flatten(await types(service, args.get("group", "44"))))
        selected = next((r for r in items if r["code"] == args["request_type"]), None)
        if not selected: raise ValueError("Unknown request type in this group; use service_request_types")
        code = str(selected.get("codeToCreate") or selected["code"])
        prefix = "/Main/api/"
        if op == "service_request_form":
            return {"type": selected,
                "params": await request(service, prefix + "generalApi/getGlobalParams?" + urlencode({"requestCode": code})),
                "courses": await request(service, prefix + "generalApi/studentCoursesSpecial?" + urlencode({"requestTypeCode": code})),
                "permissions": await request(service, prefix + "serviceRequestsApi/permissions/" + quote(code, safe="")),
                "file_types": await request(service, prefix + "serviceRequestsFilesApi/types/" + quote(code, safe="")),
                "file_constraints": await request(service, prefix + "serviceRequestsFilesApi/constraints?" + urlencode({"requestType": code}))}
        if selected.get("subTypes"): raise ValueError("Select a leaf request type first")
        chain = [selected]
        while chain[0].get("parentCode"):
            parent = next((r for r in items if r["code"] == chain[0]["parentCode"]), None)
            if not parent or parent in chain: raise ValueError("Invalid upstream request hierarchy")
            chain.insert(0, parent)
        if len(chain) > 3: raise ValueError("Use the website for this request hierarchy")
        body = {"Level1Code": "", "Level2Code": "", "Level3Code": "", "title": " / ".join(r["description"] for r in chain),
            "userId": "", "studentId": "", "date": datetime.now().strftime("%d/%m/%Y"), "freeText": args.get("description", ""),
            "files": args.get("files", []), "anotherData": "", "service": args.get("service", ""), "course": args.get("course_id", ""),
            "semester": args.get("semester", ""), "group": args.get("group", "44"), "idToCreate": str(selected["idToCreate"]), "params": args.get("params", [])}
        for index, item in enumerate(chain, 1): body[f"Level{index}Code"] = str(item["code"])
        return await request(service, prefix + "generalApi/add/", "POST", data=body, headers=await token(service))
    if op == "service_request_upload":
        data = base64.b64decode(args["base64"], validate=True)
        if len(data) > 10 * 1024 * 1024: raise ValueError("File exceeds 10 MiB")
        return await request(service, "/Main/api/serviceRequestsFilesApi/file/upload?" + urlencode({"requestType": args["request_type"], "contactId": args["contact_id"]}), "POST",
            multipart={"file": {"name": args["filename"], "mimeType": args["mime_type"], "buffer": data}}, headers=await token(service))
    if op == "service_request_files": return await request(service, "/Main/api/serviceRequestsFilesApi/files/" + quote(args["request_id"], safe=""))
    if op == "service_request_download": return await service._invoke("download_file", {"url": ROOT + "/Main/api/serviceRequestsFilesApi/download/" + quote(args["file_id"], safe="") + "?" + urlencode({"requestId": args["request_id"]})})
    if op == "service_request_delete_file": return await request(service, "/Main/api/serviceRequestsFilesApi/file/delete?" + urlencode({"fileId": args["file_id"], "requestId": args["request_id"]}), "POST", headers=await token(service))
    if op == "exam_portal": return await request(service, "/main/api/assignmentsApi/" + args.get("section", "exams"))
    if op == "exam_portal_details":
        suffix = args.get("section", "details")
        path = "/main/api/assignmentsApi/exams/" + "/".join(quote(args[k], safe="") for k in ("semester", "course_id", "assignment_id"))
        return await request(service, path + ("/" + suffix if suffix != "details" else ""))
    if op == "study_programs":
        data = await request(service, "/Main/StudyPrograms/GetIndexData")
        return {k: v for k, v in data.items() if k not in {"Menu", "CurrentUser", "ActorUser"}}
    if op == "study_program_search": return await request(service, "/Main/StudyPrograms/" + ("SearchProgramsBySubSubjectID?" + urlencode({"subSubjectId": args["subject_codes"]}) if "subject_codes" in args else "GetSearchItems"))
    if op == "study_program_details": return await request(service, "/Main/StudyPrograms/GetProgramXML?" + urlencode({"programCode": args["program_code"], "programId": args.get("program_id", ""), "parentCode": args.get("parent_code", ""), "status": "3" if args.get("program_id") else "1"}))
    if op == "study_program_courses":
        name, params = {
            "courses": ("GetCourses", {"subjectIds": args.get("subject_ids", ""), "levelIds": args.get("level_ids", ""), "freeText": args.get("query", ""), "mindExpanding": str(args.get("mind_expanding", False)).lower()}),
            "departments": ("GetAllDepartments", {}), "branches": ("GetKnowledgeBranchs", {}),
            "subjects": ("GetSubjects", {"knowledgeBranch": args.get("knowledge_branch", "")}), "levels": ("GetLevels", {"subjectId": args.get("subject_id", "")}),
            "course": ("GetCourseById", {"courseId": args.get("course_id", ""), "isAdditional": "true"})}[args["resource"]]
        return await request(service, "/Main/StudyPrograms/" + name + "?" + urlencode(params))
    if op == "course_offerings":
        mapping = {"semester": "Semester", "query": "CourseName", "degree": "Degree", "subject": "Subject", "language": "LearningLanguage", "level": "Openinglevel", "credits": "Credits", "department": "AD", "area": "GeographicalArea", "center": "LearningCenter", "hours": "InstructionHours", "days": "Days", "instruction_type": "InstructionType", "page": "Page"}
        params = {"Degree": "0", "GeographicalArea": ",01,02,03,04,05,06,07,09,", "InstructionHours": "00:01", "Days": "111111", "Page": "1"}
        params.update({mapping[k]: v for k, v in args.items() if k in mapping})
        for key in ("subject", "language", "level", "credits", "department", "area", "center"):
            if key in args:
                value = args[key].strip(",")
                if key == "area" and value == "99": continue
                if value == "all": params.pop(mapping[key], None)
                else: params[mapping[key]] = "," + value + ","
        if "course_id" in args: params["CourseIDs"] = "," + args["course_id"] + ","
        return await page(service, "/CoursesRegistration/Pages/GroupSearchResults.aspx?" + urlencode(params))
    urls = {"registration_options": "/CoursesRegistration/Pages/GroupsSearch.aspx", "exam_registration": "/pls/dmyopt2/course_info.Home_Test",
            "academic_timetable": "https://academic.openu.ac.il/yedion/timetable/pages/default.aspx", "personal_timetable": "/pls/dmyopt2/LUACH_SHANA.first"}
    if op == "course_description": return await page(service, "https://www.openu.ac.il/courses/" + args["course_id"] + ".htm?t=h")
    return await page(service, urls[op])
