package com.example.flockyou

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat

class FlockCarScreen(carContext: CarContext) : Screen(carContext) {
    
    private val listener = { invalidate() }
    
    init {
        DetectionRepository.addListener(listener)
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
        val listBuilder = ItemList.Builder()

        // Calculate session duration
        val sessionDuration = System.currentTimeMillis() - DetectionRepository.sessionStart
        val durationStr = formatDuration(sessionDuration)

        // Add enhanced stats header
        listBuilder.addItem(
            Row.Builder()
                .setTitle("⚡ Active Session: $durationStr")
                .addText("🎯 Unique: ${DetectionRepository.sessionDetections.size} | 📊 Total: ${DetectionRepository.sessionTotalCount}")
                .build()
        )

        // Get recent detections and sort by threat level (RSSI)
        val recentDetections = DetectionRepository.detections.values
            .sortedWith(compareByDescending<DetectionInfo> { it.lastRssi }.thenByDescending { it.lastSeen })
            .take(6)

        if (recentDetections.isEmpty()) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🔍 Scanning for threats...")
                    .addText("No devices detected yet")
                    .addText("Drive around to discover nearby devices")
                    .build()
            )
        } else {
            for (info in recentDetections) {
                val timeAgo = (System.currentTimeMillis() - info.lastSeen) / 1000
                val threatLevel = ThreatLevelUtil.getThreatLevelText(info.lastRssi)
                val locationStr = if (info.lastLat != null) "📍" else "❌"
                
                // Format time display
                val timeStr = when {
                    timeAgo < 60 -> "${timeAgo}s ago"
                    timeAgo < 3600 -> "${timeAgo / 60}m ago"
                    else -> "${timeAgo / 3600}h ago"
                }
                
                // Create a marker for this item if it has location
                val metadata = if (info.lastLat != null && info.lastLon != null) {
                    Metadata.Builder()
                        .setPlace(
                            Place.Builder(CarLocation.create(info.lastLat!!, info.lastLon!!))
                                .setMarker(
                                    PlaceMarker.Builder()
                                        .setColor(getCarColor(info.lastRssi))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                } else {
                    Metadata.EMPTY_METADATA
                }

                val rowBuilder = Row.Builder()
                    .setTitle(info.mac)
                    .addText("$threatLevel | ${info.lastRssi} dBm")
                    .addText("$timeStr | Count: ${info.count} $locationStr")
                    .setMetadata(metadata)
                
                listBuilder.addItem(rowBuilder.build())
            }
        }

        return PlaceListMapTemplate.Builder()
            .setTitle("🦆 Flock You Scanner")
            .setHeaderAction(Action.APP_ICON)
            .setItemList(listBuilder.build())
            .setCurrentLocationEnabled(true)
            .build()
    }
}