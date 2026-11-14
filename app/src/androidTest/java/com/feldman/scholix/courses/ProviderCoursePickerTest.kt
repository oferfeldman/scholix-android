package com.feldman.scholix.courses

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.feldman.motion.MotionTheme
import com.feldman.scholix.ui.components.CoursePickerBar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderCoursePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providersAndCoursesShareTheSameFolderDropdown() {
        val courses = listOf(
            JSONObject().put("platformId", "webtop").put("platformName", "Webtop").put("name", "All Webtop").put("providerRoot", true),
            JSONObject().put("platformId", "webtop").put("name", "School algebra").put("courseKey", "1"),
            JSONObject().put("platformId", "openu").put("platformName", "OpenU").put("name", "All OpenU").put("providerRoot", true),
            JSONObject().put("platformId", "openu").put("name", "University algebra").put("courseKey", "2")
        )
        var chosen = -1
        compose.setContent {
            var expanded by remember { mutableStateOf(false) }
            MotionTheme {
                CoursePickerBar(courses, 0, { chosen = it }, expanded, { expanded = it }, emptyMap(), groupAllProviders = true)
            }
        }
        compose.onNodeWithContentDescription("Choose course").performClick()
        compose.onNodeWithText("School algebra").assertExists()
        compose.onNodeWithText("Webtop").performClick()
        compose.onNodeWithText("OpenU").performClick()
        compose.onNodeWithText("School algebra").assertDoesNotExist()
        compose.onNodeWithText("University algebra").performClick()
        compose.runOnIdle { assertEquals(3, chosen) }
    }
}
