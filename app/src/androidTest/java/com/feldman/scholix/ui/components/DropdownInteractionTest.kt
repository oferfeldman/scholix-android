package com.feldman.scholix.ui.components

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DropdownInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedTouchesAcrossDropdownsLeaveThePageResponsive() {
        val first = mutableStateOf("First")
        val second = mutableStateOf("Second")
        var clicks = 0
        compose.setContent {
            MotionTheme {
                Column {
                    ChipPicker("One", listOf("First", "Other"), first.value, { first.value = it })
                    ChipPicker("Two", listOf("Second", "Another"), second.value, { second.value = it })
                    Button(onClick = { clicks++ }) { Text("Page action") }
                }
            }
        }
        compose.waitForIdle()
        val a = compose.onNodeWithText("First").fetchSemanticsNode().boundsInWindow.center
        val b = compose.onNodeWithText("Second").fetchSemanticsNode().boundsInWindow.center
        val button = compose.onNodeWithText("Page action").fetchSemanticsNode().boundsInWindow.center
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun tap(x: Float, y: Float) {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(event, true))
                event.recycle()
            }
            compose.mainClock.advanceTimeBy(60)
        }
        tap(button.x, button.y)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Initial activity touch must arrive", 1, clicks); clicks = 0 }
        repeat(15) {
            tap(a.x, a.y)
            SystemClock.sleep(60)
            tap(b.x, b.y)
            SystemClock.sleep(60)
            tap(button.x, button.y)
            SystemClock.sleep(60)
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        // Dismiss any remaining menu, then make sure a normal activity touch still arrives.
        tap(button.x, button.y)
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.runOnIdle { clicks = 0 }
        tap(button.x, button.y)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
