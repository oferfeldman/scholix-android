package com.feldman.scholix.services

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
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.MailboxFolder
import com.feldman.scholix.api.platforms.WebtopPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MessageMonitorWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@withContext Result.success()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Webtop messages", NotificationManager.IMPORTANCE_DEFAULT))
        val cache = context.getSharedPreferences("message_alerts", Context.MODE_PRIVATE)
        var failed = false
        for (provider in PlatformStorage.loadPlatforms(context).filterIsInstance<WebtopPlatform>()) {
            try {
                val messages = provider.mailbox.messages(MailboxFolder.INBOX, 1)
                val seen = cache.getStringSet(provider.id, null)
                val ids = messages.map { it.optString("messageId") }.toSet()
                val newCount = messages.count { seen != null && it.optString("messageId") !in seen && it.optInt("hasRead") == 0 }
                // The first successful check establishes a baseline; old mail never generates alerts.
                if (newCount > 0) {
                    val intent = Intent(context, MainActivity::class.java).putExtra("open_messages", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    val pending = PendingIntent.getActivity(context, provider.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_message)
                        .setContentTitle("New Webtop messages").setContentText("$newCount new unread ${if (newCount == 1) "message" else "messages"}")
                        .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                    NotificationManagerCompat.from(context).notify(provider.id.hashCode(), notification)
                }
                cache.edit().putStringSet(provider.id, ids).apply()
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { failed = true }
        }
        if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val CHANNEL = "webtop_messages"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MessageMonitorWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("webtop_message_alerts", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
