package com.feldman.scholix.lemida

import org.junit.Assert.*
import org.junit.Test

class LemidaParserTest {
    @Test fun reconnectUsesOnlyOneObservedUniversityLoginEntry() {
        val link = "/auth/multioauth/login.php?providerid=1"
        assertEquals(LemidaParser.BASE + link, LemidaParser.reconnectUrl("<a href='$link'>Sign in</a><a href='$link'>Again</a>"))
        assertNull(LemidaParser.reconnectUrl("<a href='$link'>First</a><a href='/auth/multioauth/login.php?providerid=2'>Second</a>"))
    }
    @Test fun reconnectFollowsTheObservedPortalAndPrefersAnUnambiguousMicrosoftProvider() {
        val portal = "<a href='/login/index.php'>Sign in</a>"
        assertEquals("${LemidaParser.BASE}/login/index.php", LemidaParser.reconnectUrl(portal))
        assertEquals("${LemidaParser.BASE}/auth/multioauth/login.php?providerid=1",
            LemidaParser.reconnectUrl(portal + "<a href='/auth/multioauth/login.php?providerid=1'>University</a>"))
        assertNull(LemidaParser.reconnectUrl(portal + "<a href='/auth/multioauth/login.php?providerid=1'>First</a>" +
            "<a href='/auth/multioauth/login.php?providerid=2'>Second</a>"))
    }
    @Test fun reconnectCannotFollowExternalOrUnexpectedLoginLinks() {
        listOf("http://lemida.biu.ac.il/auth/multioauth/login.php", "https://example.test/auth/multioauth/login.php",
            "https://lemida.biu.ac.il:444/auth/multioauth/login.php", "https://user@lemida.biu.ac.il/auth/multioauth/login.php",
            "/auth/multioauth/login.php.evil", "/login/logout.php").forEach { link ->
            assertNull(LemidaParser.reconnectUrl("<a href='$link'>Sign in</a>"))
        }
    }
    @Test fun coursePaginationCompletesOnlyOnAnEmptyPageAtTheCurrentOffset() {
        assertEquals(1, LemidaParser.nextCourseOffset(org.json.JSONObject("""{"courses":[{"id":1}],"nextoffset":1}"""), 0))
        assertEquals(2, LemidaParser.nextCourseOffset(org.json.JSONObject("""{"courses":[],"nextoffset":2}"""), 1))
        assertEquals(2, LemidaParser.nextCourseOffset(org.json.JSONObject("""{"courses":[],"nextoffset":2}"""), 2))
        assertEquals(0, LemidaParser.nextCourseOffset(org.json.JSONObject("""{"courses":[],"nextoffset":0}"""), 0))
    }
    @Test fun stalledAndBackwardsCoursePagesCannotCommitPartialSnapshots() {
        listOf("""{"courses":[{"id":1}],"nextoffset":1}""",
            """{"courses":[],"nextoffset":0}""",
            """{"courses":[{"id":2}],"nextoffset":0}""").forEach { raw ->
            assertThrows(java.io.IOException::class.java) { LemidaParser.nextCourseOffset(org.json.JSONObject(raw), 1) }
        }
    }
    @Test fun malformedCourseOffsetsAndBatchesAreRejectedWithoutCoercion() {
        listOf("-1", "1.5", "\"1\"", "true", "null", "2147483648").forEach { value ->
            val data = org.json.JSONObject("""{"courses":[],"nextoffset":$value}""")
            assertThrows(java.io.IOException::class.java) { LemidaParser.nextCourseOffset(data, 0) }
        }
        listOf("""{"courses":[]}""", """{"courses":null,"nextoffset":0}""",
            """{"courses":{},"nextoffset":0}""").forEach { raw ->
            assertThrows(java.io.IOException::class.java) { LemidaParser.nextCourseOffset(org.json.JSONObject(raw), 0) }
        }
    }
    @Test fun nestedGradingRowsBelongToTheirOwnTableOnly() {
        val detail = LemidaParser.detail("""<main id="region-main"><table><tr><th>Feedback</th>
            <td>Teacher summary<table><tr><th>Grade</th><td>95 / 100</td></tr></table></td>
            </tr></table></main>""")
        assertEquals(listOf(listOf(listOf("Feedback", "Teacher summary")),
            listOf(listOf("Grade", "95 / 100"))), detail.tables)
    }
    @Test fun gradingCaptionsAndFeedbackParagraphsSurviveCaching() {
        val detail = LemidaParser.detail("""<main id="region-main"><table><caption>Teacher feedback</caption>
            <tr><th>הערות</th><td><p>First comment</p><p>Second comment<br>Final line</p></td></tr>
            </table></main>""")
        assertEquals(listOf("Teacher feedback"), detail.tableCaptions)
        assertEquals(listOf("הערות", "First comment\nSecond comment\nFinal line"), detail.tables.single().single())
        assertEquals(detail, HomeworkDetail.fromJson(detail.json().toString()))
    }
    @Test fun emptyWrapperTablesDoNotMisalignCaptionsOrDropBlankValues() {
        val detail = LemidaParser.detail("""<main id="region-main"><table><tr><td><table>
            <caption>Final grade</caption><tr><th>Grade</th><td></td></tr>
            </table></td></tr></table></main>""")
        assertEquals(listOf(listOf(listOf("Grade", ""))), detail.tables)
        assertEquals(listOf("Final grade"), detail.tableCaptions)
    }
    @Test fun availabilityNoticesAreRetainedBesideInstructionsAndGrades() {
        val detail = LemidaParser.detail("""<main id="region-main"><div id="intro">Submit a PDF</div>
            <div class="alert alert-danger">Submission overdue</div>
            <div class="alert alert-warning"><div class="alert">Attempts exhausted</div><p>Contact your teacher</p></div>
            <table><tr><th>Grade</th><td>95</td></tr></table></main>""")
        assertEquals("Submit a PDF", detail.description)
        assertEquals(listOf("Submission overdue", "Attempts exhausted\nContact your teacher"), detail.notices)
        assertEquals(detail, HomeworkDetail.fromJson(detail.json().toString()))
    }
    @Test fun hiddenAndRepeatedNoticesDoNotClutterTheNativePage() {
        val detail = LemidaParser.detail("""<main id="region-main">
            <div class="alert">Visible<div class="alert" hidden>Nested hidden</div></div><div class="alert">Visible</div>
            <div class="alert" hidden>Hidden</div>
            <div aria-hidden="true"><div class="alert">Template</div></div>
            <div class="d-none"><div class="alert">Hidden template</div></div>
            <div style="display: none"><div class="alert">Not rendered</div></div>
            <div class="alert" style="visibility:hidden">Invisible</div></main>""")
        assertEquals(listOf("Visible"), detail.notices)
        assertFalse(detail.text.contains("Hidden"))
        assertFalse(detail.text.contains("Visible"))
    }
    @Test fun detailCachesFromEarlierVersionsRemainReadable() {
        val old = """{"description":"Instructions","dates":"Friday","tables":[],"text":"Instructions"}"""
        val detail = HomeworkDetail.fromJson(old)
        assertEquals("Instructions", detail.description)
        assertEquals("Friday", detail.dates)
        assertTrue(detail.notices.isEmpty())
        assertTrue(detail.tableCaptions.isEmpty())
    }
    @Test fun ajaxSupportsCourseObjectsAndEncodedActivityState() {
        val courses = LemidaParser.ajaxData("""[{"error":false,"data":{"courses":[],"nextoffset":0}}]""") as org.json.JSONObject
        assertEquals(0, courses.getJSONArray("courses").length())
        assertEquals("{\"cm\":[]}", LemidaParser.ajaxData("""[{"error":false,"data":"{\"cm\":[]}"}]"""))
    }
    @Test fun ajaxLoginErrorsAndHtmlLoginPagesRequireRecovery() {
        listOf("invalidsesskey", "requireloginerror", "servicerequireslogin").forEach { code ->
            assertThrows(LemidaSessionExpired::class.java) {
                LemidaParser.ajaxData("""[{"error":true,"exception":{"errorcode":"$code"}}]""")
            }
        }
        listOf("<body class='notloggedin'>Login</body>", "<body id='page-login-index'>Login</body>").forEach { html ->
            assertThrows(LemidaSessionExpired::class.java) { LemidaParser.ajaxData(html) }
        }
    }
    @Test fun unavailableAjaxAndUnknownHtmlDoNotFalselyExpireTheSession() {
        listOf("""[{"error":true,"exception":{"errorcode":"servicenotavailable"}}]""",
            "<body><div class='errorbox'>Unavailable</div></body>").forEach { raw ->
            val error = assertThrows(java.io.IOException::class.java) { LemidaParser.ajaxData(raw) }
            assertFalse(error is LemidaSessionExpired)
        }
    }
    @Test fun incompleteAjaxResponsesCannotBecomeEmptySuccessfulSnapshots() {
        listOf("[]", "{}", "not json", "[null]", "[{}]", "[{\"data\":null}]",
            "[{\"data\":{}},{\"data\":{}}]").forEach { raw ->
            val error = assertThrows(java.io.IOException::class.java) { LemidaParser.ajaxData(raw) }
            assertFalse(error is LemidaSessionExpired)
        }
    }
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
        assertEquals(listOf("Submission is overdue"), detail.notices)
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
