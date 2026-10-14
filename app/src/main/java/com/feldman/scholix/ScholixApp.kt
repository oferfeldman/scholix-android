package com.feldman.scholix

import android.app.Application
import com.feldman.scholix.util.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScholixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            com.feldman.scholix.lemida.LemidaSyncWorker.schedule(this@ScholixApp)
            com.feldman.scholix.drive.DriveSyncWorker.schedule(this@ScholixApp)
        }
    }
}
