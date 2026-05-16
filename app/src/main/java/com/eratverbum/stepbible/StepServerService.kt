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

        Log.i(TAG, "Starting JVM...")
        val ret = JVMStub.startServer(
            jreDir = jreDir.absolutePath,
            classPath = classpath,
            warPath = webappDir.absolutePath,
            port = serverPort
        )

        if (ret != 0) {
            Log.e(TAG, "JVM exited with error: $ret")
        } else {
            Log.i(TAG, "JVM exited normally")
        }
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

        try {
            val apkPath = packageManager.getApplicationInfo(packageName, 0).sourceDir
            val zip = ZipFile(apkPath)

            // Extract JRE files individually
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
            zip.close()

            // Extract STEP data from tar archive (aapt strips .gz from assets)
            Log.i(TAG, "Extracting step.tar...")
            TarExtractor.extractFromApk(apkPath, "assets/step.tar", appDir)

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
    var port = 8989
}
