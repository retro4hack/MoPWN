package com.mopwn.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class InspectPackageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspect_package)

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: return

        findViewById<TextView>(R.id.tvInspectPackageName).text = packageName

        findViewById<Button>(R.id.btnApkDetails).setOnClickListener {
            val intent = Intent(this, ApkDetailsActivity::class.java)
            intent.putExtra("PACKAGE_NAME", packageName)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnExportedActivities).setOnClickListener {
            val intent = Intent(this, ExportedActivitiesActivity::class.java)
            intent.putExtra("PACKAGE_NAME", packageName)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnBrowseFiles).setOnClickListener {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val intent = Intent(this, FileBrowserActivity::class.java)
                intent.putExtra("PACKAGE_NAME", packageName)
                intent.putExtra("DATA_DIR", appInfo.dataDir)
                startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnDumpApk).setOnClickListener {
            extractApk(packageName, share = false)
        }

        findViewById<Button>(R.id.btnShareApk).setOnClickListener {
            extractApk(packageName, share = true)
        }
    }

    private fun extractApk(packageName: String, share: Boolean) {
        val message = if (share) "Preparing APK(s) for sharing..." else "Dumping APK(s) to Downloads..."
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        
        Thread {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val allApks = mutableListOf<String>()
                allApks.add(appInfo.sourceDir)
                appInfo.splitSourceDirs?.let { allApks.addAll(it) }

                val extractedUris = ArrayList<android.net.Uri>()
                var successCount = 0
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)

                allApks.forEachIndexed { index, apkPath ->
                    val sourceFile = java.io.File(apkPath)
                    val fileName = sourceFile.name
                    
                    val destFile = if (share) {
                        java.io.File(cacheDir, fileName)
                    } else {
                        java.io.File(downloadsDir, fileName)
                    }

                    val process = try {
                        Runtime.getRuntime().exec("su -mm")
                    } catch (e: Exception) {
                        Runtime.getRuntime().exec("su")
                    }
                    
                    val os = java.io.DataOutputStream(process.outputStream)
                    os.writeBytes("cp \"${sourceFile.absolutePath}\" \"${destFile.absolutePath}\"\n")
                    os.writeBytes("chmod 666 \"${destFile.absolutePath}\"\n")
                    os.writeBytes("exit\n")
                    os.flush()
                    process.waitFor()

                    if (destFile.exists()) {
                        successCount++
                        if (share) {
                            extractedUris.add(androidx.core.content.FileProvider.getUriForFile(this, "com.mopwn.app.provider", destFile))
                        }
                    }
                }

                runOnUiThread {
                    if (successCount > 0) {
                        if (share) {
                            val shareIntent = if (extractedUris.size == 1) {
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/vnd.android.package-archive"
                                    putExtra(Intent.EXTRA_STREAM, extractedUris[0])
                                }
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "application/vnd.android.package-archive"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, extractedUris)
                                }
                            }
                            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(Intent.createChooser(shareIntent, "Share ${successCount} APK(s) via"))
                        } else {
                            android.widget.Toast.makeText(this, "Successfully dumped $successCount APK(s) to Downloads", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else {
                        android.widget.Toast.makeText(this, "Failed to extract APKs", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Root is needed for this action", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
