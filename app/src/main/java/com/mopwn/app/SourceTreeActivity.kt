package com.mopwn.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class SourceTreeActivity : AppCompatActivity() {

    private lateinit var rvClasses: RecyclerView
    private lateinit var etClassSearch: EditText
    private lateinit var tvDecompileTarget: TextView
    private lateinit var adapter: ClassAdapter
    
    private var allClasses = listOf<String>()
    private var filteredClasses = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_tree)

        val packageName = intent.getStringExtra("PACKAGE_NAME") ?: "Unknown Package"

        tvDecompileTarget = findViewById(R.id.tvDecompileTarget)
        tvDecompileTarget.text = packageName

        rvClasses = findViewById(R.id.rvClasses)
        etClassSearch = findViewById(R.id.etClassSearch)

        allClasses = DecompilerEngine.getClassList()
        filteredClasses = allClasses

        adapter = ClassAdapter(filteredClasses) { classFullName ->
            val intent = Intent(this, SourceViewerActivity::class.java)
            intent.putExtra("CLASS_FULL_NAME", classFullName)
            startActivity(intent)
        }

        rvClasses.layoutManager = LinearLayoutManager(this)
        rvClasses.adapter = adapter

        etClassSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterClasses(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<Button>(R.id.btnExitDecompiler).setOnClickListener {
            finish()
        }
    }

    private fun filterClasses(query: String) {
        filteredClasses = if (query.isEmpty()) {
            allClasses
        } else {
            val lowercaseQuery = query.lowercase()
            allClasses.filter { fullName ->
                val simpleName = fullName.substringAfterLast('.')
                fullName.lowercase().contains(lowercaseQuery) || simpleName.lowercase().contains(lowercaseQuery)
            }
        }
        adapter.updateList(filteredClasses)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            // Clean up JADX engine resources
            DecompilerEngine.shutdown()
            
            // Clean up temporary APK from cache if it exists
            try {
                val tempApk = File(cacheDir, "temp_decompile.apk")
                if (tempApk.exists()) {
                    tempApk.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private class ClassAdapter(
        private var list: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<ClassAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvClassName: TextView = view.findViewById(R.id.tvClassName)
            val tvClassPackage: TextView = view.findViewById(R.id.tvClassPackage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_class, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val fullName = list[position]
            val simpleName = fullName.substringAfterLast('.')
            val packageName = if (fullName.contains('.')) fullName.substringBeforeLast('.') else "Default Package"

            holder.tvClassName.text = simpleName
            holder.tvClassPackage.text = packageName
            
            holder.itemView.setOnClickListener {
                onClick(fullName)
            }
        }

        override fun getItemCount(): Int = list.size

        fun updateList(newList: List<String>) {
            list = newList
            notifyDataSetChanged()
        }
    }
}
