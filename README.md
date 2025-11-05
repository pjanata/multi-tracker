# Overview
This repository uses [gps-alpha-lab](https://github.com/pupil-labs/gps-alpha-lab/) from Pupil Labs as a template for an Android app.

The aim is to have a single app from which data acquisition can be started and stopped in:
1. Neon Companion - Pupil Labs Neon eye-tracking glasses
2. GPS (the original functionality of gps-alpha-lab)
3. Movella DOTS - IMUs

# Testing the app
Testing of the app is best accomplished by connecting the phone to the computer on which the code is being developed and then using Android Debug Bridge (ADB) to push updated versions to the phone.

## Building the app
```
# from repo root
./gradlew clean assembleDebug        # build debug APK
# or build release bundle (requires signing config)
./gradlew clean bundleRelease
```

## Loading the app on the phone
```adb install -r app/build/outputs/apk/debug/app-debug.apk```

## Testing the app on the phone
The multi_tracker app should be visible (or can be added to the home screen).
1. Launch the multi_tracker app
2. The app needs for the Neon Companion app to have been started, though data acquisition should be stopped.
3. Tap the "Check Device Status" button
    
## Downloading and inspecting the debug logs
Dumping the system log on the phone:
```adb logcat -d```

**Relevant tags:**
* MT_CORE
* MT_GPS
* MT_GPSWriter
* MT_HTTP
* okhttp.OkHttpClient

Filtering the log for messages logged with specific tags:
```adb logcat -d | grep "MT_CORE"```

Filtering the log for all Multi Tracker messages
```adb logcat -d | grep "MT_"```

# Issues
* GPS recording is being initialized whenever the "Check Device Status" button is pressed. It should be initialized when recording starts.
