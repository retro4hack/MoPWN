package com.mopwn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OobConsoleActivity : AppCompatActivity() {

    private lateinit var tvServerStatus: TextView
    private lateinit var tvServerIp: TextView
    private lateinit var tvTerminalLog: TextView
    private lateinit var btnToggleServer: Button
    private lateinit var btnClearTerminal: Button

    private val oobLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == OobListenerService.ACTION_NEW_OOB_LOG) {
                val jsonPayload = intent.getStringExtra(OobListenerService.EXTRA_JSON_PAYLOAD) ?: return
                renderLogEntry(jsonPayload)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oob_console)

        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvServerIp = findViewById(R.id.tvServerIp)
        tvTerminalLog = findViewById(R.id.tvTerminalLog)
        btnToggleServer = findViewById(R.id.btnToggleServer)
        btnClearTerminal = findViewById(R.id.btnClearTerminal)

        val etConsoleOobEndpoint = findViewById<android.widget.EditText>(R.id.etConsoleOobEndpoint)
        val btnSaveConsoleOob = findViewById<Button>(R.id.btnSaveConsoleOob)
        val prefs = getSharedPreferences("MoPwnPrefs", MODE_PRIVATE)

        // Load currently active redirect target endpoint
        val savedEndpoint = prefs.getString("oob_logger_endpoint", "http://127.0.0.1:1337/")
        etConsoleOobEndpoint.setText(savedEndpoint)

        // Save on button click with instant feedback
        btnSaveConsoleOob.setOnClickListener {
            val newEndpoint = etConsoleOobEndpoint.text.toString().trim()
            if (newEndpoint.isNotEmpty()) {
                prefs.edit().putString("oob_logger_endpoint", newEndpoint).apply()
                android.widget.Toast.makeText(this, "Destination updated to:\n$newEndpoint", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        updateUiState()

        // Populate console with historical logs stored in memory buffer
        if (OobListenerService.inMemoryLogs.isNotEmpty()) {
            tvTerminalLog.text = ""
            val logsSnapshot = ArrayList(OobListenerService.inMemoryLogs)
            logsSnapshot.forEach { logPayload ->
                renderLogEntry(logPayload)
            }
        }

        btnToggleServer.setOnClickListener {
            val isRunning = OobListenerService.isServiceRunning
            val serviceIntent = Intent(this, OobListenerService::class.java)
            if (isRunning) {
                stopService(serviceIntent)
                OobListenerService.clearLogs()
            } else {
                ContextCompat.startForegroundService(this, serviceIntent)
            }
            btnToggleServer.postDelayed({ updateUiState() }, 300)
        }

        btnClearTerminal.setOnClickListener {
            OobListenerService.clearLogs()
            tvTerminalLog.text = "[Console cleared. Waiting for new logs...]\n"
        }

        val filter = IntentFilter(OobListenerService.ACTION_NEW_OOB_LOG)
        ContextCompat.registerReceiver(
            this, oobLogReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun updateUiState() {
        val isRunning = OobListenerService.isServiceRunning
        if (isRunning) {
            tvServerStatus.text = "RUNNING"
            tvServerStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnToggleServer.text = "STOP SERVER"
            btnToggleServer.setBackgroundColor(Color.parseColor("#E53935"))
            
            val localIp = OobListenerService.getLocalIpAddress() ?: "127.0.0.1"
            tvServerIp.text = "Endpoint: http://$localIp:1337/"
        } else {
            tvServerStatus.text = "OFFLINE"
            tvServerStatus.setTextColor(Color.parseColor("#757575"))
            btnToggleServer.text = "START SERVER"
            btnToggleServer.setBackgroundColor(Color.parseColor("#1E88E5"))
            tvServerIp.text = "Endpoint: None (Server Stopped)"
        }
    }

    private fun renderLogEntry(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val timestampStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(json.optLong("timestamp", System.currentTimeMillis())))
            
            val device = json.optString("device", "Unknown Device")
            val interceptedUri = json.optString("intercepted_uri", "N/A")

            val parsedUri = Uri.parse(interceptedUri)

            val formattedEntry = StringBuilder().apply {
                append("========================================\n")
                append("🔥 [HIJACK SUCCESS] - $timestampStr\n")
                append("📱 Device: $device\n")
                append("🔗 URI: $interceptedUri\n")
                append("📦 Query Parameters:\n")
                
                try {
                    parsedUri.queryParameterNames.forEach { name ->
                        append("   • $name = ${parsedUri.getQueryParameter(name)}\n")
                    }
                } catch (e: Exception) {
                    append("   [Error parsing query parameters]\n")
                }
                append("========================================\n\n")
            }.toString()

            tvTerminalLog.append(formattedEntry)
        } catch (e: Exception) {
            tvTerminalLog.append("⚠️ [MALFORMED PAYLOAD] Received invalid data packet.\n\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(oobLogReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }
}
