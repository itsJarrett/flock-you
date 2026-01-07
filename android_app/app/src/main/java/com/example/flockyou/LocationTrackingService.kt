package com.example.flockyou

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class LocationTrackingService : Service(), LocationListener {

    private val binder = LocalBinder()
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    private var locationCallback: ((Location) -> Unit)? = null

    private val CHANNEL_ID = "location_tracking_channel"
    private val NOTIFICATION_ID = 1001

    companion object {
        const val ACTION_START_LOCATION_TRACKING = "com.example.flockyou.START_LOCATION_TRACKING"
        const val ACTION_STOP_LOCATION_TRACKING = "com.example.flockyou.STOP_LOCATION_TRACKING"

        var isRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): LocationTrackingService = this@LocationTrackingService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Service created")
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LOCATION_TRACKING -> {
                startForegroundService()
                startLocationTracking()
            }
            ACTION_STOP_LOCATION_TRACKING -> {
                stopLocationTracking()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        Log.d("LocationService", "Foreground service started")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Location Tracking"
            val descriptionText = "Tracks your location for device detection"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP_LOCATION_TRACKING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val locationText = if (currentLocation != null) {
            "Lat: ${String.format("%.5f", currentLocation!!.latitude)}, " +
            "Lon: ${String.format("%.5f", currentLocation!!.longitude)}"
        } else {
            "Waiting for GPS fix..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Flock Detector Active")
            .setContentText(locationText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startLocationTracking() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("LocationService", "Location permissions not granted")
            return
        }

        try {
            val providers = locationManager?.allProviders ?: emptyList()

            // Request updates from GPS provider (most accurate)
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L, // 2 seconds
                    5f,    // 5 meters
                    this
                )
                Log.d("LocationService", "GPS location tracking started")

                // Get last known location immediately
                locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    onLocationChanged(it)
                }
            }

            // Also use network provider for faster initial fix
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    5f,
                    this
                )
                Log.d("LocationService", "Network location tracking started")

                // Get last known location immediately
                locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                    onLocationChanged(it)
                }
            }

            // Use fused provider on newer devices
            if (providers.contains(LocationManager.FUSED_PROVIDER)) {
                locationManager?.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    2000L,
                    5f,
                    this
                )
                Log.d("LocationService", "Fused location tracking started")

                // Get last known location immediately
                locationManager?.getLastKnownLocation(LocationManager.FUSED_PROVIDER)?.let {
                    onLocationChanged(it)
                }
            }

            if (providers.isEmpty()) {
                Log.e("LocationService", "No location providers available")
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Error starting location tracking: ${e.message}")
        }
    }

    private fun stopLocationTracking() {
        locationManager?.removeUpdates(this)
        isRunning = false
        Log.d("LocationService", "Location tracking stopped")
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        updateNotification()
        locationCallback?.invoke(location)

        Log.d("LocationService", "Location updated: ${location.latitude}, ${location.longitude}")
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        Log.d("LocationService", "Provider enabled: $provider")
    }
    override fun onProviderDisabled(provider: String) {
        Log.d("LocationService", "Provider disabled: $provider")
    }

    fun getCurrentLocation(): Location? = currentLocation

    fun setLocationCallback(callback: (Location) -> Unit) {
        locationCallback = callback
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationTracking()
        isRunning = false
        Log.d("LocationService", "Service destroyed")
    }
}
