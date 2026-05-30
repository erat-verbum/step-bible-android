package com.eratverbum.stepbible

import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.Html
import android.util.Log
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

class MainActivity : AppCompatActivity() {

    private lateinit var container: ViewGroup
    private lateinit var toolbar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var tabScroller: HorizontalScrollView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnNewTab: ImageButton
    private lateinit var btnTabOverview: ImageButton
    private lateinit var loadingSpinner: CircularProgressIndicator
    private lateinit var loadingText: TextView
    private lateinit var retryButton: Button
    @Volatile private lateinit var appDir: File
    private val tabs = mutableListOf<TabInfo>()
    private var currentIndex = -1
    private var closingTab = false
    private var serverFailed = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingShareUrl: String? = null
    private var pendingRestoreData: List<Pair<String, String>>? = null
    private var pendingRestoreIndex = 0
    private var serverThread: Thread? = null
    private var serverPollThread: Thread? = null
    private var healthCheckThread: Thread? = null
    @Volatile private var healthCheckRunning = false
    @Volatile private var consecutiveFailures = 0
    private var isRestarting = false

    private data class TabInfo(
        val webView: WebView,
        val tabView: View,
        var title: String,
        val fromShare: Boolean = false
    ) {
        var scrollListener: View.OnLayoutChangeListener? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.webview_container)
        toolbar = findViewById(R.id.toolbar)
        tabBar = findViewById(R.id.tab_bar)
        tabScroller = findViewById(R.id.tab_scroller)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnReload = findViewById(R.id.btn_reload)
        btnHome = findViewById(R.id.btn_home)
        btnNewTab = findViewById(R.id.btn_new_tab)
        btnTabOverview = findViewById(R.id.btn_tab_overview)
        loadingSpinner = findViewById(R.id.loading_spinner)
        loadingText = findViewById(R.id.loading_text)
        retryButton = findViewById(R.id.btn_retry)

        btnBack.setOnClickListener { goBack() }
        btnForward.setOnClickListener { goForward() }
        btnBack.setOnLongClickListener { showHistory(true); true }
        btnForward.setOnLongClickListener { showHistory(false); true }
        btnReload.setOnClickListener { reloadCurrent() }
        btnHome.setOnClickListener {
            if (currentIndex in tabs.indices) {
                tabs[currentIndex].webView.loadUrl("http://127.0.0.1:${ServerState.port}/")
            }
        }
        btnNewTab.setOnClickListener {
            val url = if (currentIndex in tabs.indices) {
                tabs[currentIndex].webView.url ?: "http://127.0.0.1:${ServerState.port}/"
            } else {
                "http://127.0.0.1:${ServerState.port}/"
            }
            createTab(url)
        }
        btnTabOverview.setOnClickListener { showTabOverview() }
        retryButton.setOnClickListener { retry() }

