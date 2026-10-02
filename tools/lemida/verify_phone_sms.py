"""Exercise Android's Microsoft picker scripts with mocked pages; no SMS is sent."""
import re
from pathlib import Path

from playwright.sync_api import sync_playwright

source = (Path(__file__).resolve().parents[2] /
          'app/src/main/java/com/feldman/scholix/lemida/LemidaSms.kt').read_text(encoding='utf-8')
select = re.search(r'fun selectScript.*?= """(.*?)"""', source, re.S).group(1)
choose = re.search(r'fun chooseScript.*?return """(.*?)"""', source, re.S).group(1)
submit = re.search(r'fun submitScript.*?return """(.*?)"""', source, re.S).group(1)
fixture = '''
<a id="signInAnotherWay" href="#">Use another method</a>
<script>
window.requests = 0;
document.querySelector('a').onclick = e => {
  e.preventDefault(); document.body.innerHTML = '<div role="button" data-value="OneWaySMS">Text</div>';
  document.querySelector('div').onclick = () => {
    requests++;
    if (window.keepPicker) return;
    document.body.innerHTML = '<input id="idTxtBx_SAOTCC_OTC"><button id="idSubmit_SAOTCC_Continue">Verify</button>';
    document.querySelector('button').onclick = () => { window.submitted = document.querySelector('input').value; };
  };
};
</script>'''

with sync_playwright() as runtime:
    browser = runtime.chromium.launch()
    page = browser.new_page()
    # Intercept every HTTP request, including the real-looking Microsoft origin.
    page.route('**/*', lambda route: route.fulfill(body=fixture, content_type='text/html'))

    def probe(alternative=False, selected=False):
        return page.evaluate(select.replace('$smsSelected', str(selected).lower())
                             .replace('$alternativeClicked', str(alternative).lower()))

    def click(action):
        return page.evaluate(choose.replace('$action', action))

    page.goto('https://login.microsoftonline.com/test')
    assert probe() == 'alternative'
    assert page.locator('#signInAnotherWay').count() == 1  # Probe has no click side effect.
    assert click('alternative') is True
    assert probe(True) == 'sms'
    assert page.evaluate('requests') == 0
    assert click('sms') is True
    assert click('sms') is False  # Consumed choices cannot request SMS again.
    assert probe(True, True) == 'otp'
    assert page.evaluate(submit.replace('$code', '123456')) is True
    assert page.evaluate('window.submitted') == '123456'
    assert page.evaluate('requests') == 1

    page.reload()
    assert probe() == 'alternative'
    assert click('alternative') is True
    assert probe(True) == 'sms'
    page.evaluate('document.body.innerHTML = "Page changed"')
    assert click('sms') is False  # A detached candidate must not be clicked.
    assert page.evaluate('requests') == 0

    page.reload()
    assert probe() == 'alternative'
    assert click('alternative') is True
    page.evaluate('window.keepPicker = true')
    assert probe(True) == 'sms'
    assert click('sms') is True
    # Native flags are recorded before the click, even if its callback is lost.
    assert probe(True, True) == 'waiting'
    assert probe(True, True) == 'waiting'
    assert page.evaluate('requests') == 1

    for url in ('https://example.test/', 'http://login.microsoftonline.com/test'):
        page.goto(url)
        assert probe() == 'other'
        assert click('sms') is False
        assert page.evaluate(submit.replace('$code', '123456')) is False
    browser.close()

print('SMS probe/click separation, single request, detached choice, code submission, and HTTPS origin guards passed.')
