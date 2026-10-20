package com.hasaan.busymode

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, getString(R.string.ch_service), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERTS, getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_ALERTS = "alerts"
    }
}
