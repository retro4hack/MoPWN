package com.mopwn.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import android.content.ClipboardManager
import android.content.ClipData
import java.io.DataOutputStream

class GeneralToolsActivity : AppCompatActivity() {

    private val NOTIFICATION_PERMISSION_CODE = 101

    private lateinit var tvDeviceAbi: TextView
    private lateinit var tvDeviceBrand: TextView
    private lateinit var tvDeviceAndroidVer: TextView
    private lateinit var tvDeviceKernel: TextView
    
    private lateinit var tvGeneralFridaInstallStatus: TextView
    private lateinit var tvGeneralFridaRunStatus: TextView
    private lateinit var tvGeneralFridaVersion: TextView
    
    private lateinit var btnNavigateFrida: Button
    private lateinit var switchTracker: SwitchMaterial

    private lateinit var tvGeneralOobStatus: TextView
    private lateinit var tvGeneralOobEndpoint: TextView
    private lateinit var btnNavigateOob: Button

    private var isRequestingPermission = false

    private val trackerStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ForegroundTrackerService.ACTION_TRACKER_STOPPED) {
                // Remove listener temporarily to update check status programmatically without triggering listener actions
                switchTracker.setOnCheckedChangeListener(null)
                switchTracker.isChecked = false
                switchTracker.setOnCheckedChangeListener(switchListener)
            }
        }
    }

    private val switchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            // If on Android 13+, explicitly request notification permission first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    isRequestingPermission = true
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_CODE
                    )
                    return@OnCheckedChangeListener
                }
            }
            
            // If permission is already granted or SDK < 33, proceed to root validation
            verifyRootAndStartTracker()
        } else {
            stopTrackerService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general_tools)

        // Initialize Free-to-use Specs Views
        tvDeviceAbi = findViewById(R.id.tvDeviceAbi)
        tvDeviceBrand = findViewById(R.id.tvDeviceBrand)
        tvDeviceAndroidVer = findViewById(R.id.tvDeviceAndroidVer)
        tvDeviceKernel = findViewById(R.id.tvDeviceKernel)

        // Initialize Frida Quick Indicators
        tvGeneralFridaInstallStatus = findViewById(R.id.tvGeneralFridaInstallStatus)
        tvGeneralFridaRunStatus = findViewById(R.id.tvGeneralFridaRunStatus)
        tvGeneralFridaVersion = findViewById(R.id.tvGeneralFridaVersion)

        // Initialize Navigation Button to Frida Server
        btnNavigateFrida = findViewById(R.id.btnNavigateFrida)

        // Initialize Smart Switch for Active App & Class Tracker
        switchTracker = findViewById(R.id.switchTracker)

        // Initialize OOB Free-to-use Views
        tvGeneralOobStatus = findViewById(R.id.tvGeneralOobStatus)
        tvGeneralOobEndpoint = findViewById(R.id.tvGeneralOobEndpoint)
        btnNavigateOob = findViewById(R.id.btnNavigateOob)

        // Load Spec details
        loadDeviceSpecs()

        // Hook Navigation
        btnNavigateFrida.setOnClickListener {
            val intent = Intent(this, FridaManagerActivity::class.java)
            startActivity(intent)
        }

        btnNavigateOob.setOnClickListener {
            val intent = Intent(this, OobConsoleActivity::class.java)
            startActivity(intent)
        }

        btnNavigateOob.setOnLongClickListener {
            val isRunning = OobListenerService.isServiceRunning
            if (isRunning) {
                val localIp = OobListenerService.getLocalIpAddress() ?: "127.0.0.1"
                val endpoint = "http://$localIp:1337/"
                
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("MoPWN Endpoint", endpoint)
                clipboard.setPrimaryClip(clip)
                
                Toast.makeText(this, "🟢 Endpoint URL copied: $endpoint", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚪ Server is offline. Start the server first to copy the endpoint.", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // Set the main Switch Listener
        switchTracker.setOnCheckedChangeListener(switchListener)

        // Register local broadcast receiver to monitor foreground service destruction events in real-time
        val filter = IntentFilter(ForegroundTrackerService.ACTION_TRACKER_STOPPED)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            trackerStopReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            // Permission request flow finished, reset flag
            isRequestingPermission = false
            
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted! Make sure switch reflects the check state and proceed to verify root and run
                switchTracker.setOnCheckedChangeListener(null)
                switchTracker.isChecked = true
                switchTracker.setOnCheckedChangeListener(switchListener)
                
                verifyRootAndStartTracker()
            } else {
                // Permission denied! Turn switch off and explain to the user
                switchTracker.setOnCheckedChangeListener(null)
                switchTracker.isChecked = false
                switchTracker.setOnCheckedChangeListener(switchListener)
                
                Toast.makeText(
                    this,
                    "Notification permission is required to use this feature!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun verifyRootAndStartTracker() {
        Thread {
            val hasRoot = checkRootAvailable()
            runOnUiThread {
                if (!hasRoot) {
                    switchTracker.setOnCheckedChangeListener(null)
                    switchTracker.isChecked = false
                    switchTracker.setOnCheckedChangeListener(switchListener)
                    Toast.makeText(
                        this,
                        "Root privileges are required to run Live Screen Tracker!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    startTrackerService()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Check Frida Server Status dynamically on entry/resume
        checkFridaStatus()
        
        // Check OOB Server Status dynamically on entry/resume
        checkOobServerStatus()
        
        // Dynamically align Switch with actual background service execution status, 
        // avoiding racing alignments during notification runtime permission request flows
        if (!isRequestingPermission) {
            val isRunning = isServiceRunning(ForegroundTrackerService::class.java)
            switchTracker.setOnCheckedChangeListener(null)
            switchTracker.isChecked = isRunning
            switchTracker.setOnCheckedChangeListener(switchListener)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(trackerStopReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun loadDeviceSpecs() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
        tvDeviceAbi.text = "CPU ABI: $abi"
        tvDeviceBrand.text = "Model: ${Build.MANUFACTURER} ${Build.MODEL}"
        tvDeviceAndroidVer.text = "OS Version: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        
        val kernel = try {
            System.getProperty("os.version") ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
        tvDeviceKernel.text = "Kernel: $kernel"
    }

    private fun checkFridaStatus() {
        Thread {
            // Run all diagnostic checks within one single persistent root su shell session
            val cmd = "if [ -f /data/local/tmp/frida-server ]; then echo 1; /data/local/tmp/frida-server --version 2>/dev/null || echo 'Unknown'; else echo 0; echo 'N/A'; fi; if pgrep -f frida-server >/dev/null 2>&1; then echo 1; else echo 0; fi"
            
            val outputLines = runRootCheckMultipleLines(cmd)
            
            // Expected structured lines:
            // Line 0: "1" (Installed) or "0" (Not installed)
            // Line 1: version string or "N/A"
            // Line 2: "1" (Running) or "0" (Stopped)
            val isInstalled = outputLines.getOrNull(0) == "1"
            val versionStr = outputLines.getOrNull(1) ?: "N/A"
            val isRunning = outputLines.getOrNull(2) == "1"

            runOnUiThread {
                if (isInstalled) {
                    tvGeneralFridaInstallStatus.text = "INSTALLED"
                    tvGeneralFridaInstallStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
                    tvGeneralFridaVersion.text = versionStr
                } else {
                    tvGeneralFridaInstallStatus.text = "NOT INSTALLED"
                    tvGeneralFridaInstallStatus.setTextColor(Color.parseColor("#F44336")) // Red
                    tvGeneralFridaVersion.text = "N/A"
                }

                if (isRunning) {
                    tvGeneralFridaRunStatus.text = "RUNNING"
                    tvGeneralFridaRunStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
                } else {
                    tvGeneralFridaRunStatus.text = "STOPPED"
                    tvGeneralFridaRunStatus.setTextColor(Color.parseColor("#F44336")) // Red
                }
            }
        }.start()
    }

    private fun startTrackerService() {
        val intent = Intent(this, ForegroundTrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Active App & Class Tracker started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopTrackerService() {
        val intent = Intent(this, ForegroundTrackerService::class.java)
        stopService(intent)
        Toast.makeText(this, "Active App & Class Tracker stopped.", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun checkRootAvailable(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    private fun runRootCheckMultipleLines(cmd: String): List<String> {
        var process: Process? = null
        val output = mutableListOf<String>()
        try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()
            
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { output.add(it) }
            }
            process.waitFor()
        } catch (e: Exception) {
            // ignore
        } finally {
            process?.destroy()
        }
        return output
    }

    private fun checkOobServerStatus() {
        val isRunning = OobListenerService.isServiceRunning
        if (isRunning) {
            tvGeneralOobStatus.text = "RUNNING"
            tvGeneralOobStatus.setTextColor(Color.parseColor("#4CAF50"))
            
            val localIp = OobListenerService.getLocalIpAddress() ?: "127.0.0.1"
            val endpoint = "http://$localIp:1337/"
            tvGeneralOobEndpoint.text = endpoint
            tvGeneralOobEndpoint.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvGeneralOobStatus.text = "OFFLINE"
            tvGeneralOobStatus.setTextColor(Color.parseColor("#757575"))
            
            tvGeneralOobEndpoint.text = "None (Stopped)"
            tvGeneralOobEndpoint.setTextColor(Color.parseColor("#757575"))
        }
    }
}
