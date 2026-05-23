package com.mopwn.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class FileItem(val name: String, val isDir: Boolean, val meta: String, val fullPath: String, var isSelected: Boolean = false)

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var tvCurrentPath: TextView
    private lateinit var rvFiles: RecyclerView
    private lateinit var adapter: FileAdapter
    
    // Multi-selection UI
    private lateinit var layoutSelection: LinearLayout
    private lateinit var btnShareSelected: Button
    private lateinit var btnSelectAll: Button
    
    // Universal ZIP export
    private lateinit var btnZipRoot: com.google.android.material.floatingactionbutton.FloatingActionButton
    
    private var currentPath = ""
    private var rootPath = ""
    private var targetPackage = ""
    private var isSelectionModeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        // Clear any leftover temporary export files upon launch
        cleanupTempFiles()

        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        rvFiles = findViewById(R.id.rvFiles)
        
        layoutSelection = findViewById(R.id.layoutSelection)
        btnShareSelected = findViewById(R.id.btnShareSelected)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnZipRoot = findViewById(R.id.btnZipRoot)
        
        rvFiles.layoutManager = LinearLayoutManager(this)

        targetPackage = intent.getStringExtra("PACKAGE_NAME") ?: "unknown_package"
        rootPath = intent.getStringExtra("DATA_DIR") ?: "/data/data/$targetPackage"
        currentPath = rootPath

        adapter = FileAdapter(
            files = emptyList(),
            isSelectionMode = false,
            onClick = { fileItem ->
                if (isSelectionModeActive) {
                    // Selection mode active: tap toggles selection
                    if (!fileItem.isDir) {
                        fileItem.isSelected = !fileItem.isSelected
                        adapter.notifyDataSetChanged()
                        updateShareButton()
                    }
                } else {
                    // Standard mode: directories navigate, files view text
                    if (fileItem.isDir) {
                        loadDirectory(fileItem.fullPath)
                    } else {
                        val intent = Intent(this, FileViewerActivity::class.java).apply {
                            putExtra("FILE_PATH", fileItem.fullPath)
                            putExtra("FILE_NAME", fileItem.name)
                        }
                        startActivity(intent)
                    }
                }
            },
            onLongClick = { fileItem ->
                if (!isSelectionModeActive) {
                    // Activate selection mode on long press
                    isSelectionModeActive = true
                    adapter.isSelectionMode = true
                    layoutSelection.visibility = View.VISIBLE
                    
                    if (!fileItem.isDir) {
                        fileItem.isSelected = true
                    }
                    
                    adapter.notifyDataSetChanged()
                    updateShareButton()
                }
            },
            onSelectionChanged = {
                updateShareButton()
            }
        )
        rvFiles.adapter = adapter

        btnSelectAll.setOnClickListener {
            val allSelected = adapter.files.all { it.isSelected || it.isDir }
            adapter.files.forEach { if (!it.isDir) it.isSelected = !allSelected }
            adapter.notifyDataSetChanged()
            updateShareButton()
            btnSelectAll.text = if (allSelected) "Select All" else "Deselect All"
        }

        btnShareSelected.setOnClickListener {
            val selectedFiles = adapter.files.filter { it.isSelected && !it.isDir }
            if (selectedFiles.isEmpty()) {
                Toast.makeText(this, "No files selected to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareSelectedFiles(selectedFiles)
        }

        btnZipRoot.setOnClickListener {
            zipRootDirectory()
        }

        loadDirectory(currentPath)
    }

    private fun updateShareButton() {
        val count = adapter.files.count { it.isSelected && !it.isDir }
        btnShareSelected.text = "Share ($count)"
        btnShareSelected.isEnabled = count > 0
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isSelectionModeActive) {
            // Back button cancels selection mode first
            isSelectionModeActive = false
            adapter.isSelectionMode = false
            adapter.files.forEach { it.isSelected = false }
            layoutSelection.visibility = View.GONE
            adapter.notifyDataSetChanged()
        } else if (currentPath != rootPath && currentPath.length > rootPath.length) {
            val parentPath = currentPath.substringBeforeLast("/")
            loadDirectory(if (parentPath.isEmpty()) "/" else parentPath)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure cleanup when leaving the browser
        cleanupTempFiles()
    }

    private fun loadDirectory(path: String) {
        Thread {
            try {
                var process = try {
                    Runtime.getRuntime().exec("su -mm")
                } catch (e: Exception) {
                    Runtime.getRuntime().exec("su")
                }
                
                val os = java.io.DataOutputStream(process.outputStream)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                os.writeBytes("id\n")
                os.writeBytes("ls -la \"$path\"\n")
                os.writeBytes("exit\n")
                os.flush()

                val files = mutableListOf<FileItem>()
                var line: String?
                
                val idOutput = reader.readLine()
                
                while (reader.readLine().also { line = it } != null) {
                    val pLine = line!!.trim()
                    if (pLine.startsWith("total") || pLine.isEmpty()) continue
                    
                    val parts = pLine.split(Regex("\\s+"))
                    if (parts.size >= 8) {
                        val name = parts.last()
                        if (name == "." || name == "..") continue
                        
                        val isDir = pLine.startsWith("d")
                        val size = parts.getOrNull(4) ?: "?"
                        val meta = "${parts[0]} | $size bytes"
                        files.add(FileItem(name, isDir, meta, "$path/$name".replace("//", "/")))
                    }
                }
                
                val exitCode = process.waitFor()
                val errorOutput = errorReader.readText()
                
                runOnUiThread {
                    if (exitCode != 0 || (files.isEmpty() && errorOutput.isNotEmpty())) {
                        val errMsg = if (errorOutput.isNotEmpty()) errorOutput else "No output from root"
                        findViewById<TextView>(R.id.tvError).apply {
                            visibility = View.VISIBLE
                            text = "ROOT ERROR:\n$errMsg\n(ID: $idOutput)\nPath: $path"
                        }
                    } else {
                        findViewById<TextView>(R.id.tvError).visibility = View.GONE
                        currentPath = path
                        tvCurrentPath.text = "Path: $currentPath"
                        files.sortBy { !it.isDir }
                        adapter.updateData(files)
                        updateShareButton()
                        btnSelectAll.text = "Select All"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@FileBrowserActivity, "Root is needed for this action", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun shareSelectedFiles(fileItems: List<FileItem>) {
        Toast.makeText(this, "Preparing ${fileItems.size} file(s) for sharing...", Toast.LENGTH_SHORT).show()
        Thread {
            val sharedDir = File(cacheDir, "selected_shares")
            if (sharedDir.exists()) {
                sharedDir.deleteRecursively()
            }
            sharedDir.mkdirs()

            val sharedUris = ArrayList<Uri>()

            fileItems.forEach { fileItem ->
                try {
                    val destFile = File(sharedDir, fileItem.name)
                    val process = try {
                        Runtime.getRuntime().exec("su -mm")
                    } catch (e: Exception) {
                        Runtime.getRuntime().exec("su")
                    }
                    
                    val os = java.io.DataOutputStream(process.outputStream)
                    os.writeBytes("cp \"${fileItem.fullPath}\" \"${destFile.absolutePath}\"\n")
                    os.writeBytes("chmod 666 \"${destFile.absolutePath}\"\n")
                    os.writeBytes("exit\n")
                    os.flush()
                    
                    if (process.waitFor() == 0 && destFile.exists()) {
                        val fileUri = FileProvider.getUriForFile(this@FileBrowserActivity, "com.mopwn.app.provider", destFile)
                        sharedUris.add(fileUri)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            runOnUiThread {
                if (sharedUris.isEmpty()) {
                    Toast.makeText(this@FileBrowserActivity, "Failed to copy files for sharing", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val shareIntent = Intent().apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (sharedUris.size == 1) {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, sharedUris[0])
                        type = "*/*"
                    } else {
                        action = Intent.ACTION_SEND_MULTIPLE
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, sharedUris)
                        type = "*/*"
                    }
                }
                
                // Clear selection and hide select layout
                isSelectionModeActive = false
                adapter.isSelectionMode = false
                adapter.files.forEach { it.isSelected = false }
                layoutSelection.visibility = View.GONE
                adapter.notifyDataSetChanged()

                startActivity(Intent.createChooser(shareIntent, "Share files using"))
            }
        }.start()
    }

    private fun zipRootDirectory() {
        Toast.makeText(this, "Creating root backup archive... please wait", Toast.LENGTH_LONG).show()
        Thread {
            val exportDir = File(cacheDir, "zip_exports")
            if (exportDir.exists()) {
                exportDir.deleteRecursively()
            }
            exportDir.mkdirs()

            val cleanPackageName = targetPackage.replace('.', '_')
            var zipFile = File(exportDir, "${cleanPackageName}_backup.zip")
            
            var isZipSuccess = false
            
            // Try standard ZIP compression via shell first
            try {
                val process = try {
                    Runtime.getRuntime().exec("su -mm")
                } catch (e: Exception) {
                    Runtime.getRuntime().exec("su")
                }
                
                val os = java.io.DataOutputStream(process.outputStream)
                os.writeBytes("cd \"$rootPath\" && zip -r \"${zipFile.absolutePath}\" .\n")
                os.writeBytes("chmod 666 \"${zipFile.absolutePath}\"\n")
                os.writeBytes("exit\n")
                os.flush()
                
                val exitCode = process.waitFor()
                if (exitCode == 0 && zipFile.exists() && zipFile.length() > 0) {
                    isZipSuccess = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fallback to TAR compression if ZIP tool is missing on the device
            if (!isZipSuccess) {
                zipFile = File(exportDir, "${cleanPackageName}_backup.tar")
                try {
                    val process = try {
                        Runtime.getRuntime().exec("su -mm")
                    } catch (e: Exception) {
                        Runtime.getRuntime().exec("su")
                    }
                    
                    val os = java.io.DataOutputStream(process.outputStream)
                    os.writeBytes("cd \"$rootPath\" && tar -cf \"${zipFile.absolutePath}\" .\n")
                    os.writeBytes("chmod 666 \"${zipFile.absolutePath}\"\n")
                    os.writeBytes("exit\n")
                    os.flush()
                    
                    if (process.waitFor() == 0 && zipFile.exists() && zipFile.length() > 0) {
                        isZipSuccess = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            runOnUiThread {
                if (!isZipSuccess || !zipFile.exists()) {
                    Toast.makeText(this@FileBrowserActivity, "Failed to create compressed backup. Ensure root is configured.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }

                val backupUri = FileProvider.getUriForFile(this@FileBrowserActivity, "com.mopwn.app.provider", zipFile)
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_STREAM, backupUri)
                    type = if (zipFile.name.endsWith(".zip")) "application/zip" else "application/x-tar"
                }

                startActivity(Intent.createChooser(shareIntent, "Share Target Root Dump"))
            }
        }.start()
    }

    private fun cleanupTempFiles() {
        try {
            val exports = File(cacheDir, "zip_exports")
            if (exports.exists()) {
                exports.deleteRecursively()
            }
            val shares = File(cacheDir, "selected_shares")
            if (shares.exists()) {
                shares.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class FileAdapter(
    var files: List<FileItem>,
    var isSelectionMode: Boolean = false,
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvIcon)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileMeta: TextView = view.findViewById(R.id.tvFileMeta)
        val cbSelected: CheckBox = view.findViewById(R.id.cbSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        holder.tvIcon.text = if (file.isDir) "📁" else "📄"
        holder.tvFileName.text = file.name
        holder.tvFileMeta.text = file.meta
        
        // Hide CheckBox completely for directories
        if (file.isDir) {
            holder.cbSelected.visibility = View.GONE
        } else {
            // In selection mode show CheckBoxes, otherwise hide them
            holder.cbSelected.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            
            holder.cbSelected.setOnCheckedChangeListener(null)
            holder.cbSelected.isChecked = file.isSelected
            
            holder.cbSelected.setOnCheckedChangeListener { _, isChecked ->
                file.isSelected = isChecked
                onSelectionChanged()
            }
        }

        holder.itemView.setOnClickListener { 
            onClick(file)
        }

        holder.itemView.setOnLongClickListener {
            onLongClick(file)
            true
        }
    }

    override fun getItemCount() = files.size

    fun updateData(newFiles: List<FileItem>) {
        files = newFiles
        notifyDataSetChanged()
    }
}
