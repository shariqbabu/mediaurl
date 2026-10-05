package com.mediaurl

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
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

    private var isDesktopMode = false
    private val DEFAULT_USER_AGENT by lazy {
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
    private val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupListeners()
        setupBackHandling()

        // Listen for live stream count updates to refresh badge
        StreamExtractor.setStreamCountListener { count ->
            runOnUiThread {
                tvStreamBadgeText.text = "🎬 Streams ($count)"
            }
        }

        // Load default homepage
        loadInputUrl("https://www.google.com")
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
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
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Add JavaScript Interface Bridge for auto-sniffer communication
        webView.addJavascriptInterface(MediaUrlBridge(), "MediaUrlBridge")

        // 1. In-App WebView Client (No external redirects, network interception)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val scheme = request.url?.scheme?.lowercase() ?: ""

                if (scheme == "http" || scheme == "https") {
                    return false // Let WebView load it internally
                }

                // Handle intent://, market://, tg:// safely
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                    return true
                } catch (_: Exception) {
                    return true // Block unhandled schemes without crashing
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString()
                if (reqUrl != null && StreamExtractor.isMediaStreamUrl(reqUrl)) {
                    val referer = request.requestHeaders?.get("Referer") ?: webView.url.orEmpty()
                    val ua = request.requestHeaders?.get("User-Agent") ?: webView.settings.userAgentString
                    StreamExtractor.addStream(
                        url = reqUrl,
                        pageUrl = webView.url.orEmpty(),
                        userAgent = ua,
                        referer = referer
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                if (url != null && !etUrl.hasFocus()) {
                    etUrl.setText(url)
                }
                // Inject early sniffer hook
                view?.evaluateJavascript(StreamExtractor.getSnifferJavaScript(), null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                updateNavButtons()
                // Inject full sniffer hook
                view?.evaluateJavascript(StreamExtractor.getSnifferJavaScript(), null)
            }
        }

        // 2. WebChromeClient for smooth loading progress & title updates
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!etUrl.hasFocus() && !title.isNullOrBlank() && !title.startsWith("http")) {
                    // Update hint or text
                }
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

        // Top Navigation Buttons
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

        // Bottom Action Buttons
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
                    CookieManager.getInstance().removeAllCookies(null)
                    webView.clearCache(true)
                    Toast.makeText(this, "Cookies and cache cleared", Toast.LENGTH_SHORT).show()
                    true
                }
                4 -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, webView.url.orEmpty())
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
                runOnUiThread {
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
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // JavaScript Interface Bridge (Called from Injected Sniffer)
    // ------------------------------------------------------------------

    inner class MediaUrlBridge {
        @JavascriptInterface
        fun onStreamDetected(url: String?, source: String?, referer: String?) {
            if (url.isNullOrBlank()) return
            val added = StreamExtractor.addStream(
                url = url,
                pageUrl = webView.url.orEmpty(),
                userAgent = webView.settings.userAgentString,
                referer = referer ?: webView.url.orEmpty(),
                customFormat = null
            )
            if (added) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "🎬 Captured: ${StreamExtractor.detectFormat(url)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
