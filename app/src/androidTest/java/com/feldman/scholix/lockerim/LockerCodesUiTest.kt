package com.feldman.scholix.lockerim

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionNavFlow
import com.feldman.scholix.AppDest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Synthetic codes only, in a separately installed QA application. */
class LockerCodesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saveReopenEditAndDeleteNumericCode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".locker.qa"))
        val store = LockerCodesStore(context)
        val name = "QA locker ${UUID.randomUUID()}"
        compose.setContent {
            MotionNavFlow(startDestination = AppDest.Locker,
                destinations = listOf(AppDest.Locker, AppDest.LockerCodeEditor()), navigation = null)
        }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Add locker code").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add locker code").performClick()
            compose.onNodeWithText("Locker name").performTextInput(name)
            compose.onNodeWithText("Code").performTextInput("00705")
            compose.onNodeWithText("Save", substring = false).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save", substring = false).fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("00705", store.load().single { it.name == name }.pin)
            compose.onNodeWithText("Show", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("00705").assertExists()
            compose.onNodeWithText("Edit", substring = false).performClick()
            compose.onNodeWithText("Code").performTextReplacement("00042")
            compose.onNodeWithText("Save", substring = false).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save", substring = false).fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("00042", store.load().single { it.name == name }.pin)
            compose.onNodeWithText("Delete", substring = false).performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Delete code").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Delete code").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }
            assertTrue(store.load().none { it.name == name })
        } finally { store.load().filter { it.name == name }.forEach { store.delete(it.id) } }
    }

    @Test fun savedDialCodeKeepsItsStepsAndUsesTheSameLockerPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".locker.qa"))
        val store = LockerCodesStore(context)
        val code = LockerCode(name = "QA dial ${UUID.randomUUID()}", dial = true,
            steps = listOf(DialStep("12", true, 3), DialStep("34", false, 2), DialStep("7", true, 1)))
        store.save(code)
        try {
            compose.setContent {
                MotionNavFlow(startDestination = AppDest.Locker,
                    destinations = listOf(AppDest.Locker, AppDest.LockerCodeEditor()), navigation = null)
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(code.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Contents").assertExists()
            compose.onNodeWithText("לוקרים").assertExists()
            compose.onNodeWithText("Show", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("1. Right ↻ · 3 turns · stop at 12").assertExists()
            compose.onNodeWithText("2. Left ↺ · 2 turns · stop at 34").assertExists()
            compose.onNodeWithText("3. Right ↻ · 1 turn · stop at 7").assertExists()
            assertEquals(code.steps, LockerCode.fromJson(store.load().single { it.id == code.id }.toJson()).steps)
            compose.onNodeWithText("Edit", substring = false).performScrollTo().performClick()
            compose.onAllNodesWithText("Stop at").onFirst().performTextReplacement("22")
            compose.onAllNodesWithText("Turns").onFirst().performTextReplacement("4")
            compose.onNodeWithText("Save", substring = false).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save", substring = false).fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(code.name).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(code.steps.toMutableList().apply { this[0] = DialStep("22", true, 4) },
                store.load().single { it.id == code.id }.steps)
        } finally { store.delete(code.id) }
    }
}
