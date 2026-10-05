package com.mediaurl.model

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DetectedStream(
    val url: String,
    val format: String, // HLS, DASH, MP4, TS, DIRECT
    val pageUrl: String = "",
    val userAgent: String = "",
    val referer: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    val domain: String
        get() = try {
            val uri = URI(url)
            uri.host ?: uri.authority ?: "direct"
        } catch (_: Exception) {
            "direct"
        }

    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    val curlCommand: String
        get() {
            val sb = StringBuilder("curl --url '$url'")
            if (referer.isNotBlank()) {
                sb.append(" -H 'Referer: $referer'")
            }
            if (userAgent.isNotBlank()) {
                sb.append(" -H 'User-Agent: $userAgent'")
            }
            return sb.toString()
        }
}
