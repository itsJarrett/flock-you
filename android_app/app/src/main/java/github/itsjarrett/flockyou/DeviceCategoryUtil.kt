package github.itsjarrett.flockyou

object DeviceCategoryUtil {

    enum class DeviceCategory {
        FLOCK_SAFETY,    // Flock Safety, Falcon, Penguin, Pigvision
        AXON,            // Axon Body Cam / Fleet
        RAVEN,           // Raven/ShotSpotter gunshot detection
        RING,            // Ring Doorbell / Security Camera
        CRADLEPOINT,     // Cradlepoint routers / network equipment
        DRONE,           // DJI, Parrot, Skydio (grouped)
        NEST_GOOGLE,     // Nest/Google cameras
        ARLO,            // Arlo cameras
        EUFY,            // Eufy cameras
        WYZE,            // Wyze cameras
        BLINK,           // Blink/Amazon cameras
        UNKNOWN
    }
    
    // Category colors matching firmware LED patterns
    const val COLOR_FLOCK_SAFETY = 0xFFFF8C00.toInt()   // Orange (200ms blink)
    const val COLOR_AXON = 0xFF0000FF.toInt()            // Blue (Blue/Red strobe)
    const val COLOR_RAVEN = 0xFFDC2626.toInt()           // Red (Fast strobe 50ms)
    const val COLOR_RING = 0xFF00FFFF.toInt()            // Cyan (300ms blink)
    const val COLOR_CRADLEPOINT = 0xFF10B981.toInt()     // Green (400ms blink)
    const val COLOR_DRONE = 0xFFFFFF00.toInt()           // Yellow (500ms blink)
    const val COLOR_NEST_GOOGLE = 0xFFFFFFFF.toInt()     // White
    const val COLOR_ARLO = 0xFF00FF64.toInt()            // Green-Teal
    const val COLOR_EUFY = 0xFFFF64C8.toInt()            // Pink
    const val COLOR_WYZE = 0xFF64C8FF.toInt()            // Light Blue
    const val COLOR_BLINK = 0xFF00E5FF.toInt()           // Cyan-White
    const val COLOR_UNKNOWN = 0xFF9CA3AF.toInt()         // Gray
    
    fun getCategoryFromJson(deviceCategory: String?): DeviceCategory {
        return when (deviceCategory?.uppercase()) {
            "FLOCK_SAFETY" -> DeviceCategory.FLOCK_SAFETY
            "AXON" -> DeviceCategory.AXON
            "RAVEN" -> DeviceCategory.RAVEN
            "RING" -> DeviceCategory.RING
            "CRADLEPOINT" -> DeviceCategory.CRADLEPOINT
            "DRONE" -> DeviceCategory.DRONE
            "NEST_GOOGLE" -> DeviceCategory.NEST_GOOGLE
            "ARLO" -> DeviceCategory.ARLO
            "EUFY" -> DeviceCategory.EUFY
            "WYZE" -> DeviceCategory.WYZE
            "BLINK" -> DeviceCategory.BLINK
            // Legacy compatibility
            "SURVEILLANCE_CAMERA" -> DeviceCategory.FLOCK_SAFETY
            "LAW_ENFORCEMENT" -> DeviceCategory.AXON
            "GUNSHOT_DETECTION" -> DeviceCategory.RAVEN
            "SECURITY_CAMERA" -> DeviceCategory.RING
            "NETWORK_EQUIPMENT" -> DeviceCategory.CRADLEPOINT
            "CONSUMER_DRONE", "COMMERCIAL_DRONE" -> DeviceCategory.DRONE
            else -> DeviceCategory.UNKNOWN
        }
    }
    
