package com.feldman.scholix.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.AppState
import com.feldman.scholix.CourseSection
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.LocalScopeControl
import com.feldman.scholix.ScopeControl
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.pages.LockerDatabase
import com.feldman.scholix.pages.LockerRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CoursePickerBarTest {
    @get:Rule val compose = createComposeRule()
    private var database: LockerDatabase? = null

    @After fun closeDatabase() { database?.close() }

    @Test fun courseHeaderHasOnePickerAndOnlyTheActiveProvidersCourses() {
        val provider = OpenAUPlatform().apply { setName("OpenU") }
        val other = GoogleClassroomPlatform()
        val courses = listOf("Analysis", "Classroom course", "Logic").mapIndexed { index, name ->
            JSONObject().put("name", name).put("courseKey", index.toString())
                .put("platformId", if (index == 1) other.id else provider.id)
        }
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, LockerDatabase::class.java
        ).build().also { database = it }
        var selected: CourseSection? = null
        val state = AppState(
            preloadedCourses = courses,
            repository = LockerRepository(db.lockerItemDao(), db.lockerTabDao()),
            platforms = listOf(provider, other),
            onPlatformsChanged = {}, onLogout = {}, onLoginSuccess = {},
            activePage = CourseSection(provider.id, "0"), courseCatalog = courses,
            onScopeSelected = { selected = it as CourseSection }
        )
        compose.setContent {
            CompositionLocalProvider(LocalAppState provides state,
                LocalScopeControl provides ScopeControl("OpenU") {}) {
                MaterialTheme { SettingsTopBar("Overview") }
            }
        }
        compose.onNodeWithText("Overview").assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose course").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next course").performClick()
        compose.runOnIdle { assertEquals(CourseSection(provider.id, "2"), selected) }
        compose.onNodeWithContentDescription("Choose course").performClick()
        compose.onNodeWithText("Logic").assertIsDisplayed()
        compose.onNodeWithText("Classroom course").assertDoesNotExist()
    }

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
