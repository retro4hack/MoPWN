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

        tvPackageNameHeader.text = packageName

        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong() or PackageManager.GET_META_DATA.toLong())
                )
            } else {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
                )
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
}
