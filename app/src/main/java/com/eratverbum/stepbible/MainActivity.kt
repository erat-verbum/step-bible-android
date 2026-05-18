package com.eratverbum.stepbible

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var container: ViewGroup
    private lateinit var tabBar: LinearLayout
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var loadingText: TextView
    private val tabs = mutableListOf<TabInfo>()
    private var currentIndex = -1
    private var closingTab = false

    private data class TabInfo(
        val webView: WebView,
        val tabView: View,
        var title: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.webview_container)
        tabBar = findViewById(R.id.tab_bar)
        loadingSpinner = findViewById(R.id.loading_spinner)
        loadingText = findViewById(R.id.loading_text)

        startServerService()
    }

    private fun startServerService() {
        val intent = Intent(this, StepServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        waitForServer()
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
        tabBar.visibility = View.VISIBLE
        createTab("http://127.0.0.1:${ServerState.port}/")
    }

    private fun onServerFailed() {
        loadingText.text = getString(R.string.server_failed)
        loadingSpinner.visibility = View.GONE
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
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!url.startsWith("http://127.0.0.1:${ServerState.port}/") &&
                    !url.startsWith("http://localhost:${ServerState.port}/")) {
                    return true // block external URLs
                }
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view != null) {
                    val idx = tabs.indexOfFirst { it.webView == view }
                    if (idx >= 0) {
                        val title = view.title ?: ""
                        tabs[idx].title = title
                        if (idx == currentIndex) {
                            tabs[idx].tabView.findViewById<TextView>(R.id.tab_title).text = title
                        }
                    }
                }
                view?.evaluateJavascript("""
                    (function(){
                        window.stepInternalErrors=0;
                        var origError=console.error;
                        window.onerror=function(){return true};
                        window.addEventListener('unhandledrejection',function(e){e.preventDefault()});
                    })();
                """.trimIndent(), null)
            }
        }
        return wv
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

        titleView.text = if (title.isBlank()) "Loading..." else title
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
        if (currentIndex in tabs.indices && tabs[currentIndex].webView.canGoBack()) {
            tabs[currentIndex].webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        for (tab in tabs) {
            container.removeView(tab.webView)
            tab.webView.destroy()
        }
        tabs.clear()
        // Don't stop the service — the server runs independently.
        // Stopping it here would kill the JVM on rotation.
        super.onDestroy()
    }
}
