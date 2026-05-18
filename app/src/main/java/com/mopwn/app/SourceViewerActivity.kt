package com.mopwn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class SourceViewerActivity : AppCompatActivity() {

    private lateinit var tvClassTitle: TextView
    private lateinit var tvClassSubtitle: TextView
    private lateinit var tvSourceCode: TextView
    private lateinit var btnCopyCode: Button

    private lateinit var etCodeSearch: EditText
    private lateinit var tvSearchCount: TextView
    private lateinit var btnPrevMatch: ImageButton
    private lateinit var btnNextMatch: ImageButton
    private lateinit var scrollViewCode: ScrollView

    private var decompiledCode: String? = null
    private val matchIndices = ArrayList<Pair<Int, Int>>()
    private var currentMatchIndex = -1

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_viewer)

        val classFullName = intent.getStringExtra("CLASS_FULL_NAME") ?: return

        tvClassTitle = findViewById(R.id.tvClassTitle)
        tvClassSubtitle = findViewById(R.id.tvClassSubtitle)
        tvSourceCode = findViewById(R.id.tvSourceCode)
        btnCopyCode = findViewById(R.id.btnCopyCode)

        etCodeSearch = findViewById(R.id.etCodeSearch)
        tvSearchCount = findViewById(R.id.tvSearchCount)
        btnPrevMatch = findViewById(R.id.btnPrevMatch)
        btnNextMatch = findViewById(R.id.btnNextMatch)
        scrollViewCode = findViewById(R.id.scrollViewCode)

        val simpleName = classFullName.substringAfterLast('.')
        tvClassTitle.text = "$simpleName.java"
        tvClassSubtitle.text = classFullName

        btnCopyCode.setOnClickListener {
            val code = decompiledCode
            if (code != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Source Code", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Source code copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Decompilation in progress...", Toast.LENGTH_SHORT).show()
            }
        }

        etCodeSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Debounce typing: wait 250ms after last keystroke before executing search
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

        // Run decompilation in background thread to keep UI interactive
        Thread {
            val result = DecompilerEngine.decompileClass(classFullName)
            decompiledCode = result
            
            runOnUiThread {
                tvSourceCode.text = result
                // If standard-then-fallback appended fallback info, update subtitle
                if (result.contains("Fallback Mode")) {
                    tvClassTitle.text = "$simpleName.java (Fallback Mode)"
                }
                // If user had already typed something in search, rerun search
                performSearch(etCodeSearch.text.toString())
            }
        }.start()
    }

    private fun performSearch(query: String) {
        matchIndices.clear()
        currentMatchIndex = -1
        val code = decompiledCode
        
        // Skip searching if query is too short (less than 2 characters)
        // This avoids freezing the UI on common letters like "e", "a", "i" etc.
        if (code != null && query.length >= 2) {
            var index = code.indexOf(query, ignoreCase = true)
            while (index >= 0) {
                matchIndices.add(Pair(index, index + query.length))
                index = code.indexOf(query, index + query.length, ignoreCase = true)
            }
        }
        
        highlightMatches()
    }

    private fun highlightMatches() {
        val code = decompiledCode ?: return
        val spannable = SpannableString(code)
        
        if (matchIndices.isNotEmpty()) {
            if (currentMatchIndex == -1) {
                currentMatchIndex = 0
            }
            
            // Limit highlighted spans to 100 to prevent Android TextView measurement/layout lag
            val highlightLimit = minOf(matchIndices.size, 100)
            
            for (i in 0 until highlightLimit) {
                val range = matchIndices[i]
                // Current focused match is highlighted in bright yellow, others in transparent orange
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
        
        tvSourceCode.text = spannable
    }

    private fun scrollToCurrentMatch() {
        if (currentMatchIndex !in matchIndices.indices) return
        val startCharIndex = matchIndices[currentMatchIndex].first
        val layout = tvSourceCode.layout ?: return
        val line = layout.getLineForOffset(startCharIndex)
        val y = layout.getLineTop(line)
        scrollViewCode.post {
            scrollViewCode.scrollTo(0, y)
        }
    }
}
