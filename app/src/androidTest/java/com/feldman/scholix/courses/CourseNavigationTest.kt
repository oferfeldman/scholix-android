package com.feldman.scholix.courses

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionTheme
import com.feldman.scholix.MainScreen
import com.feldman.scholix.api.platforms.OpenAUPlatform
import org.json.JSONObject
import com.feldman.scholix.pages.LockerDatabase
import com.feldman.scholix.pages.LockerRepository
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue

class CourseNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun interruptingSettingsWithBackKeepsTheReturningCourseStateUnique() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val database = Room.inMemoryDatabaseBuilder(context, LockerDatabase::class.java).build()
        val repository = LockerRepository(database.lockerItemDao(), database.lockerTabDao())
        val provider = OpenAUPlatform()
        val course = JSONObject().put("name", "Navigation course").put("platformId", provider.id).put("courseKey", "qa")
        var courses by mutableStateOf(listOf(course))
        lateinit var activity: ComponentActivity
        try {
            compose.setContent {
                MotionTheme {
                    activity = LocalActivity.current as ComponentActivity
                    MainScreen(courses, repository, {}, listOf(provider), {}, {})
                }
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Choose course").performClick()
            compose.onAllNodesWithText("Navigation course")[1].performClick()
            val menuBounds = compose.onNodeWithContentDescription("More pages").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { courses = emptyList() }
            compose.onNodeWithContentDescription("More pages").performClick()
            compose.onNodeWithText("Recordings").assertExists()
            compose.onNodeWithContentDescription("Close more pages").performClick()
            compose.runOnIdle { courses = listOf(course) }
            repeat(3) {
                compose.onNodeWithContentDescription("More pages").performClick()
                compose.onNodeWithContentDescription("Close more pages").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Appearance").assertExists()
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread {
                    activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
                    activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(90f, 300f, 0.4f, BackEventCompat.EDGE_LEFT))
                }
                compose.mainClock.advanceTimeBy(32)
                compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
                compose.mainClock.advanceTimeBy(32)
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                compose.onNodeWithContentDescription("Choose course").assertExists()
                assertEquals(menuBounds, compose.onNodeWithContentDescription("More pages").fetchSemanticsNode().boundsInRoot)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { activity.finish() }
            database.close()
        }
    }
}
