package com.feldman.scholix.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionTheme
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScheduleWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            MotionTheme {
                val platforms by produceState<List<Platform>?>(null) {
                    value = withContext(Dispatchers.IO) { PlatformStorage.loadPlatforms(this@ScheduleWidgetConfigureActivity).filter { it.supportsSchedule } }
                }
                var saving by remember { mutableStateOf(false) }
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Schedule widget", style = MaterialTheme.typography.headlineMedium)
                        Text("Daily schedule · switches to tomorrow after your last lesson", style = MaterialTheme.typography.bodyLarge)
                        Text("Choose a provider", style = MaterialTheme.typography.titleMedium)
                        if (platforms == null) Text("Loading providers…")
                        if (platforms?.isEmpty() == true) Text("Add a schedule provider in Scholix first.")
                        platforms.orEmpty().forEach { provider ->
                            MotionButton(onClick = {
                                saving = true
                                lifecycleScope.launch {
                                    val id = GlanceAppWidgetManager(this@ScheduleWidgetConfigureActivity).getGlanceIdBy(widgetId)
                                    updateAppWidgetState(this@ScheduleWidgetConfigureActivity, id) {
                                        it[scheduleProviderKey] = provider.id
                                        it.remove(scheduleDaysKey)
                                        it.remove(scheduleUpdatedKey)
                                        it.remove(scheduleErrorKey)
                                    }
                                    ScheduleWidget().update(this@ScheduleWidgetConfigureActivity, id)
                                    ScheduleWidgetWorker.schedule(this@ScheduleWidgetConfigureActivity)
                                    ScheduleWidgetWorker.refresh(this@ScheduleWidgetConfigureActivity)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                    finish()
                                }
                            }, enabled = !saving, modifier = Modifier.fillMaxWidth(), text = provider.platformDisplayName)
                        }
                    }
                }
            }
        }
    }
}
