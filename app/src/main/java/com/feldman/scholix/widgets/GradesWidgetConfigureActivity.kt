package com.feldman.scholix.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.feldman.motion.MotionTheme
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.components.providerLabel
import com.feldman.scholix.pages.GradesLoadingIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GradesWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent {
            MotionTheme {
                val setup by produceState<Pair<List<Platform>, String?>?>(null) {
                    val prefs = getAppWidgetState(this@GradesWidgetConfigureActivity, PreferencesGlanceStateDefinition, AppWidgetId(widgetId))
                    val providers = withContext(Dispatchers.IO) {
                        PlatformStorage.loadPlatforms(this@GradesWidgetConfigureActivity).filter { it.suportsGrades }
                    }
                    value = providers to (prefs[gradesProviderKey] ?: providers.firstOrNull {
                        it.providerLabel() == prefs[courseNameKey]
                    }?.id)
                }
                var saving by remember { mutableStateOf(false) }
                if (setup == null) GradesLoadingIndicator(Modifier.fillMaxSize())
                else WidgetConfigureScreen("Grades widget", setup!!.first, setup!!.second, saving,
                    onBack = ::finish, onSave = { provider ->
                        saving = true
                        lifecycleScope.launch {
                            val id = AppWidgetId(widgetId)
                            updateAppWidgetState(this@GradesWidgetConfigureActivity, id) {
                                it[gradesProviderKey] = provider.id
                                it.remove(courseNameKey)
                            }
                            GradesWidget().update(this@GradesWidgetConfigureActivity, id)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                            finish()
                        }
                    })
            }
        }
    }
}
