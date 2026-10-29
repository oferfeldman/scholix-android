package com.feldman.scholix.widgets

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/** A native collection keeps earlier lessons available while starting near the current lesson. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ScheduleWidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val lessons = JSONArray(intent.getStringExtra("lessons") ?: "[]")
        private val renderer = GlanceRemoteViews()
        private val size = DpSize(intent.getFloatExtra("width", 300f).dp, 100.dp)
        private val numberedPeriods = intent.getBooleanExtra("numberedPeriods", true)

        override fun onCreate() {}
        override fun onDataSetChanged() {}
        override fun onDestroy() {}
        override fun getCount() = lessons.length()
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
        override fun getViewTypeCount() = count.coerceAtLeast(1)
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews = runBlocking {
            renderer.compose(this@ScheduleWidgetListService, size) {
                GlanceTheme {
                    ScheduleWidgetLesson(this@ScheduleWidgetListService, lessons.getJSONObject(position), position, numberedPeriods)
                }
            }.remoteViews.apply {
                setOnClickFillInIntent(android.R.id.background, Intent())
            }
        }
    }
}
