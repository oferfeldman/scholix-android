package com.feldman.scholix.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.feldman.scholix.MainActivity
import com.feldman.scholix.R
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

@Serializable
data class CrashRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val threadName: String,
    val exceptionClass: String,
    val message: String,
    val stackTrace: String,
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val appVersion: String = "1.0"
)

object CrashHandler {
    private const val CHANNEL_ID = "crash_reports"
    private const val CRASH_FILE_NAME = "crash_reports.json"
    private const val MAX_CRASH_RECORDS = 30
    private const val NOTIFICATION_ID = 9991

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInstalled = false

    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true

        val appContext = context.applicationContext
        createNotificationChannel(appContext)

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Ignore any secondary errors during crash handling
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun handleCrash(context: Context, thread: Thread, throwable: Throwable) {
        // 1. Report to Firebase Crashlytics
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("thread_name", thread.name)
            crashlytics.recordException(throwable)
        } catch (_: Throwable) {}

        // 2. Save crash record locally
        val stackWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackWriter))
        val stackTrace = stackWriter.toString()

        val record = CrashRecord(
            threadName = thread.name,
            exceptionClass = throwable.javaClass.name,
            message = throwable.localizedMessage ?: throwable.message ?: throwable.javaClass.simpleName,
            stackTrace = stackTrace
        )

        saveCrashRecord(context, record)

        // 3. Post notification
        sendCrashNotification(context, record)
    }

    fun recordManualCrash(context: Context, throwable: Throwable) {
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable)
        } catch (_: Throwable) {}

        val stackWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackWriter))

        val record = CrashRecord(
            threadName = Thread.currentThread().name,
            exceptionClass = throwable.javaClass.name,
            message = throwable.localizedMessage ?: throwable.message ?: throwable.javaClass.simpleName,
            stackTrace = stackWriter.toString()
        )

        saveCrashRecord(context, record)
        sendCrashNotification(context, record)
    }

    @Synchronized
    private fun saveCrashRecord(context: Context, record: CrashRecord) {
        try {
            val existing = getCrashReports(context).toMutableList()
            existing.add(0, record)
            val trimmed = if (existing.size > MAX_CRASH_RECORDS) existing.take(MAX_CRASH_RECORDS) else existing
            val file = File(context.filesDir, CRASH_FILE_NAME)
            file.writeText(json.encodeToString(trimmed))
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun getCrashReports(context: Context): List<CrashRecord> {
        return try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<CrashRecord>>(file.readText())
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun clearCrashReports(context: Context) {
        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Throwable) {}
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Crash Reports",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for application crashes and diagnostics"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun sendCrashNotification(context: Context, record: CrashRecord) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_crash_logs", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = context.getString(R.string.crash_detected)
            val detail = record.message.ifBlank { context.getString(R.string.crash_tap_details) }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n\n${record.exceptionClass}"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: Throwable) {}
    }
}
