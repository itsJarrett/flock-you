package com.example.flockyou

object ThreatLevelUtil {
    const val RSSI_CRITICAL = -60
    const val RSSI_HIGH = -70
    const val RSSI_MEDIUM = -80
    
    // Updated colors to match the dark theme
    const val COLOR_CRITICAL = 0xFFDC2626.toInt()  // Red
    const val COLOR_HIGH = 0xFFEF4444.toInt()      // Light Red  
    const val COLOR_MEDIUM = 0xFFF59E0B.toInt()    // Amber
    const val COLOR_LOW = 0xFF22C55E.toInt()       // Green
    
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
            ThreatLevel.CRITICAL -> "CRITICAL THREAT"
            ThreatLevel.HIGH -> "HIGH THREAT"
            ThreatLevel.MEDIUM -> "MEDIUM THREAT"
            ThreatLevel.LOW -> "NO THREAT"
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