package com.mediaurl.manager

import android.content.Context
import android.content.SharedPreferences
import com.mediaurl.model.BookmarkItem
import org.json.JSONArray
import org.json.JSONObject

object BookmarkManager {

    private const val PREFS_NAME = "mediaurl_bookmarks"
    private const val KEY_BOOKMARKS = "bookmarks_list"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getBookmarks(context: Context): List<BookmarkItem> {
        val jsonStr = getPrefs(context).getString(KEY_BOOKMARKS, "") ?: ""
        if (jsonStr.isBlank()) {
            return getDefaultBookmarks()
        }

        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<BookmarkItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    BookmarkItem(
                        title = obj.optString("title", "Bookmark"),
                        url = obj.optString("url", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            getDefaultBookmarks()
        }
    }

    fun isBookmarked(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val cleanUrl = cleanUrlForComparison(url)
        return getBookmarks(context).any { cleanUrlForComparison(it.url) == cleanUrl }
    }

    fun addBookmark(context: Context, title: String, url: String): Boolean {
        if (url.isBlank()) return false
        val list = getBookmarks(context).toMutableList()
        val cleanUrl = cleanUrlForComparison(url)

        if (list.any { cleanUrlForComparison(it.url) == cleanUrl }) {
            return false // Already exists
        }

        val cleanTitle = if (title.isBlank() || title.startsWith("http")) cleanUrl else title
        list.add(0, BookmarkItem(cleanTitle, url.trim()))
        saveBookmarks(context, list)
        return true
    }

    fun removeBookmark(context: Context, url: String): Boolean {
        val list = getBookmarks(context).toMutableList()
        val cleanUrl = cleanUrlForComparison(url)
        val removed = list.removeAll { cleanUrlForComparison(it.url) == cleanUrl }
        if (removed) {
            saveBookmarks(context, list)
        }
        return removed
    }

    fun toggleBookmark(context: Context, title: String, url: String): Boolean {
        return if (isBookmarked(context, url)) {
            removeBookmark(context, url)
            false
        } else {
            addBookmark(context, title, url)
            true
        }
    }

    private fun saveBookmarks(context: Context, list: List<BookmarkItem>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("title", item.title)
                put("url", item.url)
                put("timestamp", item.timestamp)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    private fun cleanUrlForComparison(url: String): String {
        return url.trim().lowercase().trimEnd('/')
    }

    private fun getDefaultBookmarks(): List<BookmarkItem> {
        return listOf(
            BookmarkItem("Google Search", "https://www.google.com"),
            BookmarkItem("DuckDuckGo", "https://duckduckgo.com"),
            BookmarkItem("CricHD Live", "https://crichd.mobile")
        )
    }
}
