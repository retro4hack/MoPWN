package com.mopwn.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.io.DataOutputStream

object GlobalProxyManager {

    const val ACTION_PROXY_STATE_CHANGED = "com.mopwn.app.ACTION_PROXY_STATE_CHANGED"
    const val PREFS_NAME = "MoPWN_GlobalProxy"
    const val KEY_LAST_IP = "last_proxy_ip"
    const val KEY_LAST_PORT = "last_proxy_port"

    /**
     * Determines whether the global HTTP proxy is currently active.
     * When deactivated with `settings put global http_proxy :0`, the value is ":0".
     */
    fun isProxyActive(proxyVal: String?): Boolean {
        if (proxyVal.isNullOrBlank()) return false
        val trimmed = proxyVal.trim()
        if (trimmed == ":0" || trimmed == "null" || trimmed == "0" || trimmed.equals("None", ignoreCase = true)) {
            return false
        }
        return trimmed.contains(":")
    }

    /**
     * Reads the current system-wide http_proxy setting.
     * Checks Settings.Global first, falling back to root command `settings get global http_proxy`.
     */
    fun getProxyHostPort(context: Context): String? {
        try {
            val settingVal = Settings.Global.getString(context.contentResolver, "http_proxy")
            if (!settingVal.isNullOrBlank() && settingVal != "null") {
                return settingVal.trim()
            }
        } catch (e: Exception) {
            // fallback to root execution
        }

        val rootVal = runRootCommandSingleLine("settings get global http_proxy")
        if (!rootVal.isNullOrBlank() && rootVal != "null") {
            return rootVal.trim()
        }

        return null
    }

    /**
     * Activates the global proxy using root shell command:
     * settings put global http_proxy IP_PC:PORT
     */
    fun activateProxy(ip: String, port: Int): Pair<Boolean, String?> {
        val hostPort = "${ip.trim()}:$port"
        val cmd = "settings put global http_proxy $hostPort"
        return executeRootCommand(cmd)
    }

    /**
     * Deactivates the global proxy using root shell command:
     * settings put global http_proxy :0
     */
    fun deactivateProxy(): Pair<Boolean, String?> {
        val cmd = "settings put global http_proxy :0"
        return executeRootCommand(cmd)
    }

    fun startProxyService(context: Context, hostPort: String) {
        val intent = Intent(context, GlobalProxyService::class.java).apply {
            action = GlobalProxyService.ACTION_START
            putExtra(GlobalProxyService.EXTRA_HOST_PORT, hostPort)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopProxyService(context: Context) {
        val intent = Intent(context, GlobalProxyService::class.java).apply {
            action = GlobalProxyService.ACTION_STOP
        }
        context.stopService(intent)
    }

    /**
     * Sends an explicit broadcast restricted to this app package,
     * satisfying Android 14+ RECEIVER_NOT_EXPORTED security requirements.
     */
    fun sendStateBroadcast(context: Context) {
        val intent = Intent(ACTION_PROXY_STATE_CHANGED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    fun checkRootAvailable(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    fun executeRootCommand(cmd: String): Pair<Boolean, String?> {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()

            val errorReader = process.errorStream.bufferedReader()
            val errorMsg = errorReader.readText().trim()

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Pair(true, null)
            } else {
                Pair(false, if (errorMsg.isNotBlank()) errorMsg else "Command exited with code $exitCode")
            }
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "Failed to execute root command")
        } finally {
            process?.destroy()
        }
    }

    fun runRootCommandSingleLine(cmd: String): String? {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = process.inputStream.bufferedReader()
            val line = reader.readLine()
            process.waitFor()
            line
        } catch (e: Exception) {
            null
        } finally {
            process?.destroy()
        }
    }
}
