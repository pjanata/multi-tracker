package com.janatalab.multi_tracker

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class GpsViewModel(
    private val repository: GpsRepository,
    private val geoCodingProvider: GeoCodingProvider,
    private val neonProvider: NeonProvider,
    // Going to add a Movella Provider here later
) : ViewModel() {
    private val _gpsUiState = MutableStateFlow(GpsRecordingUiState())
    val uiState: StateFlow<GpsRecordingUiState> = _gpsUiState.asStateFlow()

    private val _neonUiState = MutableStateFlow(NeonRecordingUiState())
    val neonUiState: StateFlow<NeonRecordingUiState> = _neonUiState.asStateFlow()

    fun setUserFolder(userFolder: Uri?) {
        repository.setUserFolder(userFolder)
    }

    suspend fun checkNeonStatus() {
        val status_message = neonProvider.getNeonStatus()

        _neonUiState.update { it.copy(
            statusMessage = status_message
        ) }
    }

    fun startStopGpsRecording() {
        val recordingState = repository.startStopGpsRecording()

        val isRecording = recordingState.first
        val path = recordingState.second

        runBlocking {
            withContext(Dispatchers.IO) {
                if (isRecording) {
                    sendGpsEvent("gps.begin")
                } else {
                    sendGpsEvent("gps.end")
                }
            }
        }

        val statusMessage = if (isRecording) "Recording started..." else "Recording stopped!"
        val buttonText = if (isRecording) "Stop recording" else "Start recording"
        val savedMessage = if (path != null) {
            "Saved to:\nDocuments/GPS/$path"
        } else {
            if (!isRecording) {
                "Save failed"
            } else {
                ""
            }
        }

        _gpsUiState.update { it.copy(
            isRecording = isRecording,
            dataSaved = !isRecording,
            statusMessage = statusMessage,
            savedMessage = savedMessage,
            buttonText = buttonText)
        }
    }

    fun fetchLatestGazeDatum() = repository.fetchLatestGpsData()

    fun fetchGpsNumSamples() {
        val currentNumSamples = repository.currentNumSamples()
        _gpsUiState.update { it.copy(numSamples = currentNumSamples) }
    }

    fun listenGpsNumSamples() {
        Log.d("GPS", "Listening for GPS data updates in separate coroutine")

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                while (true) {
                    val currentNumSamples = repository.currentNumSamples()
                    _gpsUiState.update { it.copy(numSamples = currentNumSamples) }
                }
            }
        }
    }

    suspend fun sendGpsEvent(custom_name: String?) {
        var event = Event()
        if (custom_name != null) {
            event.setName(custom_name)
        } else {
            var address: String? = ""
            try {
                val gpsDatum = repository.fetchLatestGpsData()
                address = geoCodingProvider.geocode(gpsDatum)
            } catch (e: Exception) {
                Log.d("GPS", "No geocoding available")
                e.printStackTrace()
            }
            if (address != null) {
                Log.d("GPS", "Geocoding successful: ${address}")
                event.setName(address)
            } else {
                event.setName("gps_event")
            }
        }
        neonProvider.sendEvent(event)
    }
}