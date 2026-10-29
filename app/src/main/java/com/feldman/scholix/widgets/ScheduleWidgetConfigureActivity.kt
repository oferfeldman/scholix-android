package com.feldman.scholix.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.feldman.scholix.pages.GradesLoadingIndicator
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.feldman.motion.MotionTheme
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class ScheduleWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            MotionTheme {
                val setup by produceState<Pair<List<Platform>, String?>?>(null) {
                    val prefs = getAppWidgetState(this@ScheduleWidgetConfigureActivity, PreferencesGlanceStateDefinition, AppWidgetId(widgetId))
                    value = withContext(Dispatchers.IO) {
                        PlatformStorage.loadPlatforms(this@ScheduleWidgetConfigureActivity).filter { it.supportsSchedule }
                    } to prefs[scheduleProviderKey]
                }
                var saving by remember { mutableStateOf(false) }
                if (setup == null) GradesLoadingIndicator(Modifier.fillMaxSize())
                else WidgetConfigureScreen("Schedule widget", setup!!.first, setup!!.second, saving,
                    onBack = ::finish, onSave = { provider ->
                        saving = true
                        lifecycleScope.launch {
                            val id = AppWidgetId(widgetId)
                            updateAppWidgetState(this@ScheduleWidgetConfigureActivity, id) {
                                it[scheduleProviderKey] = provider.id
                                it.remove(scheduleDaysKey)
                                it.remove(scheduleUpdatedKey)
                                it.remove(scheduleErrorKey)
                            }
                            try {
                                val days = withContext(Dispatchers.IO) {
                                    val previousCookies = (provider as? com.feldman.scholix.api.platforms.WebtopPlatform)?.cookies
                                    try {
                                        fetchScheduleWidgetDays(provider, java.time.LocalDate.now())
                                    } finally {
                                        if (provider is com.feldman.scholix.api.platforms.WebtopPlatform)
                                            PlatformStorage.saveWebtopSession(this@ScheduleWidgetConfigureActivity, provider, previousCookies)
                                    }
                                }
                                updateAppWidgetState(this@ScheduleWidgetConfigureActivity, id) {
                                    it[scheduleDaysKey] = days.toString()
                                    it[scheduleUpdatedKey] = System.currentTimeMillis().toString()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Background work retries without requiring the main app to open.
                            }
                            ScheduleWidget().update(this@ScheduleWidgetConfigureActivity, id)
                            ScheduleWidgetWorker.schedule(this@ScheduleWidgetConfigureActivity)
                            ScheduleWidgetWorker.refresh(this@ScheduleWidgetConfigureActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                            finish()
                        }
                    })
            }
        }
    }
}