    fun getCategoryFromMac(macAddress: String): DeviceCategory {
        // Extract first 3 bytes (OUI) from MAC address
        val oui = macAddress.take(8).lowercase()

        // Axon
        if (oui.startsWith("00:13:03") || oui.startsWith("00:25:df")) {
            return DeviceCategory.AXON
        }

        // Cradlepoint (check before Flock Safety)
        val cradlepointOuis = listOf("00:30:44", "00:e0:1c")
        if (cradlepointOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.CRADLEPOINT
        }

        // Flock Safety
        val flockOuis = listOf(
            "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "04:0d:84",
            "f0:82:c0", "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9",
            "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "14:5a:fc",
            "74:4c:a1", "08:3a:88", "9c:2f:9d", "94:08:53", "e4:aa:ea",
            "b4:1e:52"
        )
        if (flockOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.FLOCK_SAFETY
        }

        // Ring
        val ringOuis = listOf(
            "44:61:32", "74:c6:3b", "08:62:66", "18:b7:11", "34:d2:70",
            "b0:4e:26", "70:56:81", "50:f5:da", "f0:d7:aa", "04:d9:f5",
            "d0:52:a8", "18:7f:88", "24:2b:d6", "34:3e:a4", "54:e0:19",
            "5c:47:5e", "64:9a:63", "90:48:6c", "9c:76:13",
            "ac:9f:c3", "c4:db:ad", "cc:3b:fb"
        )
        if (ringOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.RING
        }

        // DJI (Drones - grouped)
        val djiOuis = listOf(
            "0c:9a:e6", "8c:58:23", "04:a8:5a", "58:b8:58",
            "e4:7a:2c", "60:60:1f", "48:1c:b9", "34:d2:62"
        )
        if (djiOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.DRONE
        }

        // Parrot (Drones - grouped)
        val parrotOuis = listOf(
            "00:12:1c", "00:26:7e", "90:03:b7",
            "90:3a:e6", "a0:14:3d"
        )
        if (parrotOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.DRONE
        }

        // Skydio (Drones - grouped)
        if (oui.startsWith("38:1d:14")) {
            return DeviceCategory.DRONE
        }

        // Nest/Google
        val nestOuis = listOf(
            "18:b4:30", "1c:f2:9a", "44:07:0b", "54:60:09",
            "64:16:66", "94:94:26", "98:d2:93", "ac:0d:1a",
            "d4:a9:28", "e8:eb:11", "f4:f5:d8", "f4:f5:e8"
        )
        if (nestOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.NEST_GOOGLE
        }

        // Arlo
        val arloOuis = listOf(
            "00:1a:3a", "20:df:b9", "28:b4:66", "3c:37:86",
            "44:6c:24", "6c:b0:ce", "84:d6:d0", "9c:53:22",
            "a0:c5:89", "c4:04:15", "c4:41:1e"
        )
        if (arloOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.ARLO
        }

        // Eufy
        val eufyOuis = listOf(
            "10:d7:b0", "18:3a:2d", "1c:1b:68", "48:a9:d2",
            "60:fd:a8", "74:fe:ce", "78:02:b1", "a4:3b:fa",
            "ac:c1:ee", "d4:a6:51"
        )
        if (eufyOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.EUFY
        }

        // Wyze
        val wyzeOuis = listOf(
            "2c:aa:8e", "d0:3f:27", "7c:78:b2", "8c:4b:14"
        )
        if (wyzeOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.WYZE
        }

        // Blink
        val blinkOuis = listOf(
            "18:e7:4a", "24:62:ab", "34:4b:50", "44:91:60",
            "68:9c:70", "74:6f:f7", "b4:7c:9c"
        )
        if (blinkOuis.any { oui.startsWith(it) }) {
            return DeviceCategory.BLINK
        }

        return DeviceCategory.UNKNOWN
    }
    
    fun getCategoryColor(category: DeviceCategory): Int {
        return when (category) {
            DeviceCategory.FLOCK_SAFETY -> COLOR_FLOCK_SAFETY
            DeviceCategory.AXON -> COLOR_AXON
            DeviceCategory.RAVEN -> COLOR_RAVEN
            DeviceCategory.RING -> COLOR_RING
            DeviceCategory.CRADLEPOINT -> COLOR_CRADLEPOINT
            DeviceCategory.DRONE -> COLOR_DRONE
            DeviceCategory.NEST_GOOGLE -> COLOR_NEST_GOOGLE
            DeviceCategory.ARLO -> COLOR_ARLO
            DeviceCategory.EUFY -> COLOR_EUFY
            DeviceCategory.WYZE -> COLOR_WYZE
            DeviceCategory.BLINK -> COLOR_BLINK
            DeviceCategory.UNKNOWN -> COLOR_UNKNOWN
        }
    }
    
