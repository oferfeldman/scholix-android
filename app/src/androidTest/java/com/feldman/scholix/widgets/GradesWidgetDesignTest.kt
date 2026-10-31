package com.feldman.scholix.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import androidx.core.view.descendants
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GradesWidgetDesignTest {
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun placeholderGradesShowTheEmptyState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val placeholder = JSONArray().put(JSONObject().put("grade", "null"))
        val views = GlanceRemoteViews().compose(context, DpSize(300.dp, 500.dp)) {
            GlanceTheme { GradesWidgetContent(context, null, "Webtop", placeholder) }
        }.remoteViews
        instrumentation.runOnMainSync {
            val root = views.apply(context, FrameLayout(context)) as ViewGroup
            assertTrue(root.descendants.any { it.contentDescription == "No grades yet" })
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun averageAndGradeCardsRenderInAnAsyncWidgetHost() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val grades = JSONArray().put(JSONObject().put("subject", "Math").put("name", "Algebra test").put("grade", "98"))
            .put(JSONObject().put("subject", "\u05d1\u05d9\u05d5\u05dc\u05d5\u05d2\u05d9\u05d4")
                .put("name", "\u05de\u05d1\u05d7\u05df \u05d1\u05d9\u05d5\u05dc\u05d5\u05d2\u05d9\u05d4").put("grade", "100"))
        val views = GlanceRemoteViews().compose(context, DpSize(300.dp, 500.dp)) {
            GlanceTheme { GradesWidgetContent(context, null, "Webtop", grades) }
        }.remoteViews
        val parcel = android.os.Parcel.obtain()
        val delivered = try { views.writeToParcel(parcel, 0); assertTrue("Font images must not be embedded in the widget parcel", parcel.dataSize() < 128_000); parcel.setDataPosition(0)
            android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var widget: AppWidgetHostView
            scenario.onActivity { activity ->
                val info = AppWidgetManager.getInstance(context).installedProviders.first {
                    it.provider.className == GradesWidgetReceiver::class.java.name
                }
                widget = AppWidgetHostView(activity).apply {
                    setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor()); setAppWidget(0, info)
                }
                val container = FrameLayout(activity)
                container.addView(widget, FrameLayout.LayoutParams((300 * context.resources.displayMetrics.density).toInt(),
                    (500 * context.resources.displayMetrics.density).toInt()))
                activity.setContentView(container)
                widget.updateAppWidget(delivered)
            }
            var count = 0
            val end = SystemClock.uptimeMillis() + 15_000
            while (count != 3 && SystemClock.uptimeMillis() < end) {
                scenario.onActivity { count = (widget as ViewGroup).descendants.filterIsInstance<ListView>().firstOrNull()?.adapter?.count ?: 0 }
                SystemClock.sleep(100)
            }
            assertEquals("One average card and two grade cards", 3, count)
            SystemClock.sleep(400)
            context.externalCacheDir!!.resolve("grades-widget-design.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        Unit
    }
}
