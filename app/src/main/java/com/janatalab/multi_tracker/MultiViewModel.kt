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

class MultiViewModel(
    private val gpsRepository: GpsRepository,
    private val geoCodingProvider: GeoCodingProvider,
    private val neonProvider: NeonProvider,
    //private val movellaDOTProvider: MovellaDOTProvider
) : ViewModel() {
    // Add our overall multi recording state
    private val _multiUiState = MutableStateFlow(MultiRecordingUiState())
    val multiUiState: StateFlow<MultiRecordingUiState> = _multiUiState.asStateFlow()

    // Add state flows for all our components
    private val _gpsUiState = MutableStateFlow(GpsRecordingUiState())
    val gpsUiState: StateFlow<GpsRecordingUiState> = _gpsUiState.asStateFlow()

    private val _neonUiState = MutableStateFlow(NeonRecordingUiState())
    val neonUiState: StateFlow<NeonRecordingUiState> = _neonUiState.asStateFlow()

    // private val _movellaUiState = MutableStateFlow(MovellaRecordingUiState())
    // val movellaUiState: StateFlow<MovellaRecordingUiState> = _movellaUiState.asStateFlow()

    fun setUserFolder(userFolder: Uri?) {
        gpsRepository.setUserFolder(userFolder)
    }

    suspend fun checkDeviceStatus() {
        // Here we would check the status of all connected devices
        var isReadyOverall = true
        
        //
        // Deal with Neon status
        //
        var neon_status_message = neonProvider.getNeonStatus()
        val neonReady = !neon_status_message.startsWith("Error")

        if (neon_status_message.startsWith("Error: Failed to connect to localhost")) {
            Log.d("MULTI_TRACKER", "Neon not ready: ${neon_status_message}")
            neon_status_message = "Start the Neon App"
            isReadyOverall = false
        } else if (neon_status_message.startsWith("Error:")) {
            Log.d("MULTI_TRACKER", "Neon not ready: ${neon_status_message}")
            neon_status_message = "Error: ${neon_status_message}"
            isReadyOverall = false
        } else {
            neon_status_message = "Ready"
            Log.d("MULTI_TRACKER", "Neon ready")
        }

        _neonUiState.update { it.copy(
            statusMessage = neon_status_message,
            isReady = neonReady
        )}

        // var statusMessage: String = "Neon: ${if (_neonUiState.value.isReady) "Ready" else neon_status_message}\n" 

        //
        // Deal with GPS status
        //
        val gpsInitialized = gpsRepository.initializeGpsRecording()
        val gpsReady = gpsInitialized.first

        if (!gpsReady) {
            Log.d("MULTI_TRACKER", "GPS not ready")
            isReadyOverall = false
        } else {
            Log.d("MULTI_TRACKER", "GPS ready")
        }

        _gpsUiState.update { it.copy(
            statusMessage = if (gpsReady) "Ready" else "Not ready",
            isReady = gpsReady
        ) }

        // statusMessage += "GPS: ${if (gpsReady) "Ready" else "Not Ready"}\n"

        // val statusMessage += "Movella: ${if (_movellaUiState.isReady) "Ready" else "Not Ready"}

        val statusMessage= if (isReadyOverall) {
            "All components ready"
        } else {
            "Some components not ready"
        }
                
        // Update our overall state
        _multiUiState.update { it.copy(
            isReady = isReadyOverall,
            statusMessage = statusMessage
        ) }
    }

    fun startStopMultiRecording() {
        // Handle our not recording state
        if (!_multiUiState.value.isReady) {
            viewModelScope.launch {
                checkDeviceStatus()
            }
            if (!_multiUiState.value.isReady) {
                Log.d("MULTI_TRACKER", "Not all components are ready, cannot start recording")
                return
            }
        }

        // Toggle recording state for Neon
        val isNeonRecording = _neonUiState.value.isRecording
        val neonStatus = runBlocking {
            withContext(Dispatchers.IO) {
                neonProvider.startStopNeonRecording(isNeonRecording)
            }
        }

        if (neonStatus.startsWith("Error")) {
            Log.d("MULTI_TRACKER", "Error toggling Neon recording: ${neonStatus}")
            _neonUiState.update {
                it.copy(
                    statusMessage = neonStatus,
                    isRecording = false,
                    isReady = false
                )
            }
        } else {
            Log.d("MULTI_TRACKER", "Neon recording toggled successfully: ${neonStatus}")

            _neonUiState.update {
                it.copy(
                    isRecording = !isNeonRecording,
                    statusMessage = neonStatus,
                )
            }
        }


        // Deal with GPS component
        if (false) {
            val gpsRecordingState = gpsRepository.startStopGpsRecording()

            val isGpsRecording = gpsRecordingState.first
            val path = gpsRecordingState.second

            runBlocking {
                withContext(Dispatchers.IO) {
                    if (isGpsRecording) {
                        sendGpsEvent("gps.begin")
                    } else {
                        sendGpsEvent("gps.end")
                    }
                }
            }

            val statusMessage = if (isGpsRecording) "Recording started..." else "Recording stopped!"
            val buttonText = if (isGpsRecording) "Stop recording" else "Start recording"
            val savedMessage = if (path != null) {
                "Saved to:\nDocuments/GPS/$path"
            } else {
                ""
            }

            // Update our GPS UI state
            _gpsUiState.update {
                it.copy(
                    isRecording = isGpsRecording,
                    statusMessage = statusMessage,
                    buttonText = buttonText,
                    savedMessage = savedMessage
                )
            }
        }

        _multiUiState.update {
            it.copy(
                isRecording = !it.isRecording
            )
        }
    }

    fun listenGpsNumSamples() {
        Log.d("GPS", "Listening for GPS data updates in separate coroutine")

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                while (true) {
                    val currentNumSamples = gpsRepository.currentNumSamples()
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
                val gpsDatum = gpsRepository.fetchLatestGpsData()
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