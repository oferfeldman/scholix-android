package com.feldman.scholix.api.platforms

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.scholix.api.Platform
import com.feldman.scholix.pages.SchedulePage
import com.feldman.scholix.ui.HiddenInbarLogin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference

/** The normal schedule screen with a synthetic provider; no portal requests or SMS. */
@RunWith(AndroidJUnit4::class)
class InbarScheduleUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stalePageReusesVerifiedSessionWithoutAnotherSmsRequest() {
        val stale = InbarPlatform("SYNTHETIC_REUSE")
        val saved = InbarPlatform("SYNTHETIC_REUSE").apply { loggedIn = true }
        val result = AtomicReference<InbarPlatform?>()
        compose.setContent {
            HiddenInbarLogin(account = stale, reuseSavedSession = { saved },
                onSmsRequested = { fail("Reusing a verified session must not request another SMS") },
                onResult = { account, _ -> result.set(account) })
        }
        compose.waitUntil(10_000) { result.get() != null }
        assertSame(saved, result.get())
    }

    private fun provider(empty: Boolean): Platform = object : Platform by InbarPlatform("SCHEDULE_TEST") {
        override fun getInfo() = InbarPlatform("SCHEDULE_TEST").getInfo()
            .put("scheduleYear", 2027).put("scheduleYears", JSONArray(listOf(2027, 2026)))
        override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
            if (empty) return JSONObject()
            return JSONObject().put("sample-a", JSONObject().put("subject", "Sample course A")
                .put("teacher", "Lecturer").put("room", "Room A").put("hour", 540).put("time", "09:00 - 11:00"))
                .put("sample-b", JSONObject().put("subject", "Sample course B").put("hour", 720)
                    .put("teacher", "Lecturer").put("room", "Room B").put("time", "12:00 - 14:00"))
        }
    }

    @Test fun universityTimetableUsesAcademicFiltersAndActualTimes() {
        compose.setContent { AppTheme { SchedulePage(listOf(provider(false))) } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Sample course A", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Year").assertExists()
        compose.onNodeWithText("Semester").assertExists()
        compose.onNodeWithText("Version").assertDoesNotExist()
        compose.onNodeWithText("Grade").assertDoesNotExist()
        compose.onNodeWithText("09:00", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("11:00", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("540", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Sat").assertExists()
    }

    @Test fun emptyTimetableShowsNormalEmptyStateWithAcademicFilters() {
        compose.setContent { AppTheme { SchedulePage(listOf(provider(true))) } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("No schedule", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Year").assertExists()
        compose.onNodeWithText("Semester").assertExists()
        compose.onNodeWithText("Retry login").assertDoesNotExist()
    }
}
