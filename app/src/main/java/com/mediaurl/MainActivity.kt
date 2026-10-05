package com.mediaurl

import android.app.Dialog
import android.content.Context
import android.content.Intent
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
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mediaurl.adapter.BookmarksAdapter
import com.mediaurl.adapter.StreamsAdapter
import com.mediaurl.manager.BookmarkManager
import com.mediaurl.manager.ScriptManager
import com.mediaurl.manager.StreamExtractor
import com.mediaurl.manager.SupabaseSyncManager
import com.mediaurl.model.BookmarkItem
import com.mediaurl.model.DetectedStream
import com.mediaurl.service.ExtractorService

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var etUrl: EditText
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnBookmark: ImageButton
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
    private var isBackgroundModeEnabled = false

    @Volatile
    private var cachedPageUrl: String = "https://www.google.com"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastToastTime = 0L

    private val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val PREFS_NAME = "mediaurl_prefs"
    private val KEY_BG_MODE = "bg_mode_enabled"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isBackgroundModeEnabled = prefs.getBoolean(KEY_BG_MODE, false)

            initViews()
            setupWebView()
            setupListeners()
            setupBackHandling()

            // Update streams badge whenever new media URLs are captured
            StreamExtractor.setStreamCountListener { count ->
                tvStreamBadgeText.text = "🎬 Streams ($count)"
            }

            if (isBackgroundModeEnabled) {
                ExtractorService.start(this)
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
        btnBookmark = findViewById(R.id.btnBookmark)
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
                    val added = StreamExtractor.addStream(
                        url = reqUrl,
                        pageUrl = cachedPageUrl,
                        userAgent = ua,
                        referer = referer
                    )
                    if (added) {
                        checkAutoSyncToSupabase(reqUrl, referer)
                    }
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
                    updateBookmarkIcon(url)
                }
                // Inject early sniffer hook
                try {
                    view?.evaluateJavascript(StreamExtractor.getSnifferJavaScript(), null)
                } catch (_: Exception) {}
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                if (url != null) {
                    cachedPageUrl = url
                    updateBookmarkIcon(url)
                }
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

        // Bookmark Toggle / Manage
        btnBookmark.setOnClickListener {
            val title = webView.title.orEmpty().ifBlank { cachedPageUrl }
            val added = BookmarkManager.toggleBookmark(this, title, cachedPageUrl)
            updateBookmarkIcon(cachedPageUrl)
            if (added) {
                Toast.makeText(this, "⭐ Saved to Bookmarks", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Removed from Bookmarks", Toast.LENGTH_SHORT).show()
            }
        }

        btnBookmark.setOnLongClickListener {
            showBookmarksDialog()
            true
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

    private fun updateBookmarkIcon(url: String) {
        val isBookmarked = BookmarkManager.isBookmarked(this, url)
        if (isBookmarked) {
            btnBookmark.setImageResource(R.drawable.ic_bookmark)
            btnBookmark.setColorFilter(Color.parseColor("#FFD700"))
        } else {
            btnBookmark.setImageResource(R.drawable.ic_bookmark_border)
            btnBookmark.setColorFilter(Color.parseColor("#8B949E"))
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
        updateBookmarkIcon(targetUrl)
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
        popup.menu.add(0, 2, 1, "⭐ Bookmarks Manager")
        popup.menu.add(0, 3, 2, if (isBackgroundModeEnabled) "🟢 Background Mode: ON" else "⚪ Background Mode: OFF")
        popup.menu.add(0, 4, 3, "⚡ Supabase Live Sync Config")
        popup.menu.add(0, 5, 4, "🧹 Clear All Detected Streams")
        popup.menu.add(0, 6, 5, "🍪 Clear Cookies & Cache")
        popup.menu.add(0, 7, 6, "📤 Share Current URL")

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
                    showBookmarksDialog()
                    true
                }
                3 -> {
                    toggleBackgroundMode()
                    true
                }
                4 -> {
                    showSupabaseConfigDialog()
                    true
                }
                5 -> {
                    StreamExtractor.clearStreams()
                    Toast.makeText(this, "Streams list cleared", Toast.LENGTH_SHORT).show()
                    true
                }
                6 -> {
                    try {
                        CookieManager.getInstance().removeAllCookies(null)
                        webView.clearCache(true)
                        Toast.makeText(this, "Cookies and cache cleared", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    true
                }
                7 -> {
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

    private fun toggleBackgroundMode() {
        isBackgroundModeEnabled = !isBackgroundModeEnabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BG_MODE, isBackgroundModeEnabled)
            .apply()

        if (isBackgroundModeEnabled) {
            ExtractorService.start(this)
            Toast.makeText(this, "🟢 Background Sniffing Enabled (Service Started)", Toast.LENGTH_LONG).show()
        } else {
            ExtractorService.stop(this)
            Toast.makeText(this, "⚪ Background Mode Disabled", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Modal Dialogs: Bookmarks, Streams List, JS Script & Supabase
    // ------------------------------------------------------------------

    private fun showBookmarksDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_bookmarks)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvBookmarksDialogTitle)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseBookmarks)
        val btnDone = dialog.findViewById<Button>(R.id.btnDoneBookmarks)
        val btnAddCurrent = dialog.findViewById<Button>(R.id.btnAddCurrentBookmark)
        val rvBookmarks = dialog.findViewById<RecyclerView>(R.id.rvBookmarks)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvEmptyBookmarks)

        fun refreshList() {
            val list = BookmarkManager.getBookmarks(this)
            tvTitle.text = "⭐ Bookmarks (${list.size})"

            if (list.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvBookmarks.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvBookmarks.visibility = View.VISIBLE
            }

            val adapter = BookmarksAdapter(
                bookmarkList = list,
                onItemClick = { bookmark ->
                    dialog.dismiss()
                    loadInputUrl(bookmark.url)
                },
                onDeleteClick = { bookmark ->
                    BookmarkManager.removeBookmark(this, bookmark.url)
                    updateBookmarkIcon(cachedPageUrl)
                    refreshList()
                    Toast.makeText(this, "Bookmark deleted", Toast.LENGTH_SHORT).show()
                }
            )
            rvBookmarks.layoutManager = LinearLayoutManager(this)
            rvBookmarks.adapter = adapter
        }

        btnAddCurrent.setOnClickListener {
            val pageTitle = webView.title.orEmpty().ifBlank { cachedPageUrl }
            val added = BookmarkManager.addBookmark(this, pageTitle, cachedPageUrl)
            if (added) {
                updateBookmarkIcon(cachedPageUrl)
                refreshList()
                Toast.makeText(this, "⭐ Added to Bookmarks", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Already bookmarked", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnDone.setOnClickListener { dialog.dismiss() }
        refreshList()
        dialog.show()
    }

    private fun showStreamsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_streams_list)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvDialogTitle)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseDialog)
        val btnDone = dialog.findViewById<Button>(R.id.btnDoneStreams)
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
            val adapter = StreamsAdapter(
                streamList = streams,
                onSendToSupabase = { stream ->
                    showSendToSupabaseDialog(stream)
                },
                onItemClick = { stream ->
                    StreamExtractor.copyToClipboard(this, stream.url, "Stream URL")
                }
            )
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

        btnDone.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showScriptDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_script_runner)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

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

    private fun showSupabaseConfigDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_supabase_config)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        val etUrl = dialog.findViewById<EditText>(R.id.etSupabaseUrl)
        val etKey = dialog.findViewById<EditText>(R.id.etSupabaseKey)
        val etTable = dialog.findViewById<EditText>(R.id.etTableName)
        val switchAuto = dialog.findViewById<SwitchMaterial>(R.id.switchAutoSync)
        val tvStatus = dialog.findViewById<TextView>(R.id.tvSupabaseStatus)
        val btnTest = dialog.findViewById<Button>(R.id.btnTestSupabase)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveSupabase)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseSupabaseConfig)

        etUrl.setText(SupabaseSyncManager.getSupabaseUrl(this))
        etKey.setText(SupabaseSyncManager.getSupabaseKey(this))
        etTable.setText(SupabaseSyncManager.getTableName(this))
        switchAuto.isChecked = SupabaseSyncManager.isAutoSyncEnabled(this)

        btnTest.setOnClickListener {
            val u = etUrl.text.toString().trim()
            val k = etKey.text.toString().trim()
            val t = etTable.text.toString().trim()

            tvStatus.text = "Testing connection..."
            tvStatus.setTextColor(Color.parseColor("#8B949E"))

            SupabaseSyncManager.testConnection(this, u, k, t) { success, msg ->
                tvStatus.text = msg
                tvStatus.setTextColor(if (success) Color.parseColor("#4CAF50") else Color.parseColor("#FF5722"))
            }
        }

        btnSave.setOnClickListener {
            val u = etUrl.text.toString().trim()
            val k = etKey.text.toString().trim()
            val t = etTable.text.toString().trim()
            val auto = switchAuto.isChecked

            SupabaseSyncManager.saveConfig(this, u, k, t, auto)
            Toast.makeText(this, "Supabase config saved!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSendToSupabaseDialog(stream: DetectedStream) {
        if (!SupabaseSyncManager.isConfigured(this)) {
            Toast.makeText(this, "Please configure Supabase in settings first", Toast.LENGTH_LONG).show()
            showSupabaseConfigDialog()
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_send_to_supabase)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        val etChannelId = dialog.findViewById<EditText>(R.id.etTargetChannelId)
        val tvPreview = dialog.findViewById<TextView>(R.id.tvStreamUrlPreview)
        val etReferer = dialog.findViewById<EditText>(R.id.etRefererHeader)
        val tvStatus = dialog.findViewById<TextView>(R.id.tvSendStatus)
        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmSendSupabase)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseSendDialog)

        // Pre-fill detected channel ID
        val detectedChannel = SupabaseSyncManager.autoDetectChannelId(
            streamUrl = stream.url,
            pageUrl = stream.pageUrl,
            title = webView.title.orEmpty()
        )
        if (detectedChannel.isNotBlank()) {
            etChannelId.setText(detectedChannel)
        }

        tvPreview.text = stream.url
        etReferer.setText(stream.referer.ifBlank { "https://playsza.xyz/" })

        btnConfirm.setOnClickListener {
            val chId = etChannelId.text.toString().trim()
            val ref = etReferer.text.toString().trim()

            if (chId.isBlank()) {
                Toast.makeText(this, "Please enter Channel ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvStatus.text = "Pushing update to Supabase table '${SupabaseSyncManager.getTableName(this)}'..."
            tvStatus.setTextColor(Color.parseColor("#8B949E"))
            btnConfirm.isEnabled = false

            SupabaseSyncManager.updateChannel(
                context = this,
                channelId = chId,
                streamUrl = stream.url,
                referer = ref
            ) { success, msg ->
                btnConfirm.isEnabled = true
                tvStatus.text = msg
                tvStatus.setTextColor(if (success) Color.parseColor("#4CAF50") else Color.parseColor("#FF5722"))
                if (success) {
                    Toast.makeText(this, "✅ Updated '$chId' in Supabase!", Toast.LENGTH_SHORT).show()
                    mainHandler.postDelayed({ dialog.dismiss() }, 1200)
                }
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun checkAutoSyncToSupabase(streamUrl: String, referer: String) {
        if (!SupabaseSyncManager.isAutoSyncEnabled(this) || !SupabaseSyncManager.isConfigured(this)) return

        val detectedChannel = SupabaseSyncManager.autoDetectChannelId(
            streamUrl = streamUrl,
            pageUrl = cachedPageUrl,
            title = webView.title.orEmpty()
        )
        if (detectedChannel.isNotBlank()) {
            SupabaseSyncManager.updateChannel(
                context = this,
                channelId = detectedChannel,
                streamUrl = streamUrl,
                referer = referer
            ) { success, msg ->
                if (success) {
                    mainHandler.post {
                        Toast.makeText(this, "⚡ Auto-synced '$detectedChannel' to Supabase!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
            val effectiveReferer = referer ?: cachedPageUrl
            val added = StreamExtractor.addStream(
                url = url,
                pageUrl = cachedPageUrl,
                userAgent = ua,
                referer = effectiveReferer,
                customFormat = null
            )

            if (added) {
                checkAutoSyncToSupabase(url, effectiveReferer)

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

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
            webView.resumeTimers()
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        if (!isBackgroundModeEnabled) {
            try {
                webView.onPause()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isBackgroundModeEnabled) {
            try {
                webView.destroy()
            } catch (_: Exception) {}
        }
    }
}
