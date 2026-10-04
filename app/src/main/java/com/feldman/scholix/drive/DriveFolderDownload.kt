package com.feldman.scholix.drive

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun FolderDownloadButton(folder:DriveItem,repo:DriveRepository,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val account=repo.state.value.account
    val manager=remember(context){WorkManager.getInstance(context)}
    val name=DriveFolderDownloadWorker.name(account,folder)
    val work by remember(name){manager.getWorkInfosForUniqueWorkFlow(name)}.collectAsState(initial=emptyList())
    val latest=work.maxByOrNull {it.runAttemptCount} ?: work.firstOrNull()
    val running=work.firstOrNull {!it.state.isFinished}
    Column(modifier) {
        if(running==null) OutlinedButton(onClick={DriveFolderDownloadWorker.start(context,folder)}) {
            Text(if(repo.state.value.savedFolders.any {it.effectiveId==folder.effectiveId})"Update local folder" else "Download folder")
        } else {
            val total=running.progress.getInt("total",0);val done=running.progress.getInt("done",0)
            if(total>0)LinearProgressIndicator(progress={done.toFloat()/total},modifier=Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                Text(if(total>0)"Downloading $done / $total" else "Preparing download…",Modifier.weight(1f).padding(top=12.dp),style=MaterialTheme.typography.labelSmall)
                TextButton(onClick={manager.cancelUniqueWork(name)}){Text("Cancel")}
            }
        }
        latest?.outputData?.getString("error")?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)}
    }
}

data class DriveFolderPlan(val folders:Map<String,Pair<DriveItem,List<DriveItem>>>,val files:List<DriveItem>,val skipped:Int)
object DriveFolderPlanner {
    suspend fun scan(root:DriveItem,list:suspend(DriveItem)->List<DriveItem>):DriveFolderPlan {
        require(root.folder)
        val queue=ArrayDeque<Pair<DriveItem,Int>>();queue.add(root to 0)
        val folders=linkedMapOf<String,Pair<DriveItem,List<DriveItem>>>()
        val files=linkedMapOf<String,DriveItem>();var skipped=0;var bytes=0L
        while(queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (folder,depth)=queue.removeFirst()
            if(folder.effectiveId in folders)continue
            require(depth<=32 && folders.size<5000) {"This folder has too many nested folders to download at once."}
            val items=list(folder);folders[folder.effectiveId]=folder to items
            for(item in items) {
                if(item.folder)queue.add(item to depth+1)
                else if(item.downloadable && item.size<=100L*1024*1024) {
                    if(files.putIfAbsent(item.effectiveId,item)==null)bytes+=item.size
                    require(files.size<=5000 && bytes<=2L*1024*1024*1024) {"Download a smaller subfolder. This folder exceeds 5,000 files or 2 GB."}
                } else skipped++
            }
        }
        return DriveFolderPlan(folders,files.values.toList(),skipped)
    }
}

class DriveFolderDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val account=inputData.getString("account") ?: return Result.failure()
        val item=runCatching {DriveItem.parse(JSONObject(inputData.getString("folder")!!))}.getOrElse {return Result.failure()}
        val repo=DriveRepository.get(applicationContext)
        if(repo.state.value.account!=account)return Result.failure()
        return try {
            repo.downloadFolder(account,item) {done,total,label->setProgress(workDataOf("done" to done,"total" to total,"label" to label))}
            Result.success()
        }catch(e:Exception) {
            if(e is CancellationException)throw e
            if(e is IOException && e !is DriveHttpError && runAttemptCount<3)Result.retry()
            else Result.failure(workDataOf("error" to DriveAuth.message(e).take(500)))
        }
    }
    companion object {
        const val TAG="drive-folder-download"
        fun name(account:String,folder:DriveItem) = "drive-folder-"+MessageDigest.getInstance("SHA-256").digest("$account/${folder.effectiveId}".toByteArray()).joinToString(""){"%02x".format(it)}
        fun start(context:Context,folder:DriveItem) {
            val account=DriveRepository.get(context).state.value.account
            require(account.isNotBlank())
            WorkManager.getInstance(context).enqueueUniqueWork(name(account,folder),ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveFolderDownloadWorker>().addTag(TAG)
                    .setInputData(workDataOf("account" to account,"folder" to folder.json().toString()))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
