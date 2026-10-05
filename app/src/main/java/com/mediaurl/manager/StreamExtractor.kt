package com.mediaurl.manager

import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response

data class DetectedStream(
    val url: String,
    val type: String, // m3u8, mpd, mp4, ts
    val timestamp: Long = System.currentTimeMillis()
)

object StreamExtractor {

    private val detectedStreams = mutableListOf<DetectedStream>()
    private val streamPattern = Regex("\\.(m3u8|mpd|mp4|ts)(\\?.*)?$", RegexOption.IGNORE_CASE)

    fun setupInterceptor(webView: WebView) {
        // OkHttp interceptor setup for network monitoring
        val httpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(StreamInterceptor())
            .build()
    }

    fun getDetectedStreams(): List<DetectedStream> = detectedStreams.toList()

    fun addStream(url: String) {
        val type = when {
            url.contains(".m3u8", ignoreCase = true) -> "m3u8"
            url.contains(".mpd", ignoreCase = true) -> "mpd"
            url.contains(".mp4", ignoreCase = true) -> "mp4"
            url.contains(".ts", ignoreCase = true) -> "ts"
            else -> "unknown"
        }

        if (!detectedStreams.any { it.url == url }) {
            detectedStreams.add(DetectedStream(url, type))
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("Stream URL", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun injectScript(webView: WebView, script: String) {
        webView.evaluateJavascript(script) { result ->
            android.util.Log.d("StreamExtractor", "Script result: $result")
        }
    }

    class StreamInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            if (streamPattern.containsMatchIn(url)) {
                addStream(url)
            }

            return chain.proceed(request)
        }
    }
}
