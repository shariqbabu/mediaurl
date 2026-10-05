package com.mediaurl.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import com.mediaurl.model.DetectedStream
import java.util.Collections

object StreamExtractor {

    private val streams = Collections.synchronizedList(mutableListOf<DetectedStream>())
    private var streamCountListener: ((Int) -> Unit)? = null

    fun setStreamCountListener(listener: ((Int) -> Unit)?) {
        this.streamCountListener = listener
        listener?.invoke(streams.size)
    }

    fun getDetectedStreams(): List<DetectedStream> {
        return synchronized(streams) { streams.toList() }
    }

    fun clearStreams() {
        synchronized(streams) {
            streams.clear()
        }
        streamCountListener?.invoke(0)
    }

    fun isMediaStreamUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()

        // Ignore standard static images / styling
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".css") || lower.endsWith(".svg") ||
            lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf")
        ) {
            return false
        }

        return lower.contains(".m3u8") ||
                lower.contains(".mpd") ||
                lower.contains(".mp4") ||
                lower.contains(".m4s") ||
                lower.contains(".ts") ||
                lower.contains(".webm") ||
                lower.contains(".flv") ||
                lower.contains(".mkv") ||
                lower.contains("/hls/") ||
                lower.contains("playlist.m3u8") ||
                lower.contains("master.m3u8") ||
                lower.contains("chunklist") ||
                lower.contains("/manifest") ||
                (lower.contains("stream") && (lower.contains("token") || lower.contains("expires=")))
    }

    fun detectFormat(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.contains("/hls/") -> "HLS .m3u8"
            lower.contains(".mpd") -> "DASH .mpd"
            lower.contains(".mp4") -> "MP4 Video"
            lower.contains(".m4s") -> "M4S Segment"
            lower.contains(".ts") -> "TS Segment"
            lower.contains(".webm") -> "WebM Video"
            lower.contains(".mkv") -> "MKV Video"
            else -> "STREAM"
        }
    }

    fun addStream(
        url: String,
        pageUrl: String = "",
        userAgent: String = "",
        referer: String = "",
        customFormat: String? = null
    ): Boolean {
        if (!isMediaStreamUrl(url)) return false

        val cleanUrl = url.trim()
        val format = customFormat ?: detectFormat(cleanUrl)

        synchronized(streams) {
            if (streams.any { it.url.equals(cleanUrl, ignoreCase = true) }) {
                return false
            }
            val stream = DetectedStream(
                url = cleanUrl,
                format = format,
                pageUrl = pageUrl,
                userAgent = userAgent,
                referer = referer.ifBlank { pageUrl }
            )
            streams.add(0, stream)
        }

        streamCountListener?.invoke(streams.size)
        return true
    }

    fun copyToClipboard(context: Context, text: String, label: String = "Media URL") {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    /**
     * Injects JavaScript XHR & Fetch sniffer to intercept network requests and dynamic media tags
     */
    fun getSnifferJavaScript(): String {
        return """
            (function() {
                if (window.__mediaUrlSnifferInjected) return;
                window.__mediaUrlSnifferInjected = true;

                function reportStream(url, source) {
                    if (!url || typeof url !== 'string') return;
                    if (url.startsWith('blob:') || url.startsWith('data:')) return;
                    try {
                        if (window.MediaUrlBridge && window.MediaUrlBridge.onStreamDetected) {
                            window.MediaUrlBridge.onStreamDetected(url, source || 'JS_SNIFFER', window.location.href);
                        }
                    } catch(e) {}
                }

                // 1. Hook XMLHttpRequest
                const origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this._reqUrl = url;
                    reportStream(url, 'XHR');
                    return origOpen.apply(this, arguments);
                };

                // 2. Hook Fetch API
                const origFetch = window.fetch;
                window.fetch = function(input, init) {
                    const url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                    reportStream(url, 'FETCH');
                    return origFetch.apply(this, arguments);
                };

                // 3. Scan DOM for <video>, <source>, <audio>, <iframe> elements
                function scanMediaElements() {
                    document.querySelectorAll('video, audio, source, iframe').forEach(el => {
                        const src = el.src || el.getAttribute('src');
                        if (src) reportStream(src, 'TAG_' + el.tagName);
                    });
                }
                scanMediaElements();
                setInterval(scanMediaElements, 2500);

                // 4. Observe Dynamic DOM Additions
                try {
                    const observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(m) {
                            m.addedNodes.forEach(function(node) {
                                if (node.nodeType === 1) {
                                    if (node.tagName === 'VIDEO' || node.tagName === 'SOURCE' || node.tagName === 'IFRAME') {
                                        reportStream(node.src || node.getAttribute('src'), 'MUTATION');
                                    }
                                    node.querySelectorAll?.('video, source, iframe').forEach(el => {
                                        reportStream(el.src || el.getAttribute('src'), 'MUTATION');
                                    });
                                }
                            });
                        });
                    });
                    observer.observe(document.documentElement || document.body, { childList: true, subtree: true });
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
