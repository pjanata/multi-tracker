package com.janatalab.multi_tracker

data class NeonRecordingUiState(
    val isReady: Boolean = false,
    val isRecording: Boolean = false,
    val statusMessage: String = "",
    val buttonText: String = "Neon Status"
)
