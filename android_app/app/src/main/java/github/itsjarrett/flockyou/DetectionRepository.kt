package github.itsjarrett.flockyou

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DetectionInfo(
    val mac: String,
    var count: Int = 0,
    var firstSeen: Long = 0,
    var lastSeen: Long = 0,
    var lastRssi: Int = 0,
    var lastLat: Double? = null,
    var lastLon: Double? = null,
    var manufacturer: String? = null,
    var deviceName: String? = null,
    var deviceCategory: String? = null,
    var threatLevel: String? = null
)

object DetectionRepository {
    val detections = mutableMapOf<String, DetectionInfo>()
    
    // Blocklist - devices to ignore (false positives)
    val blocklist = mutableSetOf<String>()
    
    // Route tracking - store all positions visited
    val routePoints = mutableListOf<Pair<Double, Double>>()
    
    // Session stats
    var sessionStart = System.currentTimeMillis()
    val sessionDetections = mutableSetOf<String>()
    var sessionTotalCount = 0
    
    // Listeners
    private val listeners = mutableListOf<() -> Unit>()
    
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    fun load(context: Context) {
        try {
            val file = File(context.filesDir, "detections.json")
            if (file.exists()) {
                val jsonStr = file.readText()
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val mac = obj.getString("mac")
                    val info = DetectionInfo(
                        mac = mac,
                        count = obj.optInt("count", 0),
                        firstSeen = obj.optLong("firstSeen", 0),
                        lastSeen = obj.optLong("lastSeen", 0),
                        lastRssi = obj.optInt("lastRssi", 0),
                        lastLat = if (obj.has("lastLat")) obj.getDouble("lastLat") else null,
                        lastLon = if (obj.has("lastLon")) obj.getDouble("lastLon") else null,
                        manufacturer = if (obj.has("manufacturer")) obj.getString("manufacturer") else null,
                        deviceName = if (obj.has("deviceName")) obj.getString("deviceName") else null,
                        deviceCategory = if (obj.has("deviceCategory")) obj.getString("deviceCategory") else null,
                        threatLevel = if (obj.has("threatLevel")) obj.getString("threatLevel") else null
                    )
                    detections[mac] = info
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun save(context: Context) {
        try {
            val jsonArray = JSONArray()
            for (info in detections.values) {
                val obj = JSONObject()
                obj.put("mac", info.mac)
                obj.put("count", info.count)
                obj.put("firstSeen", info.firstSeen)
                obj.put("lastSeen", info.lastSeen)
                obj.put("lastRssi", info.lastRssi)
                info.lastLat?.let { obj.put("lastLat", it) }
                info.lastLon?.let { obj.put("lastLon", it) }
                info.manufacturer?.let { obj.put("manufacturer", it) }
                info.deviceName?.let { obj.put("deviceName", it) }
                info.deviceCategory?.let { obj.put("deviceCategory", it) }
                info.threatLevel?.let { obj.put("threatLevel", it) }
                jsonArray.put(obj)
            }
            val file = File(context.filesDir, "detections.json")
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addDetection(
        mac: String,
        rssi: Int,
        location: Location?,
        manufacturer: String? = null,
        deviceName: String? = null,
        deviceCategory: String? = null,
        threatLevel: String? = null
    ): DetectionInfo {
        val info = detections.getOrPut(mac) {
             DetectionInfo(mac, firstSeen = System.currentTimeMillis())
        }

        // Update stats
        info.count++
        info.lastSeen = System.currentTimeMillis()
        info.lastRssi = rssi

        // Update device metadata (only if provided)
        manufacturer?.let { info.manufacturer = it }
        deviceName?.let { info.deviceName = it }
        deviceCategory?.let { info.deviceCategory = it }
        threatLevel?.let { info.threatLevel = it }

        // Update session stats
        if (sessionDetections.add(mac)) {
            sessionTotalCount++
        } else {
            sessionTotalCount++
        }

        // GPS Tagging
        location?.let {
            info.lastLat = it.latitude
            info.lastLon = it.longitude
        }

        notifyListeners()
        return info
    }
    
    fun clear(context: Context) {
        detections.clear()
        sessionDetections.clear()
        sessionTotalCount = 0
        sessionStart = System.currentTimeMillis()
        routePoints.clear()
        
        // Delete the file
        try {
            val file = File(context.filesDir, "detections.json")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        notifyListeners()
    }
    
    // Blocklist management
    fun addToBlocklist(mac: String) {
        blocklist.add(mac.uppercase())
    }
    
    fun removeFromBlocklist(mac: String) {
        blocklist.remove(mac.uppercase())
    }
    
    fun isBlocked(mac: String): Boolean {
        return blocklist.contains(mac.uppercase())
    }
    
    fun loadBlocklist(context: Context) {
        try {
            val file = File(context.filesDir, "blocklist.json")
            if (file.exists()) {
                val jsonArray = JSONArray(file.readText())
                for (i in 0 until jsonArray.length()) {
                    blocklist.add(jsonArray.getString(i))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun saveBlocklist(context: Context) {
        try {
            val jsonArray = JSONArray()
            blocklist.forEach { jsonArray.put(it) }
            val file = File(context.filesDir, "blocklist.json")
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // Route tracking
    fun addRoutePoint(lat: Double, lon: Double) {
        // Only add if different from last point (avoid duplicates)
        if (routePoints.isEmpty() || routePoints.last() != Pair(lat, lon)) {
            routePoints.add(Pair(lat, lon))
        }
    }
    
    // Export functions
    fun exportToCSV(context: Context): File? {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(context.getExternalFilesDir(null), "flockyou_export_$timestamp.csv")
            
            val header = "MAC,Count,FirstSeen,LastSeen,LastRSSI,Latitude,Longitude,Manufacturer,DeviceName,Category,ThreatLevel\n"
            val sb = StringBuilder(header)
            
            for (info in detections.values) {
                sb.append("${info.mac},")
                sb.append("${info.count},")
                sb.append("${info.firstSeen},")
                sb.append("${info.lastSeen},")
                sb.append("${info.lastRssi},")
                sb.append("${info.lastLat ?: ""},")
                sb.append("${info.lastLon ?: ""},")
                sb.append("\"${info.manufacturer ?: ""}\",")
                sb.append("\"${info.deviceName ?: ""}\",")
                sb.append("\"${info.deviceCategory ?: ""}\",")
                sb.append("\"${info.threatLevel ?: ""}\"\n")
            }
            
            file.writeText(sb.toString())
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    fun exportToKML(context: Context): File? {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(context.getExternalFilesDir(null), "flockyou_export_$timestamp.kml")
            
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            sb.append("<Document>\n")
            sb.append("  <name>Flock-You Detections</name>\n")
            sb.append("  <description>Surveillance device detections</description>\n")
            
            // Add style for markers
            sb.append("  <Style id=\"detection\">\n")
            sb.append("    <IconStyle><color>ff0000ff</color><scale>1.0</scale></IconStyle>\n")
            sb.append("  </Style>\n")
            
            for (info in detections.values) {
                if (info.lastLat != null && info.lastLon != null) {
                    val name = info.manufacturer ?: info.mac
                    sb.append("  <Placemark>\n")
                    sb.append("    <name>$name</name>\n")
                    sb.append("    <description><![CDATA[\n")
                    sb.append("      MAC: ${info.mac}<br/>\n")
                    sb.append("      Count: ${info.count}<br/>\n")
                    sb.append("      Category: ${info.deviceCategory ?: "Unknown"}<br/>\n")
                    sb.append("      RSSI: ${info.lastRssi} dBm<br/>\n")
                    sb.append("    ]]></description>\n")
                    sb.append("    <styleUrl>#detection</styleUrl>\n")
                    sb.append("    <Point>\n")
                    sb.append("      <coordinates>${info.lastLon},${info.lastLat},0</coordinates>\n")
                    sb.append("    </Point>\n")
                    sb.append("  </Placemark>\n")
                }
            }
            
            // Add route if available
            if (routePoints.size > 1) {
                sb.append("  <Placemark>\n")
                sb.append("    <name>Route</name>\n")
                sb.append("    <Style><LineStyle><color>ff00ff00</color><width>3</width></LineStyle></Style>\n")
                sb.append("    <LineString>\n")
                sb.append("      <coordinates>\n")
                for ((lat, lon) in routePoints) {
                    sb.append("        $lon,$lat,0\n")
                }
                sb.append("      </coordinates>\n")
                sb.append("    </LineString>\n")
                sb.append("  </Placemark>\n")
            }
            
            sb.append("</Document>\n")
            sb.append("</kml>\n")
            
            file.writeText(sb.toString())
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}