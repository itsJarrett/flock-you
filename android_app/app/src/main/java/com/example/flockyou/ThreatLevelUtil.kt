package com.example.flockyou

object ThreatLevelUtil {
    const val RSSI_CRITICAL = -60
    const val RSSI_HIGH = -70
    const val RSSI_MEDIUM = -80
    
    const val COLOR_CRITICAL = 0xFFFF0000.toInt()  // Red
    const val COLOR_HIGH = 0xFFFF6600.toInt()      // Dark Orange
    const val COLOR_MEDIUM = 0xFFFFA500.toInt()    // Orange
    const val COLOR_LOW = 0xFF0000FF.toInt()       // Blue
    
    enum class ThreatLevel {
        CRITICAL, HIGH, MEDIUM, LOW
    }
    
    fun getThreatLevel(rssi: Int): ThreatLevel {
        return when {
            rssi > RSSI_CRITICAL -> ThreatLevel.CRITICAL
            rssi > RSSI_HIGH -> ThreatLevel.HIGH
            rssi > RSSI_MEDIUM -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }
    }
    
    fun getThreatLevelText(rssi: Int): String {
        return when (getThreatLevel(rssi)) {
            ThreatLevel.CRITICAL -> "🔴 CRITICAL"
            ThreatLevel.HIGH -> "🟠 HIGH"
            ThreatLevel.MEDIUM -> "🟡 MEDIUM"
            ThreatLevel.LOW -> "🔵 LOW"
        }
    }
    
    fun getThreatColor(rssi: Int): Int {
        return when (getThreatLevel(rssi)) {
            ThreatLevel.CRITICAL -> COLOR_CRITICAL
            ThreatLevel.HIGH -> COLOR_HIGH
            ThreatLevel.MEDIUM -> COLOR_MEDIUM
            ThreatLevel.LOW -> COLOR_LOW
        }
    }
}