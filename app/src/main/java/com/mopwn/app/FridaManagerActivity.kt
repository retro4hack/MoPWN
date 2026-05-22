package com.mopwn.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.tukaani.xz.XZInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class FridaManagerActivity : AppCompatActivity() {

    private lateinit var tvFridaInstallStatus: TextView
    private lateinit var tvFridaRunStatus: TextView
    private lateinit var tvFridaInstalledVersion: TextView
    private lateinit var spinnerFridaVersion: Spinner
    private lateinit var etCustomFridaVersion: EditText

    private lateinit var layoutDownloadProgress: LinearLayout
    private lateinit var tvDownloadProgressText: TextView
    private lateinit var progressBarFridaDownload: ProgressBar

    private lateinit var btnToggleInstall: Button
    private lateinit var btnToggleRun: Button
    private lateinit var btnRefreshStatus: Button

    private var fridaInstalledState = false
    private var fridaRunningState = false

    private val statusHandler = Handler(Looper.getMainLooper())
    private val checkStatusRunnable = object : Runnable {
        override fun run() {
            checkFridaStatus()
            statusHandler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frida_manager)

        // Initialize Views
        tvFridaInstallStatus = findViewById(R.id.tvFridaInstallStatus)
        tvFridaRunStatus = findViewById(R.id.tvFridaRunStatus)
        tvFridaInstalledVersion = findViewById(R.id.tvFridaInstalledVersion)
        spinnerFridaVersion = findViewById(R.id.spinnerFridaVersion)
        etCustomFridaVersion = findViewById(R.id.etCustomFridaVersion)

        layoutDownloadProgress = findViewById(R.id.layoutDownloadProgress)
        tvDownloadProgressText = findViewById(R.id.tvDownloadProgressText)
        progressBarFridaDownload = findViewById(R.id.progressBarFridaDownload)

        btnToggleInstall = findViewById(R.id.btnToggleInstall)
        btnToggleRun = findViewById(R.id.btnToggleRun)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)

        // Setup Frida Version Spinner (including Frida 17.x and specifically 16.7.19)
        val versions = arrayOf("17.0.2", "16.7.19", "16.3.0", "16.2.1", "16.1.4", "15.2.2", "Custom Version...")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, versions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFridaVersion.adapter = adapter

        spinnerFridaVersion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (versions[position] == "Custom Version...") {
                    etCustomFridaVersion.visibility = View.VISIBLE
                } else {
                    etCustomFridaVersion.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Set Click Listeners
        btnToggleInstall.setOnClickListener {
            if (fridaInstalledState) {
                startFridaUninstallFlow()
            } else {
                startFridaInstallFlow()
            }
        }

        btnToggleRun.setOnClickListener {
            if (fridaInstalledState) {
                toggleFridaServer(!fridaRunningState)
            }
        }

        btnRefreshStatus.setOnClickListener {
            Toast.makeText(this, "Refreshing process statuses...", Toast.LENGTH_SHORT).show()
            checkFridaStatus()
        }

        // Start Periodic Frida status checking
        statusHandler.post(checkStatusRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusHandler.removeCallbacks(checkStatusRunnable)
    }

    private fun checkFridaStatus() {
        Thread {
            // Check installed
            val isInstalled = runRootCheck("[ -f /data/local/tmp/frida-server ] && echo 1 || echo 0") == "1"
            
            // Check running process
            val isRunning = runRootCheck("pgrep -f frida-server")?.isNotEmpty() == true

            // Read dynamic installed version from native bin --version
            val installedVer = if (isInstalled) {
                runRootCheck("/data/local/tmp/frida-server --version") ?: "Unknown"
            } else {
                "N/A"
            }

            fridaInstalledState = isInstalled
            fridaRunningState = isRunning

            runOnUiThread {
                // Update dynamic installed version string
                tvFridaInstalledVersion.text = installedVer

                // Update Badge: Install Status
                if (isInstalled) {
                    tvFridaInstallStatus.text = "INSTALLED"
                    tvFridaInstallStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
                    
                    btnToggleInstall.text = "Uninstall Frida"
                    btnToggleInstall.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#AA2222")) // Red/Burgundy
                } else {
                    tvFridaInstallStatus.text = "NOT INSTALLED"
                    tvFridaInstallStatus.setTextColor(Color.parseColor("#F44336")) // Red
                    
                    btnToggleInstall.text = "Install Frida"
                    btnToggleInstall.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32")) // Green
                }

                // Update Badge: Run Status
                if (isRunning) {
                    tvFridaRunStatus.text = "RUNNING"
                    tvFridaRunStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
                } else {
                    tvFridaRunStatus.text = "STOPPED"
                    tvFridaRunStatus.setTextColor(Color.parseColor("#F44336")) // Red
                }

                // Update Dynamic Toggling Control Buttons
                if (!isInstalled) {
                    btnToggleRun.isEnabled = false
                    btnToggleRun.text = "Start Server (Not Installed)"
                    btnToggleRun.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#555555")) // Disabled Grey
                } else {
                    btnToggleRun.isEnabled = true
                    if (isRunning) {
                        btnToggleRun.text = "Stop Server"
                        btnToggleRun.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#C62828")) // Vibrant Red
                    } else {
                        btnToggleRun.text = "Start Server"
                        btnToggleRun.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32")) // Vibrant Green
                    }
                }
            }
        }.start()
    }

    private fun runRootCheck(cmd: String): String? {
        var process: Process? = null
        var output: String? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()
            
            val reader = process.inputStream.bufferedReader()
            output = reader.readLine()
            process.waitFor()
        } catch (e: Exception) {
            // ignore
        } finally {
            process?.destroy()
        }
        return output
    }

    private fun toggleFridaServer(start: Boolean) {
        btnToggleRun.isEnabled = false
        Thread {
            if (start) {
                // Start Frida Server as Daemon
                executeRootCommand("/data/local/tmp/frida-server -D &")
                runOnUiThread {
                    Toast.makeText(this, "Starting Frida Server...", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Kill Frida Server using universal kill -9 loop over pgrep PIDs, alongside fallback pkill/killall
                executeRootCommand("pkill -f frida-server")
                executeRootCommand("killall frida-server")
                executeRootCommand("for pid in \$(pgrep -f frida-server); do kill -9 \$pid; done")
                runOnUiThread {
                    Toast.makeText(this, "Stopping Frida Server...", Toast.LENGTH_SHORT).show()
                }
            }
            
            // Allow 1000ms for system processes to completely terminate before updating status
            Thread.sleep(1000)
            runOnUiThread { checkFridaStatus() }
        }.start()
    }

    private fun startFridaUninstallFlow() {
        btnToggleInstall.isEnabled = false
        btnToggleRun.isEnabled = false
        Thread {
            // Kill if running using all termination tools
            executeRootCommand("pkill -f frida-server")
            executeRootCommand("killall frida-server")
            executeRootCommand("for pid in \$(pgrep -f frida-server); do kill -9 \$pid; done")
            
            // Delete file
            executeRootCommand("rm -f /data/local/tmp/frida-server")
            
            runOnUiThread {
                Toast.makeText(this, "Frida Server uninstalled successfully.", Toast.LENGTH_SHORT).show()
                btnToggleInstall.isEnabled = true
                checkFridaStatus()
            }
        }.start()
    }

    private fun executeRootCommand(cmd: String) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
        } catch (e: Exception) {
            // ignore
        } finally {
            process?.destroy()
        }
    }

    private fun isRootAvailable(): Boolean {
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

    private fun startFridaInstallFlow() {
        val selectedVer = spinnerFridaVersion.selectedItem.toString()
        val version = if (selectedVer == "Custom Version...") {
            etCustomFridaVersion.text.toString().trim()
        } else {
            selectedVer
        }

        if (version.isEmpty() || !version.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))) {
            Toast.makeText(this, "Please enter a valid Frida version (e.g. 16.1.4)", Toast.LENGTH_LONG).show()
            return
        }

        // Get hardware ABI
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"
        val fridaAbi = when {
            abi.contains("arm64") -> "arm64"
            abi.contains("armeabi") || abi.contains("arm") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> "arm64"
        }

        val downloadUrl = "https://github.com/frida/frida/releases/download/$version/frida-server-$version-android-$fridaAbi.xz"
        
        layoutDownloadProgress.visibility = View.VISIBLE
        btnToggleInstall.isEnabled = false
        btnToggleRun.isEnabled = false

        Thread {
            // Check root availability in background thread before downloading large archives
            val hasRoot = isRootAvailable()
            if (!hasRoot) {
                runOnUiThread {
                    layoutDownloadProgress.visibility = View.GONE
                    btnToggleInstall.isEnabled = true
                    Toast.makeText(this, "Root access is required to install Frida Server!", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            val tempCompressed = File(cacheDir, "frida_server.xz")
            val tempDecompressed = File(cacheDir, "frida_server")
            
            if (tempCompressed.exists()) tempCompressed.delete()
            if (tempDecompressed.exists()) tempDecompressed.delete()

            try {
                // 1. Download xz archive
                downloadFile(downloadUrl, tempCompressed) { progress ->
                    runOnUiThread {
                        progressBarFridaDownload.progress = progress
                        tvDownloadProgressText.text = "Downloading frida-server-$version: $progress%"
                    }
                }

                // 2. Decompress XZ archive
                runOnUiThread {
                    tvDownloadProgressText.text = "Decompressing Frida archive (.xz)..."
                    progressBarFridaDownload.isIndeterminate = true
                }

                XZInputStream(FileInputStream(tempCompressed)).use { xzIn ->
                    FileOutputStream(tempDecompressed).use { out ->
                        xzIn.copyTo(out)
                    }
                }

                // 3. Move via root to /data/local/tmp/
                runOnUiThread {
                    tvDownloadProgressText.text = "Installing to /data/local/tmp/ via Root..."
                }

                copyToDataLocalTmp(tempDecompressed)

                runOnUiThread {
                    layoutDownloadProgress.visibility = View.GONE
                    btnToggleInstall.isEnabled = true
                    Toast.makeText(this, "Frida Server $version installed successfully!", Toast.LENGTH_LONG).show()
                    checkFridaStatus()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    layoutDownloadProgress.visibility = View.GONE
                    btnToggleInstall.isEnabled = true
                    Toast.makeText(this, "Installation Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    checkFridaStatus()
                }
            } finally {
                // Cleanup temp files
                if (tempCompressed.exists()) tempCompressed.delete()
                if (tempDecompressed.exists()) tempDecompressed.delete()
            }
        }.start()
    }

    private fun copyToDataLocalTmp(sourceFile: File) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val targetPath = "/data/local/tmp/frida-server"
            
            os.writeBytes("cp \"${sourceFile.absolutePath}\" \"$targetPath\"\n")
            os.writeBytes("chmod 755 \"$targetPath\"\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
        } finally {
            process?.destroy()
        }
    }

    private fun downloadFile(urlStr: String, destinationFile: File, onProgress: (Int) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            var url = URL(urlStr)
            var redirect = true
            var count = 0
            while (redirect && count < 5) {
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                
                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || 
                    status == HttpURLConnection.HTTP_MOVED_PERM || 
                    status == HttpURLConnection.HTTP_SEE_OTHER) {
                    val newUrl = connection.getHeaderField("Location")
                    url = URL(newUrl)
                    count++
                } else {
                    redirect = false
                }
            }

            if (connection?.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP Error ${connection?.responseCode}: Not Found or Invalid Version.")
            }

            val fileLength = connection.contentLength
            connection.inputStream.use { input ->
                destinationFile.outputStream().use { output ->
                    val data = ByteArray(4096)
                    var total = 0L
                    var read: Int
                    while (input.read(data).also { read = it } != -1) {
                        total += read
                        if (fileLength > 0) {
                            onProgress(((total * 100) / fileLength).toInt())
                        }
                        output.write(data, 0, read)
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }
    }
}
