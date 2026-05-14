package com.mopwn.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LaunchIntentActivity : AppCompatActivity() {

    private lateinit var llExtrasContainer: LinearLayout
    private val extraTypes = arrayOf("String", "Int", "Boolean", "Float")
    private val commonActions = arrayOf(
        "Custom/None",
        Intent.ACTION_VIEW,
        Intent.ACTION_MAIN,
        Intent.ACTION_SEND,
        Intent.ACTION_EDIT
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launch_intent)

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: return
        val activityName = intent.getStringExtra("ACTIVITY_NAME") ?: return

        findViewById<TextView>(R.id.tvTargetActivity).text = "Target:\n$packageName\n$activityName"

        val spinnerAction = findViewById<Spinner>(R.id.spinnerAction)
        val actionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, commonActions)
        actionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAction.adapter = actionAdapter

        val etCustomAction = findViewById<EditText>(R.id.etCustomAction)
        val etDataUri = findViewById<EditText>(R.id.etDataUri)
        
        llExtrasContainer = findViewById(R.id.llExtrasContainer)
        
        findViewById<Button>(R.id.btnAddExtra).setOnClickListener {
            addExtraRow()
        }

        findViewById<Button>(R.id.btnLaunch).setOnClickListener {
            val launchIntent = Intent()
            launchIntent.component = ComponentName(packageName, activityName)

            // Determine Action
            val selectedAction = spinnerAction.selectedItem.toString()
            val customAction = etCustomAction.text.toString().trim()
            if (customAction.isNotEmpty()) {
                launchIntent.action = customAction
            } else if (selectedAction != "Custom/None") {
                launchIntent.action = selectedAction
            }

            // Determine Data URI
            val dataUriStr = etDataUri.text.toString().trim()
            if (dataUriStr.isNotEmpty()) {
                launchIntent.data = Uri.parse(dataUriStr)
            }

            // Build Extras
            for (i in 0 until llExtrasContainer.childCount) {
                val row = llExtrasContainer.getChildAt(i) as LinearLayout
                val key = row.findViewById<EditText>(R.id.etExtraKey).text.toString().trim()
                val type = row.findViewById<Spinner>(R.id.spinnerExtraType).selectedItem.toString()
                val valueStr = row.findViewById<EditText>(R.id.etExtraValue).text.toString().trim()

                if (key.isNotEmpty() && valueStr.isNotEmpty()) {
                    try {
                        when (type) {
                            "String" -> launchIntent.putExtra(key, valueStr)
                            "Int" -> launchIntent.putExtra(key, valueStr.toInt())
                            "Boolean" -> launchIntent.putExtra(key, valueStr.toBooleanStrict())
                            "Float" -> launchIntent.putExtra(key, valueStr.toFloat())
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to parse value '$valueStr' as $type for key '$key'", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to launch: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addExtraRow() {
        val inflater = LayoutInflater.from(this)
        val row = inflater.inflate(R.layout.item_intent_extra, llExtrasContainer, false) as LinearLayout

        val spinnerType = row.findViewById<Spinner>(R.id.spinnerExtraType)
        val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, extraTypes)
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = typeAdapter

        val btnRemove = row.findViewById<ImageButton>(R.id.btnRemoveExtra)
        btnRemove.setOnClickListener {
            llExtrasContainer.removeView(row)
        }

        llExtrasContainer.addView(row)
    }
}
