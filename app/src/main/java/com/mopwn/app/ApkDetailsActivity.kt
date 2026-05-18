package com.mopwn.app

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ApkDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apk_details)

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: return
        
        val tvPackageNameHeader = findViewById<TextView>(R.id.tvPackageNameHeader)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val tvMinSdk = findViewById<TextView>(R.id.tvMinSdk)
        val tvTargetSdk = findViewById<TextView>(R.id.tvTargetSdk)
        val tvDataDir = findViewById<TextView>(R.id.tvDataDir)
        val tvSourceDir = findViewById<TextView>(R.id.tvSourceDir)
        val tvNativeLibDir = findViewById<TextView>(R.id.tvNativeLibDir)
        val llPermissions = findViewById<LinearLayout>(R.id.llPermissions)

        // New Views
        val tvDebuggable = findViewById<TextView>(R.id.tvDebuggable)
        val tvAllowBackup = findViewById<TextView>(R.id.tvAllowBackup)
        val tvCleartext = findViewById<TextView>(R.id.tvCleartext)
        val tvSharedUid = findViewById<TextView>(R.id.tvSharedUid)
        val tvAppUid = findViewById<TextView>(R.id.tvAppUid)
        val tvCompActivities = findViewById<TextView>(R.id.tvCompActivities)
        val tvCompServices = findViewById<TextView>(R.id.tvCompServices)
        val tvCompReceivers = findViewById<TextView>(R.id.tvCompReceivers)
        val tvCompProviders = findViewById<TextView>(R.id.tvCompProviders)

        tvPackageNameHeader.text = packageName

        try {
            val flags = PackageManager.GET_PERMISSIONS or 
                        PackageManager.GET_META_DATA or 
                        PackageManager.GET_ACTIVITIES or 
                        PackageManager.GET_SERVICES or 
                        PackageManager.GET_RECEIVERS or 
                        PackageManager.GET_PROVIDERS

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                packageManager.getPackageInfo(packageName, flags)
            }

            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
            tvVersion.text = "Version: ${packageInfo.versionName} ($vCode)"
            
            val appInfo = packageInfo.applicationInfo
            if (appInfo != null) {
                tvMinSdk.text = "Min SDK: ${appInfo.minSdkVersion}"
                tvTargetSdk.text = "Target SDK: ${appInfo.targetSdkVersion}"
                tvDataDir.text = "Data Dir: ${appInfo.dataDir}"
                tvSourceDir.text = "Source Dir: ${appInfo.sourceDir}"
                tvNativeLibDir.text = "Native Lib Dir: ${appInfo.nativeLibraryDir}"

                // Security Flags
                val tvDebuggableRisk = findViewById<TextView>(R.id.tvDebuggableRisk)
                val tvAllowBackupRisk = findViewById<TextView>(R.id.tvAllowBackupRisk)
                val tvCleartextRisk = findViewById<TextView>(R.id.tvCleartextRisk)

                val isDebug = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val isBackup = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
                val isCleartext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
                } else true

                tvDebuggable.text = "Debuggable: ${if (isDebug) "YES (Vulnerable)" else "No"}"
                tvDebuggable.setTextColor(if (isDebug) android.graphics.Color.RED else android.graphics.Color.WHITE)
                if (isDebug) {
                    val cmd = "adb shell run-as $packageName"
                    tvDebuggableRisk.visibility = android.view.View.VISIBLE
                    tvDebuggableRisk.text = "Implication: Attackers can attach a debugger or execute code as the app UID.\nCommand: $cmd"
                    tvDebuggableRisk.setOnLongClickListener {
                        copyToClipboard("ADB Debug", cmd)
                        true
                    }
                } else {
                    tvDebuggableRisk.visibility = android.view.View.GONE
                }
                
                tvAllowBackup.text = "Allow Backup: ${if (isBackup) "YES (Risk)" else "No"}"
                tvAllowBackup.setTextColor(if (isBackup) android.graphics.Color.YELLOW else android.graphics.Color.WHITE)
                if (isBackup) {
                    val cmd = "adb backup $packageName"
                    tvAllowBackupRisk.visibility = android.view.View.VISIBLE
                    tvAllowBackupRisk.text = "Implication: Data can be extracted via ADB without root.\nCommand: $cmd"
                    tvAllowBackupRisk.setOnLongClickListener {
                        copyToClipboard("ADB Backup", cmd)
                        true
                    }
                } else {
                    tvAllowBackupRisk.visibility = android.view.View.GONE
                }
                
                tvSharedUid.text = "Shared User ID: ${packageInfo.sharedUserId ?: "None"}"
                tvAppUid.text = "Application UID: ${appInfo.uid}"

                val btnFindSisterApps = findViewById<android.widget.Button>(R.id.btnFindSisterApps)
                if (packageInfo.sharedUserId != null) {
                    btnFindSisterApps.visibility = android.view.View.VISIBLE
                    btnFindSisterApps.setOnClickListener {
                        val sharedId = packageInfo.sharedUserId
                        val allPackages = packageManager.getInstalledPackages(0)
                        val sisters = allPackages.filter { it.sharedUserId == sharedId && it.packageName != packageName }
                        
                        val sisterNames = sisters.joinToString("\n") { it.packageName }
                        val displayMsg = if (sisterNames.isEmpty()) "No other apps found with this Shared UID." else sisterNames
                        
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Sister Apps (Shared UID: $sharedId)")
                            .setMessage(displayMsg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                } else {
                    btnFindSisterApps.visibility = android.view.View.GONE
                }

                // Component Stats
                val actCount = packageInfo.activities?.size ?: 0
                val actExported = packageInfo.activities?.count { it.exported } ?: 0
                tvCompActivities.text = "Activities: $actCount ($actExported exported)"

                val servCount = packageInfo.services?.size ?: 0
                val servExported = packageInfo.services?.count { it.exported } ?: 0
                tvCompServices.text = "Services: $servCount ($servExported exported)"

                val recCount = packageInfo.receivers?.size ?: 0
                val recExported = packageInfo.receivers?.count { it.exported } ?: 0
                tvCompReceivers.text = "Receivers: $recCount ($recExported exported)"

                val provCount = packageInfo.providers?.size ?: 0
                val provExported = packageInfo.providers?.count { it.exported } ?: 0
                tvCompProviders.text = "Providers: $provCount ($provExported exported)"
            }

            if (packageInfo.requestedPermissions != null) {
                for (i in packageInfo.requestedPermissions.indices) {
                    val perm = packageInfo.requestedPermissions[i]
                    val isGranted = if (packageInfo.requestedPermissionsFlags != null && packageInfo.requestedPermissionsFlags.size > i) {
                        (packageInfo.requestedPermissionsFlags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    } else {
                        false
                    }
                    
                    val permView = TextView(this).apply {
                        text = if (isGranted) "✓ $perm" else "✗ $perm"
                        setTextColor(if (isGranted) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#F44336"))
                        textSize = 14f
                        setPadding(0, 4, 0, 4)
                    }
                    llPermissions.addView(permView)
                }
            } else {
                llPermissions.addView(TextView(this).apply {
                    text = "No permissions requested."
                    setTextColor(android.graphics.Color.WHITE)
                })
            }

        } catch (e: PackageManager.NameNotFoundException) {
            tvVersion.text = "Error: Package not found"
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(this, "Command copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }
}
