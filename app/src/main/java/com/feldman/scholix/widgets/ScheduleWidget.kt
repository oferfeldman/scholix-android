package com.feldman.scholix.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.graphics.shapes.toPath
import com.feldman.scholix.MainActivity
import com.feldman.scholix.R
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.WINDOW_FLAG
import com.feldman.scholix.pages.hourOf
import com.feldman.scholix.pages.scheduleColor
import com.feldman.scholix.pages.withFreePeriods
import com.feldman.scholix.ui.components.subjectIconName
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ScheduleWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in listOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            ScheduleWidgetWorker.refresh(context)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        ScheduleWidgetWorker.schedule(context)
        ScheduleWidgetWorker.refresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        ScheduleWidgetWorker.refresh(context)
    }
}

class ScheduleWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                ScheduleWidgetContent(context, id, currentState())
            }
        }
    }
}

class RefreshScheduleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        ScheduleWidgetWorker.refresh(context)
    }
}

@Composable
private fun ScheduleWidgetContent(context: Context, id: GlanceId, prefs: Preferences) {
    val provider = PlatformStorage.loadPlatforms(context).firstOrNull { it.id == prefs[scheduleProviderKey] }
    val windows = provider?.let { PlatformStorage.loadWindowSubjects(context, it.id) }.orEmpty()
    val days = JSONObject(prefs[scheduleDaysKey] ?: "{}")
    val today = LocalDate.now()
    fun lessons(date: LocalDate): List<JSONObject> {
        val array = days.optJSONArray(date.toString()) ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    val date = if (days.has(today.toString())) scheduleWidgetDate(today, LocalTime.now(), lessons(today), windows) else today
    val rows = withFreePeriods(
        lessons(date), windows,
        synthesizeGaps = provider?.getInfo()?.optBoolean("numberedLessonPeriods", true) != false &&
            provider !is com.feldman.scholix.api.platforms.ShahafPlatform,
    ).flatten()
    val configure = Intent(context, ScheduleWidgetConfigureActivity::class.java).putExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID, GlanceAppWidgetManager(context).getAppWidgetId(id),
    )
    val open = actionStartActivity(Intent(context, MainActivity::class.java).apply {
        putExtra("open_schedule", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    })
    Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(24.dp).padding(16.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(open)) {
                Text(context.getString(R.string.schedule), style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground))
                Text(provider?.platformDisplayName ?: "Choose a provider", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            }
            Text("↻", GlanceModifier.padding(12.dp).clickable(actionRunCallback<RefreshScheduleAction>()), style = TextStyle(fontSize = 24.sp, color = GlanceTheme.colors.primary))
            Text("⚙", GlanceModifier.padding(12.dp).clickable(actionStartActivity(configure)), style = TextStyle(fontSize = 20.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
        val dayLabel = date.format(DateTimeFormatter.ofPattern("EEEE · d MMM"))
        Text(dayLabel, GlanceModifier.padding(vertical = 10.dp), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.primary))
        val error = prefs[scheduleErrorKey]
        if (error != null || !days.has(date.toString())) {
            Text(error ?: "Loading schedule…", GlanceModifier.padding(bottom = 8.dp).clickable(open), style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
        LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (rows.isEmpty() && days.has(date.toString())) {
                item { Text("No lessons scheduled", GlanceModifier.padding(16.dp), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)) }
            }
            items(rows.size) { index ->
                val item = rows[index]
                val subject = item.optString("subject")
                val free = item.optBoolean(WINDOW_FLAG)
                val muted = free || item.optString("colorClass") == "cancel-cell"
                val colors = scheduleColor(subject)
                val foreground = if (muted) GlanceTheme.colors.onSurfaceVariant else ColorProvider(colors.onColor)
                Row(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        GlanceModifier.defaultWeight().background(if (muted) GlanceTheme.colors.surfaceVariant else ColorProvider(colors.color))
                            .cornerRadius(20.dp).clickable(open).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(GlanceModifier.defaultWeight()) {
                            Text(if (free) context.getString(R.string.free_period) else subject + item.optString("subjectLevel").takeIf { it.isNotBlank() && !subject.contains(it) }.let { it?.let { " · $it" }.orEmpty() },
                                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = foreground), maxLines = 2)
                            val details = listOf(item.optString("teacher"), item.optString("room").takeIf { it.isNotBlank() }?.let { context.getString(R.string.room) + ": " + it }.orEmpty()).filter { it.isNotBlank() }.joinToString(", ")
                            if (!free && details.isNotBlank()) Text(details, style = TextStyle(fontSize = 14.sp, color = foreground), maxLines = 2)
                            val changes = item.optString("changes").ifBlank { item.optString("exams") }
                            if (changes.isNotBlank()) Text(changes, style = TextStyle(fontSize = 14.sp, color = foreground), maxLines = 3)
                        }
                        if (!muted) Image(
                            provider = ImageProvider(scheduleSubjectIcon(context, subject, colors.onColor, index, item.optString("exams"))),
                            contentDescription = null, modifier = GlanceModifier.padding(start = 8.dp).size(44.dp),
                        )
                    }
                    Column(GlanceModifier.width(48.dp).padding(start = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (provider?.getInfo()?.optBoolean("numberedLessonPeriods", true) != false && hourOf(item) >= 0) Text(hourOf(item).toString(), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurfaceVariant))
                        item.optString("time").split(Regex("\\s*[-–]\\s*|\\n")).filter { it.isNotBlank() }.forEach {
                            Text(it, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                        }
                    }
                }
            }
        }
        prefs[scheduleUpdatedKey]?.toLongOrNull()?.let {
            val time = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            Text("Updated ${time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}", GlanceModifier.padding(top = 6.dp), style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
private fun scheduleSubjectIcon(context: Context, subject: String, color: Color, index: Int, exams: String): Bitmap {
    val bitmap = Bitmap.createBitmap(104, 104, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val shapes = listOf(
        androidx.compose.material3.MaterialShapes.Circle, androidx.compose.material3.MaterialShapes.Gem,
        androidx.compose.material3.MaterialShapes.Cookie6Sided, androidx.compose.material3.MaterialShapes.Cookie9Sided,
        androidx.compose.material3.MaterialShapes.Sunny, androidx.compose.material3.MaterialShapes.Cookie12Sided,
    )
    val path = shapes[index % shapes.size].toPath()
    path.transform(android.graphics.Matrix().apply { setScale(104f, 104f) })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.argb(92, 255, 255, 255) })
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 60f
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, com.feldman.motion.R.font.material_symbols_rounded)
    }
    canvas.drawText(subjectIconName(subject, if (exams.isNotBlank()) "exam" else ""), 52f, 52f - (paint.ascent() + paint.descent()) / 2, paint)
    return bitmap
}
