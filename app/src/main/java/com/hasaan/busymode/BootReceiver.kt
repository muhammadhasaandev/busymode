package com.hasaan.busymode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts Busy Mode again after the phone restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings(context).enabled) return
        try {
            EngineService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not start Busy Mode: ${e.message}")
        }
    }
}
