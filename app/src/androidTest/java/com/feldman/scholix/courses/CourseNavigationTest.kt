package com.feldman.scholix.courses

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.motion.MotionTheme
import com.feldman.motion.MotionDest
import com.feldman.scholix.MainScreen
import com.feldman.scholix.AppDest
import com.feldman.scholix.CourseSection
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.AppState
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.DemoPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.api.platforms.MashovPlatform
import org.json.JSONObject
import com.feldman.scholix.pages.LockerDatabase
import com.feldman.scholix.pages.LockerRepository
import com.feldman.scholix.pages.GradesLoadingIndicator
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue

class CourseNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun webtopGradesLoadingOffersTheOfficialSignIn() {
        var opened: Intent? = null
        compose.setContent {
            MotionTheme {
                val context = LocalContext.current
                val browserContext = object : ContextWrapper(context) {
                    override fun startActivity(intent: Intent) { opened = intent }
                }
                CompositionLocalProvider(LocalContext provides browserContext) {
                    GradesLoadingIndicator(offerWebtopSignIn = true)
                }
            }
        }
        compose.onNodeWithText("Sign in on Webtop").performClick()
        compose.runOnIdle {
            assertEquals("https://webtop.smartschool.co.il/account/login", opened?.dataString)
            assertEquals(Intent.ACTION_VIEW, opened?.action)
            assertEquals(setOf(Intent.CATEGORY_BROWSABLE), opened?.categories)
        }
    }

    @Test fun mashovPickerShowsSavedSubjectsBeforeRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val database = Room.inMemoryDatabaseBuilder(context, LockerDatabase::class.java).build()
        val repository = LockerRepository(database.lockerItemDao(), database.lockerTabDao())
        val original = PlatformStorage.loadPlatforms(context)
        val courses = listOf("Mashov algebra", "Mashov physics").map { name ->
            JSONObject().put("name", name).put("courseKey", name)
                .put("grades", org.json.JSONArray().put(JSONObject().put("subject", name)
                    .put("name", "$name exam").put("grade", 90)))
        }
        val provider = MashovPlatform.fromJson(JSONObject().put("displayName", "Mashov test account")
            .put("courses", org.json.JSONArray(courses)))
        var activity: ComponentActivity? = null
        try {
            PlatformStorage.savePlatforms(context, listOf(provider))
            val saved = PlatformStorage.getSavedCourses(context, listOf(provider))
            assertEquals(courses.map { it.getString("name") }, saved.map { it.getString("name") })
            compose.setContent {
                MotionTheme {
                    activity = LocalActivity.current as ComponentActivity
                    MainScreen(saved, repository, {}, listOf(provider), {}, {})
                }
            }
            compose.onNodeWithContentDescription("Choose course").performClick()
            compose.onNodeWithText("Mashov test account").assertExists()
            compose.onNodeWithText("Mashov physics").assertExists()
            compose.onAllNodesWithText("Mashov algebra")[0].assertExists()
            compose.onNodeWithText("Mashov physics").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Mashov physics exam").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            activity?.let { compose.runOnUiThread { it.finish() } }
            PlatformStorage.savePlatforms(context, original)
            database.close()
        }
    }

    @Test fun messagesPickerStaysWithInboxDuringSubpageTransitions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val database = Room.inMemoryDatabaseBuilder(context, LockerDatabase::class.java).build()
        val provider = WebtopPlatform()
        lateinit var activity: ComponentActivity
        lateinit var state: AppState
        try {
            compose.setContent {
                MotionTheme {
                    activity = LocalActivity.current as ComponentActivity
                    MainScreen(emptyList(), LockerRepository(database.lockerItemDao(), database.lockerTabDao()),
                        {}, listOf(provider), {}, {}, snackbarHost = { state = LocalAppState.current })
                }
            }
            compose.runOnIdle { state.onScopeSelected(AppDest.Messages) }
            compose.waitForIdle()
            val bounds = compose.onNodeWithContentDescription("Choose course").fetchSemanticsNode().boundsInRoot
            for (destination in listOf(AppDest.MessageFolders(provider.id, "INBOX"), AppDest.MessageTools(provider.id))) {
                compose.mainClock.autoAdvance = false
                compose.runOnIdle { state.onScopeSelected(destination) }
                compose.mainClock.advanceTimeBy(16)
                compose.onNodeWithContentDescription("Choose course").assertExists()
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertEquals(bounds, compose.onNodeWithContentDescription("Choose course").fetchSemanticsNode().boundsInRoot)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { activity.finish() }
            database.close()
        }
    }

    @Test fun activeCourseGradesFinishesLoadingForWebtopMashovAndOpenU() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val database = Room.inMemoryDatabaseBuilder(context, LockerDatabase::class.java).build()
        val repository = LockerRepository(database.lockerItemDao(), database.lockerTabDao())
        val states = listOf(WebtopPlatform(), MashovPlatform(), OpenAUPlatform()).map { provider ->
            val course = JSONObject().put("platformId", provider.id).put("courseKey", "spinner-qa").put("name", "Loading regression")
            val destination = CourseSection(provider.id, "spinner-qa", section = CourseSectionKind.GRADES)
            AppState(listOf(course), repository, listOf(provider), {}, {}, {}, activePage = destination, courseCatalog = listOf(course))
        }
        var selected by mutableIntStateOf(0)
        try {
            compose.setContent {
                MotionTheme {
                    key(selected) {
                        val state = states[selected]
                        CompositionLocalProvider(LocalAppState provides state) {
                            (state.activePage as CourseSection).Content(onNavigate = object : com.feldman.motion.MotionNavigator {
                                override fun invoke(dest: MotionDest, resetStack: Boolean) = Unit
                            }, onBack = {}, searchQuery = "", onFabAction = {})
                        }
                    }
                }
            }
            states.indices.forEach { index ->
                compose.runOnIdle { selected = index }
                compose.waitForIdle()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Could not find account for this course.").fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally { database.close() }
    }

    @Test fun switchingSchoolProviderToOpenUOpensOverviewInsteadOfKeepingGrades() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".studyhandoff.qa"))
        val database = Room.inMemoryDatabaseBuilder(context, LockerDatabase::class.java).build()
        val repository = LockerRepository(database.lockerItemDao(), database.lockerTabDao())
        val school = DemoPlatform()
        val openu = OpenAUPlatform()
        val courses = listOf(
            JSONObject().put("platformId", school.id).put("courseKey", "school").put("name", "School algebra"),
            JSONObject().put("platformId", openu.id).put("courseKey", "20109:2027a").put("name", "University algebra")
        )
        lateinit var activity: ComponentActivity
        var active: MotionDest? = null
        try {
            compose.setContent {
                MotionTheme {
                    activity = LocalActivity.current as ComponentActivity
                    MainScreen(courses, repository, {}, listOf(school, openu), {}, {},
                        snackbarHost = { active = LocalAppState.current.activePage })
                }
            }
            compose.runOnIdle { assertEquals(AppDest.Grades, active) }
            compose.onNodeWithContentDescription("Choose course").performClick()
            compose.onNodeWithText("University algebra").performClick()
            compose.runOnIdle {
                assertEquals(openu.id, (active as CourseSection).providerId)
                assertEquals(CourseSectionKind.OVERVIEW, (active as CourseSection).section)
            }
            compose.onNodeWithContentDescription("Overview").assertExists()
        } finally {
            compose.runOnUiThread { activity.finish() }
            database.close()
        }
    }

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
