package com.mopwn.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
    private lateinit var btnDownload: Button
    private lateinit var btnSelectAll: Button
    private var currentPath = ""
    private var rootPath = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        rvFiles = findViewById(R.id.rvFiles)
        btnDownload = findViewById(R.id.btnDownload)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        
        rvFiles.layoutManager = LinearLayoutManager(this)

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: return
        rootPath = intent.getStringExtra("DATA_DIR") ?: "/data/data/$packageName"
        currentPath = rootPath

        adapter = FileAdapter(emptyList(), 
            onClick = { fileItem ->
                if (fileItem.isDir) {
                    loadDirectory(fileItem.fullPath)
                }
            },
            onSelectionChanged = {
                updateDownloadButton()
            }
        )
        rvFiles.adapter = adapter

        btnSelectAll.setOnClickListener {
            val allSelected = adapter.files.all { it.isSelected || it.isDir }
            adapter.files.forEach { if (!it.isDir) it.isSelected = !allSelected }
            adapter.notifyDataSetChanged()
            updateDownloadButton()
            btnSelectAll.text = if (allSelected) "Select All" else "Deselect All"
        }

        btnDownload.setOnClickListener {
            val selectedFiles = adapter.files.filter { it.isSelected && !it.isDir }
            if (selectedFiles.isEmpty()) {
                Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            downloadFiles(selectedFiles)
        }

        loadDirectory(currentPath)
    }

    private fun updateDownloadButton() {
        val count = adapter.files.count { it.isSelected && !it.isDir }
        btnDownload.text = "Download ($count)"
        btnDownload.isEnabled = count > 0
    }

    override fun onBackPressed() {
        if (currentPath != rootPath && currentPath.length > rootPath.length) {
            val parentPath = currentPath.substringBeforeLast("/")
            loadDirectory(if (parentPath.isEmpty()) "/" else parentPath)
        } else {
            super.onBackPressed()
        }
    }

    private fun loadDirectory(path: String) {
        Thread {
            try {
                // Try su -mm first for global mount namespace access
                var process = try {
                    Runtime.getRuntime().exec("su -mm")
                } catch (e: Exception) {
                    Runtime.getRuntime().exec("su")
                }
                
                val os = java.io.DataOutputStream(process.outputStream)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                
                // Verify root and list directory
                os.writeBytes("id\n")
                os.writeBytes("ls -la \"$path\"\n")
                os.writeBytes("exit\n")
                os.flush()

                val files = mutableListOf<FileItem>()
                var line: String?
                
                // First line should be output of 'id'
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
                        updateDownloadButton()
                        btnSelectAll.text = "Select All"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Root is needed for this action", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun downloadFiles(fileItems: List<FileItem>) {
        Toast.makeText(this, "Starting download of ${fileItems.size} files...", Toast.LENGTH_SHORT).show()
        Thread {
            var successCount = 0
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            
            fileItems.forEach { fileItem ->
                try {
                    val cacheFile = File(cacheDir, fileItem.name)
                    val process = try {
                        Runtime.getRuntime().exec("su -mm")
                    } catch (e: Exception) {
                        Runtime.getRuntime().exec("su")
                    }
                    
                    val os = java.io.DataOutputStream(process.outputStream)
                    os.writeBytes("cp \"${fileItem.fullPath}\" \"${cacheFile.absolutePath}\"\n")
                    os.writeBytes("chmod 666 \"${cacheFile.absolutePath}\"\n")
                    os.writeBytes("exit\n")
                    os.flush()
                    
                    if (process.waitFor() == 0 && cacheFile.exists()) {
                        val destFile = File(downloadsDir, fileItem.name)
                        cacheFile.copyTo(destFile, overwrite = true)
                        cacheFile.delete()
                        successCount++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            runOnUiThread {
                Toast.makeText(this@FileBrowserActivity, "Successfully downloaded $successCount/${fileItems.size} files to Downloads", Toast.LENGTH_LONG).show()
                // Clear selection after download
                adapter.files.forEach { it.isSelected = false }
                adapter.notifyDataSetChanged()
                updateDownloadButton()
            }
        }.start()
    }
}

class FileAdapter(
    var files: List<FileItem>,
    private val onClick: (FileItem) -> Unit,
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
        
        holder.cbSelected.visibility = if (file.isDir) View.GONE else View.VISIBLE
        holder.cbSelected.setOnCheckedChangeListener(null)
        holder.cbSelected.isChecked = file.isSelected
        
        holder.cbSelected.setOnCheckedChangeListener { _, isChecked ->
            file.isSelected = isChecked
            onSelectionChanged()
        }

        holder.itemView.setOnClickListener { 
            if (file.isDir) {
                onClick(file)
            } else {
                file.isSelected = !file.isSelected
                holder.cbSelected.isChecked = file.isSelected
            }
        }
    }

    override fun getItemCount() = files.size

    fun updateData(newFiles: List<FileItem>) {
        files = newFiles
        notifyDataSetChanged()
    }
}
