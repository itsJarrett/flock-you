package com.example.flockyou

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class BluetoothScanService : Service(), LocationListener {

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    private val dataBuffer = StringBuilder()

    // Callback interface for UI updates
    interface ServiceCallback {
        fun onStatusUpdate(status: String)
        fun onLogMessage(message: String)
        fun onRssiUpdate(rssi: Int)
        fun onDeviceDetected(message: String, category: DeviceCategoryUtil.DeviceCategory)
    }

    private var callback: ServiceCallback? = null

    fun setCallback(callback: ServiceCallback?) {
        this.callback = callback
    }

    // UUIDs from the ESP32 code
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CHARACTERISTIC_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    companion object {
        const val CHANNEL_ID = "flock_scanning_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_SCAN = "com.example.flockyou.START_SCAN"
        const val ACTION_STOP_SCAN = "com.example.flockyou.STOP_SCAN"

        private var instance: BluetoothScanService? = null

        fun isRunning(): Boolean = instance != null
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothScanService = this@BluetoothScanService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        createNotificationChannel()
        log("Bluetooth Scan Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> startScanning()
            ACTION_STOP_SCAN -> stopScanning()
            else -> {
                // Start as foreground service with persistent notification
                startForeground(NOTIFICATION_ID, createForegroundNotification())
            }
        }
        return START_STICKY // Restart service if killed by system
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Flock Detector Scanning"
            val descriptionText = "Ongoing device detection and scanning"
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance = no sound for persistent notification
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, BluetoothScanService::class.java).apply {
            action = ACTION_STOP_SCAN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Flock Detector Active")
            .setContentText("Scanning for devices in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Flock Detector Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (isScanning || bluetoothGatt != null) {
            log("Already scanning or connected")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            log("Bluetooth is disabled")
            updateStatus("Bluetooth Disabled")
            return
        }

        // Check permissions
        if (!hasRequiredPermissions()) {
            log("Missing required permissions")
            updateStatus("Missing Permissions")
            return
        }

        val scanner = bluetoothAdapter!!.bluetoothLeScanner
        if (scanner == null) {
            log("BLE Scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val nameFilter = ScanFilter.Builder()
            .setDeviceName("FlockDetector")
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        updateStatus("Scanning...")
        updateForegroundNotification("Scanning for FlockDetector...")

        startLocationUpdates()

        scanner.startScan(listOf(filter, nameFilter), settings, scanCallback)
        log("Background scanning started")
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(scanCallback)

        stopLocationUpdates()
        isScanning = false
        updateStatus("Scan Stopped")
        log("Background scanning stopped")

        // Stop foreground and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        updateStatus("Disconnected")
        log("Device disconnected")
    }

    fun isConnected(): Boolean = bluetoothGatt != null

    private fun hasRequiredPermissions(): Boolean {
        val locationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val bluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return locationPermission && bluetoothPermission
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"

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

    @SuppressLint("MissingPermission")
    private fun stopScan(foundDevice: Boolean = false) {
        if (!isScanning) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(scanCallback)

        if (!foundDevice) {
            stopLocationUpdates()
            isScanning = false
            updateStatus("Disconnected")
        } else {
            isScanning = false
            updateStatus("Connecting...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        updateStatus("Connecting to ${device.name ?: "Unknown"}...")
        updateForegroundNotification("Connecting to ${device.name ?: "Unknown"}...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            bluetoothGatt = device.connectGatt(this, true, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected to GATT server.")
                updateStatus("Connected")
                updateForegroundNotification("Connected - Monitoring devices")

                log("Attempting to start service discovery...")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status=$status).")
                updateStatus("Disconnected")
                updateForegroundNotification("Disconnected")

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
            handleCharacteristicChange(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChange(characteristic, value)
        }

        private fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray? = null) {
            if (characteristic.uuid == CHARACTERISTIC_TX_UUID) {
                val data = value ?: byteArrayOf()
                val chunk = String(data)

                dataBuffer.append(chunk)

                var delimiterIndex = dataBuffer.indexOf("\n")
                while (delimiterIndex != -1) {
                    val completeMessage = dataBuffer.substring(0, delimiterIndex)
                    dataBuffer.delete(0, delimiterIndex + 1)

                    if (completeMessage.trim().startsWith("{")) {
                        processJsonMessage(completeMessage)
                    } else {
                        log("Received: $completeMessage")

                        val rssiRegex = "\\[RSSI:(-?\\d+)\\]".toRegex()
                        val matchResult = rssiRegex.find(completeMessage)
                        if (matchResult != null) {
                            val rssi = matchResult.groupValues[1].toInt()
                            callback?.onRssiUpdate(rssi)
                        }

                        if (completeMessage.startsWith("FLOCK DETECTED!")) {
                            sendDetectionNotification(completeMessage)
                        }
                    }

                    delimiterIndex = dataBuffer.indexOf("\n")
                }
            }
        }
    }

    private fun processJsonMessage(jsonStr: String) {
        try {
            val json = org.json.JSONObject(jsonStr)

            val type = json.optString("type", "")
            val protocol = json.optString("protocol", "")
            val rssi = json.optInt("rssi", -100)

            if (rssi != -100) {
                callback?.onRssiUpdate(rssi)
            }

            if (type == "heartbeat") {
                log("Heartbeat: RSSI $rssi")
            } else {
                val mac = json.optString("mac_address", "")
                if (mac.isNotEmpty()) {
                    val deviceCategory = json.optString("device_category", "UNKNOWN")
                    val manufacturer = json.optString("manufacturer", "Unknown")
                    val deviceName = json.optString("device_name", "Unknown")
                    val category = DeviceCategoryUtil.getCategoryFromJson(deviceCategory)
                    val categoryName = DeviceCategoryUtil.getCategoryName(category)
                    val categoryIcon = DeviceCategoryUtil.getCategoryIcon(category)
                    val threatLevel = DeviceCategoryUtil.getThreatLevel(category)

                    // Add detection with full device information
                    val info = DetectionRepository.addDetection(
                        mac = mac,
                        rssi = rssi,
                        location = currentLocation,
                        manufacturer = manufacturer,
                        deviceName = deviceName,
                        deviceCategory = deviceCategory,
                        threatLevel = threatLevel
                    )

                    val countStr = "Count: ${info.count}"
                    val locStr = if (info.lastLat != null) "Loc: ${String.format("%.5f, %.5f", info.lastLat, info.lastLon)}" else "Loc: N/A"

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
                        sendDetectionNotification(msg, category)
                        callback?.onDeviceDetected(msg, category)
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
                        sendDetectionNotification(msg, category)
                        callback?.onDeviceDetected(msg, category)
                    }
                }
            }
        } catch (e: org.json.JSONException) {
            log("Error parsing JSON: $jsonStr")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendDetectionNotification(message: String, category: DeviceCategoryUtil.DeviceCategory = DeviceCategoryUtil.DeviceCategory.UNKNOWN) {
        // Create detection notification channel (different from foreground service channel)
        val detectionChannelId = "flock_notifications"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Flock Detections"
            val descriptionText = "Notifications for detected devices"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(detectionChannelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val categoryName = DeviceCategoryUtil.getCategoryName(category)
        val categoryIcon = DeviceCategoryUtil.getCategoryIcon(category)
        val categoryColor = DeviceCategoryUtil.getCategoryColor(category)

        val builder = NotificationCompat.Builder(this, detectionChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$categoryIcon $categoryName Detected")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(categoryColor)

        with(androidx.core.app.NotificationManagerCompat.from(this)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val providers = locationManager?.allProviders ?: emptyList()

            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    5f,
                    this
                )
                log("GPS location updates started")
            }

            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    5f,
                    this
                )
                log("Network location updates started")
            }

            if (providers.contains(LocationManager.FUSED_PROVIDER)) {
                locationManager?.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    2000L,
                    5f,
                    this
                )
                log("Fused location updates started")
            }
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
        log("Location updated: ${location.latitude}, ${location.longitude}")
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun updateStatus(status: String) {
        callback?.onStatusUpdate(status)
        log("Status: $status")
    }

    private fun log(message: String) {
        Log.d("BluetoothScanService", message)
        callback?.onLogMessage(message)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
        stopLocationUpdates()

        log("Bluetooth Scan Service destroyed")
    }
}
