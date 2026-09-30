package com.mopwn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat

class GlobalProxyService : Service() {

    companion object {
        const val CHANNEL_ID = "GlobalProxyChannel"
        const val NOTIFICATION_ID = 4004

        const val ACTION_START = "com.mopwn.app.ACTION_START_PROXY_SERVICE"
        const val ACTION_STOP = "com.mopwn.app.ACTION_STOP_PROXY_SERVICE"
        const val ACTION_DISCONNECT = "com.mopwn.app.ACTION_PROXY_DISCONNECT"
        const val EXTRA_HOST_PORT = "extra_host_port"

        var isServiceRunning = false
            private set
    }

    private var currentHostPort: String = ""

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_DISCONNECT) {
            // Disconnect requested from notification action button
            Thread {
                val result = GlobalProxyManager.deactivateProxy()
                Handler(Looper.getMainLooper()).post {
                    if (result.first) {
                        Toast.makeText(applicationContext, "MoPWN: Global Proxy Disabled", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "Error disabling proxy: ${result.second}", Toast.LENGTH_LONG).show()
                    }
                }

                // Broadcast state change explicitly within our app package
                GlobalProxyManager.sendStateBroadcast(applicationContext)

                stopForegroundAndSelf()
            }.start()
            return START_NOT_STICKY
        } else if (action == ACTION_STOP) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        val hostPort = intent?.getStringExtra(EXTRA_HOST_PORT) ?: currentHostPort
        if (!hostPort.isNullOrBlank()) {
            currentHostPort = hostPort
        } else {
            currentHostPort = GlobalProxyManager.getProxyHostPort(this) ?: "Active"
        }

        val notification = buildProxyNotification(currentHostPort)
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    private fun stopForegroundAndSelf() {
        isServiceRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MoPWN Global Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for MoPWN Global Proxy status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildProxyNotification(hostPort: String): Notification {
        // Tapping notification opens GlobalProxyActivity
        val intent = Intent(this, GlobalProxyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Disconnect" action to immediately disable the proxy
        val disconnectIntent = Intent(this, GlobalProxyService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationText = "MoPWN Global Proxy Running on $hostPort"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MoPWN Global Proxy")
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPending)
            .build()
    }
}
