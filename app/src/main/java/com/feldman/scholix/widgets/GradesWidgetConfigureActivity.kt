package com.feldman.scholix.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.GradesLoadingIndicator
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.feldman.lockerapp.ui.theme.AppTheme
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GradesWidgetConfigureActivity : ComponentActivity() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            AppTheme {
                val courses by produceState<List<String>?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) {
                        PlatformStorage.getCourses(this@GradesWidgetConfigureActivity).map { it.optString("name") }
                    }
                }

                if (courses == null) {
                    GradesLoadingIndicator(modifier = Modifier.fillMaxSize())
                } else {
                    GradesWidgetConfigureScreen(courses!!) { course ->
                        GlobalScope.launch(Dispatchers.Main) {
                            val glanceId = GlanceAppWidgetManager(this@GradesWidgetConfigureActivity).getGlanceIdBy(appWidgetId)
                            updateAppWidgetState(this@GradesWidgetConfigureActivity, glanceId) { prefs ->
                                prefs[courseNameKey] = course
                            }
                            GradesWidget().update(this@GradesWidgetConfigureActivity, glanceId)
                            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            setResult(RESULT_OK, resultValue)
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GradesWidgetConfigureScreen(courses: List<String>, onCourseSelected: (String) -> Unit) {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
        Text("Choose a course:", color = MaterialTheme.colorScheme.onBackground)
        courses.forEach {
            Button(onClick = { onCourseSelected(it) }) {
                Text(it)
            }
        }
    }
}

@Preview
@Composable
fun GradesWidgetConfigureScreenPreview() {
    AppTheme {
        GradesWidgetConfigureScreen(listOf("Math", "Science")) {}
    }
}
