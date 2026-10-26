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
                ScheduleWidgetContent(context, currentState())
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
private fun ScheduleWidgetContent(context: Context, prefs: Preferences) {
    val provider = PlatformStorage.loadPlatforms(context).firstOrNull { it.id == prefs[scheduleProviderKey] }
    val windows = provider?.let { PlatformStorage.loadWindowSubjects(context, it.id) }.orEmpty()
    val days = JSONObject(prefs[scheduleDaysKey] ?: "{}")
    val today = LocalDate.now()
    fun lessons(date: LocalDate): List<JSONObject> {
        val array = days.optJSONArray(date.toString()) ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    val date = if (days.has(today.toString())) scheduleWidgetDate(today, LocalTime.now(), lessons(today), windows) else today
    val allRows = withFreePeriods(
        lessons(date), windows,
        synthesizeGaps = provider?.getInfo()?.optBoolean("numberedLessonPeriods", true) != false &&
            provider !is com.feldman.scholix.api.platforms.ShahafPlatform,
    ).flatten()
    val activeIndex = scheduleWidgetActiveIndex(allRows, LocalTime.now(), date == today)
    val firstVisible = (activeIndex - 1).coerceAtLeast(0)
    val rows = allRows.drop(firstVisible)
    val open = actionStartActivity(Intent(context, MainActivity::class.java).apply {
        putExtra("open_schedule", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    })
    Box(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(24.dp).clickable(open).padding(4.dp)) {
        LazyColumn(GlanceModifier.fillMaxSize()) {
            if (!days.has(date.toString())) {
                item { Text(prefs[scheduleErrorKey] ?: "Loading schedule…", GlanceModifier.padding(12.dp), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)) }
            }
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
                Row(GlanceModifier.fillMaxWidth().clickable(open).padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        GlanceModifier.defaultWeight().background(if (muted) GlanceTheme.colors.surfaceVariant else ColorProvider(colors.color))
                            .cornerRadius(20.dp).padding(12.dp),
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
                            provider = ImageProvider(scheduleSubjectIcon(context, subject, colors.onColor, index + firstVisible, item.optString("exams"))),
                            contentDescription = null, modifier = GlanceModifier.padding(start = 8.dp).size(44.dp),
                        )
                    }
                    val highlighted = index + firstVisible == activeIndex
                    val hourColor = if (highlighted) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant
                    Box(GlanceModifier.width(60.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) {
                        val badge = if (highlighted) GlanceModifier.size(56.dp).background(GlanceTheme.colors.primary).cornerRadius(28.dp)
                            else GlanceModifier.width(56.dp)
                        Column(badge, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                            if (provider?.getInfo()?.optBoolean("numberedLessonPeriods", true) != false && hourOf(item) >= 0) Text(hourOf(item).toString(), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = hourColor))
                            item.optString("time").split(Regex("\\s*[-–]\\s*|\\n")).filter { it.isNotBlank() }.forEach {
                                Text(it, style = TextStyle(fontSize = 10.sp, color = hourColor), maxLines = 1)
                            }
                        }
                    }
                }
            }
            item { Spacer(GlanceModifier.height(32.dp)) }
        }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Text("↻", GlanceModifier.background(GlanceTheme.colors.background).cornerRadius(20.dp)
                .clickable(actionRunCallback<RefreshScheduleAction>()).padding(horizontal = 12.dp, vertical = 8.dp),
                style = TextStyle(fontSize = 18.sp, color = GlanceTheme.colors.onSurfaceVariant))
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
