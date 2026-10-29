package com.feldman.scholix.widgets

import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.TypedValue
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking

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
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
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
    val rows = allRows
    val numberedPeriods = provider?.getInfo()?.optBoolean("numberedLessonPeriods", true) != false
    val openIntent = Intent(context, MainActivity::class.java).apply {
        putExtra("open_schedule", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val open = actionStartActivity(openIntent)
    val widgetId = (LocalGlanceId.current as AppWidgetId).appWidgetId
    Box(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(24.dp).clickable(open).padding(4.dp)) {
        if (rows.isEmpty()) {
            Text(if (days.has(date.toString())) "No lessons scheduled" else prefs[scheduleErrorKey] ?: "Loading schedule…",
                GlanceModifier.padding(16.dp), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
        } else {
            val adapter = Intent(context, ScheduleWidgetListService::class.java).apply {
                putExtra("lessons", JSONArray(rows).toString())
                putExtra("numberedPeriods", numberedPeriods)
                putExtra("width", LocalSize.current.width.value)
                data = Uri.parse("scholix://schedule-widget/$widgetId/$date/${prefs[scheduleUpdatedKey]}/${LocalSize.current.width.value}")
            }
            val size = LocalSize.current
            val rendered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) remember(rows.toString(), numberedPeriods, size) {
                val renderer = GlanceRemoteViews()
                runBlocking {
                    rows.mapIndexed { index, item ->
                        renderer.compose(context, DpSize(size.width, 100.dp)) {
                            GlanceTheme { ScheduleWidgetLesson(context, item, index, numberedPeriods) }
                        }.remoteViews.apply { setOnClickFillInIntent(android.R.id.background, Intent()) }
                    }
                }
            } else emptyList()
            val list = RemoteViews(context.packageName, R.layout.schedule_widget_list).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true)
                        .setViewTypeCount(rendered.size.coerceAtLeast(1))
                    rendered.forEachIndexed { index, row -> items.addItem(index.toLong(), row) }
                    // The data change defers scrolling until ListView has laid out its first rows.
                    setRemoteAdapter(R.id.schedule_widget_list, RemoteViews.RemoteCollectionItems.Builder()
                        .setHasStableIds(true).setViewTypeCount(rendered.size.coerceAtLeast(1)).build())
                    setRemoteAdapter(R.id.schedule_widget_list, items.build())
                } else {
                    setRemoteAdapter(R.id.schedule_widget_list, adapter)
                }
                setPendingIntentTemplate(R.id.schedule_widget_list, PendingIntent.getActivity(
                    context, widgetId, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                setScrollPosition(R.id.schedule_widget_list, firstVisible)
            }
            AndroidRemoteViews(list, GlanceModifier.fillMaxSize())
        }
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Text("↻", GlanceModifier.background(GlanceTheme.colors.background).cornerRadius(20.dp)
                .clickable(actionRunCallback<RefreshScheduleAction>()).padding(horizontal = 12.dp, vertical = 8.dp),
                style = TextStyle(fontSize = 18.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
    }
}

@Composable
internal fun ScheduleWidgetLesson(context: Context, item: JSONObject, index: Int, numberedPeriods: Boolean) {
    val subject = item.optString("subject")
    val free = item.optBoolean(WINDOW_FLAG)
    val title = if (free) context.getString(R.string.free_period) else subject + item.optString("subjectLevel").takeIf { it.isNotBlank() && !subject.contains(it) }.let { it?.let { " · $it" }.orEmpty() }
    val muted = free || item.optString("colorClass") == "cancel-cell"
    val colors = scheduleColor(subject)
    val foreground = if (muted) GlanceTheme.colors.onSurfaceVariant else ColorProvider(colors.onColor)
    Row(GlanceModifier.fillMaxWidth().appWidgetBackground().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            GlanceModifier.defaultWeight().background(if (muted) GlanceTheme.colors.surfaceVariant else ColorProvider(colors.color))
                .cornerRadius(20.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                ScheduleWidgetText(context, title, 16f, foreground, 2, bold = true)
                val details = listOf(item.optString("teacher"), item.optString("room").takeIf { it.isNotBlank() }?.let { context.getString(R.string.room) + ": " + it }.orEmpty()).filter { it.isNotBlank() }.joinToString(", ")
                if (!free && details.isNotBlank()) ScheduleWidgetText(context, details, 14f, foreground, 2)
                val changes = item.optString("changes").ifBlank { item.optString("exams") }
                if (changes.isNotBlank()) ScheduleWidgetText(context, changes, 14f, foreground, 3)
            }
            if (!muted) Image(
                provider = ImageProvider(scheduleSubjectIcon(context, subject, colors.onColor, index, item.optString("exams"))),
                contentDescription = null, modifier = GlanceModifier.padding(start = 8.dp).size(44.dp),
            )
        }
        val hourColor = GlanceTheme.colors.onSurfaceVariant
        Box(GlanceModifier.width(60.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) {
            Column(GlanceModifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                if (numberedPeriods && hourOf(item) >= 0) Text(hourOf(item).toString(), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = hourColor))
                item.optString("time").split(Regex("\\s*[-–]\\s*|\\n")).filter { it.isNotBlank() }.forEach {
                    Text(it, style = TextStyle(fontSize = 10.sp, color = hourColor), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ScheduleWidgetText(context: Context, text: String, size: Float, color: ColorProvider, maxLines: Int, bold: Boolean = false) {
    val content = SpannableString(text).apply {
        if (bold) setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }
    val view = RemoteViews(context.packageName, R.layout.schedule_widget_text).apply {
        setTextViewText(R.id.schedule_widget_text, content)
        setTextViewTextSize(R.id.schedule_widget_text, TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(R.id.schedule_widget_text, color.getColor(context).toArgb())
        setInt(R.id.schedule_widget_text, "setMaxLines", maxLines)
    }
    AndroidRemoteViews(view, GlanceModifier.fillMaxWidth())
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
