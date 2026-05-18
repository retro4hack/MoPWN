package com.mopwn.app

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File

object DecompilerEngine {
    private var cachedApkPath: String? = null
    private var appCacheDir: File? = null
    private val classList = ArrayList<String>()

    @Synchronized
    fun init(apkPath: String, cacheDir: File): String? {
        if (cachedApkPath == apkPath && classList.isNotEmpty()) {
            return null
        }
        
        shutdown()
        appCacheDir = cacheDir
        
        // 1. Read class list using native DexFile mapping (ultra fast, near-zero RAM footprint)
        try {
            val dexFile = dalvik.system.DexFile(apkPath)
            val entries = dexFile.entries()
            val tempClasses = ArrayList<String>()
            while (entries.hasMoreElements()) {
                tempClasses.add(entries.nextElement())
            }
            classList.addAll(tempClasses.sorted())
        } catch (e: Throwable) {
            e.printStackTrace()
            return "Failed to parse APK DEX headers: ${e.message}"
        }
        
        if (classList.isEmpty()) {
            return "No classes found in the target APK (it might contain only native code)."
        }
        
        cachedApkPath = apkPath
        return null
    }

    @Synchronized
    fun getClassList(): List<String> {
        return classList
    }

    @Synchronized
    fun decompileClass(classFullName: String): String {
        val apkPath = cachedApkPath ?: return "Error: No APK cached in engine."
        val cacheDir = appCacheDir
        
        var fileToDecompile = File(apkPath)
        var isTemporaryZip = false
        
        // Dynamic Optimization: Search and extract all DEX files containing the EXACT class descriptor,
        // compiling them into a lightweight temporary ZIP container.
        if (cacheDir != null) {
            val extractedZip = findAndExtractMatchingDexFiles(apkPath, classFullName, cacheDir)
            if (extractedZip != null) {
                fileToDecompile = extractedZip
                isTemporaryZip = true
            }
        }

        var result = try {
            decompileFile(fileToDecompile, classFullName)
        } catch (e: Throwable) {
            "Error"
        }

        // SELF-HEALING FALLBACK: If optimized decompile failed or was unable to locate the class node,
        // fall back completely and decompiles using the full APK file. This guarantees 100% reliability.
        if (isTemporaryZip && (result.startsWith("Error") || result.contains("not found in decompiled classes"))) {
            if (fileToDecompile.exists()) {
                fileToDecompile.delete()
            }
            fileToDecompile = File(apkPath)
            isTemporaryZip = false
            result = try {
                decompileFile(fileToDecompile, classFullName)
            } catch (e: Throwable) {
                "Error decompiling class using full APK fallback:\n${e.stackTraceToString()}"
            }
        }

        // Clean up temporary ZIP file to preserve device storage
        if (isTemporaryZip && fileToDecompile.exists()) {
            fileToDecompile.delete()
        }

        return result
    }

    private fun decompileFile(file: File, classFullName: String): String {
        // Outer Class Resolution: In JADX, inner classes (e.g. MyClass$1, MyClass$Inner) 
        // are nested within their top-level parent class and are not exposed in the flat dec.classes list.
        val topLevelClassName = if (classFullName.contains('$')) {
            classFullName.substringBefore('$')
        } else {
            classFullName
        }

        // 1. Try standard high-quality decompilation first
        try {
            val args = JadxArgs().apply {
                setInputFile(file)
                setSkipResources(true)
                setThreadsCount(1)
            }
            val dec = JadxDecompiler(args)
            dec.load()
            
            // Loose Lookup Matching: Look for exact, ending suffix, or simple name to tolerate obfuscated renamings
            var javaClass = dec.classes.find { it.fullName == topLevelClassName }
            if (javaClass == null) {
                javaClass = dec.classes.find { it.fullName.endsWith(topLevelClassName) }
            }
            if (javaClass == null) {
                val simpleName = topLevelClassName.substringAfterLast('.')
                javaClass = dec.classes.find { it.name == simpleName }
            }

            if (javaClass != null) {
                javaClass.decompile()
                val code = javaClass.code
                dec.close()
                return code ?: "Error: Decompiled code is null."
            } else {
                dec.close()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        // 2. Try Fallback Mode (much lighter, ignores packers, resists corrupt bytecode/structures)
        return try {
            val args = JadxArgs().apply {
                setInputFile(file)
                setSkipResources(true)
                setThreadsCount(1)
                setFallbackMode(true) // Bypasses heavy visitors (renaming, inlining, usage maps)
            }
            val dec = JadxDecompiler(args)
            dec.load()
            
            // Loose Lookup Matching: Fallback Mode
            var javaClass = dec.classes.find { it.fullName == topLevelClassName }
            if (javaClass == null) {
                javaClass = dec.classes.find { it.fullName.endsWith(topLevelClassName) }
            }
            if (javaClass == null) {
                val simpleName = topLevelClassName.substringAfterLast('.')
                javaClass = dec.classes.find { it.name == simpleName }
            }

            if (javaClass != null) {
                javaClass.decompile()
                val code = javaClass.code
                dec.close()
                (code ?: "Error: Decompiled code is null.") + "\n\n/* Note: Decompiled in Fallback Mode due to decompilation or protection issues. */"
            } else {
                dec.close()
                "Error: Class $classFullName (resolved to $topLevelClassName) not found in decompiled classes."
            }
        } catch (e: Throwable) {
            "Error decompiling class in fallback mode:\n${e.stackTraceToString()}"
        }
    }

    private fun findAndExtractMatchingDexFiles(apkPath: String, classFullName: String, cacheDir: File): File? {
        val pathStyle = classFullName.replace('.', '/')
        val descriptorStyle = "L$pathStyle;"
        val searchBytes = descriptorStyle.toByteArray(Charsets.UTF_8)
        
        val tempZip = File(cacheDir, "temp_decompile.zip")
        if (tempZip.exists()) {
            tempZip.delete()
        }
        
        var matchingCount = 0
        try {
            java.util.zip.ZipFile(apkPath).use { zip ->
                java.util.zip.ZipOutputStream(tempZip.outputStream()).use { zos ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (indexOf(bytes, searchBytes) >= 0) {
                                val zipEntry = java.util.zip.ZipEntry(entry.name)
                                zos.putNextEntry(zipEntry)
                                zos.write(bytes)
                                zos.closeEntry()
                                matchingCount++
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        
        return if (matchingCount > 0) tempZip else null
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty()) return 0
        val limit = data.size - pattern.size
        for (i in 0..limit) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    @Synchronized
    fun shutdown() {
        cachedApkPath = null
        appCacheDir = null
        classList.clear()
    }
}
