"""Run the actual Android PDF scripts in Chromium with intercepted, synthetic traffic."""
import base64
import json
import re
from pathlib import Path
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[2]
source = (root / "app/src/main/java/com/feldman/scholix/lemida/LemidaPdfScript.kt").read_text(encoding="utf-8")
cleanup = (root / "app/src/main/java/com/feldman/scholix/lemida/LemidaRequestScript.kt").read_text(encoding="utf-8")
scripts = {name: re.search(r'fun ' + name + r'\(.*?= """(.*?)"""', source, re.S).group(1)
           for name in ("start", "poll", "chunk")}
scripts["cleanup"] = re.search(r'fun cleanup\(.*?= """(.*?)"""', cleanup, re.S).group(1)
origin = "https://lemida.biu.ac.il"
limit_expression = re.search(r'const val MAX_BYTES = ([0-9 *]+)',
    (root / "app/src/main/java/com/feldman/scholix/lemida/LemidaPdf.kt").read_text(encoding="utf-8")).group(1)
limit = 1
for value in limit_expression.split("*"):
    limit *= int(value)


def script(name, url=origin + "/pluginfile.php/1/questions.pdf", offset=0):
    return (scripts[name].replace("${LemidaParser.BASE}", origin)
            .replace("${LemidaPdf.MAX_BYTES}", str(limit))
            .replace("${JSONObject.quote(url)}", json.dumps(url))
            .replace("$slot", "pdf_test").replace("$offset", str(offset)))


with sync_playwright() as runtime:
    browser = runtime.chromium.launch()
    page = browser.new_page()
    payload = b"%PDF-1.7\n" + bytes(range(256)) * 512

    def route(request):
        assert request.request.url.startswith(origin + "/"), "No external traffic allowed"
        if request.request.url.endswith("/my/"):
            request.fulfill(body="Synthetic Moodle", content_type="text/html")
        elif "/mod/resource/view.php" in request.request.url:
            request.fulfill(body="""<body><a href='/login/logout.php'>Logout</a><main id='region-main'>
                <object data='/pluginfile.php/1/questions.pdf'></object></main></body>""", content_type="text/html")
        elif "login.pdf" in request.request.url:
            request.fulfill(body="<html>Sign in</html>", content_type="text/html")
        elif "large.pdf" in request.request.url:
            request.fulfill(body=b"large", content_type="application/pdf", headers={"Content-Length": str(limit + 1)})
        else:
            request.fulfill(body=payload, content_type="application/pdf")

    page.route("**/*", route)
    page.goto(origin + "/my/")
    assert page.evaluate(script("start"))
    page.wait_for_function("window.pdf_test.result !== null")
    assert json.loads(page.evaluate(script("poll"))) == {"size": len(payload)}
    actual = b"".join(base64.b64decode(page.evaluate(script("chunk", offset=offset)))
                      for offset in range(0, len(payload), 32768))
    assert actual == payload
    page.evaluate("history.replaceState(null, '', '/my/?different=1')")
    assert json.loads(page.evaluate(script("poll"))) == {"lost": True}
    assert page.evaluate(script("chunk")) is None
    page.evaluate(script("cleanup"))
    assert page.evaluate("window.pdf_test === undefined")

    assert page.evaluate(script("start", url=origin + "/mod/resource/view.php?id=42"))
    page.wait_for_function("window.pdf_test.result !== null")
    assert json.loads(page.evaluate(script("poll"))) == {"size": len(payload)}
    page.evaluate(script("cleanup"))

    for suffix, expected in (("login.pdf", {"verification": True}), ("large.pdf", {"error": True, "oversized": True})):
        assert page.evaluate(script("start", url=origin + "/pluginfile.php/1/" + suffix))
        page.wait_for_function("window.pdf_test.result !== null")
        assert json.loads(page.evaluate(script("poll"))) == expected
        page.evaluate(script("cleanup"))
    assert page.evaluate(script("start", url="https://example.invalid/file.pdf")) is False
    page.evaluate("""(() => { window.fetch = (url, options) => new Promise((resolve, reject) => {
        window.signal = options.signal;
        options.signal.addEventListener('abort', () => reject(new Error('abort')));
    }); })()""")
    assert page.evaluate(script("start"))
    page.evaluate(script("cleanup"))
    assert page.evaluate("window.signal.aborted && window.pdf_test === undefined")
    browser.close()
print("PDF scripts passed: binary chunks, navigation, HTML login, size limit, origin and cancellation; no live requests.")
