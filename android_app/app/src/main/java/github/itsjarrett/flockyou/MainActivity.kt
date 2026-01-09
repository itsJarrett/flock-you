package github.itsjarrett.flockyou

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlertDialog
import android.content.Intent
import android.content.ServiceConnection
import android.speech.tts.TextToSpeech
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
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import java.util.UUID
import java.util.Locale
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity(), LocationListener, TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var threatLevelText: TextView
    private lateinit var scanButton: Button
    private lateinit var statsButton: Button
    private lateinit var mapButton: Button
    private lateinit var clearDbButton: Button
    private lateinit var filterButton: Button
    private lateinit var exportButton: Button
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var logCard: View
    private lateinit var rssiValue: TextView
    private lateinit var rssiProgressBar: android.widget.ProgressBar
    private lateinit var mapView: MapView
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var currentLocationMarker: Marker? = null
    
    // Text-to-Speech for voice announcements
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var voiceAnnouncementsEnabled = true

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var isMapVisible = false
    private var showUniqueOnly = false
    private val mapMarkers = mutableListOf<Marker>()
    private val handler = Handler(Looper.getMainLooper())
    
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    private var locationService: LocationTrackingService? = null
    private var isLocationServiceBound = false

    // UUIDs from the ESP32 code
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CHARACTERISTIC_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocationTrackingService.LocalBinder
            locationService = binder.getService()
            isLocationServiceBound = true

            // Set callback to receive location updates
            locationService?.setLocationCallback { location ->
                runOnUiThread {
                    onLocationChanged(location)
                }
            }

            // Get current location immediately
            locationService?.getCurrentLocation()?.let {
                onLocationChanged(it)
            }

            Log.d("FlockYou", "Location service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            locationService = null
            isLocationServiceBound = false
            Log.d("FlockYou", "Location service disconnected")
        }
    }

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
                    stopScan(true)
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
                log("Connected to GATT server.")
                runOnUiThread { 
                    updateStatus("Connected")
                    scanButton.text = "DISCONNECT"
                }
                log("Attempting to start service discovery...")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status=$status).")
                runOnUiThread { 
                    updateStatus("Disconnected") 
                    scanButton.text = "CONNECT"
                    scanButton.isEnabled = true
                }
                gatt.close()
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
            rssiValue.text = "$rssi dBm"
            threatLevelText.text = threatText
            
            // Map RSSI (-100 to -30) to Progress (0 to 100)
            // -100 -> 0
            // -30 -> 100
            val progress = ((rssi + 100) * (100.0 / 70.0)).toInt().coerceIn(0, 100)
            rssiProgressBar.progress = progress
            
            // Use consistent color scheme
            val color = ThreatLevelUtil.getThreatColor(rssi)
            rssiProgressBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            threatLevelText.setTextColor(color)
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
    private val lastNotificationTime = mutableMapOf<String, Long>()

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
                    // Get device category and manufacturer
                    val deviceCategory = json.optString("device_category", "UNKNOWN")
                    val manufacturer = json.optString("manufacturer", "Unknown")
                    val deviceName = json.optString("device_name", "Unknown")
                    val category = DeviceCategoryUtil.getCategoryFromJson(deviceCategory)
                    val categoryName = DeviceCategoryUtil.getCategoryName(category)
                    val categoryIcon = DeviceCategoryUtil.getCategoryIcon(category)
                    val threatLevel = DeviceCategoryUtil.getThreatLevel(category)

                    // Use Repository with full device information
                    val info = DetectionRepository.addDetection(
                        mac = mac,
                        rssi = rssi,
                        location = currentLocation,
                        manufacturer = manufacturer,
                        deviceName = deviceName,
                        deviceCategory = deviceCategory,
                        threatLevel = threatLevel
                    )

                    // Add marker to map with detailed info
                    if (info.lastLat != null && info.lastLon != null) {
                        addMapMarkerWithDetails(info)
                    }
                    
                    val countStr = "Count: ${info.count}"
                    val locStr = if (info.lastLat != null) "Loc: ${String.format("%.5f, %.5f", info.lastLat, info.lastLon)}" else "Loc: N/A"

                    // Debounce notifications (1 minute cooldown per device)
                    val currentTime = System.currentTimeMillis()
                    val lastTime = lastNotificationTime[mac] ?: 0L
                    val shouldNotify = currentTime - lastTime > 60000
                    
                    if (protocol == "wifi") {
                        val ssid = json.optString("ssid", "Unknown")
                        val threatScore = json.optInt("threat_score", 0)
                        val msg = "$categoryIcon $categoryName Detected!\n" +
                                  "Manufacturer: $manufacturer\n" +
                                  "SSID: $ssid\n" +
                                  "MAC: $mac\n" +
                                  "Score: $threatScore\n" +
                                  "$countStr | $locStr"
                        log(msg)
                        
                        // Always log, but update notifications only on new detection (debounced)
                        if (shouldNotify) {
                            lastNotificationTime[mac] = currentTime
                            sendNotification(msg, category)
                            speakDetection(category)
                        }
                    } else if (protocol == "bluetooth_le") {
                        val name = json.optString("device_name", "Unknown")
                        val threatScore = json.optInt("threat_score", 0)
                        val deviceType = json.optString("device_type", "")
                        val typeStr = if (deviceType.isNotEmpty()) "\nType: $deviceType" else ""
                        val msg = "$categoryIcon $categoryName Detected!\n" +
                                  "Manufacturer: $manufacturer\n" +
                                  "Name: $name\n" +
                                  "MAC: $mac$typeStr\n" +
                                  "Score: $threatScore\n" +
                                  "$countStr | $locStr"
                        log(msg)
                        
                        // Always log, but update notifications only on new detection (debounced)
                        if (shouldNotify) {
                            lastNotificationTime[mac] = currentTime
                            sendNotification(msg, category)
                            speakDetection(category)
                        }
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

        // Handle Edge-to-Edge insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        createNotificationChannel()

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        threatLevelText = findViewById(R.id.threatLevelText)
        scanButton = findViewById(R.id.scanButton)
        statsButton = findViewById(R.id.statsButton)
        mapButton = findViewById(R.id.mapButton)
        clearDbButton = findViewById(R.id.clearDbButton)
        filterButton = findViewById(R.id.filterButton)
        exportButton = findViewById(R.id.exportButton)
        
        val voiceButton: Button = findViewById(R.id.voiceButton)
        
        statsButton.setOnClickListener { showStats() }
        mapButton.setOnClickListener { toggleMapView() }
        clearDbButton.setOnClickListener { confirmClearDatabase() }
        filterButton.setOnClickListener { toggleMapFilter() }
        exportButton.setOnClickListener { showExportDialog() }
        voiceButton.setOnClickListener { toggleVoiceAnnouncements(voiceButton) }
        
        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)
        logCard = findViewById(R.id.logCard)
        rssiValue = findViewById(R.id.rssiValue)
        rssiProgressBar = findViewById(R.id.rssiProgressBar)
        mapView = findViewById(R.id.mapView)
        
        setupMap()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        // Initialize Text-to-Speech for voice announcements
        tts = TextToSpeech(this, this)
        
        // Load detections after UI is initialized because it logs to logText
        DetectionRepository.load(this)
        log("Loaded ${DetectionRepository.detections.size} detections from storage")
        
        // Add markers for loaded detections
        for (info in DetectionRepository.detections.values) {
            addMapMarkerWithDetails(info)
        }

        scanButton.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else if (bluetoothGatt != null) {
                disconnectDevice()
            } else {
                checkPermissionsAndScan()
            }
        }
        
        // Initial state
        scanButton.text = "CONNECT"
    }

    override fun onPause() {
        super.onPause()
        DetectionRepository.save(this)
    }
    
    // TextToSpeech.OnInitListener implementation
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("FlockYou", "TTS language not supported")
                isTtsReady = false
            } else {
                isTtsReady = true
                Log.d("FlockYou", "TTS initialized successfully")
            }
        } else {
            Log.e("FlockYou", "TTS initialization failed")
            isTtsReady = false
        }
    }
    
    private fun speakDetection(category: DeviceCategoryUtil.DeviceCategory) {
        if (!voiceAnnouncementsEnabled || !isTtsReady) return
        
        val announcement = DeviceCategoryUtil.getVoiceAnnouncement(category)
        tts?.speak(announcement, TextToSpeech.QUEUE_ADD, null, "detection_${System.currentTimeMillis()}")
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

        // Request background location (Android 10+)
        // Note: This must be requested separately after foreground location on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Only request if we already have foreground location
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // Show explanation before requesting background location
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Android 11+ requires separate request
                        showBackgroundLocationDialog()
                    } else {
                        permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
            }
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

    private fun showBackgroundLocationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Background Location Required")
            .setMessage(
                "To track device locations when the app is in the background or closed, " +
                "we need access to your location all the time.\n\n" +
                "On the next screen, please select 'Allow all the time'."
            )
            .setPositiveButton("Continue") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestBackgroundLocationLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }
            .setNegativeButton("Not Now") { _, _ ->
                Toast.makeText(this, "Background location tracking disabled", Toast.LENGTH_LONG).show()
                startScan() // Continue without background location
            }
            .show()
    }

    private val requestBackgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false
            if (granted) {
                Toast.makeText(this, "Background location enabled", Toast.LENGTH_SHORT).show()
                log("Background location permission granted")
            } else {
                Toast.makeText(this, "Background location denied - tracking will stop when app closes", Toast.LENGTH_LONG).show()
                log("Background location permission denied")
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
        scanButton.text = "STOP"

        startLocationService()

        // Scan for either the UUID or the Name
        scanner.startScan(listOf(filter, nameFilter), settings, scanCallback)
        
        // Timeout removed for continuous scanning until connection
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(foundDevice: Boolean = false) {
        if (!isScanning) return
        
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(scanCallback)
        
        // Don't stop location service if we are connected/connecting, only if fully stopping
        if (!foundDevice) {
            stopLocationService()
            isScanning = false
            scanButton.text = "CONNECT"
            updateStatus("Disconnected")
        } else {
             isScanning = false
             scanButton.text = "CONNECTING..."
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        if (bluetoothGatt != null) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        updateStatus("Disconnected")
        scanButton.text = "CONNECT"
        stopLocationService()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        updateStatus("Connecting to ${device.name ?: "Unknown"}...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // AutoConnect = true helps with stability for reconnections
            bluetoothGatt = device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            bluetoothGatt = device.connectGatt(this, true, gattCallback)
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText.text = status
            // Update status dot color based on state
            val dotColor = when {
                status.contains("Connected", ignoreCase = true) -> 
                    ContextCompat.getColor(this, R.color.status_connected)
                status.contains("Scanning", ignoreCase = true) || status.contains("Connecting", ignoreCase = true) -> 
                    ContextCompat.getColor(this, R.color.status_scanning)
                else -> 
                    ContextCompat.getColor(this, R.color.status_disconnected)
            }
            statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(dotColor)
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
    private fun sendNotification(message: String, category: DeviceCategoryUtil.DeviceCategory = DeviceCategoryUtil.DeviceCategory.UNKNOWN) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Create a Person for the sender with category-specific name
        val categoryName = DeviceCategoryUtil.getCategoryName(category)
        val categoryIcon = DeviceCategoryUtil.getCategoryIcon(category)
        val sender = Person.Builder()
            .setName("$categoryIcon Flock Detector")
            .setKey("flock_detector")
            .build()

        // Create a MessagingStyle
        val messagingStyle = NotificationCompat.MessagingStyle(sender)
            .addMessage(message, System.currentTimeMillis(), sender)

        // Get category-specific color for notification
        val categoryColor = DeviceCategoryUtil.getCategoryColor(category)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(messagingStyle)
            .setContentTitle("$categoryIcon $categoryName Detected")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(categoryColor)  // Color-code the notification

        with(androidx.core.app.NotificationManagerCompat.from(this)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
    
    private fun startLocationService() {
        try {
            val intent = Intent(this, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START_LOCATION_TRACKING
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // Bind to service to receive location updates
            val bindIntent = Intent(this, LocationTrackingService::class.java)
            bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

            log("Location tracking service started")
        } catch (e: Exception) {
            log("Error starting location service: ${e.message}")
        }
    }

    private fun stopLocationService() {
        try {
            // Unbind from service
            if (isLocationServiceBound) {
                unbindService(serviceConnection)
                isLocationServiceBound = false
            }

            // Stop service
            val intent = Intent(this, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP_LOCATION_TRACKING
            }
            startService(intent)

            log("Location tracking service stopped")
        } catch (e: Exception) {
            log("Error stopping location service: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        val point = GeoPoint(location.latitude, location.longitude)
        
        // Track route for export
        DetectionRepository.addRoutePoint(location.latitude, location.longitude)
        
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

    private fun confirmClearDatabase() {
        val allTimeTotalCount = DetectionRepository.detections.values.sumOf { it.count }
        val msg = """
            Are you sure you want to clear the database?
            
            This will permanently delete:
            • ${DetectionRepository.detections.size} unique devices
            • $allTimeTotalCount total detection records
            
            This action cannot be undone!
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("⚠️ Clear Database?")
            .setMessage(msg)
            .setPositiveButton("CLEAR") { _, _ ->
                clearDatabase()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
    
    private fun clearDatabase() {
        DetectionRepository.clear(this)
        
        // Clear all map markers except current location
        val toRemove = mapView.overlays.filter { it != myLocationOverlay && it != currentLocationMarker }
        mapView.overlays.removeAll(toRemove)
        mapMarkers.clear()
        mapView.invalidate()
        
        log("\n✓ Database cleared")
        Toast.makeText(this, "Database cleared", Toast.LENGTH_SHORT).show()
    }
    
    private fun toggleMapFilter() {
        showUniqueOnly = !showUniqueOnly
        filterButton.text = if (showUniqueOnly) "UNIQUE" else "ALL"
        
        if (isMapVisible) {
            refreshMap()
        }
        
        val filterMsg = if (showUniqueOnly) "Showing unique devices only" else "Showing all detections"
        Toast.makeText(this, filterMsg, Toast.LENGTH_SHORT).show()
    }
    
    private fun toggleVoiceAnnouncements(button: Button) {
        voiceAnnouncementsEnabled = !voiceAnnouncementsEnabled
        button.text = if (voiceAnnouncementsEnabled) "🔊" else "🔇"
        val msg = if (voiceAnnouncementsEnabled) "Voice announcements enabled" else "Voice announcements disabled"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
    
    private fun showExportDialog() {
        val options = arrayOf("Export as CSV", "Export as KML (Google Earth)")
        AlertDialog.Builder(this)
            .setTitle("Export Detections")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportAsCsv()
                    1 -> exportAsKml()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun exportAsCsv() {
        val file = DetectionRepository.exportToCSV(this)
        if (file != null) {
            shareFile(file, "text/csv", "FlockYou Detections CSV")
        } else {
            Toast.makeText(this, "Export failed - no detections", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun exportAsKml() {
        val file = DetectionRepository.exportToKML(this)
        if (file != null) {
            shareFile(file, "application/vnd.google-earth.kml+xml", "FlockYou Detections KML")
        } else {
            Toast.makeText(this, "Export failed - no detections with GPS", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun shareFile(file: File, mimeType: String, title: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, title))
            log("✓ Exported: ${file.name}")
        } catch (e: Exception) {
            Log.e("FlockYou", "Share failed", e)
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun refreshMap() {
        // Clear all markers except current location
        val toRemove = mapView.overlays.filter { it != myLocationOverlay && it != currentLocationMarker }
        mapView.overlays.removeAll(toRemove)
        mapMarkers.clear()

        // Add markers for all detections with detailed info windows
        DetectionRepository.detections.values.forEach { detection ->
            addMapMarkerWithDetails(detection)
        }

        mapView.invalidate()
    }

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
        // Use a Google Maps-like tile source (CartoDB Voyager has a clean, Google Maps-style look)
        // Alternative options:
        // - CartoDB Positron (Light): "https://a.basemaps.cartocdn.com/light_all/"
        // - Mapbox Streets (requires API key): "https://api.mapbox.com/styles/v1/mapbox/streets-v11/tiles/"
        // - Esri World Street Map: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"
        val customTileSource = object : org.osmdroid.tileprovider.tilesource.XYTileSource(
            "CartoDB Voyager",
            0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
            ),
            "© OpenStreetMap contributors, © CARTO"
        ) {}
        
        mapView.setTileSource(customTileSource)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)  // Closer zoom for street-level view while driving

        // Set a default center (US center) until we get actual location
        // This prevents showing ocean at 0,0
        val defaultPoint = GeoPoint(39.8283, -98.5795)
        mapView.controller.setCenter(defaultPoint)

        // Add map tap listener to close InfoWindows when tapping outside
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                // Close all open InfoWindows when tapping on empty map area
                org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(mapView)
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                return false
            }
        }
        val mapEventsOverlay = MapEventsOverlay(mapEventsReceiver)
        mapView.overlays.add(0, mapEventsOverlay)  // Add at index 0 so it's below markers

        // Do NOT enable MyLocationNewOverlay here as it requests location updates continuously
        // We will manually update the current location marker in onLocationChanged
        /*
        // Add MyLocation overlay with continuous tracking
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        myLocationOverlay?.enableMyLocation()
        myLocationOverlay?.enableFollowLocation()  // Keep map centered on user location
        
        // When location is first fixed, center the map
        myLocationOverlay?.runOnFirstFix {
            runOnUiThread {
                myLocationOverlay?.myLocation?.let { loc ->
                    mapView.controller.animateTo(loc)
                }
            }
        }
        
        mapView.overlays.add(myLocationOverlay)
        */
        
        // Set initial center if location is already known
        currentLocation?.let {
            val startPoint = GeoPoint(it.latitude, it.longitude)
            mapView.controller.setCenter(startPoint)
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleMapView() {
        isMapVisible = !isMapVisible
        if (isMapVisible) {
            mapView.visibility = View.VISIBLE
            logCard.visibility = View.GONE
            mapButton.text = "LOG"
            
            // Refresh map with current filter
            refreshMap()
            
            // Try to center on current or last known location
            val locationToUse = currentLocation 
                ?: locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            locationToUse?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                mapView.controller.animateTo(point)
            }
        } else {
            mapView.visibility = View.GONE
            logCard.visibility = View.VISIBLE
            mapButton.text = "MAP"
        }
    }
    
    // Custom InfoWindow class for detailed device information
    inner class DetailedInfoWindow(mapView: MapView) : org.osmdroid.views.overlay.infowindow.InfoWindow(R.layout.marker_info_window, mapView) {

        override fun onOpen(item: Any?) {
            val marker = item as? Marker ?: return
            val detection = marker.relatedObject as? DetectionInfo ?: return

            // Capture mutable properties to local variables for thread safety
            val deviceCategoryStr = detection.deviceCategory
            val manufacturerStr = detection.manufacturer
            val deviceNameStr = detection.deviceName
            val threatLevelStr = detection.threatLevel

            // Set up close button
            val closeButton = mView.findViewById<TextView>(R.id.infoWindowClose)
            closeButton.setOnClickListener {
                close()
            }

            // Title with icon
            val titleView = mView.findViewById<TextView>(R.id.infoWindowTitle)
            val category = DeviceCategoryUtil.getCategoryFromJson(deviceCategoryStr)
            val categoryIcon = DeviceCategoryUtil.getCategoryIcon(category)
            val categoryName = DeviceCategoryUtil.getCategoryName(category)
            titleView.text = "$categoryIcon $categoryName"

            // MAC Address
            val macView = mView.findViewById<TextView>(R.id.infoWindowMac)
            macView.text = detection.mac

            // Manufacturer
            val manufacturerView = mView.findViewById<TextView>(R.id.infoWindowManufacturer)
            val manufacturerRow = mView.findViewById<LinearLayout>(R.id.manufacturerRow)
            if (manufacturerStr != null) {
                manufacturerView.text = manufacturerStr
                manufacturerRow.visibility = View.VISIBLE
            } else {
                manufacturerRow.visibility = View.GONE
            }

            // Device Name
            val deviceNameView = mView.findViewById<TextView>(R.id.infoWindowDeviceName)
            val deviceNameRow = mView.findViewById<LinearLayout>(R.id.deviceNameRow)
            if (deviceNameStr != null && deviceNameStr != "Unknown") {
                deviceNameView.text = deviceNameStr
                deviceNameRow.visibility = View.VISIBLE
            } else {
                deviceNameRow.visibility = View.GONE
            }

            // Category
            val categoryView = mView.findViewById<TextView>(R.id.infoWindowCategory)
            val categoryRow = mView.findViewById<LinearLayout>(R.id.categoryRow)
            if (deviceCategoryStr != null) {
                categoryView.text = deviceCategoryStr.replace("_", " ")
                categoryRow.visibility = View.VISIBLE
            } else {
                categoryRow.visibility = View.GONE
            }

            // Threat Level
            val threatLevelView = mView.findViewById<TextView>(R.id.infoWindowThreatLevel)
            val threatLevelRow = mView.findViewById<LinearLayout>(R.id.threatLevelRow)
            if (threatLevelStr != null) {
                threatLevelView.text = threatLevelStr
                val color = when (threatLevelStr) {
                    "CRITICAL" -> 0xFFDC2626.toInt()
                    "HIGH" -> 0xFFFF8C00.toInt()
                    "MEDIUM" -> 0xFFFFAA00.toInt()
                    "LOW" -> 0xFF10B981.toInt()
                    else -> 0xFF9CA3AF.toInt()
                }
                threatLevelView.setTextColor(color)
                threatLevelRow.visibility = View.VISIBLE
            } else {
                threatLevelRow.visibility = View.GONE
            }

            // RSSI
            val rssiView = mView.findViewById<TextView>(R.id.infoWindowRssi)
            rssiView.text = "${detection.lastRssi} dBm"

            // Detection Count
            val countView = mView.findViewById<TextView>(R.id.infoWindowCount)
            countView.text = detection.count.toString()

            // First Seen
            val firstSeenView = mView.findViewById<TextView>(R.id.infoWindowFirstSeen)
            val firstSeenTime = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(detection.firstSeen))
            firstSeenView.text = firstSeenTime

            // Last Seen
            val lastSeenView = mView.findViewById<TextView>(R.id.infoWindowLastSeen)
            val lastSeenTime = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(detection.lastSeen))
            lastSeenView.text = lastSeenTime

            // GPS Coordinates
            val gpsView = mView.findViewById<TextView>(R.id.infoWindowGps)
            if (detection.lastLat != null && detection.lastLon != null) {
                gpsView.text = "${String.format("%.5f", detection.lastLat)}, ${String.format("%.5f", detection.lastLon)}"
            } else {
                gpsView.text = "No GPS data"
            }
        }

        override fun onClose() {
            // Nothing to clean up
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
        mapMarkers.add(marker)
        mapView.invalidate()
    }

    // New version that accepts DetectionInfo and uses custom InfoWindow
    private fun addMapMarkerWithDetails(detection: DetectionInfo) {
        if (detection.lastLat == null || detection.lastLon == null) return

        val point = GeoPoint(detection.lastLat!!, detection.lastLon!!)
        val marker = Marker(mapView)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        // Set basic title/snippet for fallback
        val category = DeviceCategoryUtil.getCategoryFromJson(detection.deviceCategory)
        val categoryIcon = DeviceCategoryUtil.getCategoryIcon(category)
        val categoryName = DeviceCategoryUtil.getCategoryName(category)
        marker.title = "$categoryIcon $categoryName"
        marker.snippet = detection.mac

        // Attach detection info for custom InfoWindow
        marker.relatedObject = detection

        // Set custom InfoWindow
        marker.infoWindow = DetailedInfoWindow(mapView)

        mapView.overlays.add(marker)
        mapMarkers.add(marker)
        mapView.invalidate()
    }

    override fun onDestroy() {
        // Shutdown TTS
        tts?.stop()
        tts?.shutdown()
        tts = null
        
        super.onDestroy()

        // Clean up location service binding
        if (isLocationServiceBound) {
            try {
                unbindService(serviceConnection)
                isLocationServiceBound = false
            } catch (e: Exception) {
                Log.e("FlockYou", "Error unbinding service: ${e.message}")
            }
        }

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
