package com.feldman.scholix.lemida

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.feldman.scholix.MainActivity
import com.feldman.scholix.R
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class LemidaSyncWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = LemidaRepository(context)
        if (!repo.enabled() && !inputData.getBoolean(MANUAL_REFRESH, false)) return Result.success()
        return try {
            val account = if (repo.needsLogin() && !LemidaSignInStore(context).canRecover()) null else try {
                repo.sync()
            } catch (_: LemidaSessionExpired) { null }
            if (account == null) {
                // A manual browser check remains necessary when automatic recovery cannot finish.
                repo.deliverLoginReminder { notify(context, 73121, "Lemida needs sign-in",
                    "Open Homework and sign in again to resume automatic updates.") }
            } else {
                repo.deliverPending(account) { items -> notify(context, 73120, "New Lemida homework",
                    items.joinToString(" • ") { "${it.course}: ${it.title}" }) }
            }
            Result.success()
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { Result.retry() }
    }
    companion object {
        private const val CHANNEL = "lemida_homework"
        private const val MANUAL_REFRESH = "manual_refresh"
        fun notify(context: Context, id: Int, title: String, content: String): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                return false
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Lemida homework", NotificationManager.IMPORTANCE_DEFAULT))
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            val intent = Intent(context, MainActivity::class.java).putExtra("open_homework", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationManagerCompat.from(context).notify(id,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_docs)
                    .setContentTitle(title).setContentText(content)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                    .setContentIntent(pending).setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            return true
        }
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LemidaSyncWorker>(30, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("lemida_homework_sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun refresh(context: Context, manual: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<LemidaSyncWorker>()
                .setInputData(workDataOf(MANUAL_REFRESH to manual))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("lemida_homework_refresh",
                if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
