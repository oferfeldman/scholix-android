package com.feldman.scholix.api.platforms

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.api.PlatformStorage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only opt-in check of the saved development session and live schedule, without logging student data. */
@RunWith(AndroidJUnit4::class)
class InbarScheduleSmokeTest {
    @Test fun savedSessionReadsLiveTimetableAndAvailableFilters() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inbarLiveSchedule") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".inbar.dev"))
        val provider = PlatformStorage.loadPlatforms(context).filterIsInstance<InbarPlatform>().firstOrNull()
        assumeTrue(provider != null && provider.isLoggedIn())
        val info = provider!!.getInfo()
        val selectedValue = "${info.getInt("scheduleYear")}|${info.getString("schedulePeriod")}"
        for (day in 0..6) {
            val schedule = provider.getSchedule(day, null, selectedValue)
            assertFalse("Saved session must authenticate the live schedule", schedule.has("error"))
            for (key in schedule.keys()) {
                val lesson = schedule.getJSONObject(key)
                assertEquals(day, lesson.getInt("day"))
                assertTrue(lesson.getString("subject").isNotBlank())
                assertTrue(lesson.getString("time").isNotBlank())
            }
        }
        val loaded = provider.getInfo()
        assertTrue(loaded.getJSONArray("scheduleYears").length() > 0)
        assertTrue(loaded.getJSONArray("schedulePeriods").length() > 0)
        assertTrue(loaded.getBoolean("supportsSchedule"))
        assertFalse(loaded.getBoolean("supportsOriginalSchedule"))
        val previousYear = loaded.getJSONArray("scheduleYears").let { years ->
            (0 until years.length()).map { years.getInt(it) }.firstOrNull { it < loaded.getInt("scheduleYear") }
        }
        if (previousYear != null) {
            var count = 0
            for (day in 0..6) {
                val previous = provider.getSchedule(day, null, "$previousYear|3")
                assertFalse("Saved session must authenticate the older schedule", previous.has("error"))
                count += previous.length()
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putInt("previousScheduleLessonCount", count)
            })
        }
    }
}
