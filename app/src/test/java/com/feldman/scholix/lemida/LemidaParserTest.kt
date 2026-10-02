package com.feldman.scholix.lemida

import org.junit.Assert.*
import org.junit.Test

class LemidaParserTest {
    @Test fun activityQueryOrderingAndFragmentsDoNotChangeIdentity() {
        val url = "https://lemida.biu.ac.il/mod/assign/view.php?forceview=1&id=900#submission"
        val html = page().replace("https://lemida.biu.ac.il/mod/assign/view.php?id=900", url.replace("&", "&amp;"))
        val fromHtml = LemidaParser.homework(html, 123, "Math").single()
        val state = """{"cm":[{"name":"Exercise","url":"$url"}]}"""
        val fromApi = LemidaParser.stateHomework(state, 123, "Math").single()
        assertEquals("123:assign:900", fromHtml.id)
        assertEquals(fromHtml.id, fromApi.id)
        assertEquals(url.substringBefore('#'), fromApi.url)
    }
    @Test fun malformedOrAmbiguousActivityLinksAreIgnored() {
        val invalid = listOf(
            "https://lemida.biu.ac.il/mod/assign/view.php?id=900junk",
            "https://lemida.biu.ac.il/mod/assign/view.php?id=900&id=901",
            "https://lemida.biu.ac.il/mod/assign/view.php?id=0",
            "https://lemida.biu.ac.il/mod/assign/view.php?id=-1",
            "https://lemida.biu.ac.il/mod/assign/view.php?id=%ZZ",
            "https://lemida.biu.ac.il/mod/assign/view.php.extra?id=900",
            "https://lemida.biu.ac.il:444/mod/assign/view.php?id=900",
            "https://user@lemida.biu.ac.il/mod/assign/view.php?id=900",
            "http://lemida.biu.ac.il/mod/assign/view.php?id=900",
            "https://lemida.biu.ac.il.example/mod/assign/view.php?id=900",
        )
        invalid.forEach { url ->
            val state = """{"cm":[{"name":"Exercise","url":"$url"}]}"""
            assertTrue(url, LemidaParser.stateHomework(state, 123, "Math").isEmpty())
            val html = page().replace("https://lemida.biu.ac.il/mod/assign/view.php?id=900", url.replace("&", "&amp;"))
            assertTrue(url, LemidaParser.homework(html, 123, "Math").isEmpty())
        }
    }
    @Test fun authenticatedErrorPagesCannotBecomeCachedInstructions() {
        val html = """<body><a href="/login/logout.php">Logout</a><main id="region-main">
            <div class="errorbox">Activity unavailable</div></main></body>"""
        assertTrue(LemidaParser.authenticated(html))
        assertThrows(java.io.IOException::class.java) { LemidaParser.detail(html) }
        assertThrows(java.io.IOException::class.java) {
            LemidaParser.detail("""<body id="page-error"><main id="region-main">Error</main></body>""")
        }
    }
    @Test fun ordinaryHomeworkWarningsRemainReadable() {
        val detail = LemidaParser.detail("""<main id="region-main"><div id="intro">Submit a PDF</div>
            <div class="alert alert-danger">Submission is overdue</div></main>""")
        assertEquals("Submit a PDF", detail.description)
        assertTrue(detail.text.contains("Submission is overdue"))
    }
    @Test fun combinedSearchMatchesWordsAcrossCourseAndTitleInAnyOrder() {
        val item = Homework("1:assign:9", 1, "Linear Algebra", "Exercise 10 — וקטורים", "assign", "", "")
        assertTrue(item.matchesSearch("  ALGEBRA  exercise ", "וקטורים 10"))
        assertTrue(item.matchesSearch("", "  "))
        assertFalse(item.matchesSearch("exercise", "quiz"))
    }
    @Test fun queuedAlertsUseCurrentTitlesAndDiscardWithdrawnHomework() {
        val old = Homework("1:assign:9", 1, "Math", "Old title", "assign", "", "")
        val updated = old.copy(title = "Corrected title", dates = "Due Friday")
        val withdrawn = old.copy(id = "1:assign:10", title = "Withdrawn")
        assertEquals(listOf(updated), LemidaParser.pendingAlerts(listOf(updated), setOf(old.id, withdrawn.id), listOf(old, withdrawn)))
    }
    @Test fun pendingAndNewHomeworkAreAnnouncedOnlyOnce() {
        val item = Homework("1:assign:9", 1, "Math", "Exercise", "assign", "", "")
        assertEquals(listOf(item), LemidaParser.pendingAlerts(listOf(item), emptySet(), listOf(item)))
        assertTrue(LemidaParser.pendingAlerts(listOf(item), null, emptyList()).isEmpty())
    }
    @Test fun nestedInstructionContainersDoNotDuplicateParagraphs() {
        val detail = LemidaParser.detail("""<main id="region-main"><div class="activity-description">
            <div id="intro" class="generalbox"><p>Submit a PDF</p></div></div></main>""")
        assertEquals("Submit a PDF", detail.description)
    }
    @Test fun instructionsPreserveParagraphsAndListItems() {
        val detail = LemidaParser.detail("""<main id="region-main"><div id="intro">
            <p>הנחיות הגשה</p><p>Upload a PDF<br>Include your name</p>
            <ul><li>First question</li><li>Second question</li></ul></div></main>""")
        assertEquals("הנחיות הגשה\nUpload a PDF\nInclude your name\n• First question\n• Second question", detail.description)
    }
    @Test fun jsonActivityStateFiltersHomeworkAndPreservesStableIds() {
        val state = """{"cm":[
            {"id":"9","name":"תרגיל &amp; תשובות","url":"https://lemida.biu.ac.il/mod/assign/view.php?id=9","uservisible":true},
            {"id":"10","name":"Hidden","url":"https://lemida.biu.ac.il/mod/quiz/view.php?id=10","uservisible":false},
            {"id":"11","name":"PDF","url":"https://lemida.biu.ac.il/mod/resource/view.php?id=11"},
            {"id":"12","name":"External","url":"https://other.example/mod/assign/view.php?id=12"}
        ]}"""
        val items = LemidaParser.stateHomework(state, 123, "Math")
        assertEquals(1, items.size)
        assertEquals("123:assign:9", items.single().id)
        assertEquals("תרגיל & תשובות", items.single().title)
    }
    @Test fun detailsPreserveHebrewInstructionsAndSubmissionStatus() {
        val detail = LemidaParser.detail("""<main id="region-main"><div id="intro">הגישו את תרגיל 1</div>
            <table><tr><th>מצב הגשה</th><td>הוגש</td></tr><tr><th>ציון</th><td>95 / 100</td></tr></table>
            <form><input value="secret"><button>Submit</button></form><script>secret</script></main>""")
        assertEquals("הגישו את תרגיל 1", detail.description)
        assertEquals(listOf("ציון", "95 / 100"), detail.tables.single()[1])
        assertFalse(detail.text.contains("secret"))
        assertEquals(detail, HomeworkDetail.fromJson(detail.json().toString()))
    }
    private fun page(title: String = "תרגיל 1", id: Int = 900) = """
        <body id="page-course-view-topics"><a href="/login/logout.php?sesskey=fixture">Logout</a>
        <li class="activity"><div data-activityname="$title"></div><div class="activityname">
        <a href="https://lemida.biu.ac.il/mod/assign/view.php?id=$id">$title</a></div>
        <div data-region="activity-dates">Due: Friday</div></li>
        <li class="activity"><div class="activityname"><a href="https://lemida.biu.ac.il/mod/resource/view.php?id=45">PDF</a></div></li>
        <li class="activity"><div class="activityname"><a href="https://other.example/mod/assign/view.php?id=9">external</a></div></li>
        </body>"""

