package com.feldman.scholix.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.feldman.scholix.api.platforms.OpenAUPlatform
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class CoursePickerBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reopeningStartsInTheSelectedCoursesFolder() {
        val provider = OpenAUPlatform().apply { setName("OpenU") }
        val courses = listOf("Analysis", "Discrete math", "Logic", "Other course").mapIndexed { index, name ->
            JSONObject().put("name", name).put("courseKey", index.toString())
                .put("platformId", if (index < 3) provider.id else "other")
        }
        compose.setContent {
            var selected by remember { mutableIntStateOf(1) }
            var expanded by remember { mutableStateOf(false) }
            MaterialTheme {
                CoursePickerBar(courses, selected, { selected = it }, expanded, { expanded = it },
                    providersById = mapOf(provider.id to provider))
            }
        }
        compose.onNodeWithContentDescription("Choose course").performClick()
        compose.onNodeWithText("Analysis").assertIsDisplayed()
        compose.onNodeWithText("Other course").assertDoesNotExist()

        compose.onNodeWithText("OpenU").performClick()
        compose.onNodeWithText("Other course").performClick()
        compose.onNodeWithContentDescription("Choose course").performClick()
        compose.onNodeWithText("Analysis").assertDoesNotExist()
        compose.onNodeWithText("OpenU").performClick()
        compose.onNodeWithText("Logic").performClick()

        compose.onNodeWithContentDescription("Choose course").performClick()
        compose.onNodeWithText("Analysis").assertIsDisplayed()
        compose.onNodeWithText("Other course").assertDoesNotExist()
    }
}
