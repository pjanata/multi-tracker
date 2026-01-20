package com.janatalab.multi_tracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GpsRepository(
    private val context: Context,
    private val gpsDataSource: GpsDataSource
) {
    companion object {
        const val VERBOSE = false  // Set to true to enable debug logging
    }
    // private var isInitialized = false
    // private var isRecording = false
    var isInitialized = false
    var isRecording = false
    private var isBound = false
    private var initializationAttempted = false

    private val gpsData = mutableListOf<GpsApiModel>()

    private var csvFile: DocumentFile? = null
    private var csvWriter: BufferedWriter? = null
    private var writerJob: Job? = null
    var csvPath: String? = null

    private var service: GpsLocalProvider? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (VERBOSE) Log.d("MT_GPS", "Service connected, starting flow collection")
            isBound = true
            val localBinder = binder as? GpsLocalProvider.LocalBinder
            service = localBinder?.getService()

            service?.gpsDataFlow?.let { flow ->
                writerJob = CoroutineScope(Dispatchers.IO).launch {
                    flow.collect { gpsDatum ->
                        if (VERBOSE) Log.d("MT_GPS", "Received GPS data point")
                        gpsData.add(gpsDatum)
                        
                        // Write immediately to CSV
                        csvWriter?.write("${gpsDatum.timestamp},${gpsDatum.latitude},${gpsDatum.longitude},${gpsDatum.accuracy}\n")
                        csvWriter?.flush()
                    }
                }
            }
            
            // Now that binding is complete and collector is ready, trigger service to start GPS
            service?.startGpsRecording()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (VERBOSE) Log.d("MT_GPS", "Service disconnected")
            service = null
            isBound = false
        }
    }

    fun bindService() {
        val intent = Intent(context, GpsLocalProvider::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (isBound) {
            if (VERBOSE) Log.d("MT_GPS", "Unbinding service")
            context.unbindService(serviceConnection)
            isBound = false
        }
    }
    
    fun cleanupFailedInitialization() {
        if (VERBOSE) Log.d("MT_GPS", "Cleaning up failed initialization")
        
        // Delete the CSV file if it was created
        csvFile?.delete()
        csvFile = null
        csvPath = null
        
        // Close writer if open
        csvWriter?.close()
        csvWriter = null
        
        // Unbind service
        unbindService()
        
        // Reset flags
        isInitialized = false
        initializationAttempted = false
    }

    private var _userFolder: Uri? = null
    fun setUserFolder(userFolder: Uri?) {
        _userFolder = userFolder
    }

    fun initializeGpsRecording(): Pair<Boolean, String?>  {
        if (VERBOSE) Log.d("MT_GPS", "Initializing GPS recording")
        
        // If already attempted initialization in this session, don't do it again
        if (initializationAttempted && isBound) {
            if (VERBOSE) Log.d("MT_GPS", "Already attempted initialization in this session")
            return isInitialized to csvPath
        }
        
        initializationAttempted = true
        bindService()
        isInitialized = true

        return isInitialized to csvPath
    }
    
    private fun createCsvFile() {
        try {
            val fileparts = openCSVFile()
            csvFile = fileparts.first
            csvPath = fileparts.second

            val outputStream = context.contentResolver.openOutputStream(csvFile?.uri!!, "wa")
            csvWriter = BufferedWriter(OutputStreamWriter(outputStream!!)).apply {
                // write("timestamp [ns],latitude,longitude\n")
                write("timestamp [ns],latitude,longitude,accuracy\n")
                flush()
            }
            Log.d("MT_GPS", "CSV file created: $csvPath")
        } catch (e: Exception) {
            Log.e("MT_GPS", "Error creating CSV file", e)
            throw e
        }
    }

    fun startGpsRecording() {
        if (VERBOSE) Log.d("MT_GPS", "Starting GPS recording (isBound=$isBound, isInitialized=$isInitialized)")

        if (!isInitialized) {
            initializeGpsRecording()
        }
        
        // If not bound (e.g., after stopping a previous recording), re-bind now
        if (!isBound) {
            if (VERBOSE) Log.d("MT_GPS", "Service not bound, binding now")
            bindService()
            // Note: onServiceConnected will call startGpsRecording() when binding completes
        }
        
        // Create the CSV file now that we're actually starting to record
        if (csvFile == null) {
            createCsvFile()
        }

        // Always start the foreground service to ensure GPS collection works
        // Even if bound, the service needs to be in foreground mode
        if (VERBOSE) Log.d("MT_GPS", "Starting foreground service for GPS recording")
        val intent = Intent(context, GpsLocalProvider::class.java)
        ContextCompat.startForegroundService(context, intent)
        
        // If already bound, also trigger recording directly
        if (isBound) {
            if (VERBOSE) Log.d("MT_GPS", "Already bound, also triggering GPS recording directly")
            service?.startGpsRecording()
        }
    }

    fun stopGpsRecording() {
        if (VERBOSE) Log.d("MT_GPS", "Sending stop intent")

        val stopIntent = Intent(context, GpsLocalProvider::class.java).apply {
            action = GpsLocalProvider.ACTION_STOP_SERVICE
        }
        ContextCompat.startForegroundService(context, stopIntent)

        writerJob?.cancel()
        writerJob = null

        csvWriter?.flush()
        csvWriter?.close()
        csvWriter = null
        
        // Reset csvFile and csvPath so a new file is created for the next recording
        csvFile = null
        csvPath = null

        unbindService()
    }

    fun startStopGpsRecording(): Pair<Boolean, String?> {
        Log.d("MT_GPS", "Toggling GPS recording state")

        if (isRecording) {
            stopGpsRecording()
            gpsData.clear()
            isRecording = !isRecording
            return isRecording to csvPath
        } else {
            startGpsRecording()
            isRecording = !isRecording
            return isRecording to null
        }
    }

    fun fetchLatestGpsData(): Gps {
        val latestGpsDatum = gpsData.lastOrNull()
        return Gps(latestGpsDatum?.timestamp!!, latestGpsDatum.latitude, latestGpsDatum.longitude)
    }

    // fun fecthAllGpsData(): List<Gps> {
    //     return gpsData.map { Gps(it.timestamp, it.latitude, it.longitude) }
    // }

    // fun currentNumSamples() = gpsData.size

    suspend fun checkGpsReady(): Boolean {
        // Check if service is bound and available
        if (service == null) {
            Log.e("MT_GPS", "Cannot check GPS ready - service not bound")
            return false
        }
        
        return try {
            service?.checkGpsReady() ?: false
        } catch (e: Exception) {
            Log.e("MT_GPS", "Exception checking GPS ready: ${e.message}")
            false
        }
    }

    fun openCSVFile(): Pair<DocumentFile?, String> {
        // val prefs = context.getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
        // val uriString = prefs.getString("gps_folder_uri", null)
        val prefs = context.getSharedPreferences("multi_tracker_prefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString("multi_tracker_folder_uri", null)
        val savedUri = uriString?.let { Uri.parse(it) }
        if (savedUri != null && hasUriPermission(context, savedUri)) {
            _userFolder = savedUri
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "gps_$timestamp.csv"

        if (VERBOSE) Log.d("MT_GPS", "${savedUri}")
        Log.d("MT_GPS", fileName)

        val gpsFolder = DocumentFile.fromTreeUri(context, _userFolder!!)
        val file = gpsFolder?.createFile("text/csv", fileName)

        return file to fileName
    }

    // fun saveGPSData(): String? {
    //     val gpsData = fecthAllGpsData()

    //     Log.d("MT_GPS", "Preparing to save GPS data")
    //     Log.d("MT_GPS", "Size of gpsData: ${gpsData.size}")

    //     if (gpsData.isNotEmpty()) {
    //         Log.d("MT_GPS", "Saving GPS data")

    //         try {
    //             val fileparts = openCSVFile()
    //             val file = fileparts.first
    //             val fileName = fileparts.second

    //             Log.d("MT_GPSWriter", "Writing to: ${file?.uri}")

    //             val csv = buildString{
    //                 //                writer.append("timestamp [ns],latitude,longitude,altitude,accuracy,speed,bearing\n")
    //                 //                    writer.append("${record.timestamp},${record.latitude},${record.longitude},${record.altitude},${record.accuracy},${record.speed},${record.bearing}\n")
    //                 append("timestamp [ns],latitude,longitude\n")
    //                 gpsData.forEach { record ->
    //                     append("${record.timestamp},${record.latitude},${record.longitude}\n")
    //                 }
    //             }

    //             file?.uri?.let { fileUri ->
    //                 context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
    //                     outputStream.write(csv.toByteArray())
    //                     outputStream.flush()
    //                     return fileName
    //                 }
    //             }
    //         } catch (e: Exception) {
    //             e.printStackTrace()
    //             return null
    //         }
    //     }
    //     return null
    // }
}

fun hasUriPermission(context: Context, uri: Uri): Boolean {
    val perms = context.contentResolver.persistedUriPermissions
    return perms.any {
        uri.toString().startsWith(it.uri.toString()) && it.isWritePermission
    }
}