package com.mediaurl.model

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BookmarkItem(
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val domain: String
        get() = try {
            val uri = URI(url)
            uri.host ?: uri.authority ?: url
        } catch (_: Exception) {
            url
        }

    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))
}
