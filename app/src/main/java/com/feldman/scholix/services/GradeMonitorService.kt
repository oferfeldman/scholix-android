package com.feldman.scholix.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.feldman.scholix.R
import com.feldman.scholix.api.PlatformStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GradeMonitorWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔁 doWork() called — Worker executing on thread: ${Thread.currentThread().name}")
        try {
            val prefs = ctx.getSharedPreferences("grades_cache", Context.MODE_PRIVATE)
            val cachedJson = prefs.getString("grades_json", "{}")
            Log.d(TAG, "Loaded cached grades JSON: ${cachedJson?.take(120)}...")

            val cached = JSONObject(cachedJson ?: "{}")
            val platforms = PlatformStorage.loadPlatforms(ctx)
            Log.i(TAG, "Loaded ${platforms.size} platforms")

            val newGrades = mutableListOf<JSONObject>()
            val updatedGrades = mutableListOf<Pair<JSONObject, JSONObject>>()

            for (platform in platforms) {
                Log.d(TAG, "Checking platform: ${platform.javaClass.simpleName}")
                if (!platform.suportsGrades) {
                    Log.d(TAG, "Platform ${platform.javaClass.simpleName} does not support grades, skipping.")
                    continue
                }
                val courses = platform.getCourses()
                Log.d(TAG, "Platform ${platform.javaClass.simpleName} has ${courses.size} courses")

                for (course in courses) {
                    val courseName = course.optString("name", "unknown_course")
                    Log.d(TAG, "Fetching grades for $courseName ...")

                    val latestGrades = platform.getGrades(courseName)
                    val prevGrades = cached.optJSONArray(courseName) ?: JSONArray()
                    val merged = JSONArray(prevGrades.toString())

                    Log.d(TAG, "Found ${latestGrades.length()} latest grades, ${prevGrades.length()} cached grades")

                    for (i in 0 until latestGrades.length()) {
                        val g = latestGrades.getJSONObject(i)
                        val id = g.optString("id", g.optString("name"))
                        val gradeValue = g.optString("grade").trim()

                        // ✅ Skip invalid or placeholder grades
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
                            // ✅ Only add new grade if it's a real, nonzero, nonempty grade
                            prev == null -> {
                                Log.i(TAG, "➕ New grade detected: ${g.optString("name")} = $gradeValue")
                                merged.put(g)
                                newGrades.add(g)
                            }

                            // ✅ Only count as updated if new grade is real and changed
                            prev.optString("grade") != gradeValue -> {
                                val oldValue = prev.optString("grade").trim()
                                if (
                                    oldValue.isNotEmpty() &&
                                    oldValue != "null" &&
                                    oldValue != "0" &&
                                    oldValue != gradeValue
                                ) {
                                    Log.i(TAG, "🟡 Updated grade detected: ${g.optString("name")} $oldValue → $gradeValue")
                                    updatedGrades.add(prev to g)
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


                    cached.put(courseName, merged)
                }
            }

            prefs.edit().putString("grades_json", cached.toString()).apply()
            Log.i(TAG, "Saved updated grade cache. New: ${newGrades.size}, Updated: ${updatedGrades.size}")

            notifyUser(newGrades, updatedGrades)
            Log.i(TAG, "✅ GradeMonitorWorker finished successfully")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error running GradeMonitorWorker", e)
            Result.retry()
        }
    }

    private fun notifyUser(
        newGrades: List<JSONObject>,
        updated: List<Pair<JSONObject, JSONObject>>
    ) {
        if (newGrades.isEmpty() && updated.isEmpty()) {
            Log.d(TAG, "No new or updated grades — skipping notification")
            return
        }

        if (ActivityCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission, skipping notification")
            return
        }

        val manager = NotificationManagerCompat.from(ctx)
        createNotificationChannel(manager)

        val title = when {
            newGrades.isNotEmpty() && updated.isNotEmpty() -> "New and updated grades!"
            newGrades.isNotEmpty() -> "New grades available!"
            else -> "Grades updated!"
        }

        val message = buildString {
            if (newGrades.isNotEmpty()) append("New: ${newGrades.size}  ")
            if (updated.isNotEmpty()) append("Updated: ${updated.size}")
        }

        Log.i(TAG, "🔔 Sending notification: $title → $message")

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_docs)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel(manager: NotificationManagerCompat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Grade Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new or changed grades"
            }
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Created notification channel: $CHANNEL_ID")
        }
    }

    companion object {
        private val TAG = this::class.java.simpleName
        private const val CHANNEL_ID = "grades_channel"

        fun schedule(context: Context) {
            Log.i(TAG, "Scheduling GradeMonitorWorker…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "POST_NOTIFICATIONS permission not granted, cannot schedule worker.")
                    return
                }
            }

            val request = PeriodicWorkRequestBuilder<GradeMonitorWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                "grade-monitor-worker",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "✅ GradeMonitorWorker scheduled with PeriodicWorkRequest every 15 min")

            // Debug log of current work state
            wm.getWorkInfosForUniqueWorkLiveData("grade-monitor-worker").observeForever {
                Log.d(TAG, "WorkManager status update: ${it.joinToString { info -> info.state.name }}")
            }
        }
    }
}
