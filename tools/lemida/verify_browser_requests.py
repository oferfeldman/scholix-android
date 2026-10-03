"""Exercise Android's AJAX request lifecycle in Chromium; all traffic is mocked."""
import json
import re
from pathlib import Path

from playwright.sync_api import sync_playwright

source = (Path(__file__).resolve().parents[2] /
          'app/src/main/java/com/feldman/scholix/lemida/LemidaRequestScript.kt').read_text(encoding='utf-8')
scripts = {name: re.search(r'fun ' + name + r'\(.*?= """(.*?)"""', source, re.S).group(1)
           for name in ('start', 'poll', 'cleanup', 'document', 'signedIn', 'interactiveSignIn')}
origin = 'https://lemida.biu.ac.il'


def script(name, slot='test_request', url=origin + '/lib/ajax/service.php', body='[{"index":0}]'):
    return (scripts[name].replace('${LemidaParser.BASE}', origin)
            .replace('${JSONObject.quote(url)}', json.dumps(url))
            .replace('${JSONObject.quote(body)}', json.dumps(body))
            .replace('$slot', slot))


with sync_playwright() as runtime:
    browser = runtime.chromium.launch()
    page = browser.new_page()
    page.route('**/*', lambda route: route.fulfill(
        body='[]' if route.request.method == 'POST' else '<p>Mock Moodle</p>',
        content_type='application/json' if route.request.method == 'POST' else 'text/html'))
    page.goto(origin + '/my/')

    assert 'Mock Moodle' in page.evaluate(script('document', url=page.url))

    # Sign-in checks require this exact document and the authenticated Moodle markers.
    assert page.evaluate(script('signedIn', url=page.url)) is False
    page.set_content('<a href="/login/logout.php">Logout</a>')
    page.evaluate('window.M = {cfg: {userId: 42}}')
    assert page.evaluate(script('signedIn', url=page.url)) is True
    assert page.evaluate(script('signedIn', url=origin + '/my/?old=1')) is False
    page.evaluate("document.body.classList.add('notloggedin')")
    assert page.evaluate(script('signedIn', url=page.url)) is False
    page.evaluate("document.body.classList.remove('notloggedin'); window.M.cfg.userId = 1")
    assert page.evaluate(script('signedIn', url=page.url)) is False
    page.evaluate("window.M.cfg.userId = 42; document.querySelector('a').remove()")
    assert page.evaluate(script('signedIn', url=page.url)) is False
    page.reload()
    assert page.evaluate(script('document', url=origin + '/mod/assign/view.php?id=9')) is None
    old_url = page.url
    page.evaluate("history.replaceState(null, '', '/my/?updated=1')")
    assert page.evaluate(script('document', url=old_url)) is None
    assert 'Mock Moodle' in page.evaluate(script('document', url=page.url))

    # A real fetch, intercepted locally, preserves the JSON response and disappears on cleanup.
    assert page.evaluate(script('start')) is True
    page.wait_for_function('window.test_request.result !== null')
    result = json.loads(page.evaluate(script('poll')))
    assert result['status'] == 200 and result['body'] == '[]'
    page.evaluate(script('cleanup'))
    assert page.evaluate('window.test_request === undefined')

    # Control deferred completion to reproduce worker cancellation and late network responses.
    page.evaluate('''(() => {window.fetch = (url, options) => new Promise((resolve, reject) => {
        window.requestOptions = options;
        window.finishRequest = () => resolve({status:200, url, text:async () => 'late result'});
        window.failRequest = () => reject(new Error('offline'));
        options.signal.addEventListener('abort', () => {window.aborted = true;});
    });})()''')
    assert page.evaluate(script('start')) is True
    assert page.evaluate(script('poll')) == 'null'
    page.evaluate(script('cleanup'))
    assert page.evaluate('window.aborted && window.requestOptions.signal.aborted')
    page.evaluate('window.finishRequest()')
    assert page.evaluate('window.test_request === undefined')  # Late completion cannot recreate it.

    assert page.evaluate(script('start')) is True
    page.evaluate('window.failRequest()')
    page.wait_for_function('window.test_request.result !== null')
    assert json.loads(page.evaluate(script('poll'))) == {'error': True}
    page.evaluate(script('cleanup'))
    page.evaluate(script('cleanup'))  # Idempotent after failure.

    assert page.evaluate(script('start')) is True
    page.reload()
    assert json.loads(page.evaluate(script('poll'))) == {'lost': True}

    # Never POST session-key payloads across an origin, including HTTP on the same hostname.
    for endpoint in ('https://example.test/api', 'http://lemida.biu.ac.il/api',
                     'https://lemida.biu.ac.il:444/api'):
        assert page.evaluate(script('start', url=endpoint)) is False
        assert page.evaluate('window.test_request === undefined')
    for url in ('https://login.microsoftonline.com/test', 'http://lemida.biu.ac.il/my/'):
        page.goto(url)
        assert page.evaluate(script('start')) is False
        assert page.evaluate(script('document', url=page.url)) is None
        page.set_content('<a href="/login/logout.php">Logout</a>')
        page.evaluate('window.M = {cfg: {userId: 42}}')
        assert page.evaluate(script('signedIn', url=page.url)) is False
        assert json.loads(page.evaluate(script('poll'))) == {'verification': True}
    page.goto('https://login.microsoftonline.com/test')
    assert page.evaluate(script('interactiveSignIn')) is False
    page.set_content('<input type="hidden"><button style="display:none">Hidden</button>')
    assert page.evaluate(script('interactiveSignIn')) is False
    page.set_content('<input id="i0118" type="password">')
    assert page.evaluate(script('interactiveSignIn')) is True
    page.set_content('<div role="button">Authenticator approval</div>')
    assert page.evaluate(script('interactiveSignIn')) is True
    browser.close()

print('Browser JSON fetch, cancellation, late completion, network errors, stale document, sign-in and origin guards passed.')
