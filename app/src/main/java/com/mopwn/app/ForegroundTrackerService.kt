package com.mopwn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream

class ForegroundTrackerService : Service() {

    private val CHANNEL_ID = "ForegroundTrackerChannel"
    private val NOTIFICATION_ID = 9988

    private var currentPackage = "Unknown"
    private var currentClass = "Unknown"

    private var isTracking = false
    private var trackingThread: Thread? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.getStringExtra(EXTRA_ACTION) ?: return
            when (action) {
                ACTION_COPY_PKG -> {
                    copyToClipboard(context, currentPackage, "Package Name Copied!")
                }
                ACTION_COPY_CLS -> {
                    copyToClipboard(context, currentClass, "Class Name Copied!")
                }
                ACTION_STOP -> {
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Register local broadcast receiver to handle notification button actions
        val filter = IntentFilter(INTENT_ACTION_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }

        isTracking = true
        startPersistentRootTracker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildTrackerNotification("Auditing screen...", "Detecting active screen/class in background...")
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isTracking = false
        trackingThread?.interrupt()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            // ignore
        }
        
        // Broadcast that the service has stopped to dynamically update GeneralToolsActivity switch state in real-time
        val stopBroadcast = Intent(ACTION_TRACKER_STOPPED)
        sendBroadcast(stopBroadcast)
        
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Spawns a single persistent su process and pipes dumpsys commands at regular intervals.
     * This avoids running new 'su' instances every 1.5 seconds, eliminating annoying Magisk root notification Toasts
     * and reducing CPU / Battery footprints drastically.
     */
    private fun startPersistentRootTracker() {
        trackingThread = Thread {
            var process: Process? = null
            try {
                // Instantiates one single persistent root shell session
                process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                val reader = process.inputStream.bufferedReader()

                while (isTracking && !Thread.currentThread().isInterrupted) {
                    // 1. Send primary query
                    os.writeBytes("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'\n")
                    os.writeBytes("echo \"__DONE__\"\n")
                    os.flush()

                    val sb = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line == "__DONE__") break
                        sb.append(line).append("\n")
                    }
                    var output = sb.toString().trim()

                    // 2. Fallback query if window manager focus is blank or not structured
                    if (output.isBlank() || !output.contains("/")) {
                        os.writeBytes("dumpsys activity activities | grep mResumedActivity\n")
                        os.writeBytes("echo \"__DONE__\"\n")
                        os.flush()

                        val sbAct = StringBuilder()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line == "__DONE__") break
                            sbAct.append(line).append("\n")
                        }
                        output = sbAct.toString().trim()
                    }

                    // 3. Parse result and validate target details
                    val details = parseForegroundAppDetails(output)
                    if (isValidTrackerTarget(details.first, details.second)) {
                        currentPackage = details.first
                        currentClass = details.second

                        // Update Notification only when valid non-system foreground app is targeted
                        updateNotification("App: $currentPackage", "Class: $currentClass")
                    }

                    Thread.sleep(1500)
                }

                os.writeBytes("exit\n")
                os.flush()
            } catch (e: InterruptedException) {
                // thread interrupted for shutdown
            } catch (e: Exception) {
                // ignore
            } finally {
                process?.destroy()
            }
        }
        trackingThread?.start()
    }

    private fun parseForegroundAppDetails(output: String): Pair<String, String> {
        if (output.isBlank()) return Pair("Unknown", "Unknown")
        try {
            if (output.contains("/")) {
                // Extracts: "com.android.settings/com.android.settings.Settings"
                val clean = output.substringBefore("}").substringAfterLast(" ").trim()
                if (clean.contains("/")) {
                    val parts = clean.split("/")
                    val pkg = parts[0]
                    var cls = parts[1]
                    if (cls.startsWith(".")) {
                        cls = pkg + cls
                    }
                    return Pair(pkg, cls)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return Pair("Unknown", "Unknown")
    }

    /**
     * Determines whether the parsed package/class is a valid user auditing target.
     * Filters out blank/unknown records, System UI notifications panels, the OS core, and keyboards.
     * Doing so ensures the notification preserves the LAST valid analyzed application name when status tray pulls down.
     */
    private fun isValidTrackerTarget(pkg: String, cls: String): Boolean {
        if (pkg.isBlank() || pkg == "Unknown" || cls == "Unknown") {
            return false
        }
        
        val ignorePackages = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.inputmethod.latin",
            "com.google.android.providers.media.module"
        )
        
        if (ignorePackages.contains(pkg)) {
            return false
        }
        
        return true
    }

    private fun updateNotification(title: String, text: String) {
        val notification = buildTrackerNotification(title, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildTrackerNotification(title: String, text: String): Notification {
        val intent = Intent(this, GeneralToolsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Copy Package
        val copyPkgIntent = Intent(INTENT_ACTION_CONTROL).apply { putExtra(EXTRA_ACTION, ACTION_COPY_PKG) }
        val copyPkgPending = PendingIntent.getBroadcast(
            this, 1, copyPkgIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Copy Class
        val copyClsIntent = Intent(INTENT_ACTION_CONTROL).apply { putExtra(EXTRA_ACTION, ACTION_COPY_CLS) }
        val copyClsPending = PendingIntent.getBroadcast(
            this, 2, copyClsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Stop Service
        val stopIntent = Intent(INTENT_ACTION_CONTROL).apply { putExtra(EXTRA_ACTION, ACTION_STOP) }
        val stopPending = PendingIntent.getBroadcast(
            this, 3, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(android.R.drawable.ic_menu_save, "Copy Package", copyPkgPending)
            .addAction(android.R.drawable.ic_menu_save, "Copy Class", copyClsPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Tracker Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun copyToClipboard(context: Context, text: String, message: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("MoPWN Tracker", text)
        clipboard.setPrimaryClip(clip)
        
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val INTENT_ACTION_CONTROL = "com.mopwn.app.ACTION_TRACKER_CONTROL"
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_COPY_PKG = "copy_package"
        const val ACTION_COPY_CLS = "copy_class"
        const val ACTION_STOP = "stop"
        const val ACTION_TRACKER_STOPPED = "com.mopwn.app.ACTION_TRACKER_STOPPED"
    }
}
