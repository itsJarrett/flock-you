package com.example.flockyou

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlertDialog
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.ContextCompat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import java.util.UUID
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var statsButton: Button
    private lateinit var mapButton: Button
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var rssiValue: TextView
    private lateinit var rssiProgressBar: android.widget.ProgressBar
    private lateinit var mapView: MapView
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var currentLocationMarker: Marker? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isMapVisible = false
    private val handler = Handler(Looper.getMainLooper())
    
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    
    // UUIDs from the ESP32 code
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CHARACTERISTIC_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            
            // 1. Check if this is our FlockDetector
            // We check by Service UUID (if advertised) or Name
            val isFlockDetector = name == "FlockDetector" || 
                                  (result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true)

            if (isFlockDetector) {
                if (bluetoothGatt == null) {
                    log("Found FlockDetector! Connecting...")
                    connectToDevice(device)
                    stopScan()
                }
                return
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed with error: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { updateStatus("Connected") }
                log("Connected to GATT server.")
                log("Attempting to start service discovery...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { 
                    updateStatus("Disconnected") 
                    scanButton.isEnabled = true
                }
                log("Disconnected from GATT server.")
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered.")
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_TX_UUID)
                    if (characteristic != null) {
                        log("Found TX Characteristic. Subscribing...")
                        gatt.setCharacteristicNotification(characteristic, true)
                        
                        // Write to descriptor to enable notifications
                        val descriptor = characteristic.getDescriptor(
                            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        )
                        if (descriptor != null) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            log("Could not find CCCD descriptor!")
                        }
                    } else {
                        log("TX Characteristic not found!")
                    }
                } else {
                    log("Target Service not found!")
                }
            } else {
                log("onServicesDiscovered received: $status")
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // For older Android versions
            handleCharacteristicChange(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // For Android 13+
            handleCharacteristicChange(characteristic, value)
        }
        
        private fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray? = null) {
            if (characteristic.uuid == CHARACTERISTIC_TX_UUID) {
                val data = value ?: byteArrayOf()
                val chunk = String(data)
                
                dataBuffer.append(chunk)
                
                // Check for delimiter (newline)
                var delimiterIndex = dataBuffer.indexOf("\n")
                while (delimiterIndex != -1) {
                    val completeMessage = dataBuffer.substring(0, delimiterIndex)
                    dataBuffer.delete(0, delimiterIndex + 1) // Remove processed message + delimiter
                    
                    // Try to parse as JSON first
                    if (completeMessage.trim().startsWith("{")) {
                        runOnUiThread { processJsonMessage(completeMessage) }
                    } else {
                        // Fallback for legacy plain text messages
                        log("Received: $completeMessage")
                        
                        // Parse RSSI if present: [RSSI:-75]
                        val rssiRegex = "\\[RSSI:(-?\\d+)\\]".toRegex()
                        val matchResult = rssiRegex.find(completeMessage)
                        if (matchResult != null) {
                            val rssi = matchResult.groupValues[1].toInt()
                            updateRssi(rssi)
                        }

                        // Only send notification for actual detections, ignore heartbeats
                        if (completeMessage.startsWith("FLOCK DETECTED!")) {
                            sendNotification(completeMessage)
                        }
                    }
                    
                    // Check for next delimiter
                    delimiterIndex = dataBuffer.indexOf("\n")
                }
            }
        }
    }

    private fun updateRssi(rssi: Int) {
        runOnUiThread {
            val threatText = ThreatLevelUtil.getThreatLevelText(rssi)
            rssiValue.text = "$rssi dBm - $threatText"
            // Map RSSI (-100 to -30) to Progress (0 to 100)
            // -100 -> 0
            // -30 -> 100
            val progress = ((rssi + 100) * (100.0 / 70.0)).toInt().coerceIn(0, 100)
            rssiProgressBar.progress = progress
            
            // Use consistent color scheme
            val color = ThreatLevelUtil.getThreatColor(rssi)
            rssiProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                startScan()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val CHANNEL_ID = "flock_notifications"
    private val dataBuffer = StringBuilder()

    private fun processJsonMessage(jsonStr: String) {
        try {
            val json = org.json.JSONObject(jsonStr)
            
            // Check for heartbeat type first
            val type = json.optString("type", "")
            val protocol = json.optString("protocol", "")
            val rssi = json.optInt("rssi", -100)
            
            // Update RSSI UI for all message types that have it
            if (rssi != -100) {
                updateRssi(rssi)
            }

            if (type == "heartbeat") {
                 log("Heartbeat: RSSI $rssi")
            } else {
                // Handle detection
                val mac = json.optString("mac_address", "")
                if (mac.isNotEmpty()) {
                    // Use Repository
                    val info = DetectionRepository.addDetection(mac, rssi, currentLocation)
                    
                    // Add marker to map
                    if (info.lastLat != null && info.lastLon != null) {
                        val title = if (protocol == "wifi") "WiFi: $mac" else "BLE: $mac"
                        val snippet = "RSSI: $rssi"
                        addMapMarker(info.lastLat!!, info.lastLon!!, title, snippet)
                    }
                    
                    val countStr = "Count: ${info.count}"
                    val locStr = if (info.lastLat != null) "Loc: ${String.format("%.5f, %.5f", info.lastLat, info.lastLon)}" else "Loc: N/A"
                    
                    if (protocol == "wifi") {
                        val ssid = json.optString("ssid", "Unknown")
                        val threatScore = json.optInt("threat_score", 0)
                        val msg = "WiFi Threat: $ssid ($mac)\nScore: $threatScore\n$countStr\n$locStr"
                        log(msg)
                        sendNotification(msg)
                    } else if (protocol == "bluetooth_le") {
                        val name = json.optString("device_name", "Unknown")
                        val threatScore = json.optInt("threat_score", 0)
                        val msg = "BLE Threat: $name ($mac)\nScore: $threatScore\n$countStr\n$locStr"
                        log(msg)
                        sendNotification(msg)
                    } else {
                        log("Unknown JSON message: $jsonStr")
                    }
                }
            }
        } catch (e: org.json.JSONException) {
            log("Error parsing JSON: $jsonStr")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize osmdroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        setContentView(R.layout.activity_main)

        createNotificationChannel()

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        statsButton = findViewById(R.id.statsButton)
        mapButton = findViewById(R.id.mapButton)
        
        statsButton.setOnClickListener { showStats() }
        mapButton.setOnClickListener { toggleMapView() }
        
        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)
        rssiValue = findViewById(R.id.rssiValue)
        rssiProgressBar = findViewById(R.id.rssiProgressBar)
        mapView = findViewById(R.id.mapView)
        
        setupMap()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Load detections after UI is initialized because it logs to logText
        DetectionRepository.load(this)
        log("Loaded ${DetectionRepository.detections.size} detections from storage")
        
        // Add markers for loaded detections
        for (info in DetectionRepository.detections.values) {
            if (info.lastLat != null && info.lastLon != null) {
                addMapMarker(info.lastLat!!, info.lastLon!!, "History: ${info.mac}", "Count: ${info.count}")
            }
        }

        scanButton.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                checkPermissionsAndScan()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        DetectionRepository.save(this)
    }

    private fun checkPermissionsAndScan() {
        val permissionsToRequest = mutableListOf<String>()

        // Always request Location for GPS tagging
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Bluetooth is disabled", Toast.LENGTH_SHORT).show()
            return
        }

        val scanner = bluetoothAdapter!!.bluetoothLeScanner
        if (scanner == null) {
            log("BLE Scanner not available")
            return
        }

        // Filter for our specific Service UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        // Also add a filter for the name just in case, or as an alternative
        val nameFilter = ScanFilter.Builder()
            .setDeviceName("FlockDetector")
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        updateStatus("Scanning...")
        scanButton.text = "Stop Scan"
        
        startLocationUpdates()
        
        // Scan for either the UUID or the Name
        scanner.startScan(listOf(filter, nameFilter), settings, scanCallback)
        
        // Stop scan after 10 seconds
        handler.postDelayed({
            if (isScanning) stopScan()
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(scanCallback)
        
        stopLocationUpdates()
        
        isScanning = false
        updateStatus("Disconnected")
        scanButton.text = getString(R.string.scan_button)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        updateStatus("Connecting to ${device.name ?: "Unknown"}...")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }

    private fun log(message: String) {
        runOnUiThread {
            logText.append("\n$message")
            // Auto scroll to bottom could be added here
        }
        Log.d("FlockYou", message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Flock Detections"
            val descriptionText = "Notifications for detected devices"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotification(message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Create a Person for the sender
        val sender = Person.Builder()
            .setName("Flock Detector")
            .setKey("flock_detector")
            .build()

        // Create a MessagingStyle
        val messagingStyle = NotificationCompat.MessagingStyle(sender)
            .addMessage(message, System.currentTimeMillis(), sender)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(messagingStyle)
            .setContentTitle("Flock Detection")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(androidx.core.app.NotificationManagerCompat.from(this)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L, // 2 seconds
                5f,    // 5 meters
                this
            )
            // Also try network provider for faster fix
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                2000L,
                5f,
                this
            )
            log("Location updates started")
        } catch (e: Exception) {
            log("Error starting location updates: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(this)
        log("Location updates stopped")
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        val point = GeoPoint(location.latitude, location.longitude)
        
        // Update "You" marker
        if (currentLocationMarker == null) {
            currentLocationMarker = Marker(mapView)
            currentLocationMarker?.title = "You"
            currentLocationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_dot)
            if (icon != null) {
                currentLocationMarker?.icon = icon
            }
            mapView.overlays.add(currentLocationMarker)
        }
        currentLocationMarker?.position = point
        mapView.invalidate()
        
        // Manually update map center if visible
        if (isMapVisible) {
            mapView.controller.animateTo(point)
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    
    // loadDetections and saveDetections moved to DetectionRepository

    private fun showStats() {
        val sessionDuration = (System.currentTimeMillis() - DetectionRepository.sessionStart) / 1000
        val sessionDurationStr = String.format("%02d:%02d:%02d", 
            sessionDuration / 3600, (sessionDuration % 3600) / 60, sessionDuration % 60)
            
        val allTimeTotalCount = DetectionRepository.detections.values.sumOf { it.count }
        
        val msg = """
            SESSION STATS
            Duration: $sessionDurationStr
            Unique Devices: ${DetectionRepository.sessionDetections.size}
            Total Detections: ${DetectionRepository.sessionTotalCount}
            
            ALL TIME STATS
            Unique Devices: ${DetectionRepository.detections.size}
            Total Detections: $allTimeTotalCount
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("Detection Statistics")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        
        // Add MyLocation overlay
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        myLocationOverlay?.enableMyLocation()
        myLocationOverlay?.enableFollowLocation()
        mapView.overlays.add(myLocationOverlay)
        
        // Set initial center if location is known
        currentLocation?.let {
            val startPoint = GeoPoint(it.latitude, it.longitude)
            mapView.controller.setCenter(startPoint)
        }
    }

    private fun toggleMapView() {
        isMapVisible = !isMapVisible
        if (isMapVisible) {
            mapView.visibility = View.VISIBLE
            logScrollView.visibility = View.GONE
            mapButton.text = "Log"
            
            // Center immediately if we have a location
            currentLocation?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                mapView.controller.setCenter(point)
            }
        } else {
            mapView.visibility = View.GONE
            logScrollView.visibility = View.VISIBLE
            mapButton.text = "Map"
        }
    }
    
    private fun addMapMarker(lat: Double, lon: Double, title: String, snippet: String) {
        val point = GeoPoint(lat, lon)
        val marker = Marker(mapView)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = title
        marker.snippet = snippet
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    override fun onDestroy() {
        super.onDestroy()
        // osmdroid cleanup
        myLocationOverlay?.disableMyLocation()
        mapView.onDetach()
        
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
    }
}
