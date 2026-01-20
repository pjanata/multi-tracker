package com.janatalab.multi_tracker

class GpsDataSource(
    private val gpsApi: GpsApi
) {
    fun startGpsRecording() = gpsApi.startGpsRecording()
    fun stopGpsRecording() = gpsApi.stopGpsRecording()
    suspend fun checkGpsReady() = gpsApi.checkGpsReady()
}