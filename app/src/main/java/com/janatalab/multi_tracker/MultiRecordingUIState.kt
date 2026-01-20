package com.janatalab.multi_tracker

data class MultiRecordingUiState(
    // This is the overall recording state
    val isRecording: Boolean = false,
    val isReady: Boolean = false,
    val showCheckStatusButton: Boolean = true,

    // Get the individual component states

    // val isNeonReady: Boolean = false,
    // val isGpsReady: Boolean = false,
    // val isMovellaReady: Boolean = false,

    // val isNeonRecording: Boolean = false,
    // val isMovellaRecording: Boolean = false,
    // val isGpsRecording: Boolean = false,


    val statusMessage: String = "",
    val buttonText: String = "Recording Status"
)
