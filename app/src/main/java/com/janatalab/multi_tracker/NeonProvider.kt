package com.janatalab.multi_tracker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException

class NeonProvider(private val url: String, enableLogging: Boolean = false) {
    private val client = OkHttpClient.Builder()
        .apply {
            if (enableLogging) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
            }
        }
        .build()

    private suspend fun postData(endpoint: String, json: String): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)

        val url = url + endpoint

        if (endpoint == "/event") {
            Log.d("MT_HTTP", "Posting GPS Event to Neon Companion")
        } else {
            Log.d("MT_HTTP", "Posting to ${url} with body: ${json}")
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).post(body).build()
            // client.newCall(request).execute().use { response ->
            //     if (!response.isSuccessful) throw IOException("Unexpected code $response")
            //     response.body?.string() ?: ""
            // }

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) throw IOException("POST: ${url}\nUnexpected code ${resp.code}, message: ${resp.message}, body: ${resp.body?.string()}")
                resp.body?.string() ?: ""
            }
        }
    }

    suspend fun getNeonStatus(): String {
        val url = url + "/status"
        Log.d("MT_HTTP", "Getting status from $url")

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    response.body?.string() ?: ""
                }
            } catch (e: Exception) {
                Log.d("MT_HTTP", "Error in GET: ${e.message}")
                e.printStackTrace()
                "Error: ${e.message}" 
            }
        }
    }

    suspend fun sendEvent(event: Event, event_type: String = "gps") {
        Log.d("MT_HTTP", "Sending ${event_type} event to Neon")

        try {
            postData("/event", event.toJson())
        } catch (e: Exception) {
            Log.d("MT_HTTP", "Error in POST: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun startNeonRecording(): String {
        Log.d("MT_HTTP", "Starting Neon recording")

        return try {
            postData("/recording:start", "")
        } catch (e: Exception) {
            Log.d("MT_HTTP", "Error in POST: ${e.message}")
            e.printStackTrace()
            "Error: ${e.message}"
        }
    }

    suspend fun stopNeonRecording(): String {
        Log.d("MT_HTTP", "Stopping Neon recording")

        return try {
            postData("/recording:stop_and_save", "")
        } catch (e: Exception) {
            Log.d("MT_HTTP", "Error in POST: ${e.message}")
            e.printStackTrace()
            "Error: ${e.message}"
        }
        // val mediaType = "text/plain".toMediaType()
        // val url  = url + "/recording:stop_and_save"
        // val body = "".toRequestBody(mediaType)

        // return withContext(Dispatchers.IO) {
        //     try {
        //         val request = Request.Builder().url(url).post(body).build()

        //         val response = client.newCall(request).execute()
        //         response.use { resp ->
        //             if (!resp.isSuccessful) throw IOException("POST: ${url}\nUnexpected code ${resp.code}, message: ${resp.message}")
        //             resp.body?.string() ?: ""
        //     }
        //     } catch (e: Exception) {
        //         Log.d("MT_HTTP", "Error in POST: ${e.message}")
        //         e.printStackTrace()
        //         "Error: ${e.message}"
        //     }
        // }

    }

    suspend fun startStopNeonRecording(isRecording: Boolean): String {
        return if (isRecording) {
            stopNeonRecording()
        } else {
            startNeonRecording()
        }
    }
}