// ==============================================================================
// MAINACTIVITY INTEGRATION SNIPPET
// Copy and paste these sections into your MainActivity.kt
// ==============================================================================

// ------------------------------------------------------------------------------
// 1. ADD THESE IMPORTS (at the top of MainActivity.kt)
// ------------------------------------------------------------------------------
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager

// ------------------------------------------------------------------------------
// 2. ADD THESE CLASS VARIABLES (inside MainActivity class)
// ------------------------------------------------------------------------------
private var scanService: BluetoothScanService? = null
private var isBound = false

// ------------------------------------------------------------------------------
// 3. ADD THIS SERVICE CONNECTION (inside MainActivity class)
// ------------------------------------------------------------------------------
private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as BluetoothScanService.LocalBinder
        scanService = binder.getService()
        isBound = true

        scanService?.setCallback(object : BluetoothScanService.ServiceCallback {
            override fun onStatusUpdate(status: String) {
                runOnUiThread { updateStatus(status) }
            }

            override fun onLogMessage(message: String) {
                runOnUiThread { log(message) }
            }

            override fun onRssiUpdate(rssi: Int) {
                runOnUiThread { updateRssi(rssi) }
            }

            override fun onDeviceDetected(message: String, category: DeviceCategoryUtil.DeviceCategory) {
                runOnUiThread {
                    log(message)
                    if (isMapVisible) {
                        refreshMap()
                    }
                }
            }
        })

        updateButtonState()
        log("Connected to scanning service")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        scanService = null
        isBound = false
        log("Disconnected from scanning service")
    }
}

// ------------------------------------------------------------------------------
// 4. ADD AT END OF onCreate() METHOD
// ------------------------------------------------------------------------------
// Bind to service if it's already running
if (BluetoothScanService.isRunning()) {
    bindToService()
}
updateButtonState()

// ------------------------------------------------------------------------------
// 5. ADD/UPDATE THESE LIFECYCLE METHODS
// ------------------------------------------------------------------------------
override fun onStart() {
    super.onStart()
    if (BluetoothScanService.isRunning() && !isBound) {
        bindToService()
    }
}

override fun onDestroy() {
    super.onDestroy()

    // Unbind from service
    if (isBound) {
        scanService?.setCallback(null)
        unbindService(serviceConnection)
        isBound = false
    }

    // osmdroid cleanup
    myLocationOverlay?.disableMyLocation()
    mapView.onDetach()
}

// ------------------------------------------------------------------------------
// 6. ADD THESE HELPER METHODS
// ------------------------------------------------------------------------------
private fun bindToService() {
    val intent = Intent(this, BluetoothScanService::class.java)
    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
}

private fun updateButtonState() {
    if (BluetoothScanService.isRunning()) {
        scanButton.text = "STOP SCANNING"
        scanButton.isEnabled = true
    } else {
        scanButton.text = "START SCANNING"
        scanButton.isEnabled = true
    }
}

private fun checkBatteryOptimization() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage("For best results, please disable battery optimization for this app. This ensures notifications work reliably in the background.")
                .setPositiveButton("SETTINGS") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("SKIP", null)
                .show()
        }
    }
}

private fun startScanningService() {
    if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
        Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(this, BluetoothScanService::class.java)
    intent.action = BluetoothScanService.ACTION_START_SCAN

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }

    bindToService()

    log("Starting background scanning service...")
    updateButtonState()
}

private fun stopScanningService() {
    scanService?.stopScanning()

    if (isBound) {
        scanService?.setCallback(null)
        unbindService(serviceConnection)
        isBound = false
    }

    log("Stopped background scanning service")
    updateButtonState()
}

// ------------------------------------------------------------------------------
// 7. REPLACE scanButton.setOnClickListener WITH THIS
// ------------------------------------------------------------------------------
scanButton.setOnClickListener {
    if (BluetoothScanService.isRunning()) {
        stopScanningService()
    } else {
        checkPermissionsAndScan()
    }
}

// ------------------------------------------------------------------------------
// 8. UPDATE requestPermissionLauncher CALLBACK
// ------------------------------------------------------------------------------
private val requestPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBatteryOptimization()
            startScanningService()
        } else {
            Toast.makeText(this, "Permissions denied - app cannot function without them", Toast.LENGTH_LONG).show()
        }
    }

// ------------------------------------------------------------------------------
// 9. UPDATE checkPermissionsAndScan() - ADD BEFORE PERMISSION CHECK
// ------------------------------------------------------------------------------
// Add this permission check for Android 14+
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
        permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
    }
}

// ------------------------------------------------------------------------------
// 10. UPDATE END OF checkPermissionsAndScan() METHOD
// ------------------------------------------------------------------------------
if (permissionsToRequest.isNotEmpty()) {
    requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
} else {
    checkBatteryOptimization()
    startScanningService()
}

// ==============================================================================
// OPTIONAL CLEANUP
// ==============================================================================
// You can remove these old methods as they're now handled by the service:
// - Old BLE scanCallback (keep if you still need it for direct scanning)
// - Old GATT callback (keep if you still need it for direct connection)
// - Old startScan() method that was Activity-based
// - Old stopScan() method that was Activity-based
// - Old connectToDevice() method if duplicated
//
// Keep all UI update methods (updateStatus, updateRssi, log) as the service
// calls them via callbacks.
// ==============================================================================
