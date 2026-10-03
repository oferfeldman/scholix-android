"""Export your Lemida courses using saved HTML or an interactive Moodle session."""
from __future__ import annotations

import argparse
import getpass
import json
import re
import sys
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urljoin, urlparse

from bs4 import BeautifulSoup
from playwright.sync_api import Error as PlaywrightError

BASE = 'https://lemida.biu.ac.il'
ROOT = Path(__file__).resolve().parent


def text(node):
    return ' '.join(node.stripped_strings) if node else ''


def soup(html):
    return BeautifulSoup(html, 'html.parser')


def site_url(href, base=BASE):
    url = urljoin(base, href)
    parsed = urlparse(url)
    if parsed.scheme == 'https' and parsed.netloc == urlparse(BASE).netloc:
        return url.split('#')[0]
    return None


def number(url, key='id'):
    values = parse_qs(urlparse(url).query, keep_blank_values=True).get(key, [])
    if len(values) != 1 or not re.fullmatch(r'[0-9]+', values[0]):
        return None
    value = int(values[0])
    return value if value > 0 else None


def courses(html):
    result = {}
    for a in soup(html).select('a[href]'):
        url = site_url(a['href'])
        if url and urlparse(url).path == '/course/view.php' and number(url):
            cid = number(url)
            if cid != 1:
                name = text(a)
                if cid not in result or len(name) > len(result[cid]['name']):
                    result[cid] = {'id': cid, 'name': name, 'url': url}
    return result


def tables(container):
    result = []
    for table in container.select('table'):
        rows = []
        for row in table.select('tr'):
            if row.find_parent('table') is not table:
                continue
            cells = []
            for cell in row.find_all(['th', 'td'], recursive=False):
                content = soup(str(cell))
                for nested in content.select('table'):
                    nested.decompose()
                for br in content.select('br'):
                    br.replace_with('\n')
                for block in content.select('p, div, li, h1, h2, h3'):
                    block.append('\n')
                cells.append('\n'.join(' '.join(line.split()) for line in content.get_text().splitlines() if line.strip()))
            if any(cells):
                rows.append(cells)
        if rows:
            result.append({'caption': text(table.find('caption', recursive=False)), 'rows': rows})
    return result


def files(container):
    result = {}
    for a in container.select('a[href]'):
        url = site_url(a['href'])
        if url and re.search(r'/(?:pluginfile|webservice/pluginfile)\.php/', urlparse(url).path):
            result[url] = {'name': text(a), 'url': url}
    return list(result.values())


def parse_course(html, url=None):
    s = soup(html)
    classes = s.body.get('class', []) if s.body else []
    cid = next((int(c[7:]) for c in classes if re.fullmatch(r'course-\d+', c)), None)
    if url and number(url):
        cid = number(url)
    title = text(s.select_one('h1')) or text(s.title).split('|')[0].strip()
    activities = {}
    for item in s.select('.activity'):
        a = item.select_one('.activityname a[href], a[href*="/mod/"]')
        link = site_url(a['href']) if a else None
        match = re.search(r'/mod/([^/]+)/', link or '')
        card = item.select_one('[data-activityname]')
        name = card.get('data-activityname') if card else text(item.select_one('.instancename'))
        section = item.find_parent(class_='course-section') or item.find_parent('li', class_='section')
        dates = [{'timestamp': n.get('data-timestamp'), 'text': text(n)}
                 for n in item.select('[data-timestamp]')]
        aid = number(link) if link else None
        completion = item.select_one('[data-toggletype]')
        record = {'id': aid, 'type': match[1] if match else 'label',
                  'name': name or text(a), 'url': link,
                  'section': text(section.select_one('.sectionname')) if section else '',
                  'description': text(item.select_one('.contentafterlink, .description')),
                  'dates': dates,
                  'completion': completion.get('data-toggletype') if completion else None}
        activities[link or item.get('id') or str(len(activities))] = record
    activities = list(activities.values())
    return {'id': cid, 'name': title, 'url': url or f'{BASE}/course/view.php?id={cid}',
            'activities': activities,
            'homework': [a for a in activities if a['type'] in ('assign', 'quiz', 'workshop')],
            'files': files(s.select_one('#region-main') or s)}


