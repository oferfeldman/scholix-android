import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import lemida


class ExportCommit(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name) / 'live.json'
        self.previous = '{"courses": [{"name": "מתמטיקה", "grades": "previous complete snapshot"}]}'
        self.output.write_text(self.previous, encoding='utf-8')
        self.args = SimpleNamespace(command='sync', course_id=[1, 2], output=self.output)
        self.client = Mock()
        self.client.fetch.side_effect = self.page

    @staticmethod
    def page(url):
        if '/course/view.php' in url:
            cid = lemida.number(url)
            return f'''<body class="course-{cid}"><h1>Course {cid}</h1>
                <div class="activity"><div class="activityname">
                <a href="/mod/assign/view.php?id={cid * 11}">Exercise {cid}</a>
                </div></div></body>'''
        return '<main id="region-main"><div id="intro">Instructions</div><table><tr><th>Grade</th><td>95</td></tr></table></main>'

    def sync(self):
        with patch('lemida.Client', return_value=self.client), redirect_stdout(io.StringIO()):
            return lemida.live(self.args)

    def test_session_expiry_after_first_course_preserves_complete_export(self):
        def fetch(url):
            if url == lemida.BASE + '/course/view.php?id=2':
                raise lemida.LoginRequired('Expired fixture session')
            return self.page(url)
        self.client.fetch.side_effect = fetch
        with self.assertRaises(lemida.LoginRequired):
            self.sync()
        self.assertEqual(self.output.read_text(encoding='utf-8'), self.previous)
        failed = json.loads(self.output.with_name('live.failed.json').read_text(encoding='utf-8'))
        self.assertEqual([c['id'] for c in failed['courses']], [1])
        self.assertEqual(failed['errors'][0]['stage'], 'session')
        self.client.close.assert_called_once()

    def test_course_failure_cannot_publish_an_incomplete_course_list(self):
        def fetch(url):
            if url == lemida.BASE + '/course/view.php?id=2':
                raise RuntimeError('Course temporarily unavailable')
            return self.page(url)
        self.client.fetch.side_effect = fetch
        self.assertEqual(self.sync(), 2)
        self.assertEqual(self.output.read_text(encoding='utf-8'), self.previous)
        failed = json.loads(self.output.with_name('live.failed.json').read_text(encoding='utf-8'))
        self.assertEqual(failed['errors'][0]['stage'], 'course')
        self.client.close.assert_called_once()

    def test_failed_grade_or_homework_read_preserves_previous_details(self):
        for failed_url, stage in (
            (lemida.BASE + '/grade/report/user/index.php?id=2', 'grades'),
            (lemida.BASE + '/mod/assign/view.php?id=22', 'activity'),
        ):
            with self.subTest(stage=stage):
                def fetch(url):
                    if url == failed_url:
                        raise RuntimeError('Detail temporarily unavailable')
                    return self.page(url)
                self.client.fetch.side_effect = fetch
                self.assertEqual(self.sync(), 2)
                self.assertEqual(self.output.read_text(encoding='utf-8'), self.previous)
                failed = json.loads(self.output.with_name('live.failed.json').read_text(encoding='utf-8'))
                self.assertEqual([c['id'] for c in failed['courses']], [1, 2])
                self.assertEqual(failed['errors'][0]['stage'], stage)

    def test_complete_sync_publishes_once_after_all_course_and_detail_reads(self):
        reads_at_write = []
        actual_write = lemida.write_json
        def record_write(path, data):
            reads_at_write.append(self.client.fetch.call_count)
            actual_write(path, data)
        with patch('lemida.write_json', side_effect=record_write):
            self.assertIsNone(self.sync())
        self.assertEqual(reads_at_write, [6])
        exported = json.loads(self.output.read_text(encoding='utf-8'))
        self.assertEqual([c['id'] for c in exported['courses']], [1, 2])
        self.assertEqual(exported['errors'], [])
        self.assertEqual(exported['courses'][1]['grades']['tables'][0]['rows'], [['Grade', '95']])
        self.assertEqual(exported['courses'][1]['homework'][0]['detail']['description'], 'Instructions')
        self.assertFalse(self.output.with_name('live.failed.json').exists())
        self.client.close.assert_called_once()

    def test_failed_file_replace_preserves_previous_bytes_and_removes_temp(self):
        with patch('os.replace', side_effect=OSError('Interrupted fixture publish')):
            with self.assertRaises(OSError):
                lemida.write_json(self.output, {'courses': []})
        self.assertEqual(self.output.read_text(encoding='utf-8'), self.previous)
        self.assertEqual(list(self.output.parent.iterdir()), [self.output])


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
            {'courses': [{'id': 1, 'fullname': 'מתמטיקה &amp; לוגיקה'}], 'nextoffset': 1},
            {'courses': [{'id': 2, 'fullname': 'Second'}], 'nextoffset': 2},
            {'courses': [], 'nextoffset': 2},
        ])
        found = client.discover()
        self.assertEqual(set(found), {1, 2})
        self.assertEqual(found[1]['name'], 'מתמטיקה & לוגיקה')
        self.assertEqual([call.args[1]['offset'] for call in client.ajax.call_args_list], [0, 1, 2])

    def test_nonempty_stalled_page_cannot_complete_discovery(self):
        client = lemida.Client.__new__(lemida.Client)
        client.fetch = Mock()
        client.ajax = Mock(return_value={'courses': [{'id': 1, 'fullname': 'Math'}], 'nextoffset': 0})
        with self.assertRaisesRegex(RuntimeError, 'previous export is preserved'):
            client.discover()
        self.assertEqual(client.ajax.call_count, 1)

    def test_backwards_offset_cannot_commit_a_partial_course_list(self):
        client = lemida.Client.__new__(lemida.Client)
        client.fetch = Mock()
        for courses in ([], [{'id': 2, 'fullname': 'Second'}]):
            with self.subTest(courses=courses):
                client.ajax = Mock(side_effect=[
                    {'courses': [{'id': 1, 'fullname': 'Math'}], 'nextoffset': 1},
                    {'courses': courses, 'nextoffset': 0},
                ])
                with self.assertRaisesRegex(RuntimeError, 'previous export is preserved'):
                    client.discover()

    def test_malformed_pagination_is_rejected_without_coercion(self):
        client = lemida.Client.__new__(lemida.Client)
        client.fetch = Mock()
        invalid = [{'courses': [], 'nextoffset': value} for value in (-1, 1.5, '1', True, None)]
        invalid += [{'courses': []}, {'courses': None, 'nextoffset': 0}, []]
        for data in invalid:
            with self.subTest(data=data):
                client.ajax = Mock(return_value=data)
                with self.assertRaisesRegex(RuntimeError, 'previous export is preserved'):
                    client.discover()

    def test_empty_first_page_completes_and_page_limit_still_fails(self):
        client = lemida.Client.__new__(lemida.Client)
        client.fetch = Mock()
        client.ajax = Mock(return_value={'courses': [], 'nextoffset': 0})
        self.assertEqual(client.discover(), {})
        client.ajax = Mock(side_effect=lambda method, args: {'courses': [], 'nextoffset': args['offset'] + 1})
        with self.assertRaisesRegex(RuntimeError, 'previous export is preserved'):
            client.discover()
        self.assertEqual(client.ajax.call_count, 100)

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
    def test_instructions_keep_question_numbers_and_explicit_restarts(self):
        result = lemida.parse_detail('''<main><div id="intro"><ol start="3">
            <li>Prove</li><li value="8">Calculate</li><li>Explain</li></ol></div></main>''')
        self.assertEqual(result['description'], '3. Prove\n8. Calculate\n9. Explain')

    def test_nested_instructions_keep_descending_numbers_and_separate_bullets(self):
        result = lemida.parse_detail('''<main><div id="intro"><ol reversed>
            <li>הוכיחו<ul><li>Explain</li><li>Check</li></ul>Then submit</li>
            <li>Next</li><li>Last</li></ol></div></main>''')
        self.assertEqual(result['description'], '3. הוכיחו\n• Explain\n• Check\nThen submit\n2. Next\n1. Last')

    def test_instructions_preserve_letter_and_roman_references_without_duplicate_wrappers(self):
        result = lemida.parse_detail('''<main><div class="activity-description"><div id="intro">
            <ol type="A" start="26"><li>Choose<ol type="i" start="4"><li>Proof</li><li>Check</li></ol></li>
            <li>Submit</li></ol><ol type="I" start="9"><li>Review</li></ol></div></div></main>''')
        self.assertEqual(result['description'], 'Z. Choose\niv. Proof\nv. Check\nAA. Submit\nIX. Review')

    def test_invalid_and_nonpositive_counters_keep_readable_fallbacks(self):
        result = lemida.parse_detail('''<main><div id="intro"><ol type="a" start="invalid">
            <li value="invalid">First</li><li value="-1">Before</li><li>Zero</li><li value=" +4tail">Fourth</li>
            </ol><ol type="I" start="4000"><li>Large</li></ol>
            <ol start="9223372036854775807"><li>Maximum</li><li>Following</li></ol></div></main>''')
        self.assertEqual(result['description'],
                         'a. First\n-1. Before\n0. Zero\nd. Fourth\n4000. Large\n'
                         '9223372036854775807. Maximum\n9223372036854775808. Following')

    def test_feedback_lists_and_fallback_instructions_keep_numbering(self):
        result = lemida.parse_detail('''<main><p>Instructions</p><ol><li>Read</li><li>Submit</li></ol>
            <table><tr><th>Feedback</th><td><ol start="2"><li>Fix proof</li><li>Explain</li></ol>
            </td></tr></table></main>''')
        self.assertEqual(result['description'], '')
        self.assertTrue(result['text'].startswith('Instructions\n1. Read\n2. Submit\nFeedback'))
        self.assertEqual(result['tables'][0]['rows'], [['Feedback', '2. Fix proof\n3. Explain']])

    def test_unordered_lists_ignore_ordered_counter_attributes(self):
        result = lemida.parse_detail('''<main><div id="intro"><ul start="9"><li value="12">Read</li>
            <li>Submit</li></ul></div></main>''')
        self.assertEqual(result['description'], '• Read\n• Submit')

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
