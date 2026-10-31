"""Owner-controlled provider sign-in, plus authenticated browser tools for Scholix."""
import base64
import copy
import json
from urllib.parse import parse_qs, urlsplit

from gateway.openu import OpenUService, OPERATIONS as OPEN_U_OPERATIONS

BROWSER_PROVIDERS = {
    "education": ("https://apps2.education.gov.il/EduLogin/login.aspx?ReturnUrl=https://students.education.gov.il/matriculation-exams/grades-page", ("education.gov.il", "edu.gov.il")),
    "webtop": ("https://webtop.smartschool.co.il/", ("smartschool.co.il", "education.gov.il", "edu.gov.il")),
    "shahaf": ("https://timetable.shahaf.info/ShfSignIn.aspx?sid=shfapp&cid=", ("shahaf.info", "education.gov.il", "edu.gov.il")),
    "lemida": ("https://lemida.biu.ac.il/", ("biu.ac.il", "microsoftonline.com", "microsoft.com", "msauth.net", "msftauth.net", "msauthimages.net", "msftauthimages.net", "live.com", "office.com")),
    "mashov": ("https://web.mashov.info/students/", ("mashov.info", "education.gov.il", "edu.gov.il")),
    "inbar": ("https://inbar.biu.ac.il/", ("biu.ac.il",)),
    "bar-ilan": ("https://biumath.michlol4.co.il/", ("michlol4.co.il",)),
}
BROWSER_ACTIONS = ("pages", "read_page", "open_page", "click", "fill", "select", "check", "press", "upload_file", "download_file", "downloads", "get_download", "screenshot", "dialog", "close_page", "wait_for")


def add_operations(operations):
    for name in BROWSER_ACTIONS:
        info = copy.deepcopy(OPEN_U_OPERATIONS[name])
        info["description"] = "Operate the selected Scholix provider's authenticated browser. " + info["description"].replace("OpenU", "provider")
        info["inputSchema"]["properties"]["connection_id"] = {"type": "string", "minLength": 1}
        info["inputSchema"].setdefault("required", []).append("connection_id")
        operations["browser_" + name] = info


class ProviderBrowser(OpenUService):
    def __init__(self, store, key, provider):
        self.slug = "scholix-browser-" + key
        self.home_url, self.hosts = BROWSER_PROVIDERS[provider]
        super().__init__(store)
        self.saved = self.saved or {"storage_state": {"cookies": [], "origins": []}}
        self.login_data = None

    def validate_url(self, url):
        parsed = urlsplit(url)
        host = parsed.hostname or ""
        if parsed.scheme != "https" or parsed.username or parsed.password or parsed.port not in (None, 443) or not any(host == suffix or host.endswith("." + suffix) for suffix in self.hosts):
            raise ValueError("This URL is outside the selected provider's HTTPS sites")
        return url

    async def _context(self, state=None):
        context = await super()._context(state)
        def on_request(request):
            parsed = urlsplit(request.url)
            if parsed.hostname == "students.education.gov.il":
                token = parse_qs(parsed.query).get("csrt", [""])[0]
                if token: self.saved["csrt"] = token
        async def on_response(response):
            parsed = urlsplit(response.url)
            if parsed.hostname == "webtopserver.smartschool.co.il" and parsed.path.rsplit("/", 1)[-1] in {"LoginMoe", "LoginByUserNameAndPassword"}:
                try:
                    value = await response.json()
                    if value.get("status") and value.get("data"): self.login_data = value["data"]
                except Exception:
                    pass
        context.on("request", on_request)
        context.on("response", on_response)
        return context


async def browser_for(service, key):
    account = service.connections[key]
    if key not in service.browsers:
        service.browsers[key] = ProviderBrowser(service.store, key, account["provider"])
    browser = service.browsers[key]
    await browser._ensure()
    return browser


async def start(service, provider, title):
    from gateway.scholix import PROVIDERS
    import secrets
    if provider not in BROWSER_PROVIDERS: raise ValueError("Use this provider's direct/OAuth sign-in")
    key = secrets.token_urlsafe(12)
    service.connections[key] = {"provider": provider, "title": title or PROVIDERS[provider]["title"], "verified": False, "browser": True}
    try:
        await browser_for(service, key)
    except BaseException:
        service.connections.pop(key)
        raise
    service.persist()
    return key


async def complete(service, key):
    browser = await browser_for(service, key)
    provider = service.connections[key]["provider"]
    cookies = await browser.context.cookies()
    if provider == "education":
        header = "; ".join(c["name"] + "=" + c["value"] for c in cookies if c["domain"].lstrip(".").endswith("education.gov.il"))
        await service.native(key, "adopt_browser", {"cookies": header, "csrt": browser.saved.get("csrt", "")})
    elif provider == "webtop":
        if not browser.login_data: raise ValueError("Complete the Webtop login in this browser first")
        header = "; ".join(c["name"] + "=" + c["value"] for c in cookies if c["domain"].lstrip(".").endswith("smartschool.co.il"))
        await service.native(key, "adopt_browser", {"cookies": header, "login_data": browser.login_data})
    elif provider == "shahaf":
        auth = None
        for page in browser.tabs:
            if page.is_closed(): continue
            parsed = urlsplit(page.url)
            if parsed.hostname == "timetable.shahaf.info" and parsed.path.lower().endswith("/signinsuccess.aspx"):
                encoded = parse_qs(parsed.query).get("s", [""])[0].replace(".", "=").replace("_", "/").replace("-", "+")
                auth = json.loads(base64.b64decode(encoded + "=" * (-len(encoded) % 4)))
        if not auth: raise ValueError("Complete the Shahaf login first")
        await service.native(key, "adopt_browser", {"auth_response": auth})
    elif provider == "lemida":
        page = next((p for p in reversed(browser.tabs) if not p.is_closed() and urlsplit(p.url).hostname == "lemida.biu.ac.il"), None)
        if not page or not any(c["name"] == "MoodleSession" for c in cookies) or not await page.locator('a[href*="/login/logout.php"]').count():
            raise ValueError("Complete Microsoft/Lemida sign-in first")
    else:
        raise ValueError("For this provider use its native sign-in form or import its saved Scholix provider state")
    browser.saved["storage_state"] = await browser.context.storage_state()
    browser.store.put("account", browser.slug, browser.saved)
    service.connections[key]["verified"] = True
    service.persist()


async def lemida(service, key, operation, args):
    from gateway import scholix_lemida as parser
    browser = await browser_for(service, key)
    if operation == "courses":
        url = "https://lemida.biu.ac.il/my/courses.php"
    elif operation == "lemida_course":
        url = "https://lemida.biu.ac.il/course/view.php?id=" + str(args["course_id"])
    elif operation == "lemida_grades":
        url = "https://lemida.biu.ac.il/grade/report/user/index.php?id=" + str(args["course_id"])
    else:
        url = browser.validate_url(args["url"])
    page = await browser.context.new_page()
    try:
        await page.goto(url, wait_until="domcontentloaded", timeout=60000)
        if urlsplit(page.url).hostname != "lemida.biu.ac.il" or await page.locator('form#login, input[type=password]').count():
            raise ValueError("Lemida session expired; reconnect in the owner console")
        content = await page.content()
        if operation == "courses": return list(parser.courses(content).values())
        if operation == "lemida_course": return parser.parse_course(content, page.url)
        if operation == "lemida_grades": return {"tables": parser.tables(parser.soup(content))}
        return parser.parse_detail(content)
    finally:
        browser.saved["storage_state"] = await browser.context.storage_state()
        browser.store.put("account", browser.slug, browser.saved)
        await page.close()
