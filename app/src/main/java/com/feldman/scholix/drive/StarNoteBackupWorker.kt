package com.feldman.scholix.drive

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class StarNoteBackupWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val account=inputData.getString("account") ?: return Result.failure()
        val source=inputData.getString("source") ?: return Result.failure()
        val drive=DriveRepository.get(applicationContext)
        if(drive.state.value.account!=account)return Result.failure()
        val repo=StarNoteRepository(applicationContext,drive)
        return try {
            // Read the latest draft each time; edits arriving during an upload remain queued.
            repeat(5) {
                val draft=repo.localDraft(account,source) ?: return Result.success()
                if(draft.backedUp)return Result.success()
                val saved=repo.backup(account,draft)
                repo.saveLocal(account,saved,acknowledge=true)
            }
            Result.retry()
        }catch(e:Exception) {
            if(e is CancellationException)throw e
            if(DriveConnection.unavailable(e))Result.retry()
            else Result.failure(workDataOf("error" to DriveAuth.message(e).take(500),"needsConsent" to (e is DriveNeedsConsent)))
        }
    }
    companion object {
        const val TAG="starnote-edit-backup"
        fun name(account:String,source:String)="star-backup-"+MessageDigest.getInstance("SHA-256").digest("$account/$source".toByteArray()).joinToString(""){"%02x".format(it)}
        fun schedule(context:Context,account:String,source:String) {
            WorkManager.getInstance(context).enqueueUniqueWork(name(account,source),ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<StarNoteBackupWorker>().addTag(TAG)
                    .setInputData(workDataOf("account" to account,"source" to source))
                    .setInitialDelay(2,TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
