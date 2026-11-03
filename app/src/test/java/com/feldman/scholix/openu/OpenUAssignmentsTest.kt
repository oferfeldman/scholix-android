package com.feldman.scholix.openu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OpenUAssignmentsTest {
    private fun course(term: String = "2026c") = JSONObject().put("id", "20476").put("name", "Discrete Mathematics")
        .put("term", term).put("courseKey", "20476:$term")
    private val list = """<body class="course-54812"><ul id="ouilassign_index_list_container"><li>
        <div index-item="name"><a href="/mod/ouilassign/view.php?id=6948443"><span>11</span> File assignment</a></div>
        <div index-item="duedate"><span class="ouilassign_index_list_title">Due</span>23/09/2026</div>
        <div index-item="status"><span class="ouilassign_index_list_title">Status</span><span ouilassign-status="wait">Awaiting grading</span></div>
        <div index-item="grade">-</div></li></ul></body>"""
    private fun item() = OpenUAssignmentParser.assignments(list, course(), "54812").single()

    @Test fun parsesUngradedPendingAssignmentAndDateWithoutColumnLabels() {
        val row = item()
        assertEquals("11", row.number)
        assertEquals("23/09/2026", row.due)
        assertEquals("wait", row.statusCode)
        assertEquals("Awaiting grading", row.status)
        assertEquals("ouilassign", row.module)
        assertEquals("6948443", row.cmid)
    }

    @Test fun accountCourseSemesterAndActivityIdentityDoNotUseDisplayNumberAlone() {
        val first = item()
        val repeated = OpenUAssignmentParser.assignments(list, course("2027a"), "54812").single()
        assertNotEquals(first.key, repeated.key)
        assertEquals(first, OpenUAssignment.from(first.json()))
    }

    @Test(expected = IOException::class) fun loginOrUnexpectedPageIsNotAnEmptySuccessfulSnapshot() {
        OpenUAssignmentParser.assignments("<form>Sign in</form>", course(), "54812")
    }

    @Test fun realEmptyListIsSupportedAndUnknownStatusRemainsUnknown() {
        assertTrue(OpenUAssignmentParser.assignments("<ul id=ouilassign_index_list_container></ul>", course(), "54812").isEmpty())
        assertEquals("future-status", OpenUAssignmentParser.assignments(list.replace("wait", "future-status"), course(), "54812").single().statusCode)
    }

    @Test fun freshOpalGradeAndExactActivityQueryWinOverCachedScoreAndSimilarParameterNames() {
        val c = course().put("assignments", org.json.JSONArray().put(JSONObject().put("number", "11").put("grade", "50")))
        val html = list.replace("<div index-item=\"grade\">-", "<div index-item=\"grade\">95")
            .replace("?id=6948443", "?otherid=9&amp;id=6948443")
        val row = OpenUAssignmentParser.assignments(html, c, "54812").single()
        assertEquals("95", row.grade)
        assertEquals("6948443", row.cmid)
    }

    @Test fun retainsReceiptAndDiscoversFilesUploadedOutsideScholix() {
        val html = """<body class="course-54812"><main id=region-main>
          <div id=ouilassign_view_submission_content><div class=ouilassign_submission_info_row>
            <div><span class=ouilassign_section_row_title>Receipt</span>fixture-receipt</div>
            <div><span class=ouilassign_section_row_title>Submitted</span>02/11/2026 23:06</div></div>
            <div class=ouilassign_section_row_file_item><a href="/mod/ouilassign/type/file/viewfile.php/54812/3491/1/hash/1/">Other-client.docx</a></div>
          </div><div id=ouilassign_view_section_assignment><a href="/pluginfile.php/12/task.pdf">Task.pdf</a></div>
          <div class=ouilassign_view_section_feedback><a href="/mod/ouilassign/view/feedback/download.php?submissionid=1">Checked.pdf</a></div>
        </main></body>"""
        val detail = OpenUAssignmentParser.detail(html, item())
        assertTrue(detail.submission.contains("Receipt" to "fixture-receipt"))
        assertEquals(listOf("Original files", "Submitted files", "Checked files"), detail.files.map { it.group })
        assertEquals("Other-client.docx", detail.files[1].name)
        assertEquals(detail, OpenUAssignmentDetail.from(detail.json()))
    }

    @Test(expected = IOException::class) fun rejectsActivityFromDifferentCourse() {
        OpenUAssignmentParser.detail("<body class=course-99><main id=region-main></main></body>", item())
    }

    @Test fun onlySecureOpenUActivityAndAttachmentLinksAreAccepted() {
        assertTrue(OpenUAssignmentParser.fileUrl("https://opal.openu.ac.il/draftfile.php/1/user/draft/2/test.pdf"))
        assertFalse(OpenUAssignmentParser.fileUrl("https://opal.openu.ac.il.evil.test/pluginfile.php/test.pdf"))
        assertFalse(OpenUAssignmentParser.fileUrl("https://opal.openu.ac.il/pluginfile.php/%2e%2e/private"))
        assertFalse(OpenUAssignmentParser.fileUrl("http://opal.openu.ac.il/pluginfile.php/test.pdf"))
        assertFalse(OpenUAssignmentParser.fileUrl("https://opal.openu.ac.il/mod/ouilassign/type/file/submission.php"))
    }

    @Test fun draftReferenceUsesExactWebsiteDraftAndCannotImportSessionTokens() {
        val link = "scholix-openu://draft?course=54812&cmid=6948443&assign=3491&itemid=12345&client=form_abc"
        assertEquals("12345", OpenUDraftReference.parse(link)?.itemId)
        assertNull(OpenUDraftReference.parse("$link&sesskey=secret"))
        assertNull(OpenUDraftReference.parse(link.replace("itemid=12345", "itemid=-1")))
        assertNull(OpenUDraftReference.parse(link.replace("scholix-openu", "https")))
    }
}
