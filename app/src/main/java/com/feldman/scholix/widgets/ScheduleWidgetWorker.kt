package com.feldman.scholix.widgets

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.*
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.platforms.ShahafPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.pages.shahafWidgetLessons
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration
import java.time.ZoneId
import com.feldman.scholix.pages.WINDOW_FLAG
import java.util.concurrent.TimeUnit

internal val scheduleProviderKey = stringPreferencesKey("schedule_provider")
internal val scheduleDaysKey = stringPreferencesKey("schedule_days")
internal val scheduleUpdatedKey = stringPreferencesKey("schedule_updated")
internal val scheduleErrorKey = stringPreferencesKey("schedule_error")

internal fun fetchScheduleWidgetDays(platform: Platform, today: LocalDate): JSONObject {
    val info = platform.getInfo()
    val published = (platform as? ShahafPlatform)?.getAppData()
    return JSONObject().apply {
        for (date in listOf(today, today.plusDays(1))) {
            val day = date.dayOfWeek.value % 7
            val lessons = if (published != null) shahafWidgetLessons(published, day) else {
                var response = if (day == 6 && !info.optBoolean("supportsSaturdaySchedule")) JSONObject()
                    else platform.getSchedule(day, null, info.optString("scheduleSelection").ifBlank { null })
                if (response.optString("error") == "login_failed" && platform.refreshCookies()) {
                    response = platform.getSchedule(day, null, info.optString("scheduleSelection").ifBlank { null })
                }
                check(!response.has("error")) { response.optString("error") }
                response.keys().asSequence().map { response.getJSONObject(it) }.toList()
            }
            put(date.toString(), JSONArray(lessons))
        }
    }
}

class ScheduleWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ids = scheduleWidgetIds(applicationContext)
        if (ids.isEmpty()) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(applicationContext).cancelUniqueWork("schedule-widget-boundary")
            return Result.success()
        }
        val platforms = PlatformStorage.loadPlatforms(applicationContext)
        val today = LocalDate.now()
        var nextUpdate = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        var failed = false
        for (id in ids) {
            val prefs = getAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, id)
            if (prefs[scheduleProviderKey] == null) continue
            val platform = platforms.firstOrNull { it.id == prefs[scheduleProviderKey] }
            try {
                check(platform != null) { "Choose a schedule provider" }
                val previousCookies = (platform as? WebtopPlatform)?.cookies
                val days = try {
                    fetchScheduleWidgetDays(platform, today)
                } finally {
                    if (platform is WebtopPlatform) PlatformStorage.saveWebtopSession(applicationContext, platform, previousCookies)
                }
                val todayLessons = days.getJSONArray(today.toString()).let { array ->
                    (0 until array.length()).map { array.getJSONObject(it) }
                }
                val windows = PlatformStorage.loadWindowSubjects(applicationContext, platform.id)
                val minute = LocalTime.now().let { it.hour * 60 + it.minute }
                val nextChange = todayLessons.filter {
                    !it.optBoolean(WINDOW_FLAG) && it.optString("colorClass") != "cancel-cell" &&
                        !PlatformStorage.isWindowSubject(it.optString("subject"), windows)
                }.flatMap { scheduleWidgetLessonMinutes(it).let { range -> listOf(range.first, range.last + 1) } }
                    .filter { it in 0..1439 && it > minute }.minOrNull()
                if (nextChange != null) {
                    val boundary = today.atTime(nextChange / 60, nextChange % 60).atZone(ZoneId.systemDefault()).toInstant()
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
                failed = failed || platform != null
                updateAppWidgetState(applicationContext, id) {
                    if (platform == null) it[scheduleErrorKey] = "Choose a schedule provider"
                    else if (runAttemptCount >= 3) it[scheduleErrorKey] = "Could not refresh · tap ↻ to retry"
                    else it.remove(scheduleErrorKey)
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
        return if (failed && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "schedule-widget-periodic"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScheduleWidgetWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
            )
        }

        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "schedule-widget-refresh", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScheduleWidgetWorker>().setConstraints(network).build(),
            )
        }
    }
}

class ScheduleWidgetBoundaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        scheduleWidgetIds(applicationContext).forEach { ScheduleWidget().update(applicationContext, it) }
        ScheduleWidgetWorker.refresh(applicationContext)
        return Result.success()
    }
}
