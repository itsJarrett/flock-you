package com.example.flockyou

object DeviceCategoryUtil {
    
    enum class DeviceCategory {
        SURVEILLANCE_CAMERA,      // Flock Safety, Falcon, Penguin, Pigvision
        LAW_ENFORCEMENT,          // Axon
        GUNSHOT_DETECTION,        // Raven/ShotSpotter
        SECURITY_CAMERA,          // Ring
        CONSUMER_DRONE,           // DJI, Parrot
        COMMERCIAL_DRONE,         // Skydio
        BLE_CAMERA,               // Generic BLE surveillance
        UNKNOWN
    }
    
    // Category colors matching LED patterns
    const val COLOR_SURVEILLANCE = 0xFFFF8C00.toInt()    // Orange (Flock/Surveillance)
    const val COLOR_LAW_ENFORCEMENT = 0xFF0000FF.toInt() // Blue (Axon)
    const val COLOR_GUNSHOT = 0xFFDC2626.toInt()        // Red (Raven/Critical)
    const val COLOR_SECURITY = 0xFF00FFFF.toInt()       // Cyan (Ring)
    const val COLOR_DRONE = 0xFFFFFF00.toInt()          // Yellow (Drones)
    const val COLOR_BLE = 0xFFFF00FF.toInt()            // Purple (BLE)
    const val COLOR_UNKNOWN = 0xFF9CA3AF.toInt()        // Gray
    
    fun getCategoryFromJson(deviceCategory: String?): DeviceCategory {
        return when (deviceCategory?.uppercase()) {
            "SURVEILLANCE_CAMERA", "FLOCK_SAFETY" -> DeviceCategory.SURVEILLANCE_CAMERA
            "LAW_ENFORCEMENT" -> DeviceCategory.LAW_ENFORCEMENT
            "GUNSHOT_DETECTION" -> DeviceCategory.GUNSHOT_DETECTION
            "SECURITY_CAMERA" -> DeviceCategory.SECURITY_CAMERA
            "CONSUMER_DRONE" -> DeviceCategory.CONSUMER_DRONE
            "COMMERCIAL_DRONE" -> DeviceCategory.COMMERCIAL_DRONE
            "BLE_CAMERA" -> DeviceCategory.BLE_CAMERA
            else -> DeviceCategory.UNKNOWN
        }
    }
    
    fun getCategoryFromMac(macAddress: String): DeviceCategory {
        // Extract first 3 bytes (OUI) from MAC address
        val oui = macAddress.take(8).lowercase()
        
        // Axon (Law Enforcement)
        if (oui.startsWith("00:13:03") || oui.startsWith("00:25:df")) {
            return DeviceCategory.LAW_ENFORCEMENT
        }
        
        // Flock Safety (Surveillance)
        val flockOuis = listOf(
            "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "04:0d:84",
            "f0:82:c0", "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9",
            "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "14:5a:fc",
            "74:4c:a1", "08:3a:88", "9c:2f:9d", "94:08:53", "e4:aa:ea",
            "b4:1e:52", "00:30:44", "00:e0:1c"
        )
        if (flockOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.SURVEILLANCE_CAMERA
        }
        
        // Ring (Security Camera)
        val ringOuis = listOf(
            "44:61:32", "74:c6:3b", "08:62:66", "18:b7:11", "34:d2:70",
            "b0:4e:26", "70:56:81", "50:f5:da", "f0:d7:aa", "04:d9:f5",
            "d0:52:a8"
        )
        if (ringOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.SECURITY_CAMERA
        }
        
        // DJI (Consumer Drone)
        val djiOuis = listOf(
            "60:60:1f", "70:f1:0b", "90:d8:f3", "44:1c:a8",
            "c0:e4:34", "50:e4:82", "14:0e:34", "00:26:59"
        )
        if (djiOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.CONSUMER_DRONE
        }
        
        // Parrot (Consumer Drone)
        val parrotOuis = listOf(
            "a0:14:3d", "90:03:b7", "00:12:1c", "00:26:7e", "00:12:5f"
        )
        if (parrotOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.CONSUMER_DRONE
        }
        
        // Skydio (Commercial Drone)
        if (oui.startsWith("94:b9:7e")) {
            return DeviceCategory.COMMERCIAL_DRONE
        }
        
        return DeviceCategory.UNKNOWN
    }
    
