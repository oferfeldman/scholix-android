package com.feldman.scholix.services

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.feldman.scholix.api.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GradeMonitorWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔄 doWork() called — Worker executing on thread: ${Thread.currentThread().name}")
        try {
            // Clean up any legacy grade notifications or channel
            clearNotificationsAndChannel(ctx)

            val prefs = ctx.getSharedPreferences("grades_cache", Context.MODE_PRIVATE)
            val cachedJson = prefs.getString("grades_json", "{}")
            Log.d(TAG, "Loaded cached grades JSON: ${cachedJson?.take(120)}...")

            val cached = JSONObject(cachedJson ?: "{}")
            val platforms = PlatformStorage.loadPlatforms(ctx)
            Log.i(TAG, "Loaded ${platforms.size} platforms")

            var newGradesCount = 0
            var updatedGradesCount = 0

            for (platform in platforms) {
                Log.d(TAG, "Checking platform: ${platform.javaClass.simpleName}")
                if (!platform.suportsGrades) {
                    Log.d(TAG, "Platform ${platform.javaClass.simpleName} does not support grades, skipping.")
                    continue
                }
                // A platform may refresh its backing list while grades are fetched. Iterate a
                // snapshot so that refresh cannot invalidate this worker's iterator.
                val courses = PlatformStorage.getProviderCourses(applicationContext, platform)
                Log.d(TAG, "Platform ${platform.javaClass.simpleName} has ${courses.size} courses")

                for (course in courses) {
                    val courseName = course.optString("name", "unknown_course")
                    val courseIdentifier = course.optString("courseKey").ifBlank { courseName }
                    val cacheKey = "${platform.id}|$courseIdentifier"
                    Log.d(TAG, "Fetching grades for $courseName ...")

                    val latestGrades = platform.getGrades(courseIdentifier)
                    val prevGrades = cached.optJSONArray(cacheKey)
                        ?: cached.optJSONArray(courseName)
                        ?: JSONArray()
                    val merged = JSONArray(prevGrades.toString())

                    Log.d(TAG, "Found ${latestGrades.length()} latest grades, ${prevGrades.length()} cached grades")

                    for (i in 0 until latestGrades.length()) {
                        val g = latestGrades.getJSONObject(i)
                        val id = g.optString("id", g.optString("name"))
                        val gradeValue = g.optString("grade").trim()

                        // Skip invalid or placeholder grades
                        if (
                            gradeValue.isEmpty() ||
                            gradeValue == "null" ||
                            gradeValue == "0" ||
                            gradeValue.equals("N/A", ignoreCase = true)
                        ) {
                            Log.d(TAG, "⏭️ Ignoring invalid grade for ${g.optString("name")}: '$gradeValue'")
                            continue
                        }

                        val prev = (0 until prevGrades.length())
                            .map { prevGrades.getJSONObject(it) }
                            .find { it.optString("id", it.optString("name")) == id }

                        when {
                            prev == null -> {
                                Log.i(TAG, "➕ New grade detected: ${g.optString("name")} = $gradeValue")
                                merged.put(g)
                                newGradesCount++
                            }

                            prev.optString("grade") != gradeValue -> {
                                val oldValue = prev.optString("grade").trim()
                                if (
                                    oldValue.isNotEmpty() &&
                                    oldValue != "null" &&
                                    oldValue != "0" &&
                                    oldValue != gradeValue
                                ) {
                                    Log.i(TAG, "🟡 Updated grade detected: ${g.optString("name")} $oldValue → $gradeValue")
                                    updatedGradesCount++
                                    for (j in 0 until merged.length()) {
                                        val mj = merged.getJSONObject(j)
                                        if (mj.optString("id") == id) {
                                            merged.put(j, g)
                                            break
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "⏭️ Ignoring update for ${g.optString("name")} — invalid or unchanged")
                                }
                            }
                        }
                    }

                    cached.put(cacheKey, merged)
                }
            }

            prefs.edit().putString("grades_json", cached.toString()).apply()
            Log.i(TAG, "Saved updated grade cache. New: $newGradesCount, Updated: $updatedGradesCount")
            Log.i(TAG, "✅ GradeMonitorWorker finished successfully without sending notifications")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error running GradeMonitorWorker", e)
            Result.retry()
        }
    }

    companion object {
        private val TAG = this::class.java.simpleName
        private const val CHANNEL_ID = "grades_channel"

        /**
         * Clears any active grade notifications and deletes the legacy notification channel.
         */
        fun clearNotificationsAndChannel(context: Context) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager?.deleteNotificationChannel(CHANNEL_ID)
                }
                NotificationManagerCompat.from(context).cancelAll()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear grade notifications/channel", e)
            }
        }

        /**
         * Refresh grade cache silently on-demand (e.g. for widgets) without notifications.
         */
        fun schedule(context: Context) {
            Log.i(TAG, "Scheduling GradeMonitorWorker for widget cache update…")
            val request = OneTimeWorkRequestBuilder<GradeMonitorWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "grade-widget-refresh",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /**
         * Cancels any previously-scheduled periodic grade monitoring worker.
         */
        fun cancelPeriodicWorker(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("grade-monitor-worker")
        }
    }
}
