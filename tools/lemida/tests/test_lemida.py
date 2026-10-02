import unittest
from pathlib import Path
from unittest.mock import Mock

import lemida


class RedirectHandling(unittest.TestCase):
    def test_ajax_expired_session_is_not_an_empty_success(self):
        client = lemida.Client.__new__(lemida.Client)
        client.page = Mock()
        client.page.evaluate.return_value = {'envelope': [{'error': True, 'exception': {'errorcode': 'invalidsesskey'}}]}
        with self.assertRaises(lemida.LoginRequired):
            client.ajax('example', {})

    def test_discovery_uses_api_pagination_and_preserves_hebrew(self):
        client = lemida.Client.__new__(lemida.Client)
        client.fetch = Mock()
        client.ajax = Mock(side_effect=[
            {'courses': [{'id': 1, 'fullname': 'מתמטיקה &amp; לוגיקה'}], 'nextoffset': 50},
            {'courses': [{'id': 2, 'fullname': 'Second'}], 'nextoffset': 0},
        ])
        found = client.discover()
        self.assertEqual(set(found), {1, 2})
        self.assertEqual(found[1]['name'], 'מתמטיקה & לוגיקה')
        self.assertEqual(client.ajax.call_args_list[1].args[1]['offset'], 50)

    def test_authentication_resumes_after_navigation(self):
        client = lemida.Client.__new__(lemida.Client)
        client.page = Mock(url=lemida.BASE + '/my/')
        client.page.evaluate.side_effect = [
            lemida.PlaywrightError('Page.evaluate: Execution context was destroyed, most likely because of a navigation'),
            True,
        ]
        self.assertFalse(client.authenticated())
        self.assertTrue(client.authenticated())

    def test_closed_browser_errors_are_not_hidden(self):
        client = lemida.Client.__new__(lemida.Client)
        client.page = Mock(url=lemida.BASE + '/my/')
        client.page.evaluate.side_effect = lemida.PlaywrightError('Target page, context or browser has been closed')
        with self.assertRaises(lemida.PlaywrightError):
            client.authenticated()

    def test_mfa_probe_tolerates_navigation_but_not_other_errors(self):
        with lemida.tolerate_navigation():
            raise lemida.PlaywrightError('Execution context was destroyed')
        with self.assertRaises(lemida.PlaywrightError):
            with lemida.tolerate_navigation():
                raise lemida.PlaywrightError('Target page has been closed')


class SavedPages(unittest.TestCase):
    def test_supplied_course_activity_counts_and_links(self):
        if not list(lemida.ROOT.glob("*.html")):
            self.skipTest("Private saved course HTML is intentionally excluded from Git")
        found = {}
        for path in lemida.ROOT.glob('*.html'):
            html = path.read_text(encoding='utf-8')
            if 'page-course-view-topics' not in html:
                continue
            course = lemida.parse_course(html)
            found[course['id']] = course
            ids = [a['id'] for a in course['activities'] if a['id']]
            self.assertEqual(len(ids), len(set(ids)))
            for activity in course['homework']:
                self.assertIn(activity['type'], ('assign', 'quiz', 'workshop'))
                self.assertEqual(lemida.number(activity['url']), activity['id'])
        self.assertEqual(set(found), {110304, 110315})
        self.assertEqual(len(found[110304]['activities']), 32)
        self.assertEqual(len(found[110315]['activities']), 77)
        self.assertEqual(sum(len(c['homework']) for c in found.values()), 19)
        self.assertIn('בדידה', found[110315]['name'])

    def test_detail_tables_keep_labels_and_grade_strings(self):
        result = lemida.parse_detail('''<main><h1>מטלה</h1>
            <table><tr><th>ציון</th><td>95 / 100</td></tr>
            <tr><th>סטטוס</th><td>הוגש</td></tr></table>
            <a href="/pluginfile.php/123/mod_assign/intro/test.pdf">קובץ</a>
            <a href="https://other.example/pluginfile.php/1">external</a>
            <script>secret state</script></main>''')
        self.assertEqual(result['tables'][0]['rows'][0], ['ציון', '95 / 100'])
        self.assertEqual(len(result['files']), 1)
        self.assertNotIn('secret state', result['text'])

    def test_url_scope_and_fragments(self):
        self.assertIsNone(lemida.site_url('https://lemida.biu.ac.il.evil.example/course/view.php?id=1'))
        self.assertIsNone(lemida.site_url('http://lemida.biu.ac.il/'))
        self.assertEqual(lemida.site_url('/course/view.php?id=12#main'),
                         lemida.BASE + '/course/view.php?id=12')

    def test_sms_fixture_controls_and_logged_in_marker(self):
        if not (lemida.ROOT / "Sign in to your account.sms.html").exists():
            self.skipTest("Private sign-in HTML is intentionally excluded from Git")
        sms = lemida.soup((lemida.ROOT / 'Sign in to your account.sms.html').read_text(encoding='utf-8'))
        self.assertEqual(sms.select_one('#idTxtBx_SAOTCC_OTC')['name'], 'otc')
        self.assertEqual(sms.select_one('#idSubmit_SAOTCC_Continue')['type'], 'submit')
        homepage = next(lemida.ROOT.glob('*מחובר.html'))
        signed_in = lemida.soup(homepage.read_text(encoding='utf-8'))
        self.assertTrue(signed_in.select_one('a[href*="/login/logout.php"]'))
        self.assertNotIn('notloggedin', signed_in.body['class'])


if __name__ == '__main__':
    unittest.main()
