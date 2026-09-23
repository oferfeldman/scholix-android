package com.feldman.scholix.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.DemoPlatform
import com.feldman.scholix.services.GradeMonitorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

val Green = Color(0xFF4CAF50)
val Red = Color(0xFFF44336)
val Gray = Color(0xFF9E9E9E)

internal val courseNameKey = stringPreferencesKey("course_name")

fun gradeColorGlance(gradeStr: String): ColorProvider {
    val grade = gradeStr.toIntOrNull()
        ?: return ColorProvider(day = Gray, night = Gray)

    val clamped = grade.coerceIn(0, 100)

    return if (clamped >= 60) {
        ColorProvider(day = Green, night = Green)
    } else {
        ColorProvider(day = Red, night = Red)
    }
}


class GradesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GradesWidget()
}

class GradesWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val prefs = currentState<Preferences>()
                val courseName = prefs[courseNameKey] ?: "Choose a course"
                GradesWidgetContent(context, id, courseName)
            }
        }
    }

    // Preview API depends on Glance version
    /*
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent {
            GlanceTheme {
                val demoPlatform = DemoPlatform()
                val courseName = "Preview Course"
                val grades = demoPlatform.getGrades(courseName)
                GradesWidgetContent(context, null, courseName, grades)
            }
        }
    }
    */
}

class RefreshGradesAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withContext(Dispatchers.Main) {
            // Trigger background refresh
            GradeMonitorWorker.schedule(context)
        }

        // Force widget redraw
        GradesWidget().update(context, glanceId)
    }
}

@Composable
fun GradesWidgetContent(context: Context, glanceId: GlanceId?, courseName: String, previewGrades: JSONArray? = null) {
    val state by produceState<Pair<String, JSONArray>?>(initialValue = null, courseName) {
        if (previewGrades != null) {
            value = Pair(courseName, previewGrades)
            return@produceState
        }
        // 🔁 Always request background refresh
        withContext(Dispatchers.Main) {
            GradeMonitorWorker.schedule(context)
        }

        withContext(Dispatchers.IO) {
            try {
                if (courseName == "Choose a course") {
                    value = Pair("Choose a course", JSONArray())
                    return@withContext
                }

                // 1️⃣ Try cache first
                val cached = loadCachedGrades(context)

                if (cached != null && cached.has(courseName)) {
                    val gradesArray = cached.optJSONArray(courseName) ?: JSONArray()
                    value = Pair(courseName, gradesArray)
                    return@withContext
                }

                // 2️⃣ Fallback: no cache yet
                val courses = PlatformStorage.getCourses(context)
                val course = courses.find { it.optString("name") == courseName }
                if (course == null) {
                    value = Pair("Error", JSONArray())
                    return@withContext
                }

                val platformIndex = course.optInt("index")
                val platform = PlatformStorage.loadPlatforms(context).getOrNull(platformIndex)

                if (platform == null) {
                    value = Pair("Error", JSONArray())
                    return@withContext
                }

                val gradesArray = platform.getGrades(courseName)

                value = Pair(courseName, gradesArray)


            } catch (e: Exception) {
                Log.e("GradesWidget", "Failed to load widget data", e)
                value = Pair("Error", JSONArray())
            }
        }
    }
    val baseModifier = GlanceModifier
        .fillMaxSize()
        .background(GlanceTheme.colors.background)
        .padding(8.dp)

    val finalModifier = if (glanceId != null) {
        // 1. Get the integer ID for the intent
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)

        // 2. Create the Intent beforehand
        val intent = Intent(context, GradesWidgetConfigureActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // 3. Pass the Intent to the Glance action
        baseModifier.clickable(actionStartActivity(intent))
    } else {
        baseModifier
    }

    Column(
        modifier = finalModifier,
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        when {
            state == null -> {
                Text(
                    text = "Loading grades…",
                    style = TextStyle(color = GlanceTheme.colors.onBackground)
                )
            }

            state!!.first == "Choose a course" -> {
                Text(
                    text = "Tap to configure",
                    style = TextStyle(color = GlanceTheme.colors.onBackground)
                )
            }

            state!!.second.length() == 0 -> {
                Text(
                    text = "No grades available for ${state!!.first}",
                    style = TextStyle(color = GlanceTheme.colors.onBackground)
                )
            }

            else -> {
                val (courseName, gradesArray) = state!!
                val (grades, average, finalGrade) = processGrades(gradesArray)

                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        text = courseName,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onBackground
                        )
                    )

                    Button(
                        text = "Refresh",
                        onClick = actionRunCallback<RefreshGradesAction>()
                    )
                }


                Spacer(GlanceModifier.height(8.dp))

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Average: ", style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground))
                    Text(
                        String.format("%.1f", average),
                        style = TextStyle(color = gradeColorGlance(average.toString()))
                    )
                }

                finalGrade?.let {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Final Grade: ", style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground))
                        Text(
                            it.optString("grade"),
                            style = TextStyle(color = gradeColorGlance(it.optString("grade")))
                        )
                    }
                }

                Spacer(GlanceModifier.height(8.dp))
                LazyColumn(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .cornerRadius(12.dp)
                ) {
                    items(grades.size) { it ->
                        val grade = grades[it]

                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
//                                .padding(horizontal = 6.dp)
                                .padding(
                                    top = if (it == 0) 0.dp else 1.dp,
                                    bottom = if (it == grades.lastIndex) 0.dp else 1.dp
                                )
                        ) {
                            Column(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .background(
                                        GlanceTheme.colors.surfaceVariant
                                    )
                                    .cornerRadius(2.dp)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = GlanceModifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = GlanceModifier.defaultWeight()
                                    ) {
                                        // Subject
                                        Text(
                                            text = grade.optString("subject", "Unknown"),
                                            style = TextStyle(
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp,
                                                color = GlanceTheme.colors.onBackground
                                            )
                                        )

                                        // Assignment name
                                        val assignmentName = grade.optString("name")
                                        if (assignmentName.isNotBlank()) {
                                            Text(
                                                text = assignmentName,
                                                style = TextStyle(
                                                    fontSize = 12.sp,
                                                    color = GlanceTheme.colors.onSurfaceVariant
                                                )
                                            )
                                        }
                                    }

                                    Text(
                                        text = grade.optString("grade", "-"),
                                        style = TextStyle(
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GlanceTheme.colors.primary
                                        )
                                    )
                                }

                            }
                        }

                    }
                }

            }
        }
    }
}

fun processGrades(gradesArray: JSONArray): Triple<List<JSONObject>, Float, JSONObject?> {
    val list = mutableListOf<JSONObject>()
    var finalGrade: JSONObject? = null
    var sum = 0f
    var count = 0

    for (i in 0 until gradesArray.length()) {
        val grade = gradesArray.optJSONObject(i) ?: continue

        val gradeStr = grade.optString("grade")
        if (
            gradeStr.isEmpty() ||
            gradeStr == "null" ||
            gradeStr == "0"
        ) continue

        if (grade.optString("type") == "final") {
            finalGrade = grade
            continue
        }

        val g = gradeStr.toFloatOrNull()
        if (g != null) {
            sum += g
            count++
        }

        list.add(grade)
    }

    val avg = if (count > 0) sum / count else 0f
    return Triple(list, avg, finalGrade)
}

private fun loadCachedGrades(context: Context): JSONObject? {
    val prefs = context.getSharedPreferences("grades_cache", Context.MODE_PRIVATE)
    val json = prefs.getString("grades_json", null)
    return json?.let {
        try {
            JSONObject(it)
        } catch (_: Exception) {
            null
        }
    }
}
