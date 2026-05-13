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

    private var serverProcess: Process? = null
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
        serverProcess?.destroy()
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

        val javaBin = File(jreDir, "bin/java")
        if (!javaBin.canExecute()) {
            javaBin.setExecutable(true)
        }
        setAllExecutable(File(jreDir, "bin"))

        val classpath = buildClasspath(stepDir)
        val webappDir = File(stepDir, "step-web")

        val pb = ProcessBuilder(
            javaBin.absolutePath,
            "-cp", classpath,
            "-Dstep.war.path=${webappDir.absolutePath}",
            "-Dstep.war.port=$serverPort",
            "-Dstep.war.context=",
            "-Djava.io.tmpdir=${File(appDir, "tmp").also { it.mkdirs() }.absolutePath}",
            "com.tyndalehouse.step.server.STEPTomcatServer",
            "backgroundLaunch"
        )
        pb.environment()["JAVA_HOME"] = jreDir.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = buildLdLibraryPath(jreDir)
        pb.directory(stepDir)
        pb.redirectErrorStream(true)

        Log.i(TAG, "Starting server: ${pb.command().joinToString(" ")}")

        serverProcess = pb.start()
        ServerState.port = serverPort
        ServerState.isRunning = true

        serverProcess?.inputStream?.bufferedReader()?.use { reader ->
            reader.lines().forEach { line ->
                Log.d(TAG, "[JRE] $line")
            }
        }

        serverProcess?.waitFor()
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

    private fun buildLdLibraryPath(jreDir: File): String {
        val paths = mutableListOf<String>()
        val libDir = File(jreDir, "lib")
        if (libDir.exists()) paths.add(libDir.absolutePath)
        val serverDir = File(libDir, "server")
        if (serverDir.exists()) paths.add(serverDir.absolutePath)
        return paths.joinToString(":")
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
                        if (relPath.startsWith("bin/") || relPath.endsWith(".so")) {
                            dest.setExecutable(true)
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

    private fun setAllExecutable(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile) f.setExecutable(true)
            else if (f.isDirectory) setAllExecutable(f)
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
