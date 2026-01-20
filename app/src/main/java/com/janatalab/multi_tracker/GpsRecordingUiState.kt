package com.janatalab.multi_tracker

data class GpsRecordingUiState(
    val isInitialized: Boolean = false,
    val isReady: Boolean = false,
    val isRecording: Boolean = false,
    val numSamples: Int = 0,
    val dataSaved: Boolean = false,
    val statusMessage: String = "",
    val savedMessage: String = "",
    val buttonText: String = "Start recording"
)
