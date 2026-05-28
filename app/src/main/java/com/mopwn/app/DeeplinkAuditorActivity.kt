package com.mopwn.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.xmlpull.v1.XmlPullParser
import android.content.res.XmlResourceParser

class DeeplinkAuditorActivity : AppCompatActivity() {

    private lateinit var packageName: String
    
    // Split lists for categorization
    private val vulnerableList = ArrayList<DeeplinkInfo>()
    private val customList = ArrayList<DeeplinkInfo>()
    private val verifiedList = ArrayList<DeeplinkInfo>()

    // Expansion flags (default: vulnerable expanded, others collapsed for clarity)
    private var isHijackableExpanded = true
    private var isCustomExpanded = false
    private var isVerifiedExpanded = false

    private lateinit var etDeeplinkUri: EditText
    private lateinit var spAction: Spinner
    private lateinit var tvConsoleLog: TextView
    private lateinit var rvDeeplinks: RecyclerView
    private lateinit var tvNoDeeplinks: TextView
    private lateinit var adapter: DeeplinkAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deeplink_auditor)

        packageName = intent.getStringExtra("PACKAGE_NAME") ?: return

        findViewById<TextView>(R.id.tvPackageName).text = packageName

        etDeeplinkUri = findViewById(R.id.etDeeplinkUri)
        spAction = findViewById(R.id.spAction)
        tvConsoleLog = findViewById(R.id.tvConsoleLog)
        rvDeeplinks = findViewById(R.id.rvDeeplinks)
        tvNoDeeplinks = findViewById(R.id.tvNoDeeplinks)

        // Setup Action Spinner
        val actions = arrayOf(
            Intent.ACTION_VIEW,
            Intent.ACTION_SEND,
            Intent.ACTION_EDIT
        )
        val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actions)
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spAction.adapter = arrayAdapter

        // Run deep links extraction
        loadDeeplinks()

        // Setup RecyclerView with the expandable Adapter
        rvDeeplinks.layoutManager = LinearLayoutManager(this)
        adapter = DeeplinkAdapter(
            onHeaderClick = { category ->
                when (category) {
                    CategoryType.HIJACKABLE -> isHijackableExpanded = !isHijackableExpanded
                    CategoryType.CUSTOM -> isCustomExpanded = !isCustomExpanded
                    CategoryType.VERIFIED -> isVerifiedExpanded = !isVerifiedExpanded
                }
                rebuildAdapterItems()
            },
            onDeeplinkClick = { info ->
                val fullUrl = buildSampleUri(info)
                etDeeplinkUri.setText(fullUrl)
                tvConsoleLog.text = "Template loaded:\n$fullUrl\n\nTarget Activity:\n${info.targetActivity}\n\nSecurity Status:\n${getSecurityStatusDescription(info)}"
            }
        )
        rvDeeplinks.adapter = adapter

        rebuildAdapterItems()

        if (vulnerableList.isEmpty() && customList.isEmpty() && verifiedList.isEmpty()) {
            tvNoDeeplinks.visibility = View.VISIBLE
            rvDeeplinks.visibility = View.GONE
        }

        // Setup launch button
        findViewById<Button>(R.id.btnLaunchIntent).setOnClickListener {
            val uriStr = etDeeplinkUri.text.toString().trim()
            if (uriStr.isEmpty()) {
                Toast.makeText(this, "Please input a valid URI string to test", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedAction = spAction.selectedItem as String
            tvConsoleLog.text = "Constructing Intent:\nAction: $selectedAction\nData: $uriStr\nTarget Package: $packageName\n\nLaunching..."

            try {
                val intent = Intent(selectedAction).apply {
                    data = Uri.parse(uriStr)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                tvConsoleLog.append("\n\n🟢 Intent sent successfully to $packageName!")
            } catch (e: ActivityNotFoundException) {
                tvConsoleLog.append("\n\n🔴 Error: No activity found in $packageName that matches this Action and URI scheme pattern.")
            } catch (e: Exception) {
                tvConsoleLog.append("\n\n🔴 System Error: ${e.message}")
            }
        }

        // Setup OOB Redirection Suite
        val etOobEndpoint = findViewById<EditText>(R.id.etOobEndpoint)
        val btnSimulateHijack = findViewById<Button>(R.id.btnSimulateHijack)
        val prefs = getSharedPreferences("MoPwnPrefs", MODE_PRIVATE)

        // Load active endpoint or pre-populate local C2
        val savedEndpoint = prefs.getString("oob_logger_endpoint", "http://127.0.0.1:1337/")
        etOobEndpoint.setText(savedEndpoint)

        // Save changes dynamically as the user types
        etOobEndpoint.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.edit().putString("oob_logger_endpoint", s.toString().trim()).apply()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Simulate Hijack Button (Implicit Intent trigger for OS Chooser dialog)
        btnSimulateHijack.setOnClickListener {
            val uriStr = etDeeplinkUri.text.toString().trim()
            if (uriStr.isEmpty()) {
                Toast.makeText(this, "Please input a valid URI string to test hijacking", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvConsoleLog.text = "🔥 [Hijack Simulator Triggered]\nLaunching IMPLICIT Intent (Package unset)\nData URI: $uriStr\n\nAndroid OS will now display the chooser dialog. Choose 'MoPWN Hijack Simulator' to intercept and exfiltrate the tokens OOB."

            try {
                // Remove setPackage to make intent implicit, forcing OS chooser list to pop up
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(uriStr)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                tvConsoleLog.append("\n\n🟢 Implicit intent launched! Check Android system chooser dialog.")
            } catch (e: ActivityNotFoundException) {
                tvConsoleLog.append("\n\n🔴 Error: No browser, simulator, or target application handles this URI pattern.")
            } catch (e: Exception) {
                tvConsoleLog.append("\n\n🔴 System Error: ${e.message}")
            }
        }
    }

    private fun loadDeeplinks() {
        try {
            val targetContext = createPackageContext(packageName, CONTEXT_IGNORE_SECURITY)
            val parser = targetContext.assets.openXmlResourceParser("AndroidManifest.xml")
            var eventType = parser.eventType

            var currentActivity: String? = null
            var insideActivity = false
            var insideIntentFilter = false
            var hasViewAction = false
            var hasDefaultCategory = false
            var hasBrowsableCategory = false
            var autoVerify = false

            val currentSchemes = ArrayList<String>()
            val currentHosts = ArrayList<String>()
            val currentPaths = ArrayList<String>()

            fun getAttr(parser: XmlResourceParser, attrName: String): String? {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == attrName) {
                        return parser.getAttributeValue(i)
                    }
                }
                return null
            }

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName == "activity" || tagName == "activity-alias") {
                            currentActivity = getAttr(parser, "name")
                            insideActivity = true
                        } else if (tagName == "intent-filter" && insideActivity) {
                            insideIntentFilter = true
                            hasViewAction = false
                            hasDefaultCategory = false
                            hasBrowsableCategory = false
                            
                            val av = getAttr(parser, "autoVerify")
                            autoVerify = av == "true"
                            
                            currentSchemes.clear()
                            currentHosts.clear()
                            currentPaths.clear()
                        } else if (tagName == "action" && insideIntentFilter) {
                            val actionName = getAttr(parser, "name")
                            if (actionName == "android.intent.action.VIEW") {
                                hasViewAction = true
                            }
                        } else if (tagName == "category" && insideIntentFilter) {
                            val catName = getAttr(parser, "name")
                            if (catName == "android.intent.category.DEFAULT") {
                                hasDefaultCategory = true
                            } else if (catName == "android.intent.category.BROWSABLE") {
                                hasBrowsableCategory = true
                            }
                        } else if (tagName == "data" && insideIntentFilter) {
                            val scheme = getAttr(parser, "scheme")
                            val host = getAttr(parser, "host")
                            val path = getAttr(parser, "path")
                            val pathPrefix = getAttr(parser, "pathPrefix")
                            val pathPattern = getAttr(parser, "pathPattern")
                            
                            if (scheme != null) currentSchemes.add(scheme)
                            if (host != null) currentHosts.add(host)
                            val finalPath = path ?: pathPrefix ?: pathPattern
                            if (finalPath != null) currentPaths.add(finalPath)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName == "activity" || tagName == "activity-alias") {
                            currentActivity = null
                            insideActivity = false
                        } else if (tagName == "intent-filter" && insideIntentFilter) {
                            if (hasViewAction && (hasDefaultCategory || hasBrowsableCategory)) {
                                for (scheme in currentSchemes) {
                                    val isApp = scheme == "http" || scheme == "https"
                                    val info = DeeplinkInfo(
                                        scheme = scheme,
                                        host = "",
                                        path = "",
                                        targetActivity = currentActivity ?: "Unknown",
                                        isAppLink = isApp,
                                        autoVerify = autoVerify
                                    )
                                    
                                    if (currentHosts.isEmpty()) {
                                        sortDeeplink(info)
                                    } else {
                                        for (host in currentHosts) {
                                            val infoWithHost = info.copy(host = host)
                                            if (currentPaths.isEmpty()) {
                                                sortDeeplink(infoWithHost)
                                            } else {
                                                for (path in currentPaths) {
                                                    sortDeeplink(infoWithHost.copy(path = path))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            insideIntentFilter = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCollisionStatus(scheme: String, host: String): Pair<String, List<String>> {
        try {
            val testUriStr = if (scheme == "http" || scheme == "https") {
                if (host.isNotEmpty()) "$scheme://$host" else return Pair("NO COLLISION", emptyList())
            } else {
                "$scheme://poc-verification"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(testUriStr))
            val flags = PackageManager.MATCH_DEFAULT_ONLY or 
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) PackageManager.MATCH_UNINSTALLED_PACKAGES else 0
            val resolveInfos = packageManager.queryIntentActivities(intent, flags)
            
            val otherApps = ArrayList<String>()
            for (info in resolveInfos) {
                val pName = info.activityInfo.packageName
                if (pName != packageName && pName != getPackageName()) {
                    otherApps.add(pName)
                }
            }
            
            return if (otherApps.isNotEmpty()) {
                Pair("COLLISION ACTIVE", otherApps)
            } else {
                Pair("NO COLLISION", emptyList())
            }
        } catch (e: Exception) {
            return Pair("NO COLLISION", emptyList())
        }
    }

    private fun getDomainVerificationState(domain: String): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val manager = getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
                val userState = manager.getDomainVerificationUserState(packageName)
                if (userState != null) {
                    val hostStateMap = userState.hostToStateMap
                    val state = hostStateMap[domain]
                    if (state != null) {
                        return when (state) {
                            android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> "VERIFIED"
                            android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED -> "USER SELECTED"
                            android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE -> "UNVERIFIED"
                            else -> "UNKNOWN ($state)"
                        }
                    }
                }
            } catch (e: Exception) {
                return "UNVERIFIED (Query Err)"
            }
        }
        return "N/A (< Android 12)"
    }

    private fun enrichDeeplink(info: DeeplinkInfo): DeeplinkInfo {
        val (colStatus, colPkgs) = getCollisionStatus(info.scheme, info.host)
        val verifyStatus = if (info.isAppLink && info.host.isNotEmpty()) {
            getDomainVerificationState(info.host)
        } else {
            "N/A"
        }
        return info.copy(
            collisionStatus = colStatus,
            collidingPackages = colPkgs,
            systemVerifyStatus = verifyStatus
        )
    }

    private fun sortDeeplink(info: DeeplinkInfo) {
        val enriched = enrichDeeplink(info)
        if (enriched.isAppLink) {
            // If the developer declared autoVerify but OS verification is not VERIFIED, it's still vulnerable!
            if (enriched.autoVerify && enriched.systemVerifyStatus == "VERIFIED") {
                verifiedList.add(enriched)
            } else {
                vulnerableList.add(enriched)
            }
        } else {
            if (enriched.collisionStatus == "COLLISION ACTIVE") {
                customList.add(0, enriched) // Push active collisions to top
            } else {
                customList.add(enriched)
            }
        }
    }

    private fun rebuildAdapterItems() {
        val items = ArrayList<AdapterItem>()

        if (vulnerableList.isNotEmpty()) {
            items.add(AdapterItem.Header(CategoryType.HIJACKABLE, "Vulnerable Web Links (Hijackable)", vulnerableList.size, isHijackableExpanded))
            if (isHijackableExpanded) {
                for (info in vulnerableList) {
                    items.add(AdapterItem.Deeplink(info))
                }
            }
        }

        if (customList.isNotEmpty()) {
            items.add(AdapterItem.Header(CategoryType.CUSTOM, "Custom URL Schemes", customList.size, isCustomExpanded))
            if (isCustomExpanded) {
                for (info in customList) {
                    items.add(AdapterItem.Deeplink(info))
                }
            }
        }

        if (verifiedList.isNotEmpty()) {
            items.add(AdapterItem.Header(CategoryType.VERIFIED, "Secured App Links", verifiedList.size, isVerifiedExpanded))
            if (isVerifiedExpanded) {
                for (info in verifiedList) {
                    items.add(AdapterItem.Deeplink(info))
                }
            }
        }

        adapter.updateItems(items)
    }

    private fun buildSampleUri(info: DeeplinkInfo): String {
        val builder = java.lang.StringBuilder()
        builder.append(info.scheme).append("://")
        if (info.host.isNotEmpty()) {
            builder.append(info.host)
        } else {
            builder.append("sample-host")
        }
        if (info.path.isNotEmpty()) {
            if (!info.path.startsWith("/")) {
                builder.append("/")
            }
            builder.append(info.path)
        }
        return builder.toString()
    }

    private fun getSecurityStatusDescription(info: DeeplinkInfo): String {
        val sb = java.lang.StringBuilder()
        if (info.isAppLink) {
            sb.append("🌐 WEB LINK (APP LINK) AUDIT:\n")
            sb.append("• Manifest autoVerify: ").append(if (info.autoVerify) "autoVerify=true" else "autoVerify=false").append("\n")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && info.host.isNotEmpty()) {
                sb.append("• OS Verification State: ").append(info.systemVerifyStatus).append("\n")
            }
            
            if (info.autoVerify && info.systemVerifyStatus == "VERIFIED") {
                sb.append("\n🟢 STATUS: SECURED\nThe Android OS successfully verified this domain via digital assets. It cannot be hijacked.")
            } else {
                sb.append("\n🔴 STATUS: VULNERABLE TO HIJACK\n")
                sb.append("Domain is unverified. Any malicious app can register this host and intercept user traffic OOB.")
            }
        } else {
            sb.append("🔌 CUSTOM SCHEME AUDIT:\n")
            sb.append("• Protocol scheme: ").append(info.scheme).append("://\n")
            sb.append("• Behavior: Custom schemes lack centralized OS domain validation. If another app registers the same scheme, a routing collision occurs.")
        }

        if (info.collisionStatus == "COLLISION ACTIVE") {
            sb.append("\n\n🚨 COLLISION WARNING (HIGH RISK):\n")
            sb.append("Another application installed on this device is registered for this scheme/host and can intercept these links!\n")
            sb.append("Conflicting packages:\n")
            info.collidingPackages.forEach { pkg ->
                sb.append("  • ").append(pkg).append("\n")
            }
        } else {
            sb.append("\n\n🛡️ COLLISION posturing: No conflicting application handlers found on this device.")
        }

        return sb.toString()
    }

    // Domain types
    enum class CategoryType {
        HIJACKABLE,
        CUSTOM,
        VERIFIED
    }

    // RecyclerView wrapper items
    sealed class AdapterItem {
        data class Header(
            val type: CategoryType,
            val title: String,
            val count: Int,
            val isExpanded: Boolean
        ) : AdapterItem()

        data class Deeplink(
            val info: DeeplinkInfo
        ) : AdapterItem()
    }

    // Individual enriched deeplink info
    data class DeeplinkInfo(
        val scheme: String,
        val host: String,
        val path: String,
        val targetActivity: String,
        val isAppLink: Boolean,
        val autoVerify: Boolean,
        val collisionStatus: String = "UNKNOWN",
        val collidingPackages: List<String> = emptyList(),
        val systemVerifyStatus: String = "UNKNOWN"
    )

    private class DeeplinkAdapter(
        private val onHeaderClick: (CategoryType) -> Unit,
        private val onDeeplinkClick: (DeeplinkInfo) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = ArrayList<AdapterItem>()

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_DEEPLINK = 1
        }

        fun updateItems(newItems: List<AdapterItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is AdapterItem.Header -> TYPE_HEADER
                is AdapterItem.Deeplink -> TYPE_DEEPLINK
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                val view = inflater.inflate(R.layout.item_deeplink_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_deeplink, parent, false)
                DeeplinkViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is AdapterItem.Header -> {
                    val hHolder = holder as HeaderViewHolder
                    hHolder.tvHeaderTitle.text = item.title
                    hHolder.tvHeaderCount.text = item.count.toString()
                    hHolder.tvHeaderIndicator.text = if (item.isExpanded) "▼" else "▶"
                    hHolder.itemView.setOnClickListener {
                        onHeaderClick(item.type)
                    }
                }
                is AdapterItem.Deeplink -> {
                    val dHolder = holder as DeeplinkViewHolder
                    val info = item.info
                    
                    val pattern = java.lang.StringBuilder(info.scheme).append("://")
                    if (info.host.isNotEmpty()) {
                        pattern.append(info.host)
                    } else {
                        pattern.append("*")
                    }
                    if (info.path.isNotEmpty()) {
                        if (!info.path.startsWith("/")) {
                            pattern.append("/")
                        }
                        pattern.append(info.path)
                    }

                    dHolder.tvDeeplinkPattern.text = pattern.toString()
                    dHolder.tvTargetActivity.text = "Activity: " + info.targetActivity.substringAfterLast('.')

                    if (info.isAppLink) {
                        if (info.autoVerify && info.systemVerifyStatus == "VERIFIED") {
                            dHolder.tvBadge.text = "SECURED"
                            dHolder.tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                        } else {
                            dHolder.tvBadge.text = "HIJACKABLE"
                            dHolder.tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#F44336"))
                        }
                    } else {
                        if (info.collisionStatus == "COLLISION ACTIVE") {
                            dHolder.tvBadge.text = "COLLISION"
                            dHolder.tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#E53935"))
                        } else {
                            dHolder.tvBadge.text = "CUSTOM"
                            dHolder.tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#FFC107"))
                        }
                    }

                    dHolder.itemView.setOnClickListener {
                        onDeeplinkClick(info)
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvHeaderIndicator: TextView = view.findViewById(R.id.tvHeaderIndicator)
            val tvHeaderTitle: TextView = view.findViewById(R.id.tvHeaderTitle)
            val tvHeaderCount: TextView = view.findViewById(R.id.tvHeaderCount)
        }

        class DeeplinkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDeeplinkPattern: TextView = view.findViewById(R.id.tvDeeplinkPattern)
            val tvBadge: TextView = view.findViewById(R.id.tvBadge)
            val tvTargetActivity: TextView = view.findViewById(R.id.tvTargetActivity)
        }
    }
}
