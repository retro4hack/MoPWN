package com.mopwn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ExportedActivitiesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exported_activities)

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: return
        val rvActivities = findViewById<RecyclerView>(R.id.rvExportedActivities)
        rvActivities.layoutManager = LinearLayoutManager(this)

        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
                )
            } else {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES
                )
            }

            val exportedActivities = packageInfo.activities?.filter { it.exported } ?: emptyList()
            val globalPermission = packageInfo.applicationInfo?.permission

            val adapter = ActivityAdapter(exportedActivities, globalPermission) { activityInfo ->
                val intent = Intent(this, LaunchIntentActivity::class.java)
                intent.putExtra("PACKAGE_NAME", activityInfo.packageName)
                intent.putExtra("ACTIVITY_NAME", activityInfo.name)
                startActivity(intent)
            }
            rvActivities.adapter = adapter

        } catch (e: PackageManager.NameNotFoundException) {
            // Handle error
        }
    }
}

class ActivityAdapter(
    private val activities: List<ActivityInfo>,
    private val globalPermission: String?,
    private val onClick: (ActivityInfo) -> Unit
) : RecyclerView.Adapter<ActivityAdapter.ActivityViewHolder>() {

    class ActivityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvActivityName: TextView = view.findViewById(R.id.tvActivityName)
        val tvActivityProtection: TextView = view.findViewById(R.id.tvActivityProtection)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActivityViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_activity, parent, false)
        return ActivityViewHolder(view)
    }

    override fun onBindViewHolder(holder: ActivityViewHolder, position: Int) {
        val activity = activities[position]
        holder.tvActivityName.text = activity.name
        holder.itemView.setOnClickListener { onClick(activity) }
        
        // Analyze protection level dynamically
        val specificPermission = activity.permission
        val effectivePermission = specificPermission ?: globalPermission

        if (effectivePermission == null) {
            holder.tvActivityProtection.text = "Unprotected (No permission required)"
            holder.tvActivityProtection.setTextColor(android.graphics.Color.parseColor("#FF5252")) // Vibrant Red
        } else {
            val sourceText = if (specificPermission != null) "Activity-level" else "Application-level global"
            holder.tvActivityProtection.text = "Protected by $sourceText permission:\n$effectivePermission"
            holder.tvActivityProtection.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Soft Green
        }
        
        // Copy class name on long-click
        holder.itemView.setOnLongClickListener {
            val context = holder.itemView.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Activity Class Name", activity.name)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied class name to clipboard", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun getItemCount() = activities.size
}
