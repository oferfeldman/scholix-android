package com.feldman.scholix.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import org.json.JSONArray
import org.json.JSONObject

val Green = Color(0xFF4CAF50)
val Red = Color(0xFFF44336)
val Gray = Color(0xFF9E9E9E)

internal val gradesUpdatedKey = stringPreferencesKey("grades_updated")
internal val gradesDataKey = stringPreferencesKey("grades_data")
internal val gradesErrorKey = stringPreferencesKey("grades_error")
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
        val saved = androidx.glance.appwidget.state.getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        if (System.currentTimeMillis() - (saved[gradesUpdatedKey]?.toLongOrNull() ?: 0) > 60_000) refreshGradesWidgetState(context, id)
        val loaded = androidx.glance.appwidget.state.getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        provideContent {
            GlanceTheme {
                val current = currentState<Preferences>()
                // The session's initial state was read before the network request completed.
                val prefs = if ((current[gradesUpdatedKey]?.toLongOrNull() ?: 0) > (loaded[gradesUpdatedKey]?.toLongOrNull() ?: 0)) current else loaded
                val courseName = if (prefs[gradesProviderKey] != null) "Grades" else prefs[courseNameKey] ?: "Choose a course"
                GradesWidgetContent(context, id, courseName, prefs[gradesDataKey]?.let(::JSONArray), error = prefs[gradesErrorKey])
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
        refreshGradesWidgetState(context, glanceId)
        GradesWidget().update(context, glanceId)
    }
}

@Composable
fun GradesWidgetContent(context: Context, glanceId: GlanceId?, courseName: String, previewGrades: JSONArray? = null, error: String? = null) {
    val state = previewGrades?.let { Pair(courseName, it) }
    val processed = previewGrades?.let { com.feldman.scholix.pages.processGrades(it) }
    val baseModifier = GlanceModifier
        .fillMaxSize()
        .background(GlanceTheme.colors.background)
        .cornerRadius(24.dp)
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

    androidx.glance.layout.Box(modifier = finalModifier) {
        when {
            state == null -> Text(error ?: "Loading grades\u2026", GlanceModifier.padding(16.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
            state!!.first == "Choose a course" -> Text("Tap to configure", GlanceModifier.padding(16.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
            processed != null && processed.first.isEmpty() && processed.third == null ->
                WidgetEmptyState(context, androidx.glance.LocalSize.current, "No grades yet", "school")
            else -> {
                val (grades, average, finalGrade) = processed!!
                val width = (androidx.glance.LocalSize.current.width.value - 48f).coerceAtLeast(1f)
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
                        val bitmap = gradeWidgetSummary(context, label, value, width, onPrimary)
                        GradeWidgetCard(context, bitmap, "$label $value", primary.toArgb(), com.feldman.scholix.R.drawable.grade_widget_summary, true, 2.dp)
                    }
                    finalGrade?.let { grade -> item {
                        val label = context.getString(com.feldman.scholix.R.string.final_grade)
                        val value = grade.optString("grade")
                        val bitmap = gradeWidgetSummary(context, label, value, width, onPrimary)
                        GradeWidgetCard(context, bitmap, "$label $value", primary.toArgb(), com.feldman.scholix.R.drawable.grade_widget_summary, true, 2.dp)
                    } }
                    items(grades.size) { index ->
                        val grade = grades[index]
                        val fraction = ((grade.optString("grade").toFloatOrNull() ?: 100f) / 100f).coerceIn(0f, 1f)
                        val bitmap = gradeWidgetRow(context, grade, width, foreground, supporting,
                            iconBackground, iconForeground, androidx.compose.ui.graphics.lerp(secondary, primary, fraction).toArgb())
                        val shape = when {
                            grades.size == 1 -> com.feldman.scholix.R.drawable.grade_widget_alone
                            index == 0 -> com.feldman.scholix.R.drawable.grade_widget_first
                            index == grades.lastIndex -> com.feldman.scholix.R.drawable.grade_widget_last
                            else -> com.feldman.scholix.R.drawable.grade_widget_middle
                        }
                        GradeWidgetCard(context, bitmap, "${grade.optString("name")} ${grade.optString("subject")} ${grade.optString("grade")}",
                            surface, shape, false, if (index == grades.lastIndex) 0.dp else 2.dp)
                    }
                }
            }
        }
        if (error != null && state != null) androidx.glance.layout.Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Text(error, GlanceModifier.fillMaxWidth().background(GlanceTheme.colors.background).padding(start = 8.dp, end = 56.dp, bottom = 4.dp),
                style = TextStyle(color = GlanceTheme.colors.error, fontSize = 12.sp), maxLines = 2)
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