        handleShareIntent(intent)
        loadSavedTabState()
        appDir = filesDir
        startServer()
    }

    private fun startServer() {
        if (!serverFailed && (serverThread?.isAlive == true || ServerState.jvmStarted)) {
            waitForServer()
            return
        }
        ServerState.jvmStarted = false
        serverFailed = false
        retryButton.visibility = View.GONE
        loadingSpinner.visibility = View.VISIBLE
        loadingSpinner.progress = 0
        loadingText.text = getString(R.string.server_starting)

        ServerState.port = serverPort

        if (serverThread?.isAlive == true || ServerState.jvmStarted) {
            waitForServer()
            return
        }

        serverPollThread?.interrupt()
        serverThread = Thread {
            try {
                setupAndStartServer()
            } catch (e: Exception) {
                Log.e(TAG, "Server failed", e)
            }
        }.apply { isDaemon = true }
        serverThread?.start()
        waitForServer()
    }

    private fun retry() {
        startServer()
    }

    private fun waitForServer() {
        serverPollThread = Thread {
            var retries = 0
            while (retries < 60 && !Thread.currentThread().isInterrupted) {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL("http://127.0.0.1:${ServerState.port}/")
                    conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 500
                    conn.readTimeout = 500
                    conn.connect()
                    if (conn.responseCode == 200) {
                        runOnUiThread { onServerReady() }
                        return@Thread
                    }
                } catch (_: Exception) {
                } finally {
                    conn?.disconnect()
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                retries++
            }
            if (!Thread.currentThread().isInterrupted) {
                runOnUiThread { onServerFailed() }
            }
        }
        serverPollThread?.start()
    }

    private fun startHealthCheck() {
        stopHealthCheck()
        consecutiveFailures = 0
        healthCheckRunning = true
        healthCheckThread = Thread {
            while (healthCheckRunning && !Thread.currentThread().isInterrupted) {
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
                var conn: HttpURLConnection? = null
                try {
                    val url = URL("http://127.0.0.1:${ServerState.port}/")
                    conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.connect()
                    if (conn.responseCode == 200) {
                        consecutiveFailures = 0
                        continue
                    }
                } catch (_: Exception) {
                } finally {
                    conn?.disconnect()
                }
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    healthCheckRunning = false
                    runOnUiThread { onServerUnresponsive() }
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopHealthCheck() {
        healthCheckRunning = false
        healthCheckThread?.interrupt()
        healthCheckThread = null
    }

    private fun onServerUnresponsive() {
        stopHealthCheck()
        if (isFinishing || isDestroyed) return
        if (serverThread?.isAlive == false) {
            isRestarting = true
            toolbar.visibility = View.GONE
            tabBar.visibility = View.GONE
            loadingSpinner.visibility = View.VISIBLE
            loadingText.text = getString(R.string.server_restarting)
            loadingText.visibility = View.VISIBLE
            startServer()
        } else {
            AlertDialog.Builder(this)
                .setMessage(R.string.server_unresponsive)
                .setCancelable(false)
                .setPositiveButton(R.string.restart_app) { _, _ ->
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    finish()
                    startActivity(intent)
                }
                .show()
        }
    }

    private fun onServerReady() {
        if (isFinishing || isDestroyed) return
        loadingSpinner.visibility = View.GONE
        loadingText.visibility = View.GONE
        retryButton.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        tabBar.visibility = View.VISIBLE
        when {
            pendingShareUrl != null -> {
                createTab(rebuildUrl(pendingShareUrl!!), fromShare = true)
                pendingShareUrl = null
                pendingRestoreData = null
            }
            pendingRestoreData != null -> {
                restoreSavedTabs(pendingRestoreData!!, pendingRestoreIndex)
                pendingRestoreData = null
            }
            isRestarting -> {
                isRestarting = false
                if (tabs.isNotEmpty()) {
                    tabs[currentIndex].webView.reload()
                } else {
                    createTab("http://127.0.0.1:${ServerState.port}/")
                }
            }
            else -> createTab("http://127.0.0.1:${ServerState.port}/")
        }
        startHealthCheck()
    }

    private fun onServerFailed() {
        stopHealthCheck()
        serverFailed = true
        loadingSpinner.visibility = View.GONE
        loadingText.text = getString(R.string.server_failed)
        retryButton.visibility = View.VISIBLE
    }

    private fun setupAndStartServer() {
        var retries = 0
        while (retries < MAX_RETRIES) {
            val stepDir = File(appDir, "step")
            val jreDir = File(appDir, "jre")

            if (needExtraction(appDir)) {
                try {
                    extractAssets(appDir)
                    markExtractionComplete(appDir)
                } catch (e: Exception) {
                    Log.e(TAG, "Extraction failed", e)
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
            updateProgress(92, R.string.starting_server)

            Log.i(TAG, "Starting JVM...")
            var ret = -1
            try {
                ret = JVMStub.startServer(
                    jreDir = jreDir.absolutePath,
                    classPath = classpath,
                    warPath = webappDir.absolutePath,
                    port = serverPort
                )
            } catch (e: Exception) {
                Log.e(TAG, "JVM start threw", e)
                ServerState.jvmStarted = false
                return
            }

            if (ret == 0) {
                Log.i(TAG, "JVM exited normally")
                ServerState.jvmStarted = false
                return
            }
            Log.e(TAG, "JVM exited with error: $ret (attempt ${retries + 1})")
            retries++
            if (retries < MAX_RETRIES) Thread.sleep(1000)
        }
        ServerState.jvmStarted = false
    }

    private fun buildClasspath(stepDir: File): String {
        return stepDir.listFiles { f -> f.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            ?.joinToString(":") { it.absolutePath }
            ?: ""
    }

    private fun detectJreAbi(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
            ?: throw RuntimeException("No supported ABIs found")
        return when {
            abi.startsWith("arm64") -> "arm64-v8a"
            abi.startsWith("armeabi") -> "armeabi-v7a"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> throw RuntimeException("Unsupported ABI: $abi")
        }
    }

    private fun extractAssets(appDir: File) {
        Log.i(TAG, "Extracting assets (first launch)...")
        val jreDir = File(appDir, "jre")
        val jreAbi = detectJreAbi()

        updateProgress(5, R.string.extracting_jre)
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

        updateProgress(45, R.string.extracting_step)
        Log.i(TAG, "Extracting step.targz...")
        TarExtractor.extractFromApk(apkPath, "assets/step.targz", appDir)

        updateProgress(82, R.string.setting_up_modules)
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
            val useSymlinks = try {
                val test = File(jswordHome, ".symtest")
                java.nio.file.Files.createSymbolicLink(test.toPath(), jswordHome.toPath())
                java.nio.file.Files.delete(test.toPath())
                true
            } catch (_: Exception) { false }

            linkOrCopy("modules", File(swordHome, "modules"), File(jswordHome, "modules"), useSymlinks)

            val modsDest = File(jswordHome, "mods.d")
            val modsSource = File(swordHome, "mods.d")
            if (modsSource.exists()) {
                modsDest.mkdirs()
                modsSource.listFiles { f -> f.name.endsWith(".conf") }?.forEach { conf ->
                    conf.copyTo(File(modsDest, conf.name), overwrite = true)
                }
                Log.i(TAG, "Copied mods.d to jsword")
            }

            linkOrCopy("lucene/Sword", File(jswordSource, "lucene/Sword"), File(jswordHome, "lucene/Sword"), useSymlinks)
            linkOrCopy("step/entities", File(jswordSource, "step/entities"), File(jswordHome, "step/entities"), useSymlinks)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to link jsword data", e)
        }
    }

    private fun linkOrCopy(label: String, source: File, dest: File, useSymlinks: Boolean) {
        if (!source.exists()) return
        dest.parentFile?.mkdirs()
        if (useSymlinks)
            java.nio.file.Files.createSymbolicLink(dest.toPath(), source.toPath().toAbsolutePath())
        else
            source.copyRecursively(dest)
        Log.i(TAG, if (useSymlinks) "Linked $label to jsword" else "Copied $label to jsword")
    }

    private fun needExtraction(appDir: File): Boolean {
        val marker = File(appDir, ".extraction-complete")
        if (!marker.exists()) return true
        val verFile = File(appDir, ".app-version")
        val storedVersion = try { verFile.readText().trim().toLong() } catch (_: Exception) { 0L }
        return storedVersion != getAppVersionCode()
    }

    private fun getAppVersionCode(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).longVersionCode
        }
    }

    private fun updateProgress(percent: Int, textResId: Int) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            loadingSpinner.setProgress(percent, true)
            loadingText.text = getString(textResId)
        }
    }

    private fun markExtractionComplete(appDir: File) {
        try {
            File(appDir, ".extraction-complete").createNewFile()
            File(appDir, ".app-version").writeText(getAppVersionCode().toString())
        } catch (_: Exception) {}
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
            Log.d(TAG, "JS [${msg?.sourceId()}:${msg?.lineNumber()}] ${msg?.message()}")
            return super.onConsoleMessage(msg)
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val src = view ?: return false
            val newTab = createConfiguredWebView()
            newTab.webChromeClient = this
            val transport = src.WebViewTransport()
            transport.setWebView(newTab)
            resultMsg?.obj = transport
            resultMsg?.sendToTarget()
            addTabView(newTab)
            return true
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (view == null || title.isNullOrBlank()) return
            val idx = tabs.indexOfFirst { it.webView == view }
            if (idx >= 0) {
                tabs[idx].title = title
                if (idx == currentIndex) {
                    tabs[idx].tabView.findViewById<TextView>(R.id.tab_title).text = title
                    scrollTabToVisible(idx)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            val intent = fileChooserParams?.createIntent() ?: return false
            fileCallback = filePathCallback
            startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
            return true
        }
    }

    private fun createTab(url: String, fromShare: Boolean = false): WebView {
        val wv = createConfiguredWebView()
        wv.webChromeClient = chromeClient

        addTabView(wv, fromShare)
        wv.loadUrl(url)
        return wv
    }

    private fun createConfiguredWebView(): WebView {
        val wv = WebView(this)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.settings.apply {
            javaScriptEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                val uri = request?.url ?: return false
                val port = ServerState.port
                if (uri.host != null && uri.host in listOf("127.0.0.1", "localhost") && uri.port == port)
                    return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    Log.w(TAG, "No activity to handle URI: $uri")
                }
                return true
            }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                updateNavButtons()
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url?.contains("version=ESV") == true) {
                    Log.i(TAG, "Page started: $url")
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val msg = error?.description?.toString() ?: "Page load error"
                    Log.e(TAG, "Page error ${error?.errorCode}: $msg")
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                updateNavButtons()
                view?.evaluateJavascript(FETCH_TIMEOUT_JS, null)
                view?.let { syncDarkMode(it) }
            }
        }
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val dlRequest = DownloadManager.Request(Uri.parse(url))
            dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val filename = url.substringAfterLast('/').substringBefore('?').substringBefore('#').takeIf { it.isNotBlank() } ?: "download"
            @Suppress("DEPRECATION")
            dlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            val dm = getSystemService(DownloadManager::class.java)
            dm?.enqueue(dlRequest)
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        }
        return wv
    }

    private fun updateNavButtons() {
        if (currentIndex !in tabs.indices) return
        val wv = tabs[currentIndex].webView
        val bfList = wv.copyBackForwardList()
        val hasBack = bfList.currentIndex > 0
        val hasForward = bfList.currentIndex < bfList.size - 1
        // Use alpha instead of enabled=false to preserve ripple feedback
        btnBack.alpha = if (hasBack) 1.0f else 0.3f
        btnForward.alpha = if (hasForward) 1.0f else 0.3f
    }

    private fun isAndroidDarkMode(): Boolean {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun syncDarkMode(wv: WebView) {
        if (currentIndex !in tabs.indices) return
        val url = wv.url ?: return
        val port = ServerState.port
        if (!url.startsWith("http://127.0.0.1:$port")) return
        val dark = isAndroidDarkMode()
        wv.evaluateJavascript(
            """
            (function() {
                if (typeof step === 'undefined' || !step.settings) return;
                var root = document.querySelector(':root');
                var currentBg = root ? root.style.getPropertyValue('--clrBackground') : '';
                var wantBg = $dark ? '#202124' : '#ffffff';
                if (currentBg === wantBg) return;
                var colors = $dark ?
                    {clrText:'#BCC0C3',clrStrongText:'#8ab4f8',clrBackground:'#202124',clrHighlight:'#c58af9',clrHighlightBg:'#800080',clr2ndHover:'#c5d0fb',colorScheme:'dark'} :
                    {clrText:'#5d5d5d',clrStrongText:'#447888',clrBackground:'#ffffff',clrHighlight:'#17758F',clrHighlightBg:'#17758F',clr2ndHover:'#d3d3d3',colorScheme:'normal'};
                root.style.setProperty('--clrText',colors.clrText);
                step.settings.save({clrText:colors.clrText});
                root.style.setProperty('--clrStrongText',colors.clrStrongText);
                step.settings.save({clrStrongText:colors.clrStrongText});
                root.style.setProperty('--clrBackground',colors.clrBackground);
                step.settings.save({clrBackground:colors.clrBackground});
                root.style.setProperty('--clrHighlight',colors.clrHighlight);
                step.settings.save({clrHighlight:colors.clrHighlight});
                root.style.setProperty('--clrHighlightBg',colors.clrHighlightBg);
                step.settings.save({clrHighlightBg:colors.clrHighlightBg});
                root.style.setProperty('--clr2ndHover',colors.clr2ndHover);
                step.settings.save({clr2ndHover:colors.clr2ndHover});
                root.style.setProperty('--clrLexiconFocusBG','#c8d8dc');
                step.settings.save({clrLexiconFocusBG:'#c8d8dc'});
                root.style.setProperty('--clrRelatedWordBg','#b2e5f3');
                step.settings.save({clrRelatedWordBg:'#b2e5f3'});
                $('body,html').css('color-scheme',colors.colorScheme);
            })();
            """.trimIndent(), null
        )
    }

    private fun showTabOverview() {
        if (tabs.isEmpty()) return

        val dialog = Dialog(this, R.style.Theme_STEPBible)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.statusBarColor = getColor(R.color.teal_primary)
        val view = layoutInflater.inflate(R.layout.dialog_tab_overview, null)
        dialog.setContentView(view)

        val grid = view.findViewById<GridView>(R.id.tab_grid)
        val countView = view.findViewById<TextView>(R.id.tab_count)
        countView.text = getString(R.string.tabs_format, tabs.size)
        view.findViewById<ImageButton>(R.id.btn_close_overview).setOnClickListener { dialog.dismiss() }

        val thumbWidth = (resources.displayMetrics.widthPixels / 2) - 24
        val thumbHeight = (thumbWidth * 3) / 2

        val adapter = object : BaseAdapter() {
            override fun getCount() = tabs.size
            override fun getItem(i: Int) = tabs[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
                val v = convert ?: layoutInflater.inflate(R.layout.item_tab_preview, parent, false)
                val title = v.findViewById<TextView>(R.id.tab_title)
                val thumb = v.findViewById<ImageView>(R.id.tab_thumbnail)
                val close = v.findViewById<ImageButton>(R.id.tab_preview_close)
                val tab = tabs[pos]

                title.text = if (tab.title.isBlank()) "STEP Bible" else tab.title
                title.isSelected = pos == currentIndex

                // Generate thumbnail from the visible viewport at scroll (0,0)
                try {
                    val wv = tab.webView
                    val wasGone = wv.visibility == View.GONE
                    if (wasGone) {
                        wv.visibility = View.VISIBLE
                        wv.measure(
                            View.MeasureSpec.makeMeasureSpec(container.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(container.height, View.MeasureSpec.EXACTLY)
                        )
                        wv.layout(0, 0, container.width, container.height)
                    }

                    val w = wv.width
                    val h = wv.height
                    if (w <= 0 || h <= 0) throw Exception("no dimensions")

                    val scale = minOf(thumbWidth.toFloat() / w, thumbHeight.toFloat() / h)
                    val outW = (w * scale).toInt().coerceAtLeast(1)
                    val outH = (h * scale).toInt().coerceAtLeast(1)

                    val old = thumb.drawable as? BitmapDrawable
                    old?.bitmap?.recycle()
                    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.scale(scale, scale)

                    val oldScrollX = wv.scrollX
                    val oldScrollY = wv.scrollY
                    wv.scrollTo(0, 0)
                    wv.draw(canvas)
                    wv.scrollTo(oldScrollX, oldScrollY)
                    if (wasGone) wv.visibility = View.GONE

                    thumb.setImageBitmap(bmp)
                } catch (_: Exception) {
                    thumb.setImageResource(android.R.drawable.ic_menu_search)
                }

                v.setOnClickListener {
                    dialog.dismiss()
                    showTab(pos)
                }
                close.setOnClickListener {
                    closeTab(pos)
                    notifyDataSetChanged()
                    if (tabs.isEmpty()) dialog.dismiss()
                }
                return v
            }
        }

        grid.adapter = adapter
        dialog.show()
    }

    private fun showHistory(back: Boolean) {
        if (currentIndex !in tabs.indices) return
        val wv = tabs[currentIndex].webView
        val list = wv.copyBackForwardList()
        val size = list.size
        val cur = list.currentIndex
        val start = if (back) 0 else cur + 1
        val end = if (back) cur else size
        if (start >= end || size == 0) return

        val indices = mutableListOf<Int>()
        val items = mutableListOf<String>()
        val range = if (back) (start until end).reversed() else (start until end)
        for (i in range) {
            val item = list.getItemAtIndex(i) ?: continue
            indices.add(i)
            val label = if (item.title.isNullOrBlank()) item.url else item.title
            items.add(label)
        }
        if (items.isEmpty()) return

        val anchor = if (back) btnBack else btnForward
        val popup = PopupMenu(this, anchor)
        for ((i, label) in items.withIndex()) {
            popup.menu.add(0, i, 0, label)
        }
        popup.setOnMenuItemClickListener { item ->
            val target = indices[item.itemId]
            val steps = target - cur
            wv.goBackOrForward(steps)
            true
        }
        popup.show()
    }

    private fun goBack() {
        if (currentIndex !in tabs.indices) return
        val wv = tabs[currentIndex].webView
        if (wv.canGoBack()) {
            wv.goBack()
        }
    }

    private fun goForward() {
        if (currentIndex !in tabs.indices) return
        val wv = tabs[currentIndex].webView
        if (wv.canGoForward()) {
            wv.goForward()
        }
    }

    private fun reloadCurrent() {
        if (currentIndex in tabs.indices) {
            tabs[currentIndex].webView.reload()
        }
    }

    private fun addTabView(wv: WebView, fromShare: Boolean = false) {
        val tabView = createTabView("Loading...")

        container.addView(wv)
        wv.visibility = View.GONE

        tabs.add(TabInfo(wv, tabView, "Loading...", fromShare))
        showTab(tabs.size - 1)
    }

    private fun createTabView(title: String): View {
        val tabView = layoutInflater.inflate(R.layout.tab_item, tabBar, false)
        val titleView = tabView.findViewById<TextView>(R.id.tab_title)
        val closeBtn = tabView.findViewById<ImageView>(R.id.tab_close)

        titleView.text = if (title.isBlank()) "STEP Bible" else title
        tabView.setOnClickListener {
            val idx = tabs.indexOfFirst { it.tabView == tabView }
            if (idx >= 0) showTab(idx)
        }
        closeBtn.setOnClickListener {
            if (closingTab) return@setOnClickListener
            closingTab = true
            try {
                val idx = tabs.indexOfFirst { it.tabView == tabView }
                if (idx >= 0) closeTab(idx)
            } finally {
                closingTab = false
            }
        }

        tabBar.addView(tabView)
        return tabView
    }

    private fun showTab(index: Int) {
        if (index !in tabs.indices) return
        if (currentIndex >= 0 && currentIndex < tabs.size) {
            tabs[currentIndex].webView.visibility = View.GONE
        }
        tabs[index].webView.visibility = View.VISIBLE
        currentIndex = index
        updateTabBarSelection()
        tabs[index].tabView.findViewById<TextView>(R.id.tab_title).text = tabs[index].title
        updateNavButtons()
        scrollTabToVisible(index)
    }

    private fun scrollTabToVisible(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        tab.scrollListener?.let { tab.tabView.removeOnLayoutChangeListener(it) }
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldRight: Int, oldTop: Int, oldBottom: Int) {
                v?.removeOnLayoutChangeListener(this)
                val visibleRight = tabScroller.scrollX + tabScroller.width
                if (right > visibleRight) {
                    tabScroller.smoothScrollBy(right - visibleRight, 0)
                } else if (left < tabScroller.scrollX) {
                    tabScroller.smoothScrollTo(left, 0)
                }
            }
        }
        tab.scrollListener = listener
        tab.tabView.addOnLayoutChangeListener(listener)
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices || tabs.size <= 1) return
        val tab = tabs[index]
        tab.scrollListener?.let { tab.tabView.removeOnLayoutChangeListener(it) }
        container.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.onPause()
        tab.webView.destroy()
        tabBar.removeView(tab.tabView)
        tabs.removeAt(index)
        val newIndex = when {
            currentIndex > index -> currentIndex - 1
            currentIndex >= tabs.size -> tabs.size - 1
            else -> currentIndex
        }
        if (tabs.isNotEmpty()) showTab(newIndex)
    }

    private fun updateTabBarSelection() {
        for (i in tabs.indices) {
            val tab = tabs[i]
            val titleView = tab.tabView.findViewById<TextView>(R.id.tab_title)
            val indicator = tab.tabView.findViewById<View>(R.id.tab_indicator)
            val isSelected = i == currentIndex
            titleView.setTextColor(if (isSelected) android.graphics.Color.WHITE else ContextCompat.getColor(this, R.color.tab_title_inactive))
            indicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            tab.tabView.findViewById<ImageView>(R.id.tab_close).alpha = if (isSelected) 1.0f else 0.5f
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith("onBackPressedDispatcher.onBackPressed()"))
    override fun onBackPressed() {
        if (currentIndex in tabs.indices) {
            val wv = tabs[currentIndex].webView
            if (wv.canGoBack()) {
                wv.goBack()
                return
            }
            if (tabs[currentIndex].fromShare) {
                closeTab(currentIndex)
            }
        }
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val result = if (resultCode == RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        super.onPause()
        saveTabState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pending_share_url", pendingShareUrl)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.containsKey("pending_share_url")) {
            pendingShareUrl = savedInstanceState.getString("pending_share_url")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        for (tab in tabs) {
            syncDarkMode(tab.webView)
        }
    }

    override fun onDestroy() {
        stopHealthCheck()
        serverThread?.interrupt()
        serverThread = null
        serverPollThread?.interrupt()
        serverPollThread = null
        for (tab in tabs) {
            container.removeView(tab.webView)
            tab.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getClipData()?.getItemAt(0)?.text?.toString()
            ?: return
        val cn = intent.component?.className
        if (cn == null) {
            Toast.makeText(this, "No Bible reference found", Toast.LENGTH_SHORT).show()
            return
        }
        val isMulti = cn.endsWith(".ShareLookupMulti")
        val refs = extractBibleReference(Html.fromHtml(sharedText, Html.FROM_HTML_MODE_COMPACT).toString())
        if (refs.isEmpty()) {
            Toast.makeText(this, "No Bible reference found", Toast.LENGTH_SHORT).show()
            return
        }
        val combined = refs.joinToString(", ")
        val parsed = parseReference(combined)
        if (parsed.isBlank()) {
            Toast.makeText(this, "No Bible reference found", Toast.LENGTH_SHORT).show()
            return
        }
        val encodedRef = Uri.encode(parsed).replace("@", "%40")
        Log.i(TAG, "Share: refs=$refs -> combined='$combined' -> parsed='$parsed'")
        val port = ServerState.port
        val q = if (isMulti) {
            "version=ESV@version=SBLG@version=THOT@reference=$encodedRef"
        } else {
            "version=ESV@reference=$encodedRef"
        }
        val options = if (isMulti) "HVUG" else "VUGH"
        val url = "http://127.0.0.1:$port/?q=$q&options=$options&display=INTERLEAVED"
        Log.d(TAG, "Share URL: $url")
        if (toolbar.visibility == View.VISIBLE) {
            createTab(url, fromShare = true)
        } else {
            pendingShareUrl = url
        }
    }

    private fun saveTabState() {
        if (tabBar.visibility != View.VISIBLE) return
        var remapped = 0
        val json = JSONArray().apply {
            for ((i, tab) in tabs.withIndex()) {
                val url = tab.webView.url ?: continue
                if (i == currentIndex) remapped = length()
                put(JSONObject().apply {
                    put("url", url)
                    put("title", tab.title)
                })
            }
        }.toString()
        @Suppress("DEPRECATION")
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString("saved_tabs", json)
            .putInt("saved_active", remapped)
            .apply()
    }

    private fun loadSavedTabState() {
        @Suppress("DEPRECATION")
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val json = prefs.getString("saved_tabs", null) ?: return
        pendingRestoreIndex = prefs.getInt("saved_active", 0)
        pendingRestoreData = try {
            val arr = JSONArray(json)
            if (arr.length() == 0) null else {
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(obj.getString("url") to obj.getString("title"))
                }
                list
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun restoreSavedTabs(data: List<Pair<String, String>>, activeIndex: Int) {
        for ((url, title) in data) {
            createTab(rebuildUrl(url))
            val tab = tabs.last()
            tab.title = title
            tab.tabView.findViewById<TextView>(R.id.tab_title).text = title
        }
        showTab(activeIndex.coerceIn(0, tabs.size - 1))
    }

    private fun rebuildUrl(saved: String): String = rebuildUrl(saved, ServerState.port)

    companion object {
        private const val TAG = "STEP"
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val MAX_RETRIES = 2
        private const val serverPort = 8989
        private const val FETCH_TIMEOUT_JS = """(function(){
  var o=window.fetch;
  window.fetch=function(u,i){
    i=i||{};if(i.signal)return o.call(this,u,i);
    var c=new AbortController();i.signal=c.signal;
    var t=setTimeout(function(){c.abort()},5000);
    return o.call(this,u,i).finally(function(){clearTimeout(t)});
  };
})();"""
    }
}

internal fun extractBibleReference(text: String): List<String> {
    var t = text
    t = t.replace(Regex("https?://\\S+"), " ")
    t = t.replace(Regex("[\"'`\u201C\u201D\u2018\u2019]"), " ")
    t = t.replace(Regex("\\s+"), " ").trim()
    val regex = Regex("(?:\\d+(?:\\s*(?:st|nd|rd|th))?\\s+)?[A-Z][a-z]+\\.?\\s*\\d+(?::\\d+(?:[-–—,]\\d+)*(?:;\\d+(?::\\d+(?:[-–—,]\\d+)*)?)*)?")
    return regex.findAll(t).map { it.value }.toList()
}

internal fun parseReference(input: String): String {
    var p = input
    p = p.replace("+", " ")
    p = p.replace(Regex("[);]\\s*\\.\\d+"), "")
    p = p.replace(Regex("[()\\[\\]{}]"), "")
    p = p.replace(Regex("\\s+"), " ").trim()
    p = p.replace(Regex("(\\d+)(st|nd|rd|th)\\s+", RegexOption.IGNORE_CASE), "$1 ")
    p = p.replace(Regex("([a-zA-Z]+)\\s*[\\.:]\\s*(\\d+)", RegexOption.IGNORE_CASE), "$1 $2")
    p = p.replace(Regex("(\\d+)\\s*[\\.:]\\s*(\\d+)"), "$1:$2")
    p = p.replace(Regex("\\s*[-–—]\\s*"), "-")
    p = p.replace(Regex(",?\\s*and\\s*", RegexOption.IGNORE_CASE), ",")
    p = p.replace(Regex(",\\s+"), ",")
    p = p.replace(Regex("\\b(cf|cf\\.|eg|eg\\.|see also|see|for example|for instance|e\\.g\\.|i\\.e\\.|such as)\\b\\.?\\s*", RegexOption.IGNORE_CASE), ", ")
    p = p.replace(Regex(";\\s*"), ", ")
    p = p.replace(Regex("\\.(\\s*)"), "$1")
    p = p.replace(Regex(",\\s*,"), ",")
    p = p.replace(Regex("\\s*,\\s*"), ",")
    return p
}

internal fun rebuildUrl(saved: String, port: Int = ServerState.port): String {
    if (saved.startsWith("http://") || saved.startsWith("https://")) {
        return saved
            .replace(Regex("http://127\\.0\\.0\\.1:\\d+"), "http://127.0.0.1:$port")
            .replace(Regex("http://localhost:\\d+"), "http://localhost:$port")
    }
    return "http://127.0.0.1:$port$saved"
}

object ServerState {
    @Volatile var port = 8989
    @Volatile var jvmStarted = false
}
