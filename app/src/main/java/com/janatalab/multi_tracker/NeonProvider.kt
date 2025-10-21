package com.janatalab.multi_tracker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NeonProvider(private val url: String) {
    private val client = OkHttpClient()

    private suspend fun postData(endpoint: String, json: String): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)

        val url = url + endpoint

        if (endpoint == "/event") {
            Log.d("GPS", "Posting GPS Event")
        } else {
            Log.d("HTTP", "Posting to $url")
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).post(body).build()
            // client.newCall(request).execute().use { response ->
            //     if (!response.isSuccessful) throw IOException("Unexpected code $response")
            //     response.body?.string() ?: ""
            // }

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) throw IOException("POST: ${url}\nUnexpected code ${resp.code}, message: ${resp.message}")
                resp.body?.string() ?: ""
            }
        }
    }

    suspend fun getNeonStatus(): String {
        Log.d("HTTP", "Getting status from $url")

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url + "/status").build()
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

    suspend fun sendEvent(event: Event, event_type: String = "gps") {
        Log.d("HTTP", "Sending ${event_type} event to Neon")

        try {
            postData("/event", event.toJson())
        } catch (e: Exception) {
            Log.d("HTTP", "Error in POST: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun startNeonRecording(): String {
        Log.d("HTTP", "Starting Neon recording")

        return try {
            postData("/recording:start", "{}")
        } catch (e: Exception) {
            Log.d("HTTP", "Error in POST: ${e.message}")
            e.printStackTrace()
            "Error: ${e.message}"
        }
    }

    suspend fun stopNeonRecording(): String {
        Log.d("HTTP", "Stopping Neon recording")

        // return try {
        //     postData("/recording:stop_and_save", "")            
        // } catch (e: Exception) {
        //     Log.d("HTTP", "Error in POST: ${e.message}")
        //     e.printStackTrace()
        //     "Error: ${e.message}"
        // }
        val mediaType = "text/plain".toMediaType()
        val endpoint  = url + "/recording:stop_and_save"
        val body = "".toRequestBody(mediaType)

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(endpoint).post(body).build()

                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) throw IOException("POST: ${url}\nUnexpected code ${resp.code}, message: ${resp.message}")
                    resp.body?.string() ?: ""
            }
            } catch (e: Exception) {
                Log.d("HTTP", "Error in POST: ${e.message}")
                e.printStackTrace()
                "Error: ${e.message}"
            }
        }

    }

    suspend fun startStopNeonRecording(isRecording: Boolean): String {
        Log.d("HTTP", "Toggling Neon recording state")

        return if (isRecording) {
            stopNeonRecording()
        } else {
            startNeonRecording()
        }
    }
}