package com.mopwn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class FileViewerActivity : AppCompatActivity() {

    private lateinit var tvFileTitle: TextView
    private lateinit var tvFileSubtitle: TextView
    private lateinit var tvFileContent: TextView
    private lateinit var btnCopyText: Button

    private lateinit var etFileSearch: EditText
    private lateinit var tvSearchCount: TextView
    private lateinit var btnPrevMatch: ImageButton
    private lateinit var btnNextMatch: ImageButton
    private lateinit var scrollViewFile: ScrollView

    private var fileContent: String? = null
    private val matchIndices = ArrayList<Pair<Int, Int>>()
    private var currentMatchIndex = -1

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    
    private var filePath = ""
    private var fileName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_viewer)

        filePath = intent.getStringExtra("FILE_PATH") ?: return
        fileName = intent.getStringExtra("FILE_NAME") ?: filePath.substringAfterLast('/')

        tvFileTitle = findViewById(R.id.tvFileTitle)
        tvFileSubtitle = findViewById(R.id.tvFileSubtitle)
        tvFileContent = findViewById(R.id.tvFileContent)
        btnCopyText = findViewById(R.id.btnCopyText)

        etFileSearch = findViewById(R.id.etFileSearch)
        tvSearchCount = findViewById(R.id.tvSearchCount)
        btnPrevMatch = findViewById(R.id.btnPrevMatch)
        btnNextMatch = findViewById(R.id.btnNextMatch)
        scrollViewFile = findViewById(R.id.scrollViewFile)

        tvFileTitle.text = fileName
        tvFileSubtitle.text = filePath

        btnCopyText.setOnClickListener {
            val content = fileContent
            if (content != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("File Content", content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "File content copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Reading file in progress...", Toast.LENGTH_SHORT).show()
            }
        }

        etFileSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    performSearch(s?.toString() ?: "")
                }
                searchHandler.postDelayed(searchRunnable!!, 250)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnPrevMatch.setOnClickListener {
            if (matchIndices.isNotEmpty()) {
                currentMatchIndex = (currentMatchIndex - 1 + matchIndices.size) % matchIndices.size
                highlightMatches()
            }
        }

        btnNextMatch.setOnClickListener {
            if (matchIndices.isNotEmpty()) {
                currentMatchIndex = (currentMatchIndex + 1) % matchIndices.size
                highlightMatches()
            }
        }

        loadFile()
    }

    private fun loadFile() {
        tvFileContent.text = "Reading file via root..."
        Thread {
            try {
                val process = try {
                    Runtime.getRuntime().exec("su -mm")
                } catch (e: Exception) {
                    Runtime.getRuntime().exec("su")
                }

                val os = java.io.DataOutputStream(process.outputStream)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                os.writeBytes("cat \"$filePath\"\n")
                os.writeBytes("exit\n")
                os.flush()

                val builder = StringBuilder()
                val buffer = CharArray(1024)
                var bytesRead: Int
                var containsBinary = false

                while (reader.read(buffer).also { bytesRead = it } != -1) {
                    // Check first block for binary null character
                    for (i in 0 until bytesRead) {
                        if (buffer[i] == '\u0000') {
                            containsBinary = true
                        }
                    }
                    builder.append(buffer, 0, bytesRead)
                    // Cap viewing size at 500KB to prevent UI lag on huge files
                    if (builder.length > 500 * 1024) {
                        builder.append("\n\n[FILE TRUNCATED: Content exceeds 500KB limit for preview]")
                        break
                    }
                }

                process.waitFor()
                val rawContent = builder.toString()

                runOnUiThread {
                    fileContent = rawContent
                    if (containsBinary) {
                        tvFileContent.setTextColor(Color.parseColor("#FF5555"))
                        tvFileContent.text = "[BINARY ALERT]\nThis file contains binary/non-text data (e.g. database, image, compressed archive, library).\n\nAttempted to load first characters:\n\n$rawContent"
                    } else if (rawContent.isEmpty()) {
                        tvFileContent.text = "[EMPTY]\nFile is empty."
                    } else {
                        tvFileContent.text = rawContent
                    }
                    performSearch(etFileSearch.text.toString())
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvFileContent.setTextColor(Color.parseColor("#FF5555"))
                    tvFileContent.text = "ERROR READING FILE:\n${e.message}"
                }
            }
        }.start()
    }

    private fun performSearch(query: String) {
        matchIndices.clear()
        currentMatchIndex = -1
        val content = fileContent
        
        if (content != null && query.length >= 2) {
            var index = content.indexOf(query, ignoreCase = true)
            while (index >= 0) {
                matchIndices.add(Pair(index, index + query.length))
                index = content.indexOf(query, index + query.length, ignoreCase = true)
            }
        }
        
        highlightMatches()
    }

    private fun highlightMatches() {
        val content = fileContent ?: return
        val spannable = SpannableString(content)
        
        if (matchIndices.isNotEmpty()) {
            if (currentMatchIndex == -1) {
                currentMatchIndex = 0
            }
            
            val highlightLimit = minOf(matchIndices.size, 100)
            
            for (i in 0 until highlightLimit) {
                val range = matchIndices[i]
                val color = if (i == currentMatchIndex) {
                    Color.parseColor("#FFFF00") // Bright Yellow
                } else {
                    Color.parseColor("#66FFA500") // Transparent Orange
                }
                spannable.setSpan(
                    BackgroundColorSpan(color),
                    range.first,
                    range.second,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            
            tvSearchCount.text = "${currentMatchIndex + 1}/${matchIndices.size}"
            scrollToCurrentMatch()
        } else {
            tvSearchCount.text = "0/0"
        }
        
        tvFileContent.text = spannable
    }

    private fun scrollToCurrentMatch() {
        if (currentMatchIndex !in matchIndices.indices) return
        val startCharIndex = matchIndices[currentMatchIndex].first
        val layout = tvFileContent.layout ?: return
        val line = layout.getLineForOffset(startCharIndex)
        val y = layout.getLineTop(line)
        scrollViewFile.post {
            scrollViewFile.scrollTo(0, y)
        }
    }
}
