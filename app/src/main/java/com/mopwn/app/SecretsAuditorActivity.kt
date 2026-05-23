package com.mopwn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

class SecretsAuditorActivity : AppCompatActivity() {

    private lateinit var tvAuditorSubtitle: TextView
    private lateinit var layoutLoading: LinearLayout
    private lateinit var scrollResults: View
    private lateinit var layoutCategories: LinearLayout
    private lateinit var etSearch: EditText

    private var targetPackage: String = ""

    // Structured storage for static analysis results
    private val rootFindings = ArrayList<Finding>()
    private val secretsFindings = ArrayList<Finding>()
    private val apiFindings = ArrayList<Finding>()

    data class Finding(
        val header: String,
        val value: String,
        val className: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secrets_auditor)

        targetPackage = intent.getStringExtra("PACKAGE_NAME") ?: "Unknown"

        tvAuditorSubtitle = findViewById(R.id.tvAuditorSubtitle)
        layoutLoading = findViewById(R.id.layoutLoading)
        scrollResults = findViewById(R.id.scrollResults)
        layoutCategories = findViewById(R.id.layoutCategories)
        etSearch = findViewById(R.id.etSearch)

        etSearch.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#1E1E1E"))
            cornerRadius = 8.dp.toFloat()
            setStroke(1, Color.parseColor("#333333"))
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateUi(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        tvAuditorSubtitle.text = "Target: $targetPackage"

        // Execute DEX extraction and static analysis in background
        Thread {
            performDexAudit()
        }.start()
    }

    private fun performDexAudit() {
        try {
            val appInfo = packageManager.getApplicationInfo(targetPackage, 0)
            val baseApkPath = appInfo.sourceDir
            val sourceFile = File(baseApkPath)

            var apkToUse = sourceFile.absolutePath

            val readable = try {
                val stream = FileInputStream(sourceFile)
                stream.close()
                true
            } catch (e: Exception) {
                false
            }

            if (!readable) {
                val tempApk = File(cacheDir, "temp_decompile.apk")
                if (tempApk.exists()) {
                    tempApk.delete()
                }

                val process = try {
                    Runtime.getRuntime().exec("su -mm")
                } catch (e: Exception) {
                    Runtime.getRuntime().exec("su")
                }

                val os = DataOutputStream(process.outputStream)
                os.writeBytes("cp \"$baseApkPath\" \"${tempApk.absolutePath}\"\n")
                os.writeBytes("chmod 666 \"${tempApk.absolutePath}\"\n")
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()

                if (tempApk.exists() && tempApk.length() > 0) {
                    apkToUse = tempApk.absolutePath
                }
            }

            // 2. Open APK as Zip stream and parse all classes*.dex files
            val apkFile = File(apkToUse)
            if (!apkFile.exists() || apkFile.length() == 0L) {
                throw Exception("Unable to access application binary APK.")
            }

            // Initialize DecompilerEngine so that single-class decompilation calls from this activity work immediately!
            DecompilerEngine.init(apkToUse, cacheDir)

            val zipStream = ZipInputStream(BufferedInputStream(FileInputStream(apkFile)))
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                    val bytes = zipStream.readBytes()
                    if (bytes.size > 112) { // Minimally valid DEX file size
                        parseDexFile(bytes)
                    }
                }
                entry = zipStream.nextEntry
            }
            zipStream.close()

