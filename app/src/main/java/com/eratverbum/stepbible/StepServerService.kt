package com.eratverbum.stepbible

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

class StepServerService : Service() {

    private var serverThread: Thread? = null
    private val serverPort = 8989

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ServerState.jvmStarted) return START_STICKY

        val notification = buildNotification()
        startForeground(1, notification)

        serverThread = Thread {
            try {
                setupAndStartServer()
            } catch (e: Exception) {
                Log.e(TAG, "Server failed", e)
            }
        }
        serverThread?.start()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serverThread?.interrupt()
        super.onDestroy()
    }

    private val extractionMarker = File(filesDir, ".extraction-complete")
    private val versionMarker = File(filesDir, ".app-version")

    private fun needExtraction(): Boolean {
        if (!extractionMarker.exists()) return true
        val storedVersion = try { versionMarker.readText().trim().toInt() } catch (_: Exception) { 0 }
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) { 0 }
        return storedVersion != currentVersion
    }

    private fun setupAndStartServer() {
        var retries = 0
        while (retries < 2) {
            val appDir = filesDir
            val jreDir = File(appDir, "jre")
            val stepDir = File(appDir, "step")

            if (needExtraction()) {
                try {
                    extractAssets(appDir)
                    extractionMarker.createNewFile()
                    versionMarker.writeText(
                        packageManager.getPackageInfo(packageName, 0).versionCode.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Extraction failed", e)
                    extractionMarker.delete()
                    return
                }
            }

            val classpath = buildClasspath(stepDir)
            val webappDir = File(stepDir, "step-web")

            ServerState.port = serverPort

            if (!JVMStub.ensureLoaded()) {
                Log.e(TAG, "Failed to load native library")
                return
            }

            ServerState.jvmStarted = true
            ServerState.port = serverPort

            Log.i(TAG, "Starting JVM...")
            val ret = JVMStub.startServer(
                jreDir = jreDir.absolutePath,
                classPath = classpath,
                warPath = webappDir.absolutePath,
                port = serverPort
            )

            if (ret == 0) {
                Log.i(TAG, "JVM exited normally")
                return
            }
            Log.e(TAG, "JVM exited with error: $ret (attempt ${retries + 1})")
            retries++
            if (retries < 2) Thread.sleep(1000)
        }
        // All retries exhausted — allow service restart to retry
        ServerState.jvmStarted = false
    }

    private fun buildClasspath(stepDir: File): String {
        return stepDir.listFiles { f -> f.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?.joinToString(":") { it.absolutePath }
            ?: ""
    }

    private fun detectJreAbi(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> "arm64-v8a"
            abi.startsWith("armeabi") -> "armeabi-v7a"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    private fun extractAssets(appDir: File) {
        Log.i(TAG, "Extracting assets (first launch)...")
        val jreDir = File(appDir, "jre")
        val jreAbi = detectJreAbi()

        val apkPath = packageManager.getApplicationInfo(packageName, 0).sourceDir
        ZipFile(apkPath).use { zip ->
            val prefixJre = "assets/jre/$jreAbi/"
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith(prefixJre) && !entry.isDirectory) {
                    val relPath = name.removePrefix(prefixJre)
                    val dest = File(jreDir, relPath)
                    dest.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        Log.i(TAG, "Extracting step.tar...")
        TarExtractor.extractFromApk(apkPath, "assets/step.tar", appDir)
        linkJswordData(appDir, File(appDir, "step"))

        Log.i(TAG, "Extraction complete (ABI: $jreAbi)")
    }

    private fun linkJswordData(appDir: File, stepDir: File) {
        val jswordHome = File(appDir, ".jsword")
        val jswordSource = File(stepDir, "homes/jsword")
        val swordHome = File(stepDir, "homes/sword")
        if (!jswordSource.exists()) return
        jswordHome.mkdirs()
        try {
            // Try symlink first (fails on API 28+ for non-system apps),
            // fall back to directory-level copy
            val useSymlinks = try {
                val test = File(jswordHome, ".symtest")
                java.nio.file.Files.createSymbolicLink(test.toPath(), jswordHome.toPath())
                java.nio.file.Files.delete(test.toPath())
                true
            } catch (_: Exception) { false }

            // modules/ (SWORD data files, needed by JSword)
            val modsLink = File(jswordHome, "modules")
            val modsSword = File(swordHome, "modules")
            if (modsSword.exists()) {
                if (useSymlinks && !modsLink.exists())
                    java.nio.file.Files.createSymbolicLink(
                        modsLink.toPath(), modsSword.toPath().toAbsolutePath())
                Log.i(TAG, if (useSymlinks) "Linked modules to jsword" else "Modules already at sword path")
            }

            // Copy mods.d/ config files
            val modsDest = File(jswordHome, "mods.d")
            val modsSource = File(swordHome, "mods.d")
            if (modsSource.exists()) {
                modsDest.mkdirs()
                modsSource.listFiles { f -> f.name.endsWith(".conf") }?.forEach { conf ->
                    val dest = File(modsDest, conf.name)
                    if (!dest.exists()) conf.copyTo(dest)
                }
                Log.i(TAG, "Copied mods.d to jsword")
            }

            // lucene/Sword modules (Lucene indexes - from jsword data)
            val swordLink = File(jswordHome, "lucene/Sword")
            val swordSource = File(jswordSource, "lucene/Sword")
            if (swordSource.exists()) {
                swordLink.parentFile?.mkdirs()
                if (useSymlinks && !swordLink.exists())
                    java.nio.file.Files.createSymbolicLink(
                        swordLink.toPath(), swordSource.toPath().toAbsolutePath())
                Log.i(TAG, if (useSymlinks) "Linked lucene/Sword to jsword" else "Lucene/Sword at original path")
            }

            // step/ entities
            val stepLink = File(jswordHome, "step/entities")
            val stepSource = File(jswordSource, "step/entities")
            if (stepSource.exists()) {
                stepLink.parentFile?.mkdirs()
                if (useSymlinks && !stepLink.exists())
                    java.nio.file.Files.createSymbolicLink(
                        stepLink.toPath(), stepSource.toPath().toAbsolutePath())
                Log.i(TAG, if (useSymlinks) "Linked step/entities to jsword" else "Step/entities at original path")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to link jsword data", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_server),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("STEP Bible")
            .setContentText(getString(R.string.server_starting))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
    }

    companion object {
        private const val TAG = "StepServer"
        private const val CHANNEL_ID = "step_server"
    }
}

object ServerState {
    @Volatile var port = 8989
    @Volatile var jvmStarted = false
}
