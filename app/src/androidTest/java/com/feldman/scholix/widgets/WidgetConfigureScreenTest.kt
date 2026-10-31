package com.feldman.scholix.widgets

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionTheme
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.platforms.MashovPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetConfigureScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerChangesAreOnlyAppliedWhenSaveIsPressed() {
        val providers = listOf(WebtopPlatform(), MashovPlatform())
        var saved: Platform? = null
        compose.setContent { MotionTheme {
            WidgetConfigureScreen("Grades widget", providers, providers[1].id, false, {}, { saved = it })
        } }
        compose.onNodeWithText("Grades widget").assertIsDisplayed()
        compose.onNodeWithText("Choose a course:").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous provider").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Webtop").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertSame(providers[0], saved) }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.targetContext.externalCacheDir!!.resolve("widget-config-design.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun scheduleSetupUsesTheSamePickerAndDisablesSaveWhileSaving() {
        val providers = listOf(WebtopPlatform())
        compose.setContent { MotionTheme {
            WidgetConfigureScreen("Schedule widget", providers, providers[0].id, true, {}, { fail("Saving must disable Save") })
        } }
        compose.onNodeWithText("Schedule widget").assertIsDisplayed()
        compose.onNodeWithText("Daily schedule", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Saving", substring = true).assertIsNotEnabled()
    }
}
