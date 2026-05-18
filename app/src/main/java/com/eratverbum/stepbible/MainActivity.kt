package com.eratverbum.stepbible

import android.content.Intent
import android.graphics.Bitmap
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
                try {
                    val url = URL("http://127.0.0.1:${ServerState.port}/")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 500
                    conn.readTimeout = 500
                    conn.connect()
                    if (conn.responseCode == 200) {
                        runOnUiThread { onServerReady() }
                        return@Thread
                    }
                    conn.disconnect()
                } catch (_: Exception) {
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

    private fun createTab(url: String): WebView {
        val wv = createConfiguredWebView()
        val chromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newTab = createConfiguredWebView()
                val transport = view!!.WebViewTransport()
                transport.setWebView(newTab)
                resultMsg?.obj = transport
                resultMsg?.sendToTarget()
                addTabView(newTab, "")
                return true
            }
        }
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
            allowFileAccess = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        wv.webViewClient = object : WebViewClient() {
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
            }
        }
        return wv
    }

    private fun addTabView(wv: WebView, url: String) {
        val tabView = createTabView(tabs.size, url)

        container.addView(wv)
        wv.visibility = View.GONE

        tabs.add(TabInfo(wv, tabView, url))
        showTab(tabs.size - 1)
    }

    private fun createTabView(index: Int, title: String): View {
        val tabView = layoutInflater.inflate(R.layout.tab_item, tabBar, false)
        val titleView = tabView.findViewById<TextView>(R.id.tab_title)
        val closeBtn = tabView.findViewById<ImageView>(R.id.tab_close)

        titleView.text = if (title.isBlank()) "Loading..." else title
        tabView.setOnClickListener { showTab(index) }
        closeBtn.setOnClickListener { closeTab(index) }

        tabBar.addView(tabView)
        return tabView
    }

    private fun showTab(index: Int) {
        if (currentIndex >= 0 && currentIndex < tabs.size) {
            tabs[currentIndex].webView.visibility = View.GONE
        }
        if (index in tabs.indices) {
            tabs[index].webView.visibility = View.VISIBLE
            currentIndex = index
            updateTabBarSelection()
            // Update title bar when switching tabs
            tabs[index].tabView.findViewById<TextView>(R.id.tab_title).text = tabs[index].title
        }
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        val tab = tabs[index]
        container.removeView(tab.webView)
        tab.webView.destroy()
        tabBar.removeView(tab.tabView)
        tabs.removeAt(index)
        val newIndex = when {
            currentIndex <= index && currentIndex > 0 -> currentIndex - 1
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
        stopService(Intent(this, StepServerService::class.java))
        super.onDestroy()
    }
}
