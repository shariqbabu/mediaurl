package com.mediaurl

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.ListView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.mediaurl.service.ExtractorService
import com.mediaurl.manager.StreamExtractor

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var extractButton: Button
    private lateinit var streamListView: ListView
    private lateinit var streamAdapter: ArrayAdapter<String>
    private val extractedStreams = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        extractButton = findViewById(R.id.extractButton)
        streamListView = findViewById(R.id.streamListView)

        setupWebView()
        setupUI()
        startExtractorService()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        }

        // Network interceptor for stream detection
        StreamExtractor.setupInterceptor(webView)
    }

    private fun setupUI() {
        streamAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, extractedStreams)
        streamListView.adapter = streamAdapter

        extractButton.setOnClickListener {
            extractedStreams.clear()
            StreamExtractor.getDetectedStreams().forEach { stream ->
                extractedStreams.add("${stream.type}: ${stream.url}")
            }
            streamAdapter.notifyDataSetChanged()
        }

        streamListView.setOnItemClickListener { _, _, position, _ ->
            val stream = extractedStreams[position]
            StreamExtractor.copyToClipboard(this, stream)
        }
    }

    private fun startExtractorService() {
        val intent = Intent(this, ExtractorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
