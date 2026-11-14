package com.feldman.scholix.openu

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionTheme
import com.feldman.scholix.AppDest
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
            val study = compose.onNodeWithText("Open fstudy").assertIsDisplayed().assertHeightIsEqualTo(56.dp).fetchSemanticsNode().boundsInRoot
            val upload = compose.onNodeWithText("Upload files").assertIsDisplayed().assertHeightIsEqualTo(56.dp).fetchSemanticsNode().boundsInRoot
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
                    notices = listOf(" ")).json())
                prefs.edit().putString("snapshot", snapshot.toString()).commit()
                revision++
            }
            compose.onNodeWithText("Files").assertDoesNotExist()
            compose.onNodeWithText("Original files").assertDoesNotExist()
            compose.onNodeWithText("Instructions").assertDoesNotExist()
            compose.onNodeWithText("Submission details").performScrollTo().performClick()
            compose.onNodeWithText("18/11/2026").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Empty field").assertDoesNotExist()
        } finally {
            PlatformStorage.savePlatforms(context, previous)
            prefs.edit().clear().commit()
        }
    }
}
