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

    fun initializeGpsRecording() {
        Log.d("MT_CORE", "Initializing GPS recording output file")
        
        val initResult = gpsRepository.initializeGpsRecording()
        val gpsInitialized = initResult.first
        val gpsPath = initResult.second
        
        if (gpsInitialized) {
            Log.d("MT_CORE", "GPS initialized successfully: $gpsPath")
            _gpsUiState.update { it.copy(
                isReady = true,
                statusMessage = "Ready",
                savedMessage = if (gpsPath != null) "Will save to:\nDocuments/GPS/$gpsPath" else ""
            ) }
        } else {
            Log.d("MT_CORE", "GPS initialization failed")
            _gpsUiState.update { it.copy(
                isReady = false,
                statusMessage = "Initialization failed"
            ) }
        }
    }

    suspend fun checkDeviceStatus() {
        // Here we would check the status of all connected devices
        var isReadyOverall = true
        
        //
        // Deal with Neon status
        //
        var neon_status_message = neonProvider.getNeonStatus()
        val neonReady = !neon_status_message.startsWith("Error")

        if (neon_status_message.startsWith("Error: Failed to connect")) {
            Log.d("MT_CORE", "Neon not ready: ${neon_status_message}")
            neon_status_message = "Start the Neon App"
            isReadyOverall = false
        } else if (neon_status_message.startsWith("Error:")) {
            Log.d("MT_CORE", "Neon not ready: ${neon_status_message}")
            neon_status_message = "Error: ${neon_status_message}"
            isReadyOverall = false
        } else {
            neon_status_message = "Ready"
            Log.d("MT_CORE", "Neon ready: ${neon_status_message}")
        }

        _neonUiState.update { it.copy(
            statusMessage = neon_status_message,
            isReady = neonReady
        )}

        // var statusMessage: String = "Neon: ${if (_neonUiState.value.isReady) "Ready" else neon_status_message}\n" 

        //
        // Deal with GPS status
        //
        var gpsReady = gpsRepository.isInitialized

        // Try to initialize GPS if not ready
        if (!gpsReady) {
            Log.d("MT_CORE", "GPS not initialized")

            initializeGpsRecording()
            gpsReady = gpsRepository.isInitialized

            if (!gpsReady) {
                _gpsUiState.update { it.copy(
                statusMessage = "Not initialized",
                isReady = false
                ) }
            }
        }

        if (!gpsReady) {
            Log.d("MT_CORE", "GPS not ready")
            _gpsUiState.update { it.copy(
                statusMessage = "Not ready",
                isReady = false
            ) }
            isReadyOverall = false
        } else {
            Log.d("MT_CORE", "GPS ready")
            _gpsUiState.update { it.copy(
                statusMessage = "Ready",
                isReady = true
            ) }
        }

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
        Log.d("MT_CORE", "Toggling multi recording state")  

        // Handle our not recording state
        if (!_multiUiState.value.isReady) {
            Log.d("MT_CORE", "Checking device status before starting recording")
            viewModelScope.launch {
                checkDeviceStatus()
            }
            if (!_multiUiState.value.isReady) {
                Log.d("MT_CORE", "Not all components are ready, cannot start recording")
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
            Log.d("MT_CORE", "Error toggling Neon recording: ${neonStatus}")
            _neonUiState.update {
                it.copy(
                    statusMessage = neonStatus,
                    isRecording = false,
                    isReady = false
                )
            }
        } else {
            Log.d("MT_CORE", "Neon recording toggled successfully: ${neonStatus}")

            _neonUiState.update {
                it.copy(
                    isRecording = !isNeonRecording,
                    statusMessage = neonStatus,
                )
            }
        }


        // Deal with GPS component
        val isCurrentlyRecordingGps = _gpsUiState.value.isRecording
        
        if (!isCurrentlyRecordingGps) {
            // Starting recording
            gpsRepository.startGpsRecording()
            
            // Send GPS begin event
            runBlocking {
                withContext(Dispatchers.IO) {
                    sendGpsEvent("gps.begin")
                }
            }
            
            _gpsUiState.update {
                it.copy(
                    isRecording = true,
                    statusMessage = "Recording...",
                    buttonText = "Stop recording"
                )
            }
        } else {
            // Stopping recording
            gpsRepository.stopGpsRecording()
            
            // Send GPS end event
            runBlocking {
                withContext(Dispatchers.IO) {
                    sendGpsEvent("gps.end")
                }
            }
            
            val path = gpsRepository.csvPath
            val savedMessage = if (path != null) {
                "Saved to:\nDocuments/GPS/$path"
            } else {
                ""
            }
            
            _gpsUiState.update {
                it.copy(
                    isRecording = false,
                    statusMessage = "Recording stopped",
                    buttonText = "Start recording",
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
        Log.d("MT_GPS", "Listening for GPS data updates in separate coroutine")

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
                Log.d("MT_GPS", "No geocoding available")
                e.printStackTrace()
            }
            if (address != null) {
                Log.d("MT_GPS", "Geocoding successful: ${address}")
                event.setName(address)
            } else {
                event.setName("gps_event")
            }
        }
        neonProvider.sendEvent(event)
    }

}