    @Test fun parsesHebrewHomeworkAndDatesWithoutResources() {
        val items = LemidaParser.homework(page(), 123, "מתמטיקה")
        assertEquals(1, items.size)
        assertEquals("תרגיל 1", items.single().title)
        assertEquals("123:assign:900", items.single().id)
        assertEquals("Due: Friday", items.single().dates)
        assertEquals(items, LemidaParser.decode(LemidaParser.encode(items)))
    }
    @Test fun firstSyncEstablishesSilentBaseline() {
        assertTrue(LemidaParser.newItems(LemidaParser.homework(page(), 123, "Math"), null).isEmpty())
    }
    @Test fun newIdAlertsButRenameDoesNot() {
        val old = LemidaParser.homework(page(), 123, "Math").map { it.id }.toSet()
        assertTrue(LemidaParser.newItems(LemidaParser.homework(page("Renamed"), 123, "Math"), old).isEmpty())
        assertEquals(1, LemidaParser.newItems(LemidaParser.homework(page(id = 901), 123, "Math"), old).size)
    }
    @Test fun identicalModuleIdInOtherCourseIsDistinct() {
        val seen = LemidaParser.homework(page(), 123, "Math").map { it.id }.toSet()
        assertEquals(1, LemidaParser.newItems(LemidaParser.homework(page(), 124, "Other"), seen).size)
    }
    @Test fun expiredLoginAndUnexpectedPagesAreNotEmptySuccessfulCourses() {
        assertFalse(LemidaParser.authenticated("<body class='notloggedin'>Login</body>"))
        assertTrue(LemidaParser.authenticated(page()))
        assertThrows(IllegalStateException::class.java) {
            LemidaParser.homework("<body id='page-login-index'>Login</body>", 123, "Math")
        }
    }
    @Test fun configSupportsObservedMixedCaseUserId() {
        val html = """<script>M.cfg={"userId":123,"sesskey":"fixture"};</script>"""
        assertEquals("123", LemidaParser.config(html, "userId"))
        assertEquals("fixture", LemidaParser.config(html, "sesskey"))
    }
}
