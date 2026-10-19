package com.wyrix.appgenerator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Lamba generation chalte waqt Android app ko background mein band na kare. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("gen", "Project generation", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "gen")
            .setContentTitle("Wyrix App Generator AI")
            .setContentText("Project ban raha hai…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, KeepAliveService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, KeepAliveService::class.java))
        }
    }
}
