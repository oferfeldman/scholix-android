package com.feldman.scholix.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
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
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun hebrewLessonTextIsRightAlignedAndReadsRightToLeft() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for ((subject, direction) in listOf("מתמטיקה" to -1, "Math" to 1)) {
            val item = JSONObject().put("hour", 1).put("subject", subject).put("subjectLevel", "5")
                .put("teacher", if (direction == -1) "מורה לדוגמה" else "Example Teacher").put("room", "102")
            val views = GlanceRemoteViews().compose(context, DpSize(300.dp, 100.dp)) {
                GlanceTheme { ScheduleWidgetLesson(context, item, 0, true) }
            }.remoteViews
            instrumentation.runOnMainSync {
                val root = views.apply(context, FrameLayout(context)) as ViewGroup
                root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val texts = root.descendants.filterIsInstance<TextView>()
                    .filter { it.text.contains(subject) || it.text.contains("102") }.toList()
                assertEquals(2, texts.size)
                texts.forEach {
                    assertEquals(View.TEXT_ALIGNMENT_TEXT_START, it.textAlignment)
                    assertEquals(direction, it.layout.getParagraphDirection(0))
                }
            }
        }
    }

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
                val view = AppWidgetHostView(activity).apply {
                    setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                    setAppWidget(0, info)
                }
                widget = view
                val container = FrameLayout(activity)
                container.addView(view, FrameLayout.LayoutParams(-1, (220 * context.resources.displayMetrics.density).toInt()))
                activity.setContentView(container)
            }
            val views = ScheduleWidget().compose(context, size = DpSize(300.dp, 220.dp), state = state)
            // A launcher receives a parcelled copy in a different process.
            val parcel = android.os.Parcel.obtain()
            val delivered = try {
                views.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
            scenario.onActivity { widget.updateAppWidget(delivered) }
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
            // Launchers reuse the host for refreshes and resize updates too.
            scenario.onActivity { widget.updateAppWidget(delivered) }
            waitFor { list?.adapter?.count == 8 }
            scenario.onActivity { list!!.setSelection(0) }
            waitFor { list?.firstVisiblePosition == 0 }
        }
    }
    private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
}
