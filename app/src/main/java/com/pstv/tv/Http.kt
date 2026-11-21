package com.pstv.tv

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    val UA: String get() = Prefs.ua

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dispatcher(Dispatcher().apply { maxRequests = 24; maxRequestsPerHost = 8 })
            .build()
    }

    fun req(url: String): Request.Builder = Request.Builder().url(url).header("User-Agent", UA)

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = req(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            val body = r.body!!.string()
            if (!r.isSuccessful) {
                // APIs such as OpenSubtitles explain the refusal in a small JSON body ({"message": "..."}): show it.
                val why = runCatching { org.json.JSONObject(body).let { it.optString("message").ifBlank { it.optString("error") } } }.getOrDefault("")
                error("HTTP ${r.code}" + if (why.isNotBlank()) ": ${why.take(120)}" else "")
            }
            return body
        }
    }
}
