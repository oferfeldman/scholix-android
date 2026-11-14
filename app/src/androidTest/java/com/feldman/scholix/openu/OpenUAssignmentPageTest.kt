package com.feldman.scholix.openu

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionTheme
import com.feldman.scholix.AppDest
import com.feldman.scholix.AppState
import com.feldman.scholix.CourseSection
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.courses.CourseSectionKind
import com.feldman.scholix.pages.LockerDatabase
import com.feldman.scholix.pages.LockerRepository
import androidx.room.Room
import androidx.compose.runtime.CompositionLocalProvider
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class OpenUAssignmentPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun combinedGradesPageShowsAndOpensBothGradedAndUngradedAssignments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val previous = PlatformStorage.loadPlatforms(context)
        val course = JSONObject().put("id", "20109").put("courseKey", "20109:2027a")
            .put("name", "Synthetic course").put("term", "2027a")
        val provider = OpenAUPlatform.fromJson(JSONObject().put("username", "synthetic-grades")
            .put("lastCourseRefresh", System.currentTimeMillis()).put("courses", JSONArray().put(course)))
        val graded = OpenUAssignment("20109", "Synthetic course", "2027a", "101", "01", "File assignment",
            "https://opal.openu.ac.il/mod/ouilassign/view.php?id=1", "ouilassign", "1", grade = "95", status = "Checked")
        val pending = graded.copy(number = "02", cmid = "2", grade = "", status = "Not submitted", due = "19/11/2026")
        val prefs = context.getSharedPreferences("openu_assignments_${provider.id}", 0)
        var opened: AppDest.OpenUAssignment? = null
        val database = Room.inMemoryDatabaseBuilder(context, LockerDatabase::class.java).build()
        val destination = CourseSection(provider.id, graded.courseKey, graded.semester, CourseSectionKind.GRADES)
        var active by mutableStateOf<com.feldman.motion.MotionDest>(destination)
        try {
            PlatformStorage.savePlatforms(context, previous + provider)
            prefs.edit().putString("snapshot", JSONObject().put("account", provider.getUsername())
                .put("coursesData", JSONObject().put(graded.courseKey, JSONObject()
                    .put("items", JSONArray().put(graded.json()).put(pending.json())))).toString()).commit()
            compose.setContent { MotionTheme {
                CompositionLocalProvider(LocalAppState provides AppState(listOf(course),
                    LockerRepository(database.lockerItemDao(), database.lockerTabDao()), listOf(provider), {}, {}, {},
                    activePage = active, courseCatalog = listOf(course))) {
                    destination.Content(onNavigate = object : com.feldman.motion.MotionNavigator {
                        override fun invoke(dest: com.feldman.motion.MotionDest, resetStack: Boolean) {
                            opened = dest as? AppDest.OpenUAssignment
                        }
                    }, onBack = {}, searchQuery = "", onFabAction = {})
                }
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("File assignment 01", substring = true)
                .fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("File assignment 02", substring = true))
            compose.onNodeWithText("File assignment 02", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Not graded").assertExists()
            compose.onNodeWithText("File assignment 02", substring = true).performClick()
            assertEquals(AppDest.OpenUAssignment(provider.id, pending.key, pending.courseKey, pending.semester), opened)
            compose.runOnIdle { active = requireNotNull(opened) }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("File assignment 01", substring = true))
            compose.onNodeWithText("File assignment 01", substring = true).performClick()
            assertEquals(graded.key, opened?.assignmentKey)
        } finally {
            PlatformStorage.savePlatforms(context, previous)
            prefs.edit().clear().commit()
            database.close()
        }
    }

    @Test fun connectedActionsSeparateRtlMetadataAndHideEmptySections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val previous = PlatformStorage.loadPlatforms(context)
        val provider = OpenAUPlatform().apply { setUsername("synthetic-ui") }
        val item = OpenUAssignment("12345", "Synthetic course", "2027a", "101", "11", "מטלה",
            "https://opal.openu.ac.il/mod/ouilassign/view.php?id=1", "ouilassign", "1", due = "19/11/2026", status = "טרם הוגש")
        val detail = OpenUAssignmentDetail(emptyList(), emptyList(), "Synthetic long instructions",
            listOf(OpenUAssignmentFile("Task.pdf", "https://opal.openu.ac.il/pluginfile.php/1/Task.pdf", "Original files")), emptyList())
        val prefs = context.getSharedPreferences("openu_assignments_${provider.id}", 0)
        // No saved course entry: this fixture never opens a provider connection.
        val snapshot = JSONObject().put("account", provider.getUsername()).put("coursesData", JSONObject().put(item.courseKey,
            JSONObject().put("items", JSONArray().put(item.json())))).put("details", JSONObject().put(item.key,
            JSONObject().put("data", detail.json())))
        var openedFile = false
        var revision by mutableIntStateOf(0)
        try {
            PlatformStorage.savePlatforms(context, previous + provider)
            prefs.edit().putString("snapshot", snapshot.toString()).commit()
            compose.setContent { MotionTheme {
                key(revision) {
                OpenUAssignmentPage(provider.id, item.key, onNavigate = object : com.feldman.motion.MotionNavigator {
                    override fun invoke(dest: com.feldman.motion.MotionDest, resetStack: Boolean) {
                        openedFile = dest is AppDest.OpenUAttachment
                    }
                }, onBack = {})
                }
            } }
            val study = compose.onNodeWithText("Open in Documents").assertIsDisplayed().assertHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot
            val upload = compose.onNodeWithText("Upload files").assertIsDisplayed().assertHeightIsEqualTo(48.dp).fetchSemanticsNode().boundsInRoot
            assertEquals(2f * context.resources.displayMetrics.density, upload.top - study.bottom, 1f)
            val status = compose.onNodeWithText(item.status).fetchSemanticsNode().boundsInRoot
            val due = compose.onNodeWithText("Due ${item.due}").fetchSemanticsNode().boundsInRoot
            assertTrue(due.top >= status.bottom)
            assertEquals(status.right, due.right, 1f)
            compose.onNodeWithText("Submission details").assertDoesNotExist()
            compose.onNodeWithText("Task.pdf").performScrollTo().performClick()
            assertTrue(openedFile)
            compose.onNodeWithText("Synthetic long instructions").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                snapshot.getJSONObject("details").getJSONObject(item.key).put("data", detail.copy(
                    instructions = "", files = emptyList(), fields = listOf("Last modified" to "18/11/2026", "Empty field" to ""),
                    submission = listOf("Receipt" to "qa-receipt"), notices = listOf(" ")).json())
                prefs.edit().putString("snapshot", snapshot.toString()).commit()
                revision++
            }
            compose.onNodeWithText("Files").assertDoesNotExist()
            compose.onNodeWithText("Original files").assertDoesNotExist()
            compose.onNodeWithText("Instructions").assertDoesNotExist()
            compose.onNodeWithText("Assignment details").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Submission details").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("qa-receipt").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("18/11/2026").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Empty field").assertDoesNotExist()
        } finally {
            PlatformStorage.savePlatforms(context, previous)
            prefs.edit().clear().commit()
        }
    }
}
