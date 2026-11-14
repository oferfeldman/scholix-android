package com.feldman.scholix.studies

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
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

    @Test fun remoteTransferImportsSourcesBeforeSavingTheStudyReceipt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("remoteContentQa") == "true")
        val assignment = StudyAssignment.create("https://synthetic.invalid", "qa", UUID.randomUUID(), "assign", "1",
            "Synthetic remote content ${UUID.randomUUID()}")
        val file = java.io.File.createTempFile("remote-questions-", ".pdf", context.cacheDir)
        val pdf = android.graphics.pdf.PdfDocument()
        val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 800, 1).create())
        page.canvas.drawText("Question 1", 40f, 40f, android.graphics.Paint().apply { textSize = 18f })
        val image = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        image.setPixels(IntArray(128 * 128) { random.nextInt() or (0xff shl 24) }, 0, 128, 0, 0, 128, 128)
        page.canvas.drawBitmap(image, 40f, 80f, null)
        pdf.finishPage(page)
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        val catalog = StudyCatalog(context)
        try {
            compose.setContent { MotionTheme { StudyActions(assignment, prepareContent = {
                AssignmentStudyBundle.build(context, assignment, listOf(AssignmentStudySource("Questions.pdf", file)))
                    .also { assertTrue(it.length() > 8192) }
            }) } }
            compose.onNodeWithText("Open fstudy").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Connect Seamless").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(hasText("Fold", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithText("Connect Seamless").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Connect Seamless").performClick()
                clickNativeText("Allow")
            }
            val qualified = hasText("Fold", substring = true) and hasText("com.feldman.documents.studyhandoff.qa", substring = true)
            val fold = hasText("Fold", substring = true)
            compose.waitUntil(30_000) { compose.onAllNodes(qualified).fetchSemanticsNodes().size == 1 || compose.onAllNodes(fold).fetchSemanticsNodes().size == 1 }
            val target = if (compose.onAllNodes(qualified).fetchSemanticsNodes().size == 1) qualified else fold
            compose.onAllNodes(target)[0].performClick()
            compose.waitUntil(90_000) { catalog.entries().any { it.assignmentKey == assignment.key && it.studyId != null } }
            val saved = catalog.entries().single { it.assignmentKey == assignment.key }
            assertEquals(assignment.title, saved.title)
            assertNull(saved.documentsPackage)
            assertTrue(requireNotNull(saved.studyId).isNotBlank())
        } finally { file.delete() }
    }

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

    @Test fun explicitConnectCanRequestSeamlessApprovalAgainAfterDenial() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".seamlessapproval.qa"))
        compose.setContent {
            MotionTheme { StudyDevicePicker(DocumentsStudyContract.request(null), "com.example.notinstalled",
                onLocal = {}, onSent = {}, onDismiss = {}) }
        }
        repeat(2) {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Connect Seamless").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Connect Seamless").performClick()
            clickNativeText("Don't allow")
        }
    }

    private fun findNativeText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) findNativeText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun clickNativeText(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = findNativeText(automation.rootInActiveWindow, text)
                ?: automation.windows.firstNotNullOfOrNull { findNativeText(it.root, text) }
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
        catalog.save(StudyReference("synthetic:${assignment.key}", assignment.title, assignmentKey = assignment.key,
            documentsPackage = documents))
        lateinit var caller: Activity
        compose.setContent {
            caller = requireNotNull(LocalActivity.current)
            MotionTheme { StudyActions(assignment, prepareContent = {
                val source = java.io.File.createTempFile("local-questions-", ".pdf", context.cacheDir)
                val pdf = android.graphics.pdf.PdfDocument()
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawText("Question 1", 40f, 40f, android.graphics.Paint().apply { textSize = 18f })
                pdf.finishPage(page)
                source.outputStream().use { pdf.writeTo(it) }
                pdf.close()
                try { AssignmentStudyBundle.build(context, assignment, listOf(AssignmentStudySource("Questions.pdf", source))) }
                finally { source.delete() }
            }) }
        }
        compose.onNodeWithText("Open fstudy").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("This device").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("This device").performClick()
        clickNativeText("No folder")
        clickNativeText("Scholix synthetic QA")
        clickNativeText("Create study")
        compose.waitUntil(20_000) { catalog.entries().any { it.assignmentKey == assignment.key && it.isLocal } }
        val first = catalog.entries().single { it.assignmentKey == assignment.key }
        assertTrue(first.isLocal)
        assertEquals(documents, first.documentsPackage)
        compose.waitUntil(20_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            findNativeText(root, assignment.title) != null && findNativeText(root, "No questions yet") == null
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
        compose.waitUntil(20_000) { compose.onAllNodesWithText("This device").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("This device").performClick()
        compose.waitUntil(20_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            findNativeText(root, assignment.title) != null && findNativeText(root, "No questions yet") == null
        }
        assertEquals(first.studyId, catalog.entries().single { it.assignmentKey == assignment.key }.studyId)
        returnToScholix()
        compose.onNodeWithText("Open fstudy").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open fstudy").assertExists()
        assertEquals(first.studyId, catalog.entries().single { it.assignmentKey == assignment.key }.studyId)
    }
}
