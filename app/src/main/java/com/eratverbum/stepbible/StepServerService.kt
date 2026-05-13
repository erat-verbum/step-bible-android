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
    private var serverPort = 8989

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(1, notification)

        intent?.let {
            serverPort = it.getIntExtra("PORT", 8989)
        }

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

    private fun setupAndStartServer() {
        val appDir = filesDir
        val jreDir = File(appDir, "jre")
        val stepDir = File(appDir, "step")

        if (!jreDir.exists() || !stepDir.exists()) {
            extractAssets(appDir)
        }

        val classpath = buildClasspath(stepDir)
        val webappDir = File(stepDir, "step-web")

        ServerState.port = serverPort
        ServerState.isRunning = true

        Log.i(TAG, "Starting JVM...")
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val ret = JVMStub.startServer(
            jreDir = jreDir.absolutePath,
            classPath = classpath,
            warPath = webappDir.absolutePath,
            port = serverPort,
            nativeLibDir = nativeLibDir
        )

        if (ret != 0) {
            Log.e(TAG, "JVM exited with error: $ret")
        } else {
            Log.i(TAG, "JVM exited normally")
        }
        ServerState.isRunning = false
    }

    private fun buildClasspath(stepDir: File): String {
        val jars = mutableListOf<String>()

        val serverJars = stepDir.listFiles { f -> f.name.startsWith("step-server-") && f.name.endsWith(".jar") }
        serverJars?.sortedBy { it.name }?.forEach { jars.add(it.absolutePath) }

        File(stepDir, "lib").takeIf { it.exists() }?.let { libDir ->
            libDir.listFiles { f -> f.name.endsWith(".jar") }
                ?.sortedBy { it.name }
                ?.forEach { jars.add(it.absolutePath) }
        }

        File(stepDir, "step-web/WEB-INF/lib").takeIf { it.exists() }?.let { webinfLib ->
            webinfLib.listFiles { f -> f.name.endsWith(".jar") }
                ?.sortedBy { it.name }
                ?.forEach { jars.add(it.absolutePath) }
        }

        return jars.joinToString(":")
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
        val stepDir = File(appDir, "step")
        val jreDir = File(appDir, "jre")
        val jreAbi = detectJreAbi()

        try {
            val apkPath = packageManager.getApplicationInfo(packageName, 0).sourceDir
            val zip = ZipFile(apkPath)
            val entries = zip.entries()
            val prefixJre = "assets/jre/$jreAbi/"
            val prefixStep = "assets/step/"

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                when {
                    name.startsWith(prefixJre) && !entry.isDirectory -> {
                        val relPath = name.removePrefix(prefixJre)
                        val dest = File(jreDir, relPath)
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    name.startsWith(prefixStep) && !entry.isDirectory -> {
                        val relPath = name.removePrefix(prefixStep)
                        val dest = File(stepDir, relPath)
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            zip.close()
            Log.i(TAG, "Extraction complete (ABI: $jreAbi)")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            throw e
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
    var isRunning = false
    var port = 8989
}
