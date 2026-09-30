package com.mopwn.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class GlobalProxyActivity : AppCompatActivity() {

    private val NOTIFICATION_PERMISSION_CODE = 202

    private lateinit var tvProxyStatus: TextView
    private lateinit var tvProxyEndpoint: TextView
    private lateinit var etProxyIp: EditText
    private lateinit var etProxyPort: EditText
    private lateinit var btnToggleProxy: Button

    private var isCurrentlyActive = false
    private var pendingIp: String? = null
    private var pendingPort: Int? = null

    private val proxyStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshProxyStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_proxy)

        tvProxyStatus = findViewById(R.id.tvProxyStatus)
        tvProxyEndpoint = findViewById(R.id.tvProxyEndpoint)
        etProxyIp = findViewById(R.id.etProxyIp)
        etProxyPort = findViewById(R.id.etProxyPort)
        btnToggleProxy = findViewById(R.id.btnToggleProxy)

        // Load saved preferences if available
        val prefs = getSharedPreferences(GlobalProxyManager.PREFS_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(GlobalProxyManager.KEY_LAST_IP, "")
        val savedPort = prefs.getString(GlobalProxyManager.KEY_LAST_PORT, "8080")

        if (!savedIp.isNullOrBlank()) {
            etProxyIp.setText(savedIp)
        }
        etProxyPort.setText(if (!savedPort.isNullOrBlank()) savedPort else "8080")

        btnToggleProxy.setOnClickListener {
            if (isCurrentlyActive) {
                deactivateProxyFlow()
            } else {
                val ip = etProxyIp.text.toString().trim()
                val portStr = etProxyPort.text.toString().trim()

                if (ip.isBlank()) {
                    Toast.makeText(this, "Inserisci un indirizzo IP valido", Toast.LENGTH_SHORT).show()
                    etProxyIp.requestFocus()
                    return@setOnClickListener
                }

                val port = portStr.toIntOrNull()
                if (port == null || port < 1 || port > 65535) {
                    Toast.makeText(this, "Inserisci una porta valida (1-65535)", Toast.LENGTH_SHORT).show()
                    etProxyPort.requestFocus()
                    return@setOnClickListener
                }

                // Check notification permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingIp = ip
                        pendingPort = port
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            NOTIFICATION_PERMISSION_CODE
                        )
                        return@setOnClickListener
                    }
                }

                activateProxyFlow(ip, port)
            }
        }

        // Register broadcast receiver for proxy status changes
        val filter = IntentFilter(GlobalProxyManager.ACTION_PROXY_STATE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            proxyStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        refreshProxyStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(proxyStateReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            val ip = pendingIp
            val port = pendingPort
            pendingIp = null
            pendingPort = null

            if (ip != null && port != null) {
                // Proceed with activation whether notification granted or denied
                activateProxyFlow(ip, port)
            }
        }
    }

    private fun refreshProxyStatus() {
        Thread {
            val proxyVal = GlobalProxyManager.getProxyHostPort(this)
            val isActive = GlobalProxyManager.isProxyActive(proxyVal)

            runOnUiThread {
                isCurrentlyActive = isActive
                if (isActive && !proxyVal.isNullOrBlank()) {
                    tvProxyStatus.text = "ATTIVO"
                    tvProxyStatus.setTextColor(Color.parseColor("#4CAF50"))
                    tvProxyEndpoint.text = proxyVal
                    tvProxyEndpoint.setTextColor(Color.parseColor("#4CAF50"))

                    val parts = proxyVal.split(":")
                    if (parts.isNotEmpty()) {
                        etProxyIp.setText(parts[0])
                    }
                    if (parts.size > 1) {
                        etProxyPort.setText(parts[1])
                    }

                    // Quando il proxy è attivo, non devi poter modificare i dati di IP e porta
                    etProxyIp.isEnabled = false
                    etProxyPort.isEnabled = false

                    btnToggleProxy.text = "Disattiva Proxy"
                    btnToggleProxy.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))

                    // Ensure foreground service notification is running
                    if (!GlobalProxyService.isServiceRunning) {
                        GlobalProxyManager.startProxyService(this, proxyVal)
                    }
                } else {
                    tvProxyStatus.text = "DISATTIVO"
                    tvProxyStatus.setTextColor(Color.parseColor("#F44336"))
                    tvProxyEndpoint.text = "None (:0)"
                    tvProxyEndpoint.setTextColor(Color.parseColor("#757575"))

                    // Quando il proxy è disattivo, puoi modificare i dati di IP e porta
                    etProxyIp.isEnabled = true
                    etProxyPort.isEnabled = true

                    btnToggleProxy.text = "Attiva Proxy"
                    btnToggleProxy.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                }
                btnToggleProxy.isEnabled = true
            }
        }.start()
    }

    private fun activateProxyFlow(ip: String, port: Int) {
        btnToggleProxy.isEnabled = false
        btnToggleProxy.text = "Attivazione..."

        Thread {
            val hasRoot = GlobalProxyManager.checkRootAvailable()
            if (!hasRoot) {
                runOnUiThread {
                    btnToggleProxy.isEnabled = true
                    btnToggleProxy.text = "Attiva Proxy"
                    Toast.makeText(this, "Permessi di root necessari per attivare il proxy!", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            val result = GlobalProxyManager.activateProxy(ip, port)
            val success = result.first
            val errorMsg = result.second

            runOnUiThread {
                btnToggleProxy.isEnabled = true
                if (success) {
                    // Save last entered IP and Port in SharedPreferences
                    val prefs = getSharedPreferences(GlobalProxyManager.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(GlobalProxyManager.KEY_LAST_IP, ip)
                        .putString(GlobalProxyManager.KEY_LAST_PORT, port.toString())
                        .apply()

                    val hostPort = "$ip:$port"
                    GlobalProxyManager.startProxyService(this, hostPort)

                    // Notify state change explicitly within our app package
                    GlobalProxyManager.sendStateBroadcast(this)

                    Toast.makeText(this, "Proxy Globale Attivato ($hostPort)", Toast.LENGTH_SHORT).show()
                    refreshProxyStatus()
                } else {
                    btnToggleProxy.text = "Attiva Proxy"
                    Toast.makeText(this, "Errore attivazione proxy: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun deactivateProxyFlow() {
        btnToggleProxy.isEnabled = false
        btnToggleProxy.text = "Disattivazione..."

        Thread {
            val result = GlobalProxyManager.deactivateProxy()
            val success = result.first
            val errorMsg = result.second

            runOnUiThread {
                btnToggleProxy.isEnabled = true
                if (success) {
                    GlobalProxyManager.stopProxyService(this)

                    // Notify state change explicitly within our app package
                    GlobalProxyManager.sendStateBroadcast(this)

                    Toast.makeText(this, "Proxy Globale Disattivato", Toast.LENGTH_SHORT).show()
                    refreshProxyStatus()
                } else {
                    btnToggleProxy.text = "Disattiva Proxy"
                    Toast.makeText(this, "Errore disattivazione proxy: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
