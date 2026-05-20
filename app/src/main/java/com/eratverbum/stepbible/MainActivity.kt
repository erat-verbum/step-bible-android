package com.eratverbum.stepbible

import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var container: ViewGroup
    private lateinit var toolbar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var tabScroller: HorizontalScrollView
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var btnNewTab: ImageButton
    private lateinit var btnTabOverview: ImageButton
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var retryButton: Button
    private val tabs = mutableListOf<TabInfo>()
    private var currentIndex = -1
    private var closingTab = false
    private var serverFailed = false

    private data class TabInfo(
        val webView: WebView,
        val tabView: View,
        var title: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebView.enableSlowWholeDocumentDraw()
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.webview_container)
        toolbar = findViewById(R.id.toolbar)
        tabBar = findViewById(R.id.tab_bar)
        tabScroller = findViewById(R.id.tab_scroller)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnReload = findViewById(R.id.btn_reload)
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
        btnNewTab.setOnClickListener { createTab("http://127.0.0.1:${ServerState.port}/") }
        btnTabOverview.setOnClickListener { showTabOverview() }
        retryButton.setOnClickListener { retry() }

        startServerService()
    }

    private fun startServerService() {
        serverFailed = false
        retryButton.visibility = View.GONE
        loadingSpinner.visibility = View.VISIBLE
        loadingText.text = getString(R.string.server_starting)
        val intent = Intent(this, StepServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        waitForServer()
    }

    private fun retry() {
        startServerService()
    }

    private fun waitForServer() {
        Thread {
            var retries = 0
            while (retries < 60) {
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
                Thread.sleep(1000)
                retries++
            }
            runOnUiThread { onServerFailed() }
        }.start()
    }

    private fun onServerReady() {
        loadingSpinner.visibility = View.GONE
        loadingText.visibility = View.GONE
        retryButton.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        tabBar.visibility = View.VISIBLE
        updateNotificationServerRunning()
        createTab("http://127.0.0.1:${ServerState.port}/")
    }

    private fun onServerFailed() {
        serverFailed = true
        loadingSpinner.visibility = View.GONE
        loadingText.text = getString(R.string.server_failed)
        retryButton.visibility = View.VISIBLE
    }

    private fun updateNotificationServerRunning() {
        val intent = Intent(this, StepServerService::class.java).apply {
            action = "SERVER_READY"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private val chromeClient = object : WebChromeClient() {
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
            addTabView(newTab, "")
            return true
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            val intent = fileChooserParams?.createIntent() ?: return false
            startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
            return true
        }
    }

    private fun createTab(url: String): WebView {
        val wv = createConfiguredWebView()
        wv.webChromeClient = chromeClient

        addTabView(wv, url)
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
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onTitleChanged(title: String) {
                runOnUiThread {
                    val idx = tabs.indexOfFirst { it.webView == wv }
                    if (idx >= 0) {
                        tabs[idx].title = title
                        if (idx == currentIndex) {
                            tabs[idx].tabView.findViewById<TextView>(R.id.tab_title).text = title
                        }
                    }
                }
            }
        }, "StepBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                val url = request?.url?.toString() ?: return false
                val port = ServerState.port
                if (url.startsWith("http://127.0.0.1:$port") ||
                    url.startsWith("http://localhost:$port")) {
                    val rest = url.removePrefix("http://127.0.0.1:$port")
                        .removePrefix("http://localhost:$port")
                    if (rest.isEmpty() || rest[0] == '/' || rest[0] == '#' || rest[0] == '?')
                        return false
                }
                return true
            }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                updateNavButtons()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                updateNavButtons()
                if (view != null) {
                    val idx = tabs.indexOfFirst { it.webView == view }
                    if (idx >= 0) {
                        val title = view.title ?: ""
                        tabs[idx].title = title
                        if (idx == currentIndex) {
                            tabs[idx].tabView.findViewById<TextView>(R.id.tab_title).text = title
                        }
                    }
                    view.evaluateJavascript("""
                        (function(){
                            if (window.__stepBridgeReady) return;
                            window.__stepBridgeReady = true;
                            var el = document.querySelector('title');
                            if (el) {
                                new MutationObserver(function() {
                                    StepBridge.onTitleChanged(document.title);
                                }).observe(el, { childList: true, subtree: true });
                            }
                        })();
                    """.trimIndent(), null)
                }
            }
        }
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val dlRequest = DownloadManager.Request(Uri.parse(url))
            dlRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            dlRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, url.substringAfterLast('/'))
            getSystemService(Context.DOWNLOAD_SERVICE)?.let { dm ->
                (dm as DownloadManager).enqueue(dlRequest)
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            }
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

    private fun showTabOverview() {
        if (tabs.isEmpty()) return

        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.white)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Status bar matches the toolbar color
        dialog.window?.statusBarColor = 0xFF6200EE.toInt()
        val view = layoutInflater.inflate(R.layout.dialog_tab_overview, null)
        dialog.setContentView(view)

        val grid = view.findViewById<GridView>(R.id.tab_grid)
        val countView = view.findViewById<TextView>(R.id.tab_count)
        countView.text = "Tabs (${tabs.size})"
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

                // Generate thumbnail from full page content
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

                    val fullH = minOf((wv.contentHeight * wv.scale).toInt(), 20000)
                    if (wv.width <= 0 || fullH <= 0) throw Exception("no content")

                    // Capture full page at full resolution, then scale down
                    val fullBmp = Bitmap.createBitmap(wv.width, fullH, Bitmap.Config.RGB_565)
                    Canvas(fullBmp).apply {
                        wv.draw(this)
                    }
                    val scaled = Bitmap.createScaledBitmap(fullBmp, thumbWidth, thumbHeight, true)
                    fullBmp.recycle()
                    if (wasGone) wv.visibility = View.GONE

                    thumb.setImageBitmap(scaled)
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
        for (i in start until end) {
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
            return
        }
        wv.evaluateJavascript("window.history.back()", ValueCallback { _ -> })
    }

    private fun goForward() {
        if (currentIndex !in tabs.indices) return
        val wv = tabs[currentIndex].webView
        if (wv.canGoForward()) {
            wv.goForward()
        } else {
            wv.evaluateJavascript(
                "if(window.history.length>1)window.history.forward();",
                null
            )
        }
    }

    private fun reloadCurrent() {
        if (currentIndex in tabs.indices) {
            tabs[currentIndex].webView.reload()
        }
    }

    private fun addTabView(wv: WebView, url: String) {
        val tabView = createTabView(url)

        container.addView(wv)
        wv.visibility = View.GONE

        tabs.add(TabInfo(wv, tabView, url))
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
        tabScroller.post { tabScroller.smoothScrollTo(tabs[index].tabView.left, 0) }
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices || tabs.size <= 1) return
        val tab = tabs[index]
        container.removeView(tab.webView)
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
            tabs[i].tabView.alpha = if (i == currentIndex) 1.0f else 0.6f
        }
    }

    override fun onBackPressed() {
        if (currentIndex in tabs.indices) {
            val wv = tabs[currentIndex].webView
            if (wv.canGoBack()) {
                wv.goBack()
                return
            }
            wv.evaluateJavascript("window.history.back()", null)
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        for (tab in tabs) {
            container.removeView(tab.webView)
            tab.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
    }
}
