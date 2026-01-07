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