    fun getCategoryName(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.FLOCK_SAFETY -> "Flock Safety"
            DeviceCategory.AXON -> "Axon"
            DeviceCategory.RAVEN -> "Raven"
            DeviceCategory.RING -> "Ring"
            DeviceCategory.CRADLEPOINT -> "Cradlepoint"
            DeviceCategory.DRONE -> "Drone"
            DeviceCategory.NEST_GOOGLE -> "Nest/Google"
            DeviceCategory.ARLO -> "Arlo"
            DeviceCategory.EUFY -> "Eufy"
            DeviceCategory.WYZE -> "Wyze"
            DeviceCategory.BLINK -> "Blink"
            DeviceCategory.UNKNOWN -> "Unknown Device"
        }
    }
    
    fun getCategoryIcon(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.FLOCK_SAFETY -> "📹" // Camera
            DeviceCategory.AXON -> "👮" // Police
            DeviceCategory.RAVEN -> "🚨" // Siren
            DeviceCategory.RING -> "🚪" // Doorbell
            DeviceCategory.CRADLEPOINT -> "🌐" // Globe/Network
            DeviceCategory.DRONE -> "🛸" // UFO/Drone
            DeviceCategory.NEST_GOOGLE -> "🏠" // Home
            DeviceCategory.ARLO -> "📷" // Camera
            DeviceCategory.EUFY -> "🔒" // Lock
            DeviceCategory.WYZE -> "👁️" // Eye
            DeviceCategory.BLINK -> "💡" // Light
            DeviceCategory.UNKNOWN -> "❓" // Question
        }
    }
    
    fun getCategoryDescription(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.FLOCK_SAFETY -> "Flock Safety ALPR / Surveillance Camera"
            DeviceCategory.AXON -> "Axon Body Cam / Fleet System"
            DeviceCategory.RAVEN -> "Raven / ShotSpotter Gunshot Detection"
            DeviceCategory.RING -> "Ring Doorbell / Security Camera"
            DeviceCategory.CRADLEPOINT -> "Cradlepoint Router / Network Equipment"
            DeviceCategory.DRONE -> "Drone (DJI / Parrot / Skydio)"
            DeviceCategory.NEST_GOOGLE -> "Nest / Google Camera"
            DeviceCategory.ARLO -> "Arlo Security Camera"
            DeviceCategory.EUFY -> "Eufy Security Camera"
            DeviceCategory.WYZE -> "Wyze Camera"
            DeviceCategory.BLINK -> "Blink / Amazon Camera"
            DeviceCategory.UNKNOWN -> "Unidentified Device"
        }
    }
    
    fun getThreatLevel(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.RAVEN, DeviceCategory.AXON -> "CRITICAL"
            DeviceCategory.FLOCK_SAFETY -> "HIGH"
            DeviceCategory.RING, DeviceCategory.CRADLEPOINT -> "MEDIUM"
            DeviceCategory.DRONE -> "LOW"
            DeviceCategory.NEST_GOOGLE, DeviceCategory.ARLO, 
            DeviceCategory.EUFY, DeviceCategory.WYZE, DeviceCategory.BLINK -> "LOW"
            DeviceCategory.UNKNOWN -> "UNKNOWN"
        }
    }
    
    // Voice announcement text for TTS
    fun getVoiceAnnouncement(category: DeviceCategory): String {
        return when (category) {
            DeviceCategory.FLOCK_SAFETY -> "Flock Safety camera detected"
            DeviceCategory.AXON -> "Warning! Axon body camera detected"
            DeviceCategory.RAVEN -> "Critical alert! Gunshot detector nearby"
            DeviceCategory.RING -> "Ring camera detected"
            DeviceCategory.CRADLEPOINT -> "Surveillance network equipment detected"
            DeviceCategory.DRONE -> "Drone detected nearby"
            DeviceCategory.NEST_GOOGLE -> "Nest camera detected"
            DeviceCategory.ARLO -> "Arlo camera detected"
            DeviceCategory.EUFY -> "Eufy camera detected"
            DeviceCategory.WYZE -> "Wyze camera detected"
            DeviceCategory.BLINK -> "Blink camera detected"
            DeviceCategory.UNKNOWN -> "Unknown device detected"
        }
    }
}
