import unittest
from pathlib import Path
from unittest.mock import Mock

import lemida


class RedirectHandling(unittest.TestCase):
    def test_overdue_warning_is_not_a_failed_fetch(self):
        client = lemida.Client.__new__(lemida.Client)
        client.page = Mock()
        client.page.goto.return_value.status = 200
        client.page.content.return_value = '<main><div class="alert-danger">Submission overdue</div></main>'
        client.authenticated = Mock(return_value=True)
        self.assertIn('Submission overdue', client.fetch(lemida.BASE + '/mod/assign/view.php?id=9'))

    def test_authenticated_error_page_is_a_failed_fetch(self):
        client = lemida.Client.__new__(lemida.Client)
        client.page = Mock()
        client.page.goto.return_value.status = 200
        client.page.content.return_value = '<main><div class="errorbox">Activity unavailable</div></main>'
        client.authenticated = Mock(return_value=True)
        with self.assertRaises(RuntimeError):
            client.fetch(lemida.BASE + '/mod/assign/view.php?id=9')

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
    def test_nested_grade_tables_are_not_duplicated(self):
        result = lemida.parse_detail('''<main><table><tr><th>Feedback</th><td>Teacher summary
            <table><tr><th>Grade</th><td>95 / 100</td></tr></table></td></tr></table></main>''')
        self.assertEqual([table['rows'] for table in result['tables']],
                         [[['Feedback', 'Teacher summary']], [['Grade', '95 / 100']]])

    def test_grade_captions_and_feedback_lines_are_preserved(self):
        result = lemida.parse_detail('''<main><table><caption>Teacher feedback</caption>
            <tr><th>הערות</th><td><p>First comment</p><p>Second comment<br>Final line</p></td></tr>
            </table></main>''')
        self.assertEqual(result['tables'][0]['caption'], 'Teacher feedback')
        self.assertEqual(result['tables'][0]['rows'], [['הערות', 'First comment\nSecond comment\nFinal line']])

    def test_empty_wrapper_does_not_hide_grade_caption_or_blank_value(self):
        result = lemida.parse_detail('''<main><table><tr><td><table><caption>Final grade</caption>
            <tr><th>Grade</th><td></td></tr></table></td></tr></table></main>''')
        self.assertEqual(result['tables'], [{'caption': 'Final grade', 'rows': [['Grade', '']]}])

    def test_error_pages_are_not_successful_details(self):
        for html in ('<main><div class="errorbox">Missing activity</div></main>',
                     '<body id="page-error"><main>Unavailable</main></body>'):
            with self.subTest(html=html), self.assertRaises(RuntimeError):
                lemida.parse_detail(html)

    def test_activity_ids_require_one_complete_positive_parameter(self):
        self.assertEqual(lemida.number('/mod/assign/view.php?forceview=1&id=9#intro'), 9)
        for query in ('id=9junk', 'id=9&id=10', 'id=9&id=', 'id=0', 'id=-1', 'id=%ZZ'):
            with self.subTest(query=query):
                self.assertIsNone(lemida.number('/mod/assign/view.php?' + query))

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
