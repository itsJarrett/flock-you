package com.example.flockyou

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
    var lastLon: Double? = null
)

object DetectionRepository {
    val detections = mutableMapOf<String, DetectionInfo>()
    
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
                        lastLon = if (obj.has("lastLon")) obj.getDouble("lastLon") else null
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
                jsonArray.put(obj)
            }
            val file = File(context.filesDir, "detections.json")
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addDetection(mac: String, rssi: Int, location: Location?): DetectionInfo {
        val info = detections.getOrPut(mac) { 
             DetectionInfo(mac, firstSeen = System.currentTimeMillis()) 
        }
        
        // Update stats
        info.count++
        info.lastSeen = System.currentTimeMillis()
        info.lastRssi = rssi
        
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
}