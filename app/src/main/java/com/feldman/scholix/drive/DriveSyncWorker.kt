package com.feldman.scholix.drive

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = DriveRepository.get(applicationContext)
        if (repo.state.value.account.isBlank()) return Result.success()
        var transientFailure = false
        // Refresh each followed folder independently; a failure preserves that folder's last complete list.
        for (folder in repo.state.value.followed) {
            try { repo.refresh(folder) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is DriveNeedsConsent || e is DriveHttpError && e.status in listOf(401, 403, 404)) continue
                transientFailure = true
            }
        }
        return if (transientFailure && runAttemptCount < 3) Result.retry() else Result.success()
    }
    companion object {
        private const val NAME = "drive-materials-refresh"
        fun schedule(context: Context) {
            val state = DriveRepository.get(context).state.value
            if (state.account.isBlank() || state.followed.isEmpty()) { cancel(context); return }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}