    fun getCategoryColor(category: DeviceCategory): Int {
        return when (category) {
            DeviceCategory.SURVEILLANCE_CAMERA -> COLOR_SURVEILLANCE
            DeviceCategory.LAW_ENFORCEMENT -> COLOR_LAW_ENFORCEMENT
            DeviceCategory.GUNSHOT_DETECTION -> COLOR_GUNSHOT
            DeviceCategory.SECURITY_CAMERA -> COLOR_SECURITY
            DeviceCategory.CONSUMER_DRONE, DeviceCategory.COMMERCIAL_DRONE -> COLOR_DRONE
            DeviceCategory.BLE_CAMERA -> COLOR_BLE
            DeviceCategory.UNKNOWN -> COLOR_UNKNOWN
        }
    }
    
    fun getCategoryName(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.SURVEILLANCE_CAMERA -> "Surveillance Camera"
            DeviceCategory.LAW_ENFORCEMENT -> "Law Enforcement"
            DeviceCategory.GUNSHOT_DETECTION -> "Gunshot Detection"
            DeviceCategory.SECURITY_CAMERA -> "Security Camera"
            DeviceCategory.CONSUMER_DRONE -> "Consumer Drone"
            DeviceCategory.COMMERCIAL_DRONE -> "Commercial Drone"
            DeviceCategory.BLE_CAMERA -> "BLE Camera"
            DeviceCategory.UNKNOWN -> "Unknown Device"
        }
    }
    
    fun getCategoryIcon(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.SURVEILLANCE_CAMERA -> "📹" // Camera
            DeviceCategory.LAW_ENFORCEMENT -> "👮" // Police
            DeviceCategory.GUNSHOT_DETECTION -> "🚨" // Siren
            DeviceCategory.SECURITY_CAMERA -> "🚪" // Doorbell
            DeviceCategory.CONSUMER_DRONE -> "🛸" // UFO/Drone
            DeviceCategory.COMMERCIAL_DRONE -> "✈️" // Plane
            DeviceCategory.BLE_CAMERA -> "📡" // Antenna
            DeviceCategory.UNKNOWN -> "❓" // Question
        }
    }
    
    fun getCategoryDescription(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.SURVEILLANCE_CAMERA -> "Flock Safety / ALPR Camera"
            DeviceCategory.LAW_ENFORCEMENT -> "Axon Body Cam / Fleet"
            DeviceCategory.GUNSHOT_DETECTION -> "Raven / ShotSpotter System"
            DeviceCategory.SECURITY_CAMERA -> "Ring Doorbell / Camera"
            DeviceCategory.CONSUMER_DRONE -> "DJI / Parrot Drone"
            DeviceCategory.COMMERCIAL_DRONE -> "Skydio Enterprise Drone"
            DeviceCategory.BLE_CAMERA -> "BLE Surveillance Device"
            DeviceCategory.UNKNOWN -> "Unidentified Device"
        }
    }
    
    fun getThreatLevel(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.GUNSHOT_DETECTION, DeviceCategory.LAW_ENFORCEMENT -> "CRITICAL"
            DeviceCategory.SURVEILLANCE_CAMERA, DeviceCategory.BLE_CAMERA -> "HIGH"
            DeviceCategory.SECURITY_CAMERA -> "MEDIUM"
            DeviceCategory.CONSUMER_DRONE, DeviceCategory.COMMERCIAL_DRONE -> "LOW"
            DeviceCategory.UNKNOWN -> "UNKNOWN"
        }
    }
}
