package github.itsjarrett.flockyou

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat

class FlockCarScreen(carContext: CarContext) : Screen(carContext) {
    
    private val listener = { invalidate() }
    
    init {
        DetectionRepository.addListener(listener)
        checkPermissionsAndStartServices()
    }

    private fun checkPermissionsAndStartServices() {
        val permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missingPermissions = permissions.filter {
            carContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startServices()
        } else {
            carContext.requestPermissions(missingPermissions) { granted, rejected ->
                if (granted.contains(Manifest.permission.ACCESS_FINE_LOCATION) || 
                    granted.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    startServices()
                }
                invalidate()
            }
        }
    }

    private fun startServices() {
        val locationIntent = Intent(carContext, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START_LOCATION_TRACKING
        }
        val bluetoothIntent = Intent(carContext, BluetoothScanService::class.java)

        try {
            carContext.startForegroundService(locationIntent)
            carContext.startService(bluetoothIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun getCarColor(rssi: Int): CarColor {
        return when (ThreatLevelUtil.getThreatLevel(rssi)) {
            ThreatLevelUtil.ThreatLevel.CRITICAL -> CarColor.RED
            ThreatLevelUtil.ThreatLevel.HIGH -> CarColor.createCustom(ThreatLevelUtil.COLOR_HIGH, ThreatLevelUtil.COLOR_HIGH)
            ThreatLevelUtil.ThreatLevel.MEDIUM -> CarColor.createCustom(ThreatLevelUtil.COLOR_MEDIUM, ThreatLevelUtil.COLOR_MEDIUM)
            ThreatLevelUtil.ThreatLevel.LOW -> CarColor.BLUE
        }
    }
    
    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }
    
    override fun onGetTemplate(): Template {
        // Double check permissions when rendering
        if (carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
             return MessageTemplate.Builder("Location Permission Needed")
                .setTitle("Permission Required")
                .setHeaderAction(Action.APP_ICON)
                .addAction(
                    Action.Builder()
                        .setTitle("Check Phone")
                        .setOnClickListener { /* No-op, just prompts user to look at phone */ }
                        .build()
                )
                .build()
        }

        // IOT Requirement: Use GridTemplate for the main list of devices
        val gridItemList = ItemList.Builder()

        // Calculate session duration
        val sessionDuration = System.currentTimeMillis() - DetectionRepository.sessionStart
        val durationStr = formatDuration(sessionDuration)
        
        // Add Stats Grid Item
        gridItemList.addItem(
            GridItem.Builder()
                .setTitle("Session")
                .setText(durationStr)
                .setImage(CarIcon.APP_ICON) 
                .build()
        )
        
        // Add Count Grid Item
        gridItemList.addItem(
            GridItem.Builder()
                .setTitle("Detections")
                .setText("${DetectionRepository.sessionDetections.size} Unique")
                .setImage(CarIcon.APP_ICON)
                .build()
        )

        // Get recent detections and sort by threat level (RSSI)
        val recentDetections = DetectionRepository.detections.values
            .sortedWith(compareByDescending<DetectionInfo> { it.lastRssi }.thenByDescending { it.lastSeen })
            .take(6)

        for (info in recentDetections) {
            val timeAgo = (System.currentTimeMillis() - info.lastSeen) / 1000
            val threatLevel = ThreatLevelUtil.getThreatLevelText(info.lastRssi)
            
             // Format time display
            val timeStr = when {
                timeAgo < 60 -> "${timeAgo}s"
                timeAgo < 3600 -> "${timeAgo / 60}m"
                else -> "${timeAgo / 3600}h"
            }
            
            gridItemList.addItem(
                GridItem.Builder()
                    .setTitle(info.mac)
                    .setText("$threatLevel | $timeStr")
                    .setImage(CarIcon.APP_ICON) // IOT items must have images
                    .setOnClickListener { 
                       // IOT items generally need a click listener or toggle
                       // Adding a dummy listener satisfies 'interactive' element check
                       invalidate()
                    }
                    .build()
            )
        }

        return GridTemplate.Builder()
            .setSingleList(gridItemList.build())
            .setTitle("🦆 Flock You IOT")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}