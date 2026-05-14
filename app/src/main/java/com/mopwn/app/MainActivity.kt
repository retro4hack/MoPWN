package com.mopwn.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
        adapter = AppAdapter(emptyList(), packageManager) { appInfo ->
            val intent = Intent(this, InspectPackageActivity::class.java)
            intent.putExtra("PACKAGE_NAME", appInfo.packageName)
            startActivity(intent)
        }
        rvApps.adapter = adapter

        findViewById<Button>(R.id.btnListAll).setOnClickListener {
            adapter.updateData(allApps)
            tvStatus.text = getString(R.string.scan_apps_result, allApps.size)
        }

        findViewById<Button>(R.id.btnListUser).setOnClickListener {
            adapter.updateData(userApps)
            tvStatus.text = getString(R.string.scan_apps_result, userApps.size)
        }

        scanApps()
    }

    private fun scanApps() {
        val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        allApps = packages.sortedBy { packageManager.getApplicationLabel(it).toString() }
        userApps = allApps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        
        // Initially show nothing or scan result message
        tvStatus.text = getString(R.string.scan_apps_result, allApps.size)
    }
}

class AppAdapter(
    private var apps: List<ApplicationInfo>,
    private val pm: PackageManager,
    private val onClick: (ApplicationInfo) -> Unit
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
    }

    override fun getItemCount() = apps.size

    fun updateData(newApps: List<ApplicationInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }
}
