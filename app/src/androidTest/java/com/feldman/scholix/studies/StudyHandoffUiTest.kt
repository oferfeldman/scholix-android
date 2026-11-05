package com.feldman.scholix.studies

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Runs only with the matching Documents QA package, using synthetic data in isolated app IDs. */
class StudyHandoffUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pickerOpensWithoutDocumentsInstalledOnThisDevice() {
        var dismissed = false
        compose.setContent {
            MotionTheme { StudyDevicePicker(DocumentsStudyContract.request(null), "com.example.notinstalled",
                onLocal = { fail("An unavailable local app was offered") }, onSent = {}, onDismiss = { dismissed = true }) }
        }
        compose.onNodeWithText("Open fstudy").assertExists()
        compose.onNodeWithText("This device").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    private fun findNativeText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) findNativeText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun clickNativeText(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = findNativeText(automation.rootInActiveWindow, text)
            if (node != null) {
                var button: AccessibilityNodeInfo? = node
                while (button != null && !button.isClickable) button = button.parent
                if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            SystemClock.sleep(100)
        }
        fun describe(node: AccessibilityNodeInfo?): String = if (node == null) "No active accessibility window" else
            "[${node.packageName}: ${node.text ?: node.contentDescription}]" +
                (0 until node.childCount).joinToString { describe(node.getChild(it)) }
        fail("Native Documents action not found: $text. ${describe(automation.rootInActiveWindow)}")
    }

    @Test fun createReopenAndCancelUseTheSameRealDocumentsStudy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val documents = InstrumentationRegistry.getArguments().getString("documentsPackage")
            ?: "com.feldman.documents.studyhandoff.qa"
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val target = Intent(DocumentsStudyContract.ACTION).setPackage(documents)
        assumeTrue(context.packageManager.queryIntentActivities(target, 0).isNotEmpty())
        val assignment = StudyAssignment.create("https://university.invalid", "synthetic-account",
            UUID.randomUUID().toString(), "assign", "test-activity", "Synthetic handoff ${UUID.randomUUID()}")
        val catalog = StudyCatalog(context)
        lateinit var caller: Activity
        compose.setContent {
            caller = requireNotNull(LocalActivity.current)
            MotionTheme { StudyActions(assignment) }
        }
        compose.onNodeWithText("Open fstudy").performClick()
        compose.onNodeWithText("This device").performClick()
        clickNativeText("Create study")
        compose.waitUntil(20_000) { catalog.entries().any { it.assignmentKey == assignment.key } }
        val first = catalog.entries().single { it.assignmentKey == assignment.key }
        assertTrue(first.isLocal)
        assertEquals(documents, first.documentsPackage)
        compose.waitUntil(20_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            findNativeText(root, assignment.title) != null && findNativeText(root, "No questions yet") != null
        }
        fun returnToScholix() {
            instrumentation.runOnMainSync {
                caller.startActivity(Intent(caller, caller.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            compose.waitUntil(20_000) { caller.hasWindowFocus() }
            compose.onNodeWithText("Open fstudy").assertExists()
        }
        returnToScholix()
        compose.onNodeWithText("Open fstudy").performClick()
        compose.onNodeWithText("This device").performClick()
        compose.waitUntil(20_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            findNativeText(root, assignment.title) != null && findNativeText(root, "No questions yet") != null
        }
        assertEquals(first.studyId, catalog.entries().single { it.assignmentKey == assignment.key }.studyId)
        returnToScholix()
        compose.onNodeWithText("Open fstudy").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open fstudy").assertExists()
        assertEquals(first.studyId, catalog.entries().single { it.assignmentKey == assignment.key }.studyId)
    }
}
