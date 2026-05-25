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
        val tvAppType = findViewById<TextView>(R.id.tvAppType)
        val tvMainActivity = findViewById<TextView>(R.id.tvMainActivity)
        val tvMinSdk = findViewById<TextView>(R.id.tvMinSdk)
        val tvTargetSdk = findViewById<TextView>(R.id.tvTargetSdk)
        val tvInstaller = findViewById<TextView>(R.id.tvInstaller)
        val tvInstallTime = findViewById<TextView>(R.id.tvInstallTime)
        val tvUpdateTime = findViewById<TextView>(R.id.tvUpdateTime)
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
        val btnViewManifest = findViewById<android.widget.Button>(R.id.btnViewManifest)
        val tvFrameworkPacker = findViewById<TextView>(R.id.tvFrameworkPacker)
        val tvObfuscationScore = findViewById<TextView>(R.id.tvObfuscationScore)
        val tvTechnology = findViewById<TextView>(R.id.tvTechnology)
        tvTechnology.text = "Technology: Scanning..."

        tvPackageNameHeader.text = packageName

        btnViewManifest.setOnClickListener {
            val intent = android.content.Intent(this, ManifestViewerActivity::class.java)
            intent.putExtra("PACKAGE_NAME", packageName)
            startActivity(intent)
        }

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
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }

            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            tvVersion.text = "Version: ${packageInfo.versionName} ($vCode)"
            
            val appInfo = packageInfo.applicationInfo
            if (appInfo != null) {
                // App Type (System vs User)
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                tvAppType.text = "App Type: ${if (isSystem) "System App" else "User App"}"

                // Main Activity
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                val mainActivityName = launchIntent?.component?.className ?: "None / Not Launchable"
                tvMainActivity.text = "Main Activity: $mainActivityName"

                if (launchIntent != null) {
                    tvMainActivity.setOnLongClickListener {
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Main Activity", mainActivityName)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(this, "Main Activity class name copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                tvMinSdk.text = "Min SDK: ${appInfo.minSdkVersion}"
                tvTargetSdk.text = "Target SDK: ${appInfo.targetSdkVersion}"
                
                // Installer Info
                val installer = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        packageManager.getInstallSourceInfo(packageName).installingPackageName ?: "Sideloaded / Unknown"
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getInstallerPackageName(packageName) ?: "Sideloaded / Unknown"
                    }
                } catch (e: Exception) {
                    "Sideloaded / Unknown"
                }
                tvInstaller.text = "Installed Via: $installer"

                // Installation & Update times
                tvInstallTime.text = "First Installed: ${formatTime(packageInfo.firstInstallTime)}"
                tvUpdateTime.text = "Last Updated: ${formatTime(packageInfo.lastUpdateTime)}"

                tvDataDir.text = "Data Dir: ${appInfo.dataDir}"
                tvSourceDir.text = "Source Dir: ${appInfo.sourceDir}"
                tvNativeLibDir.text = "Native Lib Dir: ${appInfo.nativeLibraryDir}"

                // Security Flags
                val tvDebuggableRisk = findViewById<TextView>(R.id.tvDebuggableRisk)
                val tvAllowBackupRisk = findViewById<TextView>(R.id.tvAllowBackupRisk)
                val tvCleartextRisk = findViewById<TextView>(R.id.tvCleartextRisk)


                val isDebug = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val isBackup = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
                val isCleartext = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0

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

                tvCleartext.text = "Allows Cleartext Traffic (HTTP): ${if (isCleartext) "YES (Risk)" else "No"}"
                tvCleartext.setTextColor(if (isCleartext) android.graphics.Color.YELLOW else android.graphics.Color.WHITE)
                if (isCleartext) {
                    tvCleartextRisk.visibility = android.view.View.VISIBLE
                    tvCleartextRisk.text = "Implication: Sensitive data may be transmitted unencrypted over the network."
                } else {
                    tvCleartextRisk.visibility = android.view.View.GONE
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

                // Global app-level permission fallback (if package manifest declares a global permission)
                val globalPermission = appInfo.permission

                // Component Stats with Unprotected Exported auditing
                val actCount = packageInfo.activities?.size ?: 0
                val actExportedList = packageInfo.activities?.filter { it.exported } ?: emptyList()
                val actExportedCount = actExportedList.size
                val actUnprotectedCount = actExportedList.count { it.permission == null && globalPermission == null }
                tvCompActivities.text = "Activities: $actCount ($actExportedCount exported, $actUnprotectedCount unprotected)"

                val servCount = packageInfo.services?.size ?: 0
                val servExportedList = packageInfo.services?.filter { it.exported } ?: emptyList()
                val servExportedCount = servExportedList.size
                val servUnprotectedCount = servExportedList.count { it.permission == null && globalPermission == null }
                tvCompServices.text = "Services: $servCount ($servExportedCount exported, $servUnprotectedCount unprotected)"

                val recCount = packageInfo.receivers?.size ?: 0
                val recExportedList = packageInfo.receivers?.filter { it.exported } ?: emptyList()
                val recExportedCount = recExportedList.size
                val recUnprotectedCount = recExportedList.count { it.permission == null && globalPermission == null }
                tvCompReceivers.text = "Receivers: $recCount ($recExportedCount exported, $recUnprotectedCount unprotected)"

                val provCount = packageInfo.providers?.size ?: 0
                val provExportedList = packageInfo.providers?.filter { it.exported } ?: emptyList()
                val provExportedCount = provExportedList.size
                val provUnprotectedCount = provExportedList.count { it.readPermission == null && it.writePermission == null && globalPermission == null }
                tvCompProviders.text = "Providers: $provCount ($provExportedCount exported, $provUnprotectedCount unprotected)"
            }

            val dangerousList = mutableListOf<String>()
            val signatureList = mutableListOf<String>()
            val normalList = mutableListOf<String>()

            val dangerousPerms = setOf(
                "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS",
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
                "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
                "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE",
                "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
                "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.QUERY_ALL_PACKAGES"
            )

            if (packageInfo.requestedPermissions != null) {
                for (i in packageInfo.requestedPermissions.indices) {
                    val perm = packageInfo.requestedPermissions[i]
                    val isGranted = if (packageInfo.requestedPermissionsFlags != null && packageInfo.requestedPermissionsFlags.size > i) {
                        (packageInfo.requestedPermissionsFlags[i] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    } else {
                        false
                    }
                    
                    val statusStr = if (isGranted) "✓" else "✗"
                    val entryText = "$statusStr ${perm.substringAfterLast('.')}"
                    val entryColor = if (isGranted) "#4CAF50" else "#F44336"
                    
                    if (dangerousPerms.contains(perm)) {
                        dangerousList.add("<font color='$entryColor'>$entryText</font>")
                    } else if (perm.contains("signature", ignoreCase = true) || perm.contains("system", ignoreCase = true)) {
                        signatureList.add("<font color='$entryColor'>$entryText</font>")
                    } else {
                        normalList.add("<font color='$entryColor'>$entryText</font>")
                    }
                }

                if (dangerousList.isNotEmpty()) {
                    llPermissions.addView(TextView(this).apply {
                        text = "🔴 DANGEROUS / PRIVACY PERMISSIONS:"
                        setTextColor(android.graphics.Color.parseColor("#FF5722"))
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 8, 0, 4)
                    })
                    for (p in dangerousList) {
                        llPermissions.addView(TextView(this).apply {
                            text = android.text.Html.fromHtml(p, android.text.Html.FROM_HTML_MODE_LEGACY)
                            textSize = 13f
                            setPadding(12, 2, 0, 2)
                        })
                    }
                }

                if (signatureList.isNotEmpty()) {
                    llPermissions.addView(TextView(this).apply {
                        text = "🟡 SIGNATURE / ELEVATED PERMISSIONS:"
                        setTextColor(android.graphics.Color.parseColor("#FFEB3B"))
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 8, 0, 4)
                    })
                    for (p in signatureList) {
                        llPermissions.addView(TextView(this).apply {
                            text = android.text.Html.fromHtml(p, android.text.Html.FROM_HTML_MODE_LEGACY)
                            textSize = 13f
                            setPadding(12, 2, 0, 2)
                        })
                    }
                }

                if (normalList.isNotEmpty()) {
                    llPermissions.addView(TextView(this).apply {
                        text = "🟢 NORMAL / UTILITY PERMISSIONS:"
                        setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 8, 0, 4)
                    })
                    for (p in normalList) {
                        llPermissions.addView(TextView(this).apply {
                            text = android.text.Html.fromHtml(p, android.text.Html.FROM_HTML_MODE_LEGACY)
                            textSize = 13f
                            setPadding(12, 2, 0, 2)
                        })
                    }
                }
            } else {
                llPermissions.addView(TextView(this).apply {
                    text = "No permissions requested."
                    setTextColor(android.graphics.Color.WHITE)
                })
            }

            // Asynchronous premium audits for Modules 1, 2, and 4
            Thread {
                val cacheDir = cacheDir
                val apkPaths = ArrayList<String>().apply {
                    add(appInfo.sourceDir)
                    appInfo.splitSourceDirs?.let { addAll(it) }
                }
                
                // 1. Scan ZIP entries across base and all split APKs to locate native libraries
                val entriesList = ArrayList<Pair<String, String>>() // Pair(apkPath, entryName)
                for (apk in apkPaths) {
                    try {
                        java.util.zip.ZipFile(apk).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                entriesList.add(Pair(apk, entries.nextElement().name))
                            }
                        }
                    } catch (e: Exception) {
                        // Fallback to unzip -l via root
                        try {
                            val process = Runtime.getRuntime().exec("su")
                            val os = java.io.DataOutputStream(process.outputStream)
                            os.writeBytes("unzip -l \"$apk\"\n")
                            os.writeBytes("exit\n")
                            os.flush()
                            
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val parts = line!!.trim().split(Regex("\\s+"))
                                if (parts.size >= 4) {
                                    val name = parts.subList(3, parts.size).joinToString(" ")
                                    entriesList.add(Pair(apk, name))
                                }
                            }
                            process.waitFor()
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }

                // 2. Detect Framework and Packer across all entries
                var framework = "Native (Java/Kotlin)"
                var packer = "None detected (Clean)"
                
                for (pair in entriesList) {
                    val entry = pair.second
                    if (entry.contains("libflutter.so") || entry.contains("libapp.so")) {
                        framework = "Flutter"
                    } else if (entry.contains("libreactnativejni.so") || entry.contains("index.android.bundle")) {
                        framework = "React Native"
                    } else if (entry.contains("libmonodroid.so") || entry.contains("libmonosgen-2.0.so")) {
                        framework = "Xamarin"
                    } else if (entry.contains("libunity.so") || entry.contains("libmain.so")) {
                        framework = "Unity"
                    } else if (entry.contains("assets/www/cordova.js") || entry.contains("assets/www/index.html")) {
                        framework = "Cordova"
                    }
                    
                    if (entry.contains("libjiagu.so") || entry.contains("libjiagu_art.so")) {
                        packer = "Qihoo 360 (Jiagu)"
                    } else if (entry.contains("libshell.so") || entry.contains("libtx3g.so")) {
                        packer = "Tencent Legu"
                    } else if (entry.contains("libsecapk.so") || entry.contains("libsecexe.so")) {
                        packer = "Bangcle (SecApk)"
                    } else if (entry.contains("libbaiduprotect.so")) {
                        packer = "Baidu Protect"
                    }
                }

                // 3. Initialize DecompilerEngine to calculate Obfuscation Score
                DecompilerEngine.init(appInfo.sourceDir, cacheDir)
                val classes = DecompilerEngine.getClassList()
                
                var obfuscationScore = 0
                var obfuscatorTech = "None detected (Clean)"
                
                if (classes.isNotEmpty()) {
                    var obfuscatedCount = 0
                    var singleLetterCount = 0
                    val alphabeticRenames = setOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z")
                    
                    for (className in classes) {
                        val simpleName = className.substringAfterLast('.')
                        if (simpleName.length <= 2) {
                            obfuscatedCount++
                            if (alphabeticRenames.contains(simpleName.lowercase(java.util.Locale.getDefault()))) {
                                singleLetterCount++
                            }
                        }
                        if (className.contains("ALLATORI_DEMO", ignoreCase = true)) {
                            obfuscatorTech = "Allatori Obfuscator"
                        }
                    }
                    
                    obfuscationScore = (obfuscatedCount * 100) / classes.size
                    
                    if (obfuscatorTech == "None detected (Clean)" && obfuscationScore > 30) {
                        if (singleLetterCount > (obfuscatedCount * 0.7)) {
                            obfuscatorTech = "R8 / ProGuard"
                        } else {
                            obfuscatorTech = "Generic Class Renaming"
                        }
                    }
                }

                // Double check packer detection via native libraries
                var detectedPacker = packer
                val nativeLibEntries = entriesList.filter { it.second.startsWith("lib/") && it.second.endsWith(".so") }
                for (pair in nativeLibEntries) {
                    val entry = pair.second
                    if (entry.contains("libjiagu.so") || entry.contains("libjiagu_art.so")) {
                        detectedPacker = "Qihoo 360 (Jiagu)"
                    } else if (entry.contains("libshell.so") || entry.contains("libtx3g.so")) {
                        detectedPacker = "Tencent Legu"
                    } else if (entry.contains("libsecapk.so") || entry.contains("libsecexe.so")) {
                        detectedPacker = "Bangcle (SecApk)"
                    } else if (entry.contains("libbaiduprotect.so")) {
                        detectedPacker = "Baidu Protect"
                    } else if (entry.contains("libdexprotector.so") || entry.contains("libdp.so")) {
                        detectedPacker = "DexProtector"
                    }
                }
                
                if (detectedPacker != "None detected (Clean)") {
                    obfuscatorTech = "Packer ($detectedPacker)"
                    obfuscationScore = maxOf(obfuscationScore, 95)
                } else {
                    for (pair in entriesList) {
                        val entry = pair.second
                        if (entry.contains("libdexprotector") || entry.contains("libdp.so")) {
                            detectedPacker = "DexProtector"
                            obfuscatorTech = "DexProtector / DexGuard"
                            obfuscationScore = maxOf(obfuscationScore, 95)
                        }
                    }
                }
                
                // Audit native mitigations across correct split APK files
                val targetAbi = if (nativeLibEntries.any { it.second.contains("arm64-v8a") }) "arm64-v8a" else if (nativeLibEntries.any { it.second.contains("armeabi-v7a") }) "armeabi-v7a" else ""
                val libsToAudit = if (targetAbi.isNotEmpty()) {
                    nativeLibEntries.filter { it.second.contains(targetAbi) }
                } else {
                    nativeLibEntries.take(5)
                }
                val auditedLibs = libsToAudit.mapNotNull { pair -> auditNativeLibrary(pair.first, pair.second) }



                // Update UI on main thread
                runOnUiThread {
                    tvTechnology.text = "Technology: $framework"
                    tvFrameworkPacker.text = "Protector/Packer: $detectedPacker"
                    
                    val scoreText = "Obfuscation Score: $obfuscationScore% (${if (obfuscationScore > 50) "Highly Obfuscated" else if (obfuscationScore > 15) "Moderately Obfuscated" else "Low/No Obfuscation"})"
                    val techText = if (obfuscationScore > 15) " | Method: $obfuscatorTech" else ""
                    tvObfuscationScore.text = "$scoreText$techText"
                    tvObfuscationScore.setTextColor(if (obfuscationScore > 50) android.graphics.Color.parseColor("#FF5722") else if (obfuscationScore > 15) android.graphics.Color.parseColor("#FFEB3B") else android.graphics.Color.WHITE)



                    if (auditedLibs.isNotEmpty()) {
                        findViewById<androidx.cardview.widget.CardView>(R.id.cardNativeLibs).visibility = android.view.View.VISIBLE
                        val llNativeLibs = findViewById<android.widget.LinearLayout>(R.id.llNativeLibs)
                        llNativeLibs.removeAllViews()
                        
                        for (lib in auditedLibs) {
                            val view = TextView(this@ApkDetailsActivity).apply {
                                val pieText = if (lib.hasPie) "<font color='#4CAF50'>PIE</font>" else "<font color='#F44336'>No PIE</font>"
                                val canaryText = if (lib.hasCanary) "<font color='#4CAF50'>Canary</font>" else "<font color='#F44336'>No Canary</font>"
                                val nxText = if (lib.hasNx) "<font color='#4CAF50'>NX</font>" else "<font color='#F44336'>No NX</font>"
                                
                                text = android.text.Html.fromHtml("<b>${lib.name}</b>: $pieText | $canaryText | $nxText", android.text.Html.FROM_HTML_MODE_LEGACY)
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 13f
                                setPadding(0, 4, 0, 4)
                            }
                            llNativeLibs.addView(view)
                        }
                    }
                }
            }.start()

        } catch (e: PackageManager.NameNotFoundException) {
            tvVersion.text = "Error: Package not found"
        }
    }

    private fun formatTime(timeMs: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timeMs))
    }



    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(this, "Command copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    private data class NativeMitigations(
        val name: String,
        val hasPie: Boolean,
        val hasCanary: Boolean,
        val hasNx: Boolean
    )

    private fun detectFrameworkAndPacker(apkPath: String): Pair<String, String> {
        var framework = "Native (Java/Kotlin)"
        var packer = "None detected (Clean)"
        val entriesList = mutableListOf<String>()
        
        try {
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    entriesList.add(entries.nextElement().name)
                }
            }
        } catch (e: Exception) {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = java.io.DataOutputStream(process.outputStream)
                os.writeBytes("unzip -l \"$apkPath\"\n")
                os.writeBytes("exit\n")
                os.flush()
                
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val name = parts.subList(3, parts.size).joinToString(" ")
                        entriesList.add(name)
                    }
                }
                process.waitFor()
            } catch (ex: Exception) {}
        }

        for (entry in entriesList) {
            if (entry.contains("libflutter.so") || entry.contains("libapp.so")) {
                framework = "Flutter"
            } else if (entry.contains("libreactnativejni.so") || entry.contains("index.android.bundle")) {
                framework = "React Native"
            } else if (entry.contains("libmonodroid.so") || entry.contains("libmonosgen-2.0.so")) {
                framework = "Xamarin"
            } else if (entry.contains("libunity.so") || entry.contains("libmain.so")) {
                framework = "Unity"
            } else if (entry.contains("assets/www/cordova.js") || entry.contains("assets/www/index.html")) {
                framework = "Cordova"
            }
            
            if (entry.contains("libjiagu.so") || entry.contains("libjiagu_art.so")) {
                packer = "Qihoo 360 (Jiagu)"
            } else if (entry.contains("libshell.so") || entry.contains("libtx3g.so")) {
                packer = "Tencent Legu"
            } else if (entry.contains("libsecapk.so") || entry.contains("libsecexe.so")) {
                packer = "Bangcle (SecApk)"
            } else if (entry.contains("libbaiduprotect.so")) {
                packer = "Baidu Protect"
            }
        }
        return Pair(framework, packer)
    }

    private fun auditNativeLibrary(apkPath: String, entryName: String): NativeMitigations? {
        val maxBytes = 65536
        val buffer = ByteArray(maxBytes)
        var bytesRead: Int
        
        try {
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null
                zip.getInputStream(entry).use { stream ->
                    bytesRead = stream.read(buffer)
                }
            }
        } catch (e: Exception) {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = java.io.DataOutputStream(process.outputStream)
                os.writeBytes("unzip -p \"$apkPath\" \"$entryName\"\n")
                os.writeBytes("exit\n")
                os.flush()
                
                val stream = process.inputStream
                bytesRead = stream.read(buffer)
                process.waitFor()
            } catch (ex: Exception) {
                return null
            }
        }

        if (bytesRead < 54) return null
        if (buffer[0] != 0x7F.toByte() || buffer[1] != 0x45.toByte() || buffer[2] != 0x4C.toByte() || buffer[3] != 0x46.toByte()) {
            return null
        }

        val is64Bit = buffer[4] == 2.toByte()
        val typeLow = buffer[16].toInt() and 0xFF
        val typeHigh = buffer[17].toInt() and 0xFF
        val elfType = (typeHigh shl 8) or typeLow
        val hasPie = elfType == 3

        val canaryString = "__stack_chk_fail"
        val canaryBytes = canaryString.toByteArray(Charsets.US_ASCII)
        val hasCanary = indexOfBytes(buffer, canaryBytes, bytesRead) != -1

        val phoff = if (is64Bit) {
            readLongLE(buffer, 32)
        } else {
            readIntLE(buffer, 28).toLong()
        }
        
        val phnum = if (is64Bit) {
            readShortLE(buffer, 56)
        } else {
            readShortLE(buffer, 44)
        }

        val phentsize = if (is64Bit) {
            readShortLE(buffer, 54)
        } else {
            readShortLE(buffer, 42)
        }

        var hasNx = true
        try {
            val ptGnuStackType = 0x6474e551L
            for (i in 0 until phnum) {
                val offset = (phoff + i * phentsize).toInt()
                if (offset + phentsize > bytesRead) break
                val pType = readIntLE(buffer, offset).toLong() and 0xFFFFFFFFL
                if (pType == ptGnuStackType) {
                    val pFlagsOffset = if (is64Bit) offset + 4 else offset + 24
                    val pFlags = readIntLE(buffer, pFlagsOffset)
                    if ((pFlags and 1) != 0) {
                        hasNx = false
                    }
                    break
                }
            }
        } catch (e: Exception) {}

        val simpleName = entryName.substringAfterLast('/')
        return NativeMitigations(simpleName, hasPie, hasCanary, hasNx)
    }

    private fun readIntLE(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
               ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
               ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
               ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
               ((buffer[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLongLE(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((buffer[offset + i].toLong() and 0xFF) shl (i * 8))
        }
        return value
    }

    private fun indexOfBytes(data: ByteArray, pattern: ByteArray, limit: Int): Int {
        for (i in 0..limit - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
