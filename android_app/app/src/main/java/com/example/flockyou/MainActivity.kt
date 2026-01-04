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
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var logText: TextView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // UUIDs from the ESP32 code
    private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CHARACTERISTIC_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            log("Found device: $name (${device.address})")
            
            // Stop scanning and connect
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed with error: $errorCode")
            isScanning = false
            updateStatus("Scan Failed")
            scanButton.isEnabled = true
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
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
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
                val data = value ?: characteristic.value
                val message = String(data)
                log("Received: $message")
                
                // Only send notification for actual detections, ignore heartbeats
                if (message.startsWith("FLOCK DETECTED!")) {
                    sendNotification(message)
                }
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()

        statusText = findViewById(R.id.statusText)
        scanButton = findViewById(R.id.scanButton)
        logText = findViewById(R.id.logText)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        scanButton.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                checkPermissionsAndScan()
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
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

        // Add CarExtender for better Android Auto support
        val carExtender = NotificationCompat.CarExtender()
            .setUnreadConversation(
                NotificationCompat.CarExtender.UnreadConversation.Builder("Flock Detection")
                    .setReadPendingIntent(pendingIntent)
                    .setReplyAction(pendingIntent, androidx.core.app.RemoteInput.Builder("voice_reply").build())
                    .addMessage(message)
                    .setLatestTimestamp(System.currentTimeMillis())
                    .build()
            )
        
        builder.extend(carExtender)

        with(androidx.core.app.NotificationManagerCompat.from(this)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
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
