package com.mediaurl.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object SupabaseSyncManager {

    private const val PREFS_NAME = "supabase_config"
    private const val KEY_URL = "supabase_url"
    private const val KEY_KEY = "supabase_key"
    private const val KEY_TABLE = "supabase_table"
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"

    val DEFAULT_CHANNELS = listOf(
        "sony1" to "Sony Sports Ten 1 HD",
        "willow" to "Willow Cricket HD",
        "star1" to "Star Sports 1 HD",
        "starhindi" to "Star Sports 1 Hindi (Khel)",
        "star2" to "Star Sports 2 HD",
        "startelugu" to "Star Sports 2 Telugu",
        "ddsports" to "DD Sports 1.0 HD",
        "ddnational" to "DD National HD",
        "cricketgold" to "Cricket Gold 24x7 HD",
        "sonytv" to "Sony Entertainment TV HD",
        "tennis" to "Tennis Channel HD",
        "barca" to "Barca TV HD",
        "bein" to "beIN SPORTS XTRA HD",
        "redbull" to "Red Bull TV Sports HD"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSupabaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_URL, "")?.trim()?.trimEnd('/') ?: ""
    }

    fun getSupabaseKey(context: Context): String {
        return getPrefs(context).getString(KEY_KEY, "")?.trim() ?: ""
    }

    fun getTableName(context: Context): String {
        return getPrefs(context).getString(KEY_TABLE, "live_channels")?.trim() ?: "live_channels"
    }

    fun isAutoSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_SYNC, false)
    }

    fun saveConfig(
        context: Context,
        url: String,
        key: String,
        table: String = "live_channels",
        autoSync: Boolean = false
    ) {
        getPrefs(context).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_KEY, key.trim())
            .putString(KEY_TABLE, table.trim().ifBlank { "live_channels" })
            .putBoolean(KEY_AUTO_SYNC, autoSync)
            .apply()
    }

    fun isConfigured(context: Context): Boolean {
        val url = getSupabaseUrl(context)
        val key = getSupabaseKey(context)
        return url.isNotBlank() && key.isNotBlank() && url.startsWith("http")
    }

    /**
     * Auto-detects the matching Supabase channel ID from stream URL, referer, or webpage title
     */
    fun autoDetectChannelId(streamUrl: String, pageUrl: String = "", title: String = ""): String {
        val combined = "$streamUrl $pageUrl $title".lowercase()

        return when {
            combined.contains("sony1") || combined.contains("ten1") || combined.contains("sony ten 1") -> "sony1"
            combined.contains("willow") -> "willow"
            combined.contains("starhindi") || combined.contains("star sport 1 hindi") || combined.contains("khel") -> "starhindi"
            combined.contains("startelugu") || combined.contains("telugu") -> "startelugu"
            combined.contains("star2") || combined.contains("star sports 2") -> "star2"
            combined.contains("star1") || combined.contains("star sports 1") || combined.contains("starsports1") -> "star1"
            combined.contains("ddsports") || combined.contains("dd sports") -> "ddsports"
            combined.contains("ddnational") || combined.contains("dd national") -> "ddnational"
            combined.contains("cricketgold") || combined.contains("cricket gold") -> "cricketgold"
            combined.contains("sonytv") || combined.contains("set hd") || combined.contains("sonyhd") -> "sonytv"
            combined.contains("tennis") -> "tennis"
            combined.contains("barca") -> "barca"
            combined.contains("bein") -> "bein"
            combined.contains("redbull") -> "redbull"
            else -> ""
        }
    }

    /**
     * Executes HTTP PATCH to update public.live_channels on Supabase
     */
    fun updateChannel(
        context: Context,
        channelId: String,
        streamUrl: String,
        referer: String = "",
        onResult: (Boolean, String) -> Unit
    ) {
        val baseUrl = getSupabaseUrl(context)
        val apiKey = getSupabaseKey(context)
        val table = getTableName(context)

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            onResult(false, "Supabase URL and API Key are not configured in settings")
            return
        }

        if (channelId.isBlank()) {
            onResult(false, "Channel ID cannot be blank")
            return
        }

        val endpoint = "$baseUrl/rest/v1/$table?id=eq.$channelId"

        val jsonPayload = JSONObject().apply {
            put("stream_url", streamUrl.trim())
            if (referer.isNotBlank()) {
                put("referer", referer.trim())
            }
            put("is_active", true)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(endpoint)
            .patch(body)
            .addHeader("apikey", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    onResult(false, "Network error: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val isSuccess = code in 200..299
                val respBody = response.body?.string().orEmpty()

                mainHandler.post {
                    if (isSuccess) {
                        onResult(true, "Successfully updated '$channelId' (HTTP $code)")
                    } else {
                        onResult(false, "Supabase error (HTTP $code): $respBody")
                    }
                }
            }
        })
    }

    /**
     * Verifies Supabase connection by querying table rows count
     */
    fun testConnection(context: Context, url: String, key: String, table: String, onResult: (Boolean, String) -> Unit) {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanKey = key.trim()
        val cleanTable = table.trim().ifBlank { "live_channels" }

        if (cleanUrl.isBlank() || cleanKey.isBlank()) {
            onResult(false, "URL or API Key is missing")
            return
        }

        val endpoint = "$cleanUrl/rest/v1/$cleanTable?select=id,title&limit=1"
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .addHeader("apikey", cleanKey)
            .addHeader("Authorization", "Bearer $cleanKey")
            .addHeader("Accept", "application/json")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult(false, "Connection failed: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = response.body?.string().orEmpty()
                mainHandler.post {
                    if (code in 200..299) {
                        onResult(true, "Connected successfully to table '$cleanTable' (HTTP $code)")
                    } else {
                        onResult(false, "Authentication / Query failed (HTTP $code): $body")
                    }
                }
            }
        })
    }
}
