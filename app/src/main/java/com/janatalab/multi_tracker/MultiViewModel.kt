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
        val initResult = gpsRepository.initializeGpsRecording()
        val gpsInitialized = initResult.first
        
        if (gpsInitialized) {
            Log.d("MT_CORE", "GPS initialized successfully")
            _gpsUiState.update { it.copy(
                isInitialized = true,
                statusMessage = "Initialized",
                savedMessage = "File will be created when recording starts"
            ) }
        } else {
            Log.d("MT_CORE", "GPS initialization failed")
            _gpsUiState.update { it.copy(
                isReady = false,
                statusMessage = "Initialization failed"
            ) }
        }
    }

    suspend fun checkDeviceStatus(maxRetries: Int = 10, retryInterval: Long = 1000) {
        // Here we would check the status of all connected devices
        var isReadyOverall = false
        Log.d("MT_CORE", "Checking device statuses")
        //
        // Deal with Neon status
        //
        var neon_status_message = neonProvider.getNeonStatus()
        val neonReady = !neon_status_message.startsWith("Error")

        if (neon_status_message.startsWith("Error: Failed to connect")) {
            Log.d("MT_CORE", "Neon not ready: ${neon_status_message}")
            neon_status_message = "Start the Neon App"

        } else if (neon_status_message.startsWith("Error:")) {
            Log.d("MT_CORE", "Neon not ready: ${neon_status_message}")
            neon_status_message = "Error: ${neon_status_message}"

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
        var gpsReady = false
        var gpsInitialized = gpsRepository.isInitialized

        // Try to initialize GPS if not ready
        if (!gpsInitialized) {
            Log.d("MT_CORE", "GPS not initialized, initializing now")

            initializeGpsRecording()
            gpsInitialized = gpsRepository.isInitialized

            if (!gpsInitialized) {
                _gpsUiState.update { it.copy(
                statusMessage = "Initialization failed",
                isReady = false
                ) }
            } else {
                // Give service binding a moment to complete
                kotlinx.coroutines.delay(100)
            }
        }

        // Even if initialized, check if GPS can actually get location (with retries)
        if (gpsInitialized) {
            Log.d("MT_CORE", "GPS initialized, checking if location available")
            
            var retryCount = 0
            while (retryCount <= maxRetries) {
                gpsReady = gpsRepository.checkGpsReady()
                
                if (gpsReady) {
                    Log.d("MT_CORE", "GPS location acquired after $retryCount retries")
                    break
                }
                
                if (retryCount < maxRetries) {
                    val retriesRemaining = maxRetries - retryCount
                    val retryIntervalSeconds = retryInterval / 1000
                    Log.d("MT_CORE", "GPS location not available, retry $retryCount/$maxRetries")
                    _gpsUiState.update { it.copy(
                        statusMessage = "No GPS location, retrying in ${retryIntervalSeconds}s ($retriesRemaining left)",
                        isReady = false
                    ) }
                    kotlinx.coroutines.delay(retryInterval)
                    retryCount++
                } else {
                    Log.d("MT_CORE", "GPS location not available after $maxRetries retries")
                    _gpsUiState.update { it.copy(
                        statusMessage = "No location after ${maxRetries} retries - move outdoors",
                        isReady = false
                    ) }
                    break
                }
            }
            
            if (!gpsReady) {
                // Note: We don't clean up here since recording hasn't started yet
                // File creation is deferred until startGpsRecording() is called
            }
        }

        if (!gpsReady) {
            Log.d("MT_CORE", "GPS not ready")
            _gpsUiState.update { it.copy(
                statusMessage = if (!gpsRepository.isInitialized) "Not initialized" else "No location available",
                isReady = false
            ) }
            isReadyOverall = false
        } else {
            Log.d("MT_CORE", "GPS ready with location")
            _gpsUiState.update { it.copy(
                statusMessage = "Ready",
                isReady = true
            ) }
        }

        isReadyOverall = neonReady && gpsReady

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
        
        val isCurrentlyRecording = _multiUiState.value.isRecording

        if (!isCurrentlyRecording) {
            // STARTING RECORDING
            
            // Check if devices are ready
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
            
            // Step 1: Start Neon first
            Log.d("MT_CORE", "Starting Neon recording")
            val neonStatus = runBlocking {
                withContext(Dispatchers.IO) {
                    neonProvider.startStopNeonRecording(false)
                }
            }

            if (neonStatus.startsWith("Error")) {
                Log.e("MT_CORE", "Error starting Neon recording: ${neonStatus}")
                _neonUiState.update {
                    it.copy(
                        statusMessage = neonStatus,
                        isRecording = false,
                        isReady = false
                    )
                }
                return
            } else {
                Log.d("MT_CORE", "Neon recording started successfully")
                _neonUiState.update {
                    it.copy(
                        isRecording = true,
                        statusMessage = "Recording...",
                    )
                }
            }

            // Step 2: Start GPS second
            Log.d("MT_CORE", "Starting GPS recording")
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
            
            _multiUiState.update {
                it.copy(
                    isRecording = true
                )
            }
            
        } else {
            // STOPPING RECORDING
            
            // Step 1: Stop GPS first
            Log.d("MT_CORE", "Stopping GPS recording")
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
                    isReady = false,
                    statusMessage = "Not ready",
                    buttonText = "Start recording",
                    savedMessage = savedMessage
                )
            }
            
            // Step 2: Stop Neon second
            Log.d("MT_CORE", "Stopping Neon recording")
            val neonStatus = runBlocking {
                withContext(Dispatchers.IO) {
                    neonProvider.startStopNeonRecording(true)
                }
            }

            if (neonStatus.startsWith("Error")) {
                Log.e("MT_CORE", "Error stopping Neon recording: ${neonStatus}")
                _neonUiState.update {
                    it.copy(
                        statusMessage = neonStatus,
                        isRecording = false,
                        isReady = false
                    )
                }
            } else {
                Log.d("MT_CORE", "Neon recording stopped successfully")
                _neonUiState.update {
                    it.copy(
                        isRecording = false,
                        isReady = false,
                        statusMessage = "Not ready",
                    )
                }
            }

            _multiUiState.update {
                it.copy(
                    isRecording = false,
                    isReady = false,
                    statusMessage = "Not ready"
                )
            }
        }
    }

    // fun listenGpsNumSamples() {
    //     Log.d("MT_GPS", "Listening for GPS data updates in separate coroutine")

    //     viewModelScope.launch {
    //         withContext(Dispatchers.IO) {
    //             while (true) {
    //                 val currentNumSamples = gpsRepository.currentNumSamples()
    //                 _gpsUiState.update { it.copy(numSamples = currentNumSamples) }
    //             }
    //         }
    //     }
    // }

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