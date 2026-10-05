package com.mediaurl

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mediaurl.adapter.StreamsAdapter
import com.mediaurl.manager.ScriptManager
import com.mediaurl.manager.StreamExtractor

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var etUrl: EditText
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStreamsList: View
    private lateinit var tvStreamBadgeText: TextView
    private lateinit var btnScriptInject: View

    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isDesktopMode = false

    @Volatile
    private var cachedPageUrl: String = "https://www.google.com"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastToastTime = 0L

    private val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            initViews()
            setupWebView()
            setupListeners()
            setupBackHandling()

            // Update streams badge whenever new media URLs are captured
            StreamExtractor.setStreamCountListener { count ->
                tvStreamBadgeText.text = "🎬 Streams ($count)"
            }

            // Load default home
            loadInputUrl("https://www.google.com")
        } catch (e: Exception) {
            Toast.makeText(this, "Init Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        etUrl = findViewById(R.id.etUrl)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnMore = findViewById(R.id.btnMore)
        progressBar = findViewById(R.id.progressBar)
        btnStreamsList = findViewById(R.id.btnStreamsList)
        tvStreamBadgeText = findViewById(R.id.tvStreamBadgeText)
        btnScriptInject = findViewById(R.id.btnScriptInject)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = DEFAULT_USER_AGENT
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
        }

        try {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        } catch (_: Exception) {}

        // Add JavaScript Interface Bridge for injected sniffer
        webView.addJavascriptInterface(MediaUrlBridge(), "MediaUrlBridge")

        // 1. In-App WebView Client
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val scheme = request.url?.scheme?.lowercase() ?: ""

                if (scheme == "http" || scheme == "https") {
                    cachedPageUrl = url
                    return false // Load internally inside app WebView
                }

                // Handle intent://, market://, tg://, etc. without crashing
                return try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                    true
                } catch (_: Exception) {
                    true // Block unhandled schemes silently
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString()
                if (reqUrl != null && StreamExtractor.isMediaStreamUrl(reqUrl)) {
                    val referer = request.requestHeaders?.get("Referer") ?: cachedPageUrl
                    val ua = request.requestHeaders?.get("User-Agent") ?: if (isDesktopMode) DESKTOP_USER_AGENT else DEFAULT_USER_AGENT
                    StreamExtractor.addStream(
                        url = reqUrl,
                        pageUrl = cachedPageUrl,
                        userAgent = ua,
                        referer = referer
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // Allow dynamic CDN streaming certs without aborting video player
                handler?.proceed()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                if (url != null) {
                    cachedPageUrl = url
                    if (!etUrl.hasFocus()) {
                        etUrl.setText(url)
                    }
                }
                // Inject early sniffer hook
                try {
                    view?.evaluateJavascript(StreamExtractor.getSnifferJavaScript(), null)
                } catch (_: Exception) {}
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                if (url != null) cachedPageUrl = url
                updateNavButtons()

                // Inject full sniffer hook
                try {
                    view?.evaluateJavascript(StreamExtractor.getSnifferJavaScript(), null)
                } catch (_: Exception) {}
            }
        }

        // 2. WebChromeClient with Fullscreen HTML5 Video Support
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customVideoView = view
                customViewCallback = callback

                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                topBar.visibility = View.GONE
                bottomBar.visibility = View.GONE

                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            override fun onHideCustomView() {
                if (customVideoView == null) return

                fullscreenContainer.visibility = View.GONE
                fullscreenContainer.removeAllViews()
                customVideoView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                webView.visibility = View.VISIBLE
                topBar.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE

                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
    }

    private fun setupListeners() {
        // URL / Search Input Handling
        etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = etUrl.text.toString().trim()
                if (input.isNotBlank()) {
                    loadInputUrl(input)
                    hideKeyboard()
                }
                return@setOnEditorActionListener true
            }
            false
        }

        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearUrl.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearUrl.setOnClickListener {
            etUrl.setText("")
            etUrl.requestFocus()
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }

        btnForward.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }

        btnRefresh.setOnClickListener {
            webView.reload()
        }

        btnMore.setOnClickListener { view ->
            showOptionsMenu(view)
        }

        btnStreamsList.setOnClickListener {
            showStreamsDialog()
        }

        btnScriptInject.setOnClickListener {
            showScriptDialog()
        }
    }

    private fun loadInputUrl(input: String) {
        val trimmed = input.trim()
        val targetUrl = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
        cachedPageUrl = targetUrl
        webView.loadUrl(targetUrl)
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (webView.canGoBack()) 1.0f else 0.4f
        btnForward.alpha = if (webView.canGoForward()) 1.0f else 0.4f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etUrl.windowToken, 0)
        etUrl.clearFocus()
    }

    private fun showOptionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (isDesktopMode) "📱 Mobile Mode" else "💻 Desktop Site")
        popup.menu.add(0, 2, 1, "🧹 Clear All Detected Streams")
        popup.menu.add(0, 3, 2, "🍪 Clear Cookies & Cache")
        popup.menu.add(0, 4, 3, "📤 Share Current URL")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    isDesktopMode = !isDesktopMode
                    webView.settings.userAgentString = if (isDesktopMode) DESKTOP_USER_AGENT else DEFAULT_USER_AGENT
                    webView.reload()
                    Toast.makeText(this, if (isDesktopMode) "Switched to Desktop Mode" else "Switched to Mobile Mode", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    StreamExtractor.clearStreams()
                    Toast.makeText(this, "Streams list cleared", Toast.LENGTH_SHORT).show()
                    true
                }
                3 -> {
                    try {
                        CookieManager.getInstance().removeAllCookies(null)
                        webView.clearCache(true)
                        Toast.makeText(this, "Cookies and cache cleared", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    true
                }
                4 -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, cachedPageUrl)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share URL"))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ------------------------------------------------------------------
    // Modal Dialogs: 1. Streams List & 2. Custom JS Script Automation
    // ------------------------------------------------------------------

    private fun showStreamsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_streams_list)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvDialogTitle)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseDialog)
        val btnClearAll = dialog.findViewById<ImageButton>(R.id.btnClearAll)
        val rvStreams = dialog.findViewById<RecyclerView>(R.id.rvStreams)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvEmptyStreams)

        val streams = StreamExtractor.getDetectedStreams()
        tvTitle.text = "Detected Streams (${streams.size})"

        if (streams.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvStreams.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvStreams.visibility = View.VISIBLE
            val adapter = StreamsAdapter(streams) { stream ->
                StreamExtractor.copyToClipboard(this, stream.url, "Stream URL")
            }
            rvStreams.layoutManager = LinearLayoutManager(this)
            rvStreams.adapter = adapter
        }

        btnClearAll.setOnClickListener {
            StreamExtractor.clearStreams()
            dialog.dismiss()
            Toast.makeText(this, "Streams cleared", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showScriptDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_script_runner)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val etScriptCode = dialog.findViewById<EditText>(R.id.etScriptCode)
        val tvResult = dialog.findViewById<TextView>(R.id.tvScriptResult)
        val btnRun = dialog.findViewById<Button>(R.id.btnRunScript)
        val btnClear = dialog.findViewById<Button>(R.id.btnClearScript)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseScript)

        // Preset buttons
        val btnPresetPlay = dialog.findViewById<Button>(R.id.btnPresetPlayVideo)
        val btnPresetClick = dialog.findViewById<Button>(R.id.btnPresetClickSelector)
        val btnPresetRemove = dialog.findViewById<Button>(R.id.btnPresetRemoveAds)
        val btnPresetExtract = dialog.findViewById<Button>(R.id.btnPresetExtractTags)
        val btnPresetCookies = dialog.findViewById<Button>(R.id.btnPresetGetCookies)

        btnPresetPlay.setOnClickListener { etScriptCode.setText(ScriptManager.PRESET_AUTO_PLAY) }
        btnPresetClick.setOnClickListener { etScriptCode.setText(ScriptManager.PRESET_CLICK_SELECTOR) }
        btnPresetRemove.setOnClickListener { etScriptCode.setText(ScriptManager.PRESET_REMOVE_OVERLAYS) }
        btnPresetExtract.setOnClickListener { etScriptCode.setText(ScriptManager.PRESET_EXTRACT_TAGS) }
        btnPresetCookies.setOnClickListener { etScriptCode.setText(ScriptManager.PRESET_GET_COOKIES) }

        btnRun.setOnClickListener {
            val script = etScriptCode.text.toString().trim()
            if (script.isBlank()) {
                Toast.makeText(this, "Please enter JS code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvResult.text = "Executing script..."
            webView.evaluateJavascript(script) { result ->
                mainHandler.post {
                    val cleanResult = result?.removeSurrounding("\"")?.replace("\\n", "\n")?.replace("\\\"", "\"")
                    tvResult.text = "Result:\n${cleanResult ?: "null"}"
                    Toast.makeText(this, "Script Executed!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnClear.setOnClickListener {
            etScriptCode.setText("")
            tvResult.text = "Console: Cleared"
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customVideoView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // JavaScript Interface Bridge (Thread-Safe Sniffer)
    // ------------------------------------------------------------------

    inner class MediaUrlBridge {
        @JavascriptInterface
        fun onStreamDetected(url: String?, source: String?, referer: String?) {
            if (url.isNullOrBlank()) return

            val ua = if (isDesktopMode) DESKTOP_USER_AGENT else DEFAULT_USER_AGENT
            val added = StreamExtractor.addStream(
                url = url,
                pageUrl = cachedPageUrl,
                userAgent = ua,
                referer = referer ?: cachedPageUrl,
                customFormat = null
            )

            if (added) {
                val now = System.currentTimeMillis()
                if (now - lastToastTime > 2500) {
                    lastToastTime = now
                    mainHandler.post {
                        Toast.makeText(
                            this@MainActivity,
                            "🎬 Detected: ${StreamExtractor.detectFormat(url)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webView.destroy()
        } catch (_: Exception) {}
    }
}
