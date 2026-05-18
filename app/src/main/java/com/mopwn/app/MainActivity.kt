package com.mopwn.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: AppAdapter
    private var allApps = listOf<ApplicationInfo>()
    private var userApps = listOf<ApplicationInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        rvApps = findViewById(R.id.rvApps)
        
        rvApps.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter(emptyList(), packageManager, onClick = { appInfo ->
            val intent = Intent(this, InspectPackageActivity::class.java)
            intent.putExtra("PACKAGE_NAME", appInfo.packageName)
            startActivity(intent)
        }, onLongClick = { appInfo ->
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Package Name", appInfo.packageName)
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(this, "Copied: ${appInfo.packageName}", android.widget.Toast.LENGTH_SHORT).show()
        })
        rvApps.adapter = adapter
        
        findViewById<Button>(R.id.btnGithub).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/retro4hack/MoPwn"))
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnListAll).setOnClickListener {
            currentList = allApps
            filterApps(findViewById<android.widget.EditText>(R.id.etSearch).text.toString())
            tvStatus.text = getString(R.string.scan_apps_result, allApps.size)
        }

        findViewById<Button>(R.id.btnListUser).setOnClickListener {
            currentList = userApps
            filterApps(findViewById<android.widget.EditText>(R.id.etSearch).text.toString())
            tvStatus.text = getString(R.string.scan_apps_result, userApps.size)
        }

        findViewById<android.widget.EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        scanApps()
    }

    private var currentList: List<android.content.pm.ApplicationInfo> = emptyList()

    private fun filterApps(query: String) {
        val filtered = if (query.isEmpty()) {
            currentList
        } else {
            currentList.filter {
                val label = packageManager.getApplicationLabel(it).toString().lowercase()
                val pkg = it.packageName.lowercase()
                label.contains(query.lowercase()) || pkg.contains(query.lowercase())
            }
        }
        adapter.updateData(filtered)
    }

    private fun scanApps() {
        val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        allApps = packages.sortedBy { packageManager.getApplicationLabel(it).toString() }
        userApps = allApps.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
        currentList = allApps
        adapter.updateData(allApps)
        
        // Initially show nothing or scan result message
        tvStatus.text = getString(R.string.scan_apps_result, allApps.size)
    }
}

class AppAdapter(
    private var apps: List<ApplicationInfo>,
    private val pm: PackageManager,
    private val onClick: (ApplicationInfo) -> Unit,
    private val onLongClick: (ApplicationInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        holder.tvAppName.text = pm.getApplicationLabel(app)
        holder.tvPackageName.text = app.packageName
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener {
            onLongClick(app)
            true
        }
    }

    override fun getItemCount() = apps.size

    fun updateData(newApps: List<ApplicationInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }
}
