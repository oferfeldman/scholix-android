package com.feldman.scholix.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import com.feldman.scholix.ui.components.providerLabel
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

internal val gradesProviderKey = stringPreferencesKey("grades_provider")
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
                GradesWidgetContent(context, id, courseName, providerId = prefs[gradesProviderKey])
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
fun GradesWidgetContent(context: Context, glanceId: GlanceId?, courseName: String, previewGrades: JSONArray? = null, providerId: String? = null) {
    val state by produceState<Pair<String, JSONArray>?>(initialValue = previewGrades?.let { Pair(courseName, it) }, courseName, providerId) {
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
                if (providerId != null) {
                    val platform = PlatformStorage.loadPlatforms(context).firstOrNull { it.id == providerId }
                    check(platform != null) { "Choose a provider" }
                    val cached = loadCachedGrades(context)
                    val cachedKeys = cached?.keys()?.asSequence()?.filter { it.startsWith("$providerId|") }?.toList().orEmpty()
                    val all = JSONArray()
                    if (cachedKeys.isNotEmpty()) {
                        cachedKeys.forEach { key ->
                            val items = cached!!.getJSONArray(key)
                            for (i in 0 until items.length()) all.put(items.getJSONObject(i))
                        }
                    } else {
                        val previousCookies = (platform as? com.feldman.scholix.api.platforms.WebtopPlatform)?.cookies
                        try {
                            PlatformStorage.getProviderCourses(context, platform).forEach { course ->
                                val items = course.optJSONArray("grades") ?: platform.getGrades(
                                    course.optString("courseKey").ifBlank { course.optString("name") })
                                for (i in 0 until items.length()) all.put(items.getJSONObject(i))
                            }
                        } finally {
                            if (platform is com.feldman.scholix.api.platforms.WebtopPlatform)
                                PlatformStorage.saveWebtopSession(context, platform, previousCookies)
                        }
                    }
                    value = Pair(platform.providerLabel(), all)
                    return@withContext
                }
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
        .cornerRadius(24.dp)
        .padding(4.dp)

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

    androidx.glance.layout.Box(modifier = finalModifier) {
        when {
            state == null -> Text("Loading grades\u2026", GlanceModifier.padding(16.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
            state!!.first == "Choose a course" -> Text("Tap to configure", GlanceModifier.padding(16.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
            state!!.second.length() == 0 -> Text("No grades available", GlanceModifier.padding(16.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
            else -> {
                val (grades, average, finalGrade) = com.feldman.scholix.pages.processGrades(state!!.second)
                val width = (androidx.glance.LocalSize.current.width.value - 8f).coerceAtLeast(1f)
                val primary = GlanceTheme.colors.primary.getColor(context)
                val secondary = GlanceTheme.colors.secondary.getColor(context)
                val foreground = GlanceTheme.colors.onSurface.getColor(context).toArgb()
                val supporting = GlanceTheme.colors.onSurfaceVariant.getColor(context).toArgb()
                val surface = GlanceTheme.colors.surfaceVariant.getColor(context).toArgb()
                val onPrimary = GlanceTheme.colors.onPrimary.getColor(context).toArgb()
                val iconBackground = GlanceTheme.colors.tertiaryContainer.getColor(context).toArgb()
                val iconForeground = GlanceTheme.colors.onTertiaryContainer.getColor(context).toArgb()
                LazyColumn(GlanceModifier.fillMaxSize()) {
                    if (grades.isNotEmpty()) item {
                        val label = context.getString(com.feldman.scholix.R.string.average)
                        val value = String.format("%.1f", average)
                        val bitmap = gradeWidgetSummary(context, label, value, width, primary.toArgb(), onPrimary)
                        androidx.glance.Image(androidx.glance.ImageProvider(bitmap), "$label $value",
                            GlanceModifier.fillMaxWidth().height((bitmap.height * width / bitmap.width + 4f).dp).padding(bottom = 4.dp))
                    }
                    finalGrade?.let { grade -> item {
                        val label = context.getString(com.feldman.scholix.R.string.final_grade)
                        val value = grade.optString("grade")
                        val bitmap = gradeWidgetSummary(context, label, value, width, primary.toArgb(), onPrimary)
                        androidx.glance.Image(androidx.glance.ImageProvider(bitmap), "$label $value",
                            GlanceModifier.fillMaxWidth().height((bitmap.height * width / bitmap.width + 4f).dp).padding(bottom = 4.dp))
                    } }
                    items(grades.size) { index ->
                        val grade = grades[index]
                        val fraction = ((grade.optString("grade").toFloatOrNull() ?: 100f) / 100f).coerceIn(0f, 1f)
                        val bitmap = gradeWidgetRow(context, grade, width, index, grades.size, surface, foreground,
                            supporting, iconBackground, iconForeground, androidx.compose.ui.graphics.lerp(secondary, primary, fraction).toArgb())
                        androidx.glance.Image(androidx.glance.ImageProvider(bitmap),
                            "${grade.optString("name")} ${grade.optString("subject")} ${grade.optString("grade")}",
                            GlanceModifier.fillMaxWidth().height((bitmap.height * width / bitmap.width + 2f).dp).padding(bottom = 2.dp))
                    }
                }
            }
        }
        androidx.glance.layout.Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Text("\u21bb", GlanceModifier.background(GlanceTheme.colors.background).cornerRadius(20.dp)
                .clickable(actionRunCallback<RefreshGradesAction>()).padding(horizontal = 12.dp, vertical = 8.dp),
                style = TextStyle(fontSize = 18.sp, color = GlanceTheme.colors.onSurfaceVariant))
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
