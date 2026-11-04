package com.feldman.scholix.openu.course

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OpenUCourseParserTest {
    @Test fun unitsAndActivitiesKeepTheirServerIdentity() {
        val html = """<body class="course-12"><div class="sections-nav-list"><div class="dropdown-menu">
            <a href="/course/view.php?id=12&sectionid=40">Limits</a>
            <a href="/course/view.php?id=12&sectionid=41">Limits</a>
            </div></div><div class="course-content"><li class="activity mod_completed">
            <a href="/mod/quiz/view.php?id=88"><span class="instancename">Practice</span></a>
            </li><li class="activity"><a href="https://other.test/mod/quiz/view.php?id=89">Foreign</a></li></div></body>"""
        val units = OpenUCourseParser.sections(html).objects()
        assertEquals(listOf("40", "41"), units.map { it.getString("id") })
        val activities = OpenUCourseParser.activities(html).objects()
        assertEquals(1, activities.size)
        assertEquals("88", activities.single().getString("id"))
        assertTrue(activities.single().getBoolean("completed"))
    }

    @Test fun lazyNavigationBranchUsesOnlyOpalActivities() {
        val result = OpenUCourseParser.navigationActivities("""{"children":[
            {"name":"Example","link":"/mod/cmpage/view.php?id=7&amp;from=nav","class":"mod_completed"},
            {"name":"Fake","link":"https://other.test/mod/cmpage/view.php?id=8"}]}
        """).objects()
        assertEquals(1, result.size)
        assertEquals("cmpage", result.single().getString("module"))
        assertEquals("7", result.single().getString("id"))
    }

    @Test fun bookConsentIsRequiredUntilThereIsAnActualSignedFile() {
        val books = OpenUCourseParser.books("""<div class="ebook_cell" id="ebook1"><span class="ebook_name">Calculus</span>
            <a class="ebook_digital_link" href="#">Read</a></div>
            <div class="ebook_cell" id="ebook2"><span class="ebook_name">Algebra</span>
            <a class="ebook_digital_link" href-content="/local/ouil_ebook/signedpdf.php?book=2">Read</a></div>""").objects()
        assertTrue(books[0].getBoolean("requiresConsent"))
        assertFalse(books[1].getBoolean("requiresConsent"))
        assertTrue(OpenUCourseParser.documentUrl(books[1].getString("url")))
        assertFalse(OpenUCourseParser.documentUrl("https://opal.openu.ac.il/local/ouil_ebook/ebook_actions.php?action=subscribe"))
    }

    @Test fun bookletFramesAndMenuDeduplicateAndRejectFileTraversal() {
        val items = OpenUCourseParser.booklet("""<frame src="booklet.download?in_file_name=course.pdf">
            <a href="booklet.download?in_file_name=course.pdf">Same</a>
            <a href="booklet.download?in_file_name=..%2Fprivate.pdf">Bad</a>""").objects()
        assertEquals(listOf("course.pdf"), items.map { it.getString("file") })
        assertTrue(OpenUCourseParser.hasBooklet("<a href='booklet.viewbook'>Course booklet</a>"))
        assertFalse(OpenUCourseParser.hasBooklet("<h1>Course</h1>"))
    }

    @Test fun recordingHintsAreReadFromActualPlaylistAndCollectionAttributes() {
        val items = OpenUCourseParser.recordings("""<script>var cfg = {"courseid":"12"};</script>
            <a class="collection_link" cid="4" mid="5" cmid="6">Lectures</a>
            <div id="playlist23" class="ovc_playlist_border"><div class="ovc_playlist_title">Lecture 1</div>
            <span class="pl_instructor">Lecturer</span><span class="pl_recorddate">2026-01-01</span></div>""", "Lectures", "6").objects()
        assertEquals("23", items.single().getString("id"))
        assertEquals("4", items.single().getString("context"))
        assertEquals("12", items.single().getString("course"))
        assertTrue(OpenUCourseParser.hlsUrl("https://video.bynetcdn.com/stream.m3u8"))
        assertFalse(OpenUCourseParser.hlsUrl("https://bynetcdn.com.attacker.test/stream.m3u8"))
        assertFalse(OpenUCourseParser.hlsUrl("http://video.bynetcdn.com/stream.m3u8"))
        assertFalse(OpenUCourseParser.hlsUrl("https://token@video.bynetcdn.com/stream.m3u8"))
        assertTrue(OpenUCourseParser.nativeActivityUrl("https://opal.openu.ac.il/mod/quiz/view.php?id=9"))
        assertFalse(OpenUCourseParser.nativeActivityUrl("https://opal.openu.ac.il/mod/quiz/startattempt.php?id=9"))
        assertFalse(OpenUCourseParser.nativeActivityUrl("https://opal.openu.ac.il/mod/ouilforum/view.php?id=9&sesskey=secret&action=delete"))
    }

    @Test fun forumDetailPreservesPostsAndDoesNotExposeFormsAsActions() {
        val detail = OpenUCourseParser.detail("""<div id="region-main"><article class="ouilforum_post" data-postid="9">
            <h2 class="post_subject">A question</h2><span class="of_post_author"><a>Student</a></span>
            <div class="post_content">Original wording.</div></article><form action="/reply"><input name="sesskey" value="secret">Reply</form></div>""",
            "https://opal.openu.ac.il/mod/ouilforum/view.php?f=3")
        assertEquals("Original wording.", detail.getJSONArray("posts").getJSONObject(0).getString("body"))
        assertFalse(detail.toString().contains("secret"))
    }

    @Test fun quizOverviewNeverCachesAnswerFormsOrStartsAttempts() {
        val detail = OpenUCourseParser.detail("""<div id="region-main"><form id="responseform" action="/mod/quiz/processattempt.php">
            <input type="hidden" name="sesskey" value="secret"><input type="hidden" name="attempt" value="77">
            <div class="que"><span class="qno">1</span><div class="qtext">Choose one</div><div class="answer">
            <div class="r0"><label><input type="radio" name="q1_answer" value="0" checked>First</label></div>
            <div class="r1"><label><input type="radio" name="q1_answer" value="1">Second</label></div>
            </div></div></form></div>""", "https://opal.openu.ac.il/mod/quiz/attempt.php?attempt=77&cmid=4")
        val quiz = detail.getJSONObject("quiz")
        assertEquals("attempt", quiz.getString("state"))
        assertEquals("77", quiz.getString("attempt"))
        assertFalse(detail.toString().contains("q1_answer"))
        assertFalse(detail.toString().contains("secret"))
        assertFalse(quiz.getBoolean("canStart"))
    }

    @Test(expected = IOException::class) fun differentCourseContentIsRejected() {
        OpenUCourseParser.checkCourse("<body class='course-123'>Other course</body>", "12")
    }

    @Test fun richContentKeepsOfficialMathAndImagesWithoutScriptsOrSubmissionForms() {
        val detail = OpenUCourseParser.detail("""<div id="region-main"><div dir="rtl"><p>Original wording.</p>
            <math><mfrac><mi>x</mi><mn>2</mn></mfrac></math><img src="/pluginfile.php/12/figure.png" onerror="evil()">
            <script>evil()</script><form action="/mod/quiz/startattempt.php"><input name="sesskey" value="secret"></form></div></div>""",
            "https://opal.openu.ac.il/mod/cmpage/view.php?id=4")
        val html = detail.getString("html")
        assertTrue(html.contains("Original wording."))
        assertTrue(html.contains("<mfrac>"))
        assertTrue(html.contains("https://opal.openu.ac.il/pluginfile.php/12/figure.png"))
        assertFalse(html.contains("evil"))
        assertFalse(html.contains("<form"))
        assertFalse(html.contains("secret"))
        assertTrue(OpenUCourseParser.containsResource(detail, "https://opal.openu.ac.il/pluginfile.php/12/figure.png"))
        assertFalse(OpenUCourseParser.containsResource(detail, "https://opal.openu.ac.il/pluginfile.php/99/other-course.png"))
    }
}
