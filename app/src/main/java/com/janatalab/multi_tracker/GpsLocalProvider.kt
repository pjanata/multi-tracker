package com.janatalab.multi_tracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GpsLocalProvider() : GpsApi, Service() {
    companion object {
        const val NOTIFICATION_ID = 1234
        val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationLooper: Looper? = null

    val nowMillis = System.currentTimeMillis()
    val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
    val offsetNanos = nowMillis * 1_000_000 - nowElapsedNanos

    private var isRecording = false
    
    // Track the most recent location received from the callback
    @Volatile
    private var lastReceivedLocation: Location? = null

    private val _gpsDataFlow = MutableSharedFlow<GpsApiModel>(replay = 0, extraBufferCapacity = 100)
    val gpsDataFlow: SharedFlow<GpsApiModel> = _gpsDataFlow.asSharedFlow()

    inner class LocalBinder : Binder() {
        fun getService(): GpsLocalProvider = this@GpsLocalProvider
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d("MT_GPS", "onBind called, starting foreground service")
        // Start foreground when binding (needed for BIND_AUTO_CREATE case)
        startForegroundService()
        return LocalBinder()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            Log.d("MT_GPS", "onLocationResult called with ${result.locations.size} locations")
            result.lastLocation?.let { loc: Location ->
                // Store the most recent location for readiness checks
                lastReceivedLocation = loc
                
                Log.d("MT_GPS", "Received Location: lat=${loc.latitude}, lon=${loc.longitude}, time=${loc.elapsedRealtimeNanos}")
                val locationUtcNanos = loc.elapsedRealtimeNanos + offsetNanos
                val gpsDatum = GpsApiModel(
                    locationUtcNanos,
                    loc.latitude,
                    loc.longitude,
                    loc.altitude,
                    loc.accuracy,
                    loc.speed,
                    loc.bearing
                 )
                val emitResult = _gpsDataFlow.tryEmit(gpsDatum)
                Log.d("MT_GPS", "Emitted GPS data to flow: success=$emitResult")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Create a background thread for location updates
        val handlerThread = android.os.HandlerThread("LocationThread")
        handlerThread.start()
        locationLooper = handlerThread.looper
        
        Log.d("MT_GPS", "GpsLocalProvider created with background looper")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d("MT_GPS", "Received stop intent")
            stopGpsRecording()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. Start foreground with notification
        startForegroundService()

        // 2. Don't start GPS recording here - wait for explicit call after binding completes
        Log.d("MT_GPS", "Service started, waiting for binding to complete before GPS recording")

        // If the system kills the service, do NOT recreate until explicitly started again
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "location_channel"
        val channel = NotificationChannel(
            channelId,
            "GPS Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Running GPS Service in background")
            .setContentText("GPS data is being saved")
            // .setSmallIcon(R.drawable.multi_tracker)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun startGpsRecording(): Boolean {
        Log.d("MT_GPS", "Requesting start of GPS recording")

        // Configure the LocationRequest with a reasonable update interval
        // Note: 0L is too aggressive and may not work on all devices
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L)  // Update every 1000 milliseconds
            .setMinUpdateIntervalMillis(10L)  // But accept updates as fast as every 10ms
            .setWaitForAccurateLocation(false)  // Don't wait, give us what you have
            .build()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("MT_GPS", "Permissions granted, checking last known location")
            
            // Try to get last known location synchronously using await-like pattern
            var lastLocationFound = false
            try {
                val locationTask = fusedLocationClient.lastLocation
                locationTask.addOnSuccessListener { location ->
                    if (location != null) {
                        Log.d("MT_GPS", "Got last known location: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}m")
                        val ageSeconds = (System.currentTimeMillis() - location.time) / 1000
                        val maxAgeSeconds = 5
                        
                        if (ageSeconds <= maxAgeSeconds) {
                            lastLocationFound = true
                            Log.d("MT_GPS", "Last known location is current (age=${ageSeconds}s)")
                        } else {
                            Log.w("MT_GPS", "Last known location is too old (age=${ageSeconds}s)")
                        }
                    } else {
                        Log.w("MT_GPS", "No last known location available - GPS may need time to acquire fix")
                    }
                }
                locationTask.addOnFailureListener { e ->
                    Log.e("MT_GPS", "Failed to get last known location: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("MT_GPS", "Exception getting last known location: ${e.message}")
            }
            
            Log.d("MT_GPS", "Starting location updates on background thread")
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                locationLooper!!  // Use background thread instead of MainLooper
            )

            isRecording = true
            Log.d("MT_GPS", "GPS recording started (last known location found: $lastLocationFound)")
            return isRecording
        } else {
            Log.e("MT_GPS", "Location permission not granted!")
            isRecording = false
            return isRecording
        }
    }

    override fun stopGpsRecording(): Boolean {
        Log.d("MT_GPS", "Stopping GPS recording")

        fusedLocationClient.removeLocationUpdates(locationCallback)
        
        // Clear the stored location to ensure fresh checks on next recording
        lastReceivedLocation = null

        isRecording = false
        return isRecording
    }

    override suspend fun checkGpsReady(): Boolean {
        Log.d("MT_GPS", "Checking GPS readiness")
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("MT_GPS", "Location permission not granted")
            return false
        }
        
        // First check if we have a recently received location from our callback (while recording)
        lastReceivedLocation?.let { location ->
            val ageSeconds = (System.currentTimeMillis() - location.time) / 1000
            val maxAgeSeconds = 5
            
            if (ageSeconds <= maxAgeSeconds) {
                Log.d("MT_GPS", "GPS ready - recent location from callback: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}m, age=${ageSeconds}s")
                return true
            } else {
                Log.d("MT_GPS", "Callback location too old (${ageSeconds}s), requesting current location")
            }
        }
        
        // Request a current location instead of relying on cached data
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    null
                )
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            val ageSeconds = (System.currentTimeMillis() - location.time) / 1000
                            val maxAgeSeconds = 5
                            
                            if (ageSeconds <= maxAgeSeconds) {
                                Log.d("MT_GPS", "GPS ready - current location obtained: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}m, age=${ageSeconds}s")
                                continuation.resume(true)
                            } else {
                                Log.w("MT_GPS", "GPS not ready - location too old (${ageSeconds}s)")
                                continuation.resume(false)
                            }
                        } else {
                            Log.w("MT_GPS", "GPS not ready - could not get current location")
                            continuation.resume(false)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("MT_GPS", "GPS check failed: ${e.message}")
                        continuation.resume(false)
                    }
                    
                // Set a timeout for cancellation
                continuation.invokeOnCancellation {
                    Log.w("MT_GPS", "GPS readiness check cancelled")
                }
            }
        } catch (e: Exception) {
            Log.e("MT_GPS", "Exception checking GPS readiness: ${e.message}")
            false
        }
    }

    override fun onDestroy() {
        stopGpsRecording()
        locationLooper?.quit()
        super.onDestroy()
    }
}