def reject_error_page(document):
    main = document.select_one('#region-main') or document.select_one('main') or document
    if (document.body and document.body.get('id') == 'page-error') or main.select_one('.errorbox'):
        raise RuntimeError('Moodle returned an error page; check access in the browser.')


def parse_detail(html):
    s = soup(html)
    reject_error_page(s)
    main = s.select_one('#region-main') or s.select_one('main') or s
    for node in main.select('script, style, noscript'):
        node.decompose()
    return {'title': text(s.select_one('h1')) or text(s.title),
            'description': text(main.select_one('.activity-description, .box.generalbox')),
            'dates': [{'timestamp': n.get('data-timestamp'), 'text': text(n)}
                      for n in main.select('[data-timestamp]')],
            'tables': tables(main), 'files': files(main), 'text': text(main)}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


def offline(args):
    result = {'source': 'saved_html', 'exported_at': datetime.now(timezone.utc).isoformat(),
              'courses': [], 'discovered_courses': [], 'pages': []}
    found = {}
    for p in sorted(args.directory.glob('*.html')):
        html = p.read_text(encoding='utf-8-sig', errors='replace')
        s = soup(html)
        found.update(courses(html))
        body_id = s.body.get('id', '') if s.body else ''
        if body_id.startswith('page-course-view'):
            result['courses'].append(parse_course(html))
        elif body_id.startswith(('page-mod-', 'page-grade-')):
            result['pages'].append({'file': p.name, 'detail': parse_detail(html)})
    result['discovered_courses'] = list(found.values())
    result['limitations'] = ['Saved HTML only: no fresh data or reusable login session.',
                            'Grade and assignment detail pages are included only if supplied.']
    write_json(args.output, result)
    print(f'Exported {len(result["courses"])} saved course pages to {args.output}')


class LoginRequired(RuntimeError):
    pass


def navigation_in_progress(exc):
    message = str(exc).lower()
    return any(fragment in message for fragment in (
        'execution context was destroyed',
        'cannot find context with specified id',
        'most likely because of a navigation',
    ))


@contextmanager
def tolerate_navigation():
    try:
        yield
    except PlaywrightError as exc:
        if not navigation_in_progress(exc):
            raise


