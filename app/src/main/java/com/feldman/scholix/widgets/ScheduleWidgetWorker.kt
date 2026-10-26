package com.feldman.scholix.widgets

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.*
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.ShahafPlatform
import com.feldman.scholix.pages.shahafWidgetLessons
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration
import java.time.ZoneId
import com.feldman.scholix.pages.hourOf
import com.feldman.scholix.pages.parseLessonEndTimeMinutes
import com.feldman.scholix.pages.WINDOW_FLAG
import java.util.concurrent.TimeUnit

internal val scheduleProviderKey = stringPreferencesKey("schedule_provider")
internal val scheduleDaysKey = stringPreferencesKey("schedule_days")
internal val scheduleUpdatedKey = stringPreferencesKey("schedule_updated")
internal val scheduleErrorKey = stringPreferencesKey("schedule_error")

class ScheduleWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ids = GlanceAppWidgetManager(applicationContext).getGlanceIds(ScheduleWidget::class.java)
        if (ids.isEmpty()) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(applicationContext).cancelUniqueWork("schedule-widget-boundary")
            return Result.success()
        }
        val platforms = PlatformStorage.loadPlatforms(applicationContext)
        val today = LocalDate.now()
        var nextUpdate = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        for (id in ids) {
            val prefs = getAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id)
            val platform = platforms.firstOrNull { it.id == prefs[scheduleProviderKey] }
            try {
                check(platform != null) { "Choose a schedule provider" }
                val info = platform.getInfo()
                val days = JSONObject()
                val published = (platform as? ShahafPlatform)?.getAppData()
                for (date in listOf(today, today.plusDays(1))) {
                    val day = date.dayOfWeek.value % 7
                    val lessons = if (published != null) shahafWidgetLessons(published, day) else {
                        val response = if (day == 6 && !info.optBoolean("supportsSaturdaySchedule")) JSONObject()
                            else platform.getSchedule(day, null, info.optString("scheduleSelection").ifBlank { null })
                        check(!response.has("error")) { "Open Scholix to reconnect, then refresh" }
                        response.keys().asSequence().map { response.getJSONObject(it) }.toList()
                    }
                    days.put(date.toString(), JSONArray(lessons))
                }
                val todayLessons = days.getJSONArray(today.toString()).let { array ->
                    (0 until array.length()).map { array.getJSONObject(it) }
                }
                val windows = PlatformStorage.loadWindowSubjects(applicationContext, platform.id)
                val end = todayLessons.filter {
                    !it.optBoolean(WINDOW_FLAG) && it.optString("colorClass") != "cancel-cell" &&
                        !PlatformStorage.isWindowSubject(it.optString("subject"), windows)
                }.maxOfOrNull { parseLessonEndTimeMinutes(it.optString("time"), hourOf(it)) }
                if (end != null && end in 0..1439 && LocalTime.now() < LocalTime.of(end / 60, end % 60)) {
                    val boundary = today.atTime(end / 60, end % 60).atZone(ZoneId.systemDefault()).toInstant()
                    if (boundary < nextUpdate) nextUpdate = boundary
                }
                updateAppWidgetState(applicationContext, id) {
                    it[scheduleDaysKey] = days.toString()
                    it[scheduleUpdatedKey] = System.currentTimeMillis().toString()
                    it.remove(scheduleErrorKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateAppWidgetState(applicationContext, id) {
                    it[scheduleErrorKey] = if (platform == null) "Choose a schedule provider"
                        else "Could not refresh · Open Scholix to reconnect"
                }
            }
            ScheduleWidget().update(applicationContext, id)
        }
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "schedule-widget-boundary", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScheduleWidgetBoundaryWorker>()
                .setInitialDelay(Duration.between(java.time.Instant.now(), nextUpdate).toMillis().coerceAtLeast(1000), TimeUnit.MILLISECONDS)
                .build(),
        )
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "schedule-widget-periodic"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScheduleWidgetWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "schedule-widget-refresh", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScheduleWidgetWorker>().build(),
            )
        }
    }
}

class ScheduleWidgetBoundaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ScheduleWidget().updateAll(applicationContext)
        ScheduleWidgetWorker.refresh(applicationContext)
        return Result.success()
    }
}
