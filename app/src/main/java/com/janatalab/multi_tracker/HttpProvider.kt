package com.janatalab.multi_tracker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class HttpProvider(private val url: String) {
    private val client = OkHttpClient()

    private suspend fun postData(endpoint: String, json: String): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)

        val url = url + endpoint

        if (endpoint == "/api/event") {
            Log.d("GPS", "Posting GPS Event")
        } else {
            Log.d("HTTP", "Posting to $url")
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                response.body?.string() ?: ""
            }
        }
    }

    suspend fun sendEvent(event: Event) {
        Log.d("GPS", "Sending GPS event")

        try {
            postData("/api/event", event.toJson())
        } catch (e: Exception) {
            Log.d("GPS", "Error in POST: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun getNeonStatus(): String {
        Log.d("HTTP", "Getting status from $url")

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url + "/api/status").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    response.body?.string() ?: ""
                }
            } catch (e: Exception) {
                Log.d("HTTP", "Error in GET: ${e.message}")
                e.printStackTrace()
                "Error: ${e.message}" 
            }
        }
    }

    suspend fun startNeonRecording(): String {
        Log.d("HTTP", "Starting Neon recording")

        return try {
            postData("/api/recording:start", "{}")
        } catch (e: Exception) {
            Log.d("HTTP", "Error in POST: ${e.message}")
            e.printStackTrace()
            "Error: ${e.message}"
        }
    }
}