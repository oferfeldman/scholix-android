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
import kotlinx.coroutines.launch

class ScheduleWidgetReceiver : android.appwidget.AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in listOf(ACTION_REFRESH, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            ScheduleWidgetWorker.refresh(context)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, ids)
        ScheduleWidgetWorker.schedule(context)
        ScheduleWidgetWorker.refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        render(context, intArrayOf(id))
    }

    private fun render(context: Context, ids: IntArray) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                ids.forEach { ScheduleWidget().update(context, AppWidgetId(it)) }
            } finally { pending.finish() }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                ids.forEach { id ->
                    androidx.glance.appwidget.state.updateAppWidgetState(context, AppWidgetId(id)) { it.clear() }
                }
            } finally { pending.finish() }
        }
    }

    override fun onDisabled(context: Context) {
        ScheduleWidgetWorker.refresh(context)
    }

    companion object {
        internal const val ACTION_REFRESH = "com.feldman.scholix.REFRESH_SCHEDULE_WIDGET"
    }
}

internal fun scheduleWidgetIds(context: Context): List<AppWidgetId> =
    AppWidgetManager.getInstance(context).getAppWidgetIds(
        android.content.ComponentName(context, ScheduleWidgetReceiver::class.java)
    ).map { AppWidgetId(it) }

class ScheduleWidget {
    suspend fun update(context: Context, id: AppWidgetId) {
        val manager = AppWidgetManager.getInstance(context)
        val prefs = androidx.glance.appwidget.state.getAppWidgetState(context, androidx.glance.state.PreferencesGlanceStateDefinition, id)
        val options = manager.getAppWidgetOptions(id.appWidgetId)
        val sizes = if (Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) else null
        val views = if (Build.VERSION.SDK_INT >= 31 && !sizes.isNullOrEmpty()) {
            RemoteViews(sizes.associateWith { compose(context, DpSize(it.width.dp, it.height.dp), prefs, id.appWidgetId) })
        } else compose(context, DpSize(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).dp,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 220).dp,
        ), prefs, id.appWidgetId)
        manager.updateAppWidget(id.appWidgetId, views)
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    internal suspend fun compose(context: Context, size: DpSize, state: Preferences, widgetId: Int = 0): RemoteViews {
        val prefs = state
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
        val renderer = GlanceRemoteViews()
        val rendered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) rows.mapIndexed { index, item ->
            renderer.compose(context, DpSize(size.width, 100.dp)) {
                GlanceTheme { ScheduleWidgetLesson(context, item, index, numberedPeriods) }
            }.remoteViews.apply { setOnClickFillInIntent(android.R.id.background, Intent()) }
        } else emptyList()
        val views = renderer.compose(context, size) {
            GlanceTheme {
                Box(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(24.dp).clickable(open).padding(4.dp)) {
                    if (rows.isEmpty() && days.has(date.toString())) {
                        WidgetEmptyState(context, size, if (date == today) context.getString(R.string.noSchedule)
                            else context.getString(R.string.noScheduleForDay,
                                date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", context.resources.configuration.locales[0]))))
                    } else if (rows.isEmpty()) {
                        Text(prefs[scheduleErrorKey] ?: "Loading schedule\u2026",
                            GlanceModifier.padding(16.dp), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                    } else {
                        AndroidRemoteViews(RemoteViews(context.packageName, R.layout.schedule_widget_list), GlanceModifier.fillMaxSize())
                    }
                    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                        Text("\u21bb", GlanceModifier.background(GlanceTheme.colors.background).cornerRadius(20.dp)
                            .clickable(androidx.glance.appwidget.action.actionSendBroadcast(
                                Intent(context, ScheduleWidgetReceiver::class.java).setAction(ScheduleWidgetReceiver.ACTION_REFRESH)))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = TextStyle(fontSize = 18.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    }
                }
            }
        }.remoteViews
        if (rows.isNotEmpty()) {
            // Adapter actions must belong to the root RemoteViews. Async launcher inflation
            // passes a nested view's container as rootParent, which Android rejects as a widget host.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true)
                    .setViewTypeCount(rendered.size.coerceAtLeast(1))
                rendered.forEachIndexed { index, row -> items.addItem(index.toLong(), row) }
                views.setRemoteAdapter(R.id.schedule_widget_list, RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true).setViewTypeCount(rendered.size.coerceAtLeast(1)).build())
                views.setRemoteAdapter(R.id.schedule_widget_list, items.build())
            } else {
                val adapter = Intent(context, ScheduleWidgetListService::class.java).apply {
                    putExtra("lessons", JSONArray(rows).toString())
                    putExtra("numberedPeriods", numberedPeriods)
                    putExtra("width", size.width.value)
                    data = Uri.parse("scholix://schedule-widget/$widgetId/$date/${prefs[scheduleUpdatedKey]}/${size.width.value}")
                }
                views.setRemoteAdapter(R.id.schedule_widget_list, adapter)
            }
            views.setPendingIntentTemplate(R.id.schedule_widget_list, PendingIntent.getActivity(
                context, widgetId, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            views.setScrollPosition(R.id.schedule_widget_list, firstVisible)
        }
        return views
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
internal fun WidgetEmptyState(context: Context, size: DpSize, message: String, icon: String = "schedule") {
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val compact = size.height < 220.dp
    val width = ((size.width.value - 40f) * density).toInt().coerceAtLeast(1)
    val iconSize = (if (compact) 48f else 128f) * density
    val gap = (if (compact) 12f else 20f) * density
    val titlePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, com.feldman.motion.R.font.feldman_font)
        fontVariationSettings = "'wght' 600, 'wdth' 140, 'ROND' 200"
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (compact) 20f else 28f, metrics)
        color = GlanceTheme.colors.onSurface.getColor(context).toArgb()
    }
    val textWidth = (if (compact) width - iconSize - gap else width.toFloat()).toInt().coerceAtLeast(1)
    val title = android.text.StaticLayout.Builder.obtain(message, 0, message.length, titlePaint, textWidth)
        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    val height = (if (compact) maxOf(iconSize, title.height.toFloat()) else iconSize + gap + title.height).toInt()
    // Bake the variable font into the image so every launcher preserves Feldman's rounded axes.
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val iconX = if (compact) 0f else (width - iconSize) / 2f
    val iconY = if (compact) (height - iconSize) / 2f else 0f
    val path = androidx.compose.material3.MaterialShapes.Cookie12Sided.toPath()
    path.transform(android.graphics.Matrix().apply { setScale(iconSize, iconSize); postTranslate(iconX, iconY) })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlanceTheme.colors.primary.getColor(context).toArgb()
    })
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GlanceTheme.colors.onPrimary.getColor(context).toArgb()
        textSize = iconSize * 68f / 128f
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, com.feldman.motion.R.font.material_symbols_rounded)
    }
    canvas.drawText(icon, iconX + iconSize / 2f,
        iconY + iconSize / 2f - (iconPaint.ascent() + iconPaint.descent()) / 2f, iconPaint)
    canvas.save()
    canvas.translate(if (compact) iconSize + gap else 0f,
        if (compact) (height - title.height) / 2f else iconSize + gap)
    title.draw(canvas)
    canvas.restore()
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(ImageProvider(bitmap), message, GlanceModifier.width((width / density).dp).height((height / density).dp))
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