class Client:
    def __init__(self, args):
        from playwright.sync_api import sync_playwright
        self.args = args
        self.runtime = sync_playwright().start()
        self.context = None
        try:
            self.context = self.runtime.chromium.launch_persistent_context(
                str(args.profile.resolve()), headless=getattr(args, 'headless', False),
                accept_downloads=False, viewport={'width': 1400, 'height': 950})
            self.page = self.context.pages[0] if self.context.pages else self.context.new_page()
            self.page.set_default_timeout(15000)
        except Exception:
            self.runtime.stop()
            raise

    def close(self):
        if self.context:
            self.context.close()
        self.runtime.stop()

    def authenticated(self):
        if urlparse(self.page.url).netloc != urlparse(BASE).netloc:
            return False
        try:
            # Check the origin inside the evaluation too: the URL can change
            # between the Python-side check and this browser operation.
            return self.page.evaluate('''() => location.origin === 'https://lemida.biu.ac.il'
                && !!document.body
                && !document.body.classList.contains('notloggedin')
                && !!document.querySelector('a[href*="/login/logout.php"]')''')
        except PlaywrightError as exc:
            if navigation_in_progress(exc):
                return False
            raise

    def login(self):
        self.page.goto(BASE + '/my/', wait_until='domcontentloaded', timeout=60000)
        if self.authenticated():
            print('Existing session is signed in.')
            return
        if getattr(self.args, 'headless', False):
            raise LoginRequired('No active session. Run login in a visible browser first.')
        # The entry point is observed in the provided homepage, rather than a replayed OAuth URL.
        if urlparse(self.page.url).netloc == urlparse(BASE).netloc:
            entry = self.page.locator('a[href*="/auth/multioauth/login.php"]').first
            if entry.count():
                entry.click()
        print('Complete Microsoft sign-in in the browser, including Authenticator approval')
        print('or SMS verification if your account offers it. Waiting for Moodle to return...')
        deadline = time.monotonic() + self.args.login_timeout
        switched = False
        sms_selected = False
        code_prompted = False
        while time.monotonic() < deadline:
            if self.authenticated():
                print('Signed in. The browser profile will preserve the session.')
                return
            with tolerate_navigation():
                if urlparse(self.page.url).hostname == 'login.microsoftonline.com':
                    if self.args.mfa == 'sms' and not switched:
                        alternative = self.page.locator('#signInAnotherWay')
                        if alternative.count() and alternative.is_visible():
                            alternative.click()
                            switched = True
                    if self.args.mfa == 'sms' and not sms_selected:
                        choice = self.page.locator('[data-value="OneWaySMS"]').first
                        if not choice.count():
                            choice = self.page.get_by_role('button', name=re.compile(r'^(Text|שלח.*SMS)', re.I)).first
                        if choice.count() and choice.is_visible():
                            # Mark before clicking so a navigation error cannot
                            # cause another automatic SMS request.
                            sms_selected = True
                            choice.click()
                            print('Selected SMS verification. No automatic resend will be requested.')
                    otp = self.page.locator('#idTxtBx_SAOTCC_OTC')
                    if otp.count() and otp.is_visible() and not code_prompted:
                        code_prompted = True
                        print('SMS code entry detected. Enter the code in the browser.')
                        if self.args.console_sms:
                            code = getpass.getpass('SMS verification code (hidden): ').strip()
                            if not re.fullmatch(r'\d{6,8}', code):
                                raise ValueError('Expected a 6–8 digit code. Continue in the browser or rerun login.')
                            otp.fill(code)
                            self.page.locator('#idSubmit_SAOTCC_Continue').click()
                            del code
            self.page.wait_for_timeout(1000)
        raise LoginRequired('Login timed out. Run login again and complete sign-in in the browser.')

    def fetch(self, url):
        if not site_url(url):
            raise ValueError('Only same-site HTTPS pages are fetched.')
        response = self.page.goto(url, wait_until='domcontentloaded', timeout=60000)
        if not self.authenticated():
            raise LoginRequired('Session expired. Run login again, then rerun sync.')
        if response and response.status >= 400:
            raise RuntimeError(f'Page returned HTTP {response.status}')
        html = self.page.content()
        s = soup(html)
        reject_error_page(s)
        return html

    def ajax(self, method, arguments):
        result = self.page.evaluate("""async ({method, arguments}) => {
            if (location.origin !== 'https://lemida.biu.ac.il' || !window.M?.cfg?.sesskey)
                return {loginRequired: true};
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 45000);
            try {
                const response = await fetch('/lib/ajax/service.php?sesskey=' + encodeURIComponent(M.cfg.sesskey), {
                    method: 'POST', credentials: 'same-origin', signal: controller.signal,
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify([{index: 0, methodname: method, args: arguments}])
                });
                if (new URL(response.url).origin !== location.origin)
                    return {verificationRequired: true};
                if (!response.ok) return {httpError: response.status};
                return {envelope: await response.json()};
            } finally { clearTimeout(timeout); }
        }""", {'method': method, 'arguments': arguments})
        if result.get('loginRequired'):
            raise LoginRequired('Session expired. Run login again, then rerun sync.')
        if result.get('verificationRequired'):
            raise LoginRequired('Complete Lemida browser verification using the login command.')
        if 'httpError' in result:
            raise RuntimeError(f"Moodle API returned HTTP {result['httpError']}")
        envelope = result.get('envelope')
        if not isinstance(envelope, list) or len(envelope) != 1 or not isinstance(envelope[0], dict):
            raise RuntimeError('Unexpected Moodle API response; previous export is preserved.')
        response = envelope[0]
        if response.get('error'):
            code = response.get('exception', {}).get('errorcode', 'unknown')
            if code in ('invalidsesskey', 'requireloginerror', 'servicerequireslogin'):
                raise LoginRequired('Session expired. Run login again, then rerun sync.')
            raise RuntimeError(f'Moodle API is unavailable ({code}).')
        return response['data']

    def discover(self):
        self.fetch(BASE + '/my/')
        found, offset = {}, 0
        for _ in range(100):
            data = self.ajax('core_course_get_enrolled_courses_by_timeline_classification', {
                'classification': 'allincludinghidden', 'limit': 50, 'offset': offset, 'sort': 'fullname'})
            if not isinstance(data, dict):
                raise RuntimeError('Unexpected Moodle course data; previous export is preserved.')
            batch, next_offset = data.get('courses'), data.get('nextoffset')
            if (not isinstance(batch, list) or type(next_offset) is not int or next_offset < offset
                    or (next_offset == offset and batch)):
                raise RuntimeError('Course pagination did not finish; previous export is preserved.')
            for course in batch:
                cid = int(course['id'])
                found[cid] = {'id': cid, 'name': text(soup(course['fullname'])),
                              'url': f'{BASE}/course/view.php?id={cid}'}
            if next_offset == offset:
                return found
            offset = next_offset
        raise RuntimeError('Course pagination did not finish; previous export is preserved.')


