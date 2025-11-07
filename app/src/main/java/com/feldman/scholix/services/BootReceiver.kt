package com.feldman.scholix.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Automatically restarts the grade monitoring worker after the device reboots.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            Log.i("BootReceiver", "Device booted — rescheduling GradeMonitorWorker")
            GradeMonitorWorker.schedule(context.applicationContext)
        }
    }
}