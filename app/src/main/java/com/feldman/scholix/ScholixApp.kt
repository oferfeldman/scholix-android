package com.feldman.scholix

import android.app.Application
import com.feldman.scholix.util.CrashHandler

class ScholixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        com.feldman.scholix.lemida.LemidaSyncWorker.schedule(this)
        com.feldman.scholix.drive.DriveSyncWorker.schedule(this)
    }
}
