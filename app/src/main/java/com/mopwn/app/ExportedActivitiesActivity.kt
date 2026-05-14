package com.mopwn.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

            val adapter = ActivityAdapter(exportedActivities) { activityInfo ->
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
    private val onClick: (ActivityInfo) -> Unit
) : RecyclerView.Adapter<ActivityAdapter.ActivityViewHolder>() {

    class ActivityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvActivityName: TextView = view.findViewById(R.id.tvActivityName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActivityViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_activity, parent, false)
        return ActivityViewHolder(view)
    }

    override fun onBindViewHolder(holder: ActivityViewHolder, position: Int) {
        val activity = activities[position]
        holder.tvActivityName.text = activity.name
        holder.itemView.setOnClickListener { onClick(activity) }
    }

    override fun getItemCount() = activities.size
}
