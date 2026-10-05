package com.mediaurl

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.Toast
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

        try {
            setContentView(R.layout.activity_main)

            webView = findViewById(R.id.webView)
            extractButton = findViewById(R.id.extractButton)
            streamListView = findViewById(R.id.streamListView)

            setupWebView()
            setupUI()

            // Load default page
            webView.loadUrl("https://www.google.com")

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        }
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

            if (extractedStreams.isEmpty()) {
                Toast.makeText(this, "No streams detected yet", Toast.LENGTH_SHORT).show()
            }
        }

        streamListView.setOnItemClickListener { _, _, position, _ ->
            val stream = extractedStreams[position]
            StreamExtractor.copyToClipboard(this, stream)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