            // 3. Render findings to the UI
            runOnUiThread {
                layoutLoading.visibility = View.GONE
                etSearch.visibility = View.VISIBLE
                scrollResults.visibility = View.VISIBLE
                populateUi()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                layoutLoading.visibility = View.GONE
                Toast.makeText(this, "Audit Error: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun parseDexFile(bytes: ByteArray) {
        val magic = String(bytes.copyOfRange(0, 4), Charsets.US_ASCII)
        if (magic != "dex\n") return

        // CORRECT CRITICAL DEX OFFSETS:
        // map_off is at offset 52.
        // string_ids_size starts at 56!
        // string_ids_off starts at 60!
        val stringIdsSize = readInt(bytes, 56)
        val stringIdsOff = readInt(bytes, 60)
        
        val typeIdsSize = readInt(bytes, 64)
        val typeIdsOff = readInt(bytes, 68)
        val methodIdsSize = readInt(bytes, 88)
        val methodIdsOff = readInt(bytes, 92)
        val classDefsSize = readInt(bytes, 96)
        val classDefsOff = readInt(bytes, 100)

        // 1. Extract all string literals
        val strings = ArrayList<String>(stringIdsSize)
        for (i in 0 until stringIdsSize) {
            val stringOff = readInt(bytes, stringIdsOff + i * 4)
            var cursor = stringOff
            
            // Read ULEB128 string length prefix
            var len = 0
            var shift = 0
            while (cursor < bytes.size) {
                val b = bytes[cursor++].toInt() and 0xFF
                len = len or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            
            val start = cursor
            while (cursor < bytes.size && bytes[cursor].toInt() != 0) {
                cursor++
            }
            
            val strBytes = bytes.copyOfRange(start, cursor)
            strings.add(String(strBytes, Charsets.UTF_8))
        }

        // Helper functions
        fun getTypeName(typeIdx: Int): String {
            if (typeIdx in 0 until typeIdsSize) {
                val stringIdx = readInt(bytes, typeIdsOff + typeIdx * 4)
                if (stringIdx in 0 until strings.size) {
                    return strings[stringIdx]
                }
            }
            return ""
        }

        fun getMethodName(methodIdx: Int): String {
            if (methodIdx in 0 until methodIdsSize) {
                val offset = methodIdsOff + methodIdx * 8
                val classIdx = readUShort(bytes, offset)
                val nameIdx = readInt(bytes, offset + 4)
                val classNameStr = getTypeName(classIdx)
                val methodNameStr = if (nameIdx in 0 until strings.size) strings[nameIdx] else ""
                return "$classNameStr->$methodNameStr"
            }
            return ""
        }

        // 2. Parse Class definitions and scan their active instructions
        for (c in 0 until classDefsSize) {
            val classDefOffset = classDefsOff + c * 32
            val classIdx = readInt(bytes, classDefOffset)
            val classDataOff = readInt(bytes, classDefOffset + 24)

            if (classDataOff == 0) continue

            val classTypeDescriptor = getTypeName(classIdx)
            if (classTypeDescriptor.isEmpty()) continue

            // Convert JNI type descriptor to standard package notation (e.g. Lcom/app/Main; -> com.app.Main)
            val className = classTypeDescriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

            // Filter out system and framework classes to keep results highly relevant
            if (className.startsWith("android.") || className.startsWith("androidx.") ||
                className.startsWith("java.") || className.startsWith("kotlin.") ||
                className.startsWith("com.google.android.") || className.startsWith("org.jetbrains.")
            ) {
                continue
            }

            // Parse class data item
            var cursor = classDataOff
            val staticFieldsInfo = readUleb128(bytes, cursor)
            val staticFieldsSize = staticFieldsInfo.first
            cursor = staticFieldsInfo.second

            val instanceFieldsInfo = readUleb128(bytes, cursor)
            val instanceFieldsSize = instanceFieldsInfo.first
            cursor = instanceFieldsInfo.second

            val directMethodsInfo = readUleb128(bytes, cursor)
            val directMethodsSize = directMethodsInfo.first
            cursor = directMethodsInfo.second

            val virtualMethodsInfo = readUleb128(bytes, cursor)
            val virtualMethodsSize = virtualMethodsInfo.first
            cursor = virtualMethodsInfo.second

            // Skip fields to reach method structures
            for (f in 0 until staticFieldsSize) {
                cursor = readUleb128(bytes, cursor).second // field_idx_diff
                cursor = readUleb128(bytes, cursor).second // access_flags
            }
            for (f in 0 until instanceFieldsSize) {
                cursor = readUleb128(bytes, cursor).second
                cursor = readUleb128(bytes, cursor).second
            }

            // Parse direct and virtual methods to extract instruction offsets
            val codeOffsets = ArrayList<Int>()
            var mIdx = 0
            for (m in 0 until directMethodsSize) {
                val diffInfo = readUleb128(bytes, cursor)
                mIdx += diffInfo.first
                cursor = diffInfo.second

                cursor = readUleb128(bytes, cursor).second // access_flags

                val codeOffInfo = readUleb128(bytes, cursor)
                val codeOff = codeOffInfo.first
                cursor = codeOffInfo.second

                if (codeOff != 0) {
                    codeOffsets.add(codeOff)
                }
            }

            var vmIdx = 0
            for (m in 0 until virtualMethodsSize) {
                val diffInfo = readUleb128(bytes, cursor)
                vmIdx += diffInfo.first
                cursor = diffInfo.second

                cursor = readUleb128(bytes, cursor).second // access_flags

                val codeOffInfo = readUleb128(bytes, cursor)
                val codeOff = codeOffInfo.first
                cursor = codeOffInfo.second

                if (codeOff != 0) {
                    codeOffsets.add(codeOff)
                }
            }

            // Scan instructions inside each code item
            for (codeOff in codeOffsets) {
                val insnsSize = readInt(bytes, codeOff + 12)
                val insnsOff = codeOff + 16
                val insnsEnd = insnsOff + insnsSize * 2

                var i = insnsOff
                while (i < insnsEnd - 1) {
                    val opcode = bytes[i].toInt() and 0xFF

                    // const-string (opcode 0x1A): format is 1A AA BBBB. BBBB is string index
                    if (opcode == 0x1A) {
                        val strIdx = (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
                        if (strIdx in 0 until strings.size) {
                            evaluateStringFinding(strings[strIdx], className)
                        }
                        i += 4
                    }
                    // const-string/jumbo (opcode 0x1B): format is 1B AA BBBBBBBB
                    else if (opcode == 0x1B) {
                        val strIdx = (bytes[i + 2].toInt() and 0xFF) or
                                     ((bytes[i + 3].toInt() and 0xFF) shl 8) or
                                     ((bytes[i + 4].toInt() and 0xFF) shl 16) or
                                     ((bytes[i + 5].toInt() and 0xFF) shl 24)
                        if (strIdx in 0 until strings.size) {
                            evaluateStringFinding(strings[strIdx], className)
                        }
                        i += 6
                    }
                    // invoke-* (opcodes 0x6E to 0x72 and 0x74 to 0x78)
                    else if ((opcode in 0x6E..0x72) || (opcode in 0x74..0x78)) {
                        val methodIdx = (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
                        if (methodIdx in 0 until methodIdsSize) {
                            evaluateMethodFinding(getMethodName(methodIdx), className)
                        }
                        i += 6
                    }
                    else {
                        i += 2 // Jump to next 16-bit instruction unit
                    }
                }
            }
        }
    }

    private fun evaluateStringFinding(str: String, className: String) {
        val trimmed = str.trim()
        if (trimmed.isEmpty() || trimmed.length < 3) return

        // 1. Root & Security Controls Indicators (Precision root keywords and specific paths)
        val lower = trimmed.lowercase()
        if (lower.contains("isrooted") || lower.contains("checkroot") || lower.contains("rootbeer") ||
            lower.contains("supersu") || lower.contains("superuser") || lower.contains("busybox") ||
            lower.contains("daemonsu") || lower.contains("kinguser") || lower.contains("magisk") ||
            trimmed.contains("/system/app/Superuser.apk") || trimmed.contains("test-keys") ||
            lower.contains("/sbin/su") || lower.contains("/system/bin/su") || lower.contains("/system/xbin/su")
        ) {
            val cleanVal = if (trimmed.length > 100) trimmed.substring(0, 97) + "..." else trimmed
            addFinding(rootFindings, Finding("Root Indicator String", cleanVal, className))
            return
        }

        // 2. API Keys, Secrets & Sensitive configurations (Precision keywords with length > 8 to avoid resource name overlaps)
        if (trimmed.startsWith("AIzaSy") && trimmed.length >= 30) {
            addFinding(secretsFindings, Finding("Google API Key", trimmed, className))
        } else if (trimmed.startsWith("AKIA") && trimmed.length == 20) {
            addFinding(secretsFindings, Finding("AWS Access Key ID", trimmed, className))
        } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            // Filter out safe/common framework URLs
            if (!trimmed.contains("schemas.android.com") && !trimmed.contains("w3.org") &&
                !trimmed.contains("google.com/metadata") && trimmed.length > 10
            ) {
                val cleanUrl = if (trimmed.length > 80) trimmed.substring(0, 77) + "..." else trimmed
                addFinding(secretsFindings, Finding("Static URL Endpoint", cleanUrl, className))
            }
            // General high-entropy token indicators (looking for assignment variables)
            val lowerTrimmed = trimmed.lowercase()
            if (trimmed.length > 8 && (
                lowerTrimmed.contains("secret") || lowerTrimmed.contains("password") ||
                lowerTrimmed.contains("private_key") || lowerTrimmed.contains("encryption_key") ||
                lowerTrimmed.contains("database_url") || lowerTrimmed.contains("api_token") ||
                lowerTrimmed.contains("api_key") || lowerTrimmed.contains("api_keys") ||
                lowerTrimmed.contains("client_secret") || lowerTrimmed.contains("secret_key") ||
                lowerTrimmed.contains("api_secret") || lowerTrimmed.contains("auth_token") ||
                lowerTrimmed.contains("jwt") || lowerTrimmed.contains("bearer")
            )) {
                val cleanVal = if (trimmed.length > 80) trimmed.substring(0, 77) + "..." else trimmed
                addFinding(secretsFindings, Finding("Potential Credential / Config String", cleanVal, className))
            }
        }
    }

    private fun evaluateMethodFinding(methodSignature: String, className: String) {
        if (methodSignature.isEmpty()) return

        // 1. Core Cryptographic & Execution APIs
        if (methodSignature.contains("Cipher;->getInstance")) {
            addFinding(apiFindings, Finding("Cryptographic Init", "Cipher.getInstance()", className))
        } else if (methodSignature.contains("SecretKeySpec;-><init>") || methodSignature.contains("KeyStore;->load") || methodSignature.contains("Mac;->getInstance")) {
            addFinding(apiFindings, Finding("Key Management / Mac Init", methodSignature.substringAfterLast("->"), className))
        } else if (methodSignature.contains("Runtime;->exec") || methodSignature.contains("ProcessBuilder;->start")) {
            addFinding(apiFindings, Finding("Command Execution", methodSignature.substringAfterLast("->"), className))
        } else if (methodSignature.contains("System;->loadLibrary") || methodSignature.contains("Runtime;->loadLibrary")) {
            addFinding(apiFindings, Finding("Native Library Load", methodSignature.substringAfterLast("->"), className))
        }
        
        // 2. WebView Security & Navigation Configuration
        else if (methodSignature.contains("WebView;->addJavascriptInterface")) {
            addFinding(apiFindings, Finding("WebView RCE Vector", "addJavascriptInterface()", className))
        } else if (methodSignature.contains("WebView;->setWebContentsDebuggingEnabled") || methodSignature.contains("WebSettings;->setAllowFileAccess")) {
            addFinding(apiFindings, Finding("Insecure WebView Config", methodSignature.substringAfterLast("->"), className))
        } else if (methodSignature.contains("WebView;->loadUrl") || methodSignature.contains("WebView;->postUrl")) {
            addFinding(apiFindings, Finding("WebView Navigation", methodSignature.substringAfterLast("->"), className))
        }
        
        // 3. SSL Security & Web Connections (Precision X509 signatures prevent false matches)
        else if (methodSignature.contains("HostnameVerifier;->verify") || methodSignature.contains("SSLSocketFactory;->createSocket") ||
                 methodSignature.contains("X509TrustManager;->checkServerTrusted") || methodSignature.contains("X509TrustManager;->checkClientTrusted")) {
            addFinding(apiFindings, Finding("Custom Network Security (SSL)", methodSignature.substringAfterLast("->"), className))
        } else if (methodSignature.contains("URL;->openConnection") || methodSignature.contains("URL;->openStream") || 
                 methodSignature.contains("HttpURLConnection;->connect") || methodSignature.contains("OkHttpClient;->newCall")) {
            addFinding(apiFindings, Finding("Network Connection Trigger", methodSignature.substringAfterLast("->"), className))
        }
        
        // 4. Root Checks Method References
        else if (methodSignature.contains("RootBeer;->") || methodSignature.lowercase().contains("isrooted")) {
            addFinding(rootFindings, Finding("Root Check Method Reference", methodSignature.substringAfterLast("->"), className))
        }
    }

    @Synchronized
    private fun addFinding(list: ArrayList<Finding>, finding: Finding) {
        // Prevent duplicate logs for the same string/class combo
        if (!list.any { it.value == finding.value && it.className == finding.className }) {
            list.add(finding)
        }
    }

    private fun populateUi(query: String = "") {
        layoutCategories.removeAllViews()

        val filteredRoot = if (query.isEmpty()) rootFindings else {
            rootFindings.filter { it.header.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
        }
        val filteredSecrets = if (query.isEmpty()) secretsFindings else {
            secretsFindings.filter { it.header.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
        }
        val filteredApi = if (query.isEmpty()) apiFindings else {
            apiFindings.filter { it.header.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
        }

        // 1. Root & Security Controls
        createCategorySection("ROOT DETECTION & SECURITY CONTROLS", "#FF5555", filteredRoot)

        // 2. API Keys & Secrets
        createCategorySection("API KEYS & SECRETS", "#FF9800", filteredSecrets)

        // 3. Sensitive APIs
        createCategorySection("SENSITIVE API METHOD CALLS", "#2196F3", filteredApi)
    }

    private fun createCategorySection(title: String, colorHex: String, list: List<Finding>) {
        val context = this

        // Section Title view
        val tvSection = TextView(this).apply {
            text = "$title (${list.size})"
            setTextColor(Color.parseColor(colorHex))
            textSize = 14.sp
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 24.dp, 0, 8.dp)
        }
        layoutCategories.addView(tvSection)

        if (list.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No indicators found in this category."
                setTextColor(Color.parseColor("#777777"))
                textSize = 14.sp
                setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            }
            layoutCategories.addView(tvEmpty)
            return
        }

        // Render card container
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getCardBackgroundDrawable()
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }

        for (finding in list) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                isClickable = true
                isFocusable = true
                
                // Premium ripple background selection feedback
                val outValue = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }

            // Click listener: navigates directly to decompiler for target parent class and auto-highlights the finding string
            itemLayout.setOnClickListener {
                val intent = Intent(context, SourceViewerActivity::class.java)
                intent.putExtra("CLASS_FULL_NAME", finding.className)
                // Pass the raw value to search for
                intent.putExtra("SEARCH_QUERY", finding.value)
                startActivity(intent)
            }

            // Long click listener: copies key-value mapping to clipboard
            itemLayout.setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val textToCopy = "${finding.header}: ${finding.value}"
                val clip = ClipData.newPlainText("Dex Auditor Finding", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Copied finding to clipboard!", Toast.LENGTH_SHORT).show()
                true
            }

            // ROW 1: Heading Badge (Indicator Type)
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvBadge = TextView(this).apply {
                text = finding.header.uppercase()
                setTextColor(Color.parseColor(colorHex))
                textSize = 9.sp
                typeface = Typeface.DEFAULT_BOLD
                background = getBadgeDrawable(colorHex)
                setPadding(6.dp, 2.dp, 6.dp, 2.dp)
            }
            headerRow.addView(tvBadge)

            itemLayout.addView(headerRow)

            // ROW 2: Finding Value (in monospaced/code style)
            val tvValue = TextView(this).apply {
                text = finding.value
                setTextColor(Color.parseColor("#A3E2A3")) // soft developer-green code color
                textSize = 13.sp
                typeface = Typeface.MONOSPACE
                setPadding(0, 6.dp, 0, 0)
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 3
            }
            itemLayout.addView(tvValue)

            // ROW 3: Full Package Path
            val tvPackage = TextView(this).apply {
                text = finding.className
                setTextColor(Color.parseColor("#666666"))
                textSize = 10.sp
                setPadding(0, 4.dp, 0, 0)
            }
            itemLayout.addView(tvPackage)

            cardLayout.addView(itemLayout)

            // Divider view between list items
            if (finding != list.last()) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp).apply {
                        setMargins(12.dp, 0, 12.dp, 0)
                    }
                    setBackgroundColor(Color.parseColor("#292929"))
                }
                cardLayout.addView(divider)
            }
        }

        layoutCategories.addView(cardLayout)
    }

    private fun getBadgeDrawable(colorHex: String): android.graphics.drawable.Drawable {
        val baseColor = colorHex.removePrefix("#")
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            // Subtle tinted background with ~10% opacity
            setColor(Color.parseColor("#15$baseColor"))
            cornerRadius = 4.dp.toFloat()
            // 50% opacity matching border
            setStroke(1.dp, Color.parseColor("#80$baseColor"))
        }
    }

    private fun getCardBackgroundDrawable(): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#1E1E1E"))
            cornerRadius = 8.dp.toFloat()
            setStroke(1.dp, Color.parseColor("#333333"))
        }
        return shape
    }

    // Heuristics binary readers
    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readUShort(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUleb128(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        var cursor = offset
        var result = 0
        var shift = 0
        while (cursor < bytes.size) {
            val b = bytes[cursor++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return Pair(result, cursor)
    }

}

// Helper dimension properties
private val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

private val Int.sp: Float
    get() = this.toFloat()