def live(args):
    client = Client(args)
    try:
        client.login()
        if args.command == 'login':
            return
        selected = {cid: {'id': cid, 'url': f'{BASE}/course/view.php?id={cid}'}
                    for cid in args.course_id} if args.course_id else client.discover()
        if not selected:
            raise RuntimeError('No courses found. Supply --course-id from a course URL.')
        result = {'source': BASE, 'exported_at': datetime.now(timezone.utc).isoformat(),
                  'courses': [], 'errors': []}
        for cid, course in selected.items():
            print(f'Reading course {cid}...')
            try:
                record = parse_course(client.fetch(course['url']), course['url'])
                result['courses'].append(record)
            except LoginRequired:
                write_json(args.output, result)
                raise
            except Exception as exc:
                result['errors'].append({'course_id': cid, 'stage': 'course', 'error': str(exc)})
                continue
            targets = [('grades', f'{BASE}/grade/report/user/index.php?id={cid}')]
            targets += [('activity', a['url']) for a in record['homework'] if a['url']]
            for kind, url in targets:
                try:
                    detail = parse_detail(client.fetch(url))
                    if kind == 'grades':
                        record['grades'] = detail
                    else:
                        next(a for a in record['activities'] if a['url'] == url)['detail'] = detail
                except LoginRequired:
                    write_json(args.output, result)
                    raise
                except Exception as exc:
                    result['errors'].append({'course_id': cid, 'url': url, 'stage': kind, 'error': str(exc)})
                client.page.wait_for_timeout(300)
            write_json(args.output, result)
        print(f'Exported {len(result["courses"])} courses to {args.output}; {len(result["errors"])} errors.')
        if result['errors']:
            return 2
    finally:
        client.close()


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('offline', help='Extract saved HTML without connecting to the site')
    p.add_argument('--directory', type=Path, default=ROOT)
    p.add_argument('--output', type=Path, default=ROOT / 'exports' / 'saved_pages.json')
    for command in ('login', 'sync'):
        p = sub.add_parser(command)
        p.add_argument('--profile', type=Path, default=ROOT / '.lemida-profile')
        p.add_argument('--login-timeout', type=int, default=600)
        p.add_argument('--mfa', choices=('manual', 'sms'), default='sms',
                       help='Prefer SMS when Microsoft offers that verification method')
        p.add_argument('--console-sms', action='store_true',
                       help='Prompt for the SMS code in the terminal instead of the browser')
        if command == 'sync':
            p.add_argument('--course-id', type=int, action='append', default=[])
            p.add_argument('--headless', action='store_true', help='Requires a valid saved session')
            p.add_argument('--output', type=Path, default=ROOT / 'exports' / 'live.json')
    args = parser.parse_args()
    try:
        return offline(args) if args.command == 'offline' else live(args)
    except KeyboardInterrupt:
        print('Stopped.', file=sys.stderr)
        return 130
    except Exception as exc:
        print(f'{type(exc).__name__}: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main() or 0)
