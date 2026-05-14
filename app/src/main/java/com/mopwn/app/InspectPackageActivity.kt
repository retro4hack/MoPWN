package com.mopwn.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class InspectPackageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspect_package)

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: return

        findViewById<TextView>(R.id.tvInspectPackageName).text = packageName

        findViewById<Button>(R.id.btnApkDetails).setOnClickListener {
            val intent = Intent(this, ApkDetailsActivity::class.java)
            intent.putExtra("PACKAGE_NAME", packageName)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnExportedActivities).setOnClickListener {
            val intent = Intent(this, ExportedActivitiesActivity::class.java)
            intent.putExtra("PACKAGE_NAME", packageName)
            startActivity(intent)
        }
    }
}
