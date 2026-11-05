package com.feldman.scholix.openu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionTheme
import com.feldman.scholix.AppDest
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class OpenUAssignmentPageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun filesAndUploadAreVisibleBeforeExpandedInstructions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val previous = PlatformStorage.loadPlatforms(context)
        val provider = OpenAUPlatform().apply { setUsername("synthetic-ui") }
        val item = OpenUAssignment("12345", "Synthetic course", "2027a", "101", "11", "Assignment",
            "https://opal.openu.ac.il/mod/ouilassign/view.php?id=1", "ouilassign", "1", status = "Not submitted")
        val detail = OpenUAssignmentDetail(emptyList(), emptyList(), "Synthetic long instructions",
            listOf(OpenUAssignmentFile("Task.pdf", "https://opal.openu.ac.il/pluginfile.php/1/Task.pdf", "Original files")), emptyList())
        val prefs = context.getSharedPreferences("openu_assignments_${provider.id}", 0)
        // No saved course entry: this fixture never opens a provider connection.
        val snapshot = JSONObject().put("account", provider.getUsername()).put("coursesData", JSONObject().put(item.courseKey,
            JSONObject().put("items", JSONArray().put(item.json())))).put("details", JSONObject().put(item.key,
            JSONObject().put("data", detail.json())))
        var openedFile = false
        try {
            PlatformStorage.savePlatforms(context, previous + provider)
            prefs.edit().putString("snapshot", snapshot.toString()).commit()
            compose.setContent { MotionTheme {
                OpenUAssignmentPage(provider.id, item.key, onNavigate = object : com.feldman.motion.MotionNavigator {
                    override fun invoke(dest: com.feldman.motion.MotionDest, resetStack: Boolean) {
                        openedFile = dest is AppDest.OpenUAttachment
                    }
                }, onBack = {})
            } }
            compose.onNodeWithText("Open fstudy").assertIsDisplayed()
            compose.onNodeWithText("Upload files").assertIsDisplayed()
            compose.onNodeWithText("Task.pdf").performScrollTo().performClick()
            assertTrue(openedFile)
            compose.onNodeWithText("Synthetic long instructions").assertDoesNotExist()
            compose.onNodeWithText("Instructions and details").performScrollTo().performClick()
            compose.onNodeWithText("Synthetic long instructions").performScrollTo().assertIsDisplayed()
        } finally {
            PlatformStorage.savePlatforms(context, previous)
            prefs.edit().clear().commit()
        }
    }
}
