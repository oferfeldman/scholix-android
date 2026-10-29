package com.feldman.scholix.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ListView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.MainActivity
import com.feldman.scholix.R
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ScheduleWidgetScrollTest {
    @Test fun startsOneLessonBeforeNowAndCanScrollBackToTheMorning() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        val start = (minute - 130).coerceAtLeast(0)
        val lessons = JSONArray((0..7).map { index ->
            JSONObject().put("hour", index).put("subject", "Lesson $index")
                .put("time", "${clock(start + index * 40)} - ${clock(start + (index + 1) * 40)}")
        })
        val state = preferencesOf(
            scheduleProviderKey to "widget-scroll-fixture",
            scheduleDaysKey to JSONObject().put(LocalDate.now().toString(), lessons).toString(),
            scheduleUpdatedKey to System.currentTimeMillis().toString(),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var list: ListView? = null
            lateinit var widget: AppWidgetHostView
            scenario.onActivity { activity ->
                val info = AppWidgetManager.getInstance(context).installedProviders.first {
                    it.provider.className == ScheduleWidgetReceiver::class.java.name
                }
                val view = AppWidgetHostView(activity).apply { setAppWidget(0, info) }
                widget = view
                val container = FrameLayout(activity)
                container.addView(view, FrameLayout.LayoutParams(-1, (220 * context.resources.displayMetrics.density).toInt()))
                activity.setContentView(container)
            }
            val views = ScheduleWidget().compose(context, size = DpSize(300.dp, 220.dp), state = state)
            scenario.onActivity { widget.updateAppWidget(views) }
            fun waitFor(condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 20_000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < end) {
                    scenario.onActivity { activity ->
                        list = widget.findViewById(R.id.schedule_widget_list)
                        ready = condition()
                    }
                    if (!ready) SystemClock.sleep(100)
                }
                if (!ready) context.externalCacheDir!!.resolve("schedule-widget-failure.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue("Widget did not reach the expected scroll position: count=${list?.adapter?.count}, position=${list?.firstVisiblePosition}", ready)
            }
            val expected = ((minute - start) / 40 - 1).coerceAtLeast(0)
            waitFor {
                val first = list?.getChildAt(0)
                val firstComplete = list?.firstVisiblePosition?.plus(if (first != null && first.top < list!!.paddingTop) 1 else 0)
                list?.adapter?.count == 8 && firstComplete == expected
            }
            scenario.onActivity { list!!.setSelection(0) }
            waitFor { list?.firstVisiblePosition == 0 }
            assertEquals(8, list!!.adapter.count)
        }
    }
    private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
}
