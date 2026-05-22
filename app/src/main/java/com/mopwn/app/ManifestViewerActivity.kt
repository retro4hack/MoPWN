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

class ManifestViewerActivity : AppCompatActivity() {

    private lateinit var tvManifestTitle: TextView
    private lateinit var tvManifestSubtitle: TextView
    private lateinit var tvManifestContent: TextView
    private lateinit var btnCopyManifest: Button

    private lateinit var etManifestSearch: EditText
    private lateinit var tvManifestSearchCount: TextView
    private lateinit var btnManifestPrevMatch: ImageButton
    private lateinit var btnManifestNextMatch: ImageButton
    private lateinit var scrollViewManifest: ScrollView

    private var manifestText: String? = null
    private val matchIndices = ArrayList<Pair<Int, Int>>()
    private var currentMatchIndex = -1

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manifest_viewer)

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: return

        tvManifestTitle = findViewById(R.id.tvManifestTitle)
        tvManifestSubtitle = findViewById(R.id.tvManifestSubtitle)
        tvManifestContent = findViewById(R.id.tvManifestContent)
        btnCopyManifest = findViewById(R.id.btnCopyManifest)

        etManifestSearch = findViewById(R.id.etManifestSearch)
        tvManifestSearchCount = findViewById(R.id.tvManifestSearchCount)
        btnManifestPrevMatch = findViewById(R.id.btnManifestPrevMatch)
        btnManifestNextMatch = findViewById(R.id.btnManifestNextMatch)
        scrollViewManifest = findViewById(R.id.scrollViewManifest)

        tvManifestTitle.text = "AndroidManifest.xml"
        tvManifestSubtitle.text = packageName

        btnCopyManifest.setOnClickListener {
            val xmlText = manifestText
            if (xmlText != null) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("AndroidManifest.xml", xmlText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Manifest copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Decoding in progress...", Toast.LENGTH_SHORT).show()
            }
        }

        etManifestSearch.addTextChangedListener(object : TextWatcher {
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

        btnManifestPrevMatch.setOnClickListener {
            if (matchIndices.isNotEmpty()) {
                currentMatchIndex = (currentMatchIndex - 1 + matchIndices.size) % matchIndices.size
                highlightMatches()
            }
        }

        btnManifestNextMatch.setOnClickListener {
            if (matchIndices.isNotEmpty()) {
                currentMatchIndex = (currentMatchIndex + 1) % matchIndices.size
                highlightMatches()
            }
        }

        // Run manifest decoding in background thread to avoid freezing UI
        Thread {
            val result = DecompilerEngine.decompileManifest(packageName, packageManager, cacheDir)
            manifestText = result
            
            runOnUiThread {
                tvManifestContent.text = result
                performSearch(etManifestSearch.text.toString())
            }
        }.start()
    }

    private fun performSearch(query: String) {
        matchIndices.clear()
        currentMatchIndex = -1
        val text = manifestText
        
        if (text != null && query.length >= 2) {
            var index = text.indexOf(query, ignoreCase = true)
            while (index >= 0) {
                matchIndices.add(Pair(index, index + query.length))
                index = text.indexOf(query, index + query.length, ignoreCase = true)
            }
        }
        
        highlightMatches()
    }

    private fun highlightMatches() {
        val text = manifestText ?: return
        val spannable = SpannableString(text)
        
        if (matchIndices.isNotEmpty()) {
            if (currentMatchIndex == -1) {
                currentMatchIndex = 0
            }
            
            val highlightLimit = minOf(matchIndices.size, 100)
            
            for (i in 0 until highlightLimit) {
                val range = matchIndices[i]
                val color = if (i == currentMatchIndex) {
                    Color.parseColor("#FFFF00") // Yellow
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
            
            tvManifestSearchCount.text = "${currentMatchIndex + 1}/${matchIndices.size}"
            scrollToCurrentMatch()
        } else {
            tvManifestSearchCount.text = "0/0"
        }
        
        tvManifestContent.text = spannable
    }

    private fun scrollToCurrentMatch() {
        if (currentMatchIndex !in matchIndices.indices) return
        val startCharIndex = matchIndices[currentMatchIndex].first
        val layout = tvManifestContent.layout ?: return
        val line = layout.getLineForOffset(startCharIndex)
        val y = layout.getLineTop(line)
        scrollViewManifest.post {
            scrollViewManifest.scrollTo(0, y)
        }
    }
}
