#include <Arduino.h>
#include <WiFi.h>
#include <NimBLEDevice.h>
#include <NimBLEScan.h>
#include <NimBLEAdvertisedDevice.h>
#include <NimBLEServer.h>
#include <NimBLEUtils.h>
#include <ArduinoJson.h>
#include <Adafruit_NeoPixel.h>
#include <string.h>
#include <ctype.h>
#include <stdio.h>
#include <stdint.h>
#include "esp_wifi.h"
#include "esp_wifi_types.h"

// Mutex to protect BLE notifications
SemaphoreHandle_t bleMutex = NULL;

// ============================================================================
// CONFIGURATION
// ============================================================================

// LED Configuration - Using Adafruit NeoPixel on Pin 21 (Common for Waveshare S3 Zero)
// The datasheet confirms it's a WS2812B compatible RGB LED
#define NEOPIXEL_PIN 21
#define NUMPIXELS 1

Adafruit_NeoPixel pixel(NUMPIXELS, NEOPIXEL_PIN, NEO_GRB + NEO_KHZ800);

// BLE Notification Configuration
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E" // UART Service
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// WiFi Promiscuous Mode Configuration
#define MAX_CHANNEL 13
#define CHANNEL_HOP_INTERVAL 500  // milliseconds

// BLE SCANNING CONFIGURATION
#define BLE_SCAN_DURATION 1    // Seconds
#define BLE_SCAN_INTERVAL 5000 // Milliseconds between scans
static unsigned long last_ble_scan = 0;

// Detection Debouncing Configuration
#define DEBOUNCE_WINDOW_MS 30000  // Don't re-alert same device within 30 seconds
#define MAX_SEEN_DEVICES 50       // Cache size for debouncing

// Detection Pattern Limits
#define MAX_SSID_PATTERNS 10
#define MAX_MAC_PATTERNS 50
#define MAX_DEVICE_NAMES 20

// ============================================================================
// DETECTION DEBOUNCE CACHE
// ============================================================================
struct SeenDevice {
    char mac[18];           // MAC address string
    unsigned long lastSeen; // millis() timestamp
    int detectionCount;     // How many times seen
};

static SeenDevice seenDevices[MAX_SEEN_DEVICES];
static int seenDeviceCount = 0;

// ============================================================================
// DETECTION PATTERNS (Extracted from Real Flock Safety Device Databases)
// ============================================================================
//
// MULTI-LAYER DETECTION STRATEGY:
// --------------------------------
// This firmware uses a 3-layer approach to maximize detection capabilities:
//
// LAYER 1: MAC Address OUI Detection (Most Reliable)
//   - Detects via WiFi (longer range: 100-300m+) OR BLE (shorter range: 10-100m)
//   - MAC prefix uniquely identifies manufacturer
//   - HIGH CONFIDENCE regardless of protocol
//
// LAYER 2: WiFi SSID Pattern Matching (Medium-Long Range)
//   - Detects via WiFi probe requests and beacons
//   - Range: 100-300m+ depending on power
//   - MEDIUM-HIGH CONFIDENCE (SSIDs can be spoofed but unlikely)
//   - Can detect devices even without matching OUI
//
// LAYER 3: BLE Device Name Pattern Matching (Short Range)
//   - Detects via BLE advertisements
//   - Range: 10-100m (close proximity)
//   - HIGH CONFIDENCE for positive identification
//   - Can detect devices even without matching OUI
//
// DETECTION PRIORITY:
//   1. OUI match (MAC) = immediate categorization
//   2. SSID/Name match = override or refine categorization
//   3. If both match = HIGHEST CONFIDENCE detection
//
// ============================================================================

// WiFi SSID patterns to detect (case-insensitive)
// These patterns maximize detection across all vendors
static const char* wifi_ssid_patterns[] = {
    // Flock Safety & Surveillance
    "flock", "Flock", "FLOCK",
    "FS Ext Battery",
    "Falcon",           // Flock Falcon cameras
    "Penguin",          // Penguin surveillance devices
    "Pigvision",        // Pigvision surveillance systems

    // Axon (Law Enforcement)
    "Axon", "axon",
    "Axon Body",
    "Axon Fleet",

    // Ring (Security Cameras)
    "Ring", "ring",
    "Ring-",            // Ring devices often use "Ring-XXXXX"

    // Cradlepoint (Network Equipment)
    "Cradlepoint", "cradlepoint",
    "CP ",              // Cradlepoint prefix

    // Aruba Networks
    "Aruba", "aruba",
    "instant",          // Aruba Instant
    "SetMeUp",          // Aruba SetMeUp
    "Aruba-Instant",    // Aruba Instant SSID

    // DJI Drones
    "DJI", "dji",
    "Mavic",
    "Phantom",
    "Mini",
    "Air",              // DJI Air series
    "FPV",              // DJI FPV

    // Parrot Drones
    "Parrot", "parrot",
    "Anafi",
    "Bebop",
    "Disco",

    // Skydio Drones
    "Skydio", "skydio",
    "SKYDIO",

    // Nest/Google Cameras
    "Nest", "nest",
    "Google Nest",

    // Arlo Cameras
    "Arlo", "arlo",
    "VMC",              // Arlo model prefix

    // Eufy Cameras
    "Eufy", "eufy",
    "eufyCam",
    "SoloCam",

    // Wyze Cameras
    "Wyze", "wyze",
    "WYZE",

    // Blink Cameras
    "Blink", "blink"
};

// ============================================================================
// COMPREHENSIVE OUI DATABASE (Organizationally Unique Identifiers)
// ============================================================================

// FLOCK SAFETY & SURVEILLANCE SYSTEMS
static const char* flock_safety_ouis[] = {
    // FS Ext Battery devices
    "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "04:0d:84",
    "f0:82:c0", "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9",

    // Flock WiFi devices
    "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "14:5a:fc",
    "74:4c:a1", "08:3a:88", "9c:2f:9d", "94:08:53", "e4:aa:ea",

    // Flock Safety official OUI (ALPR/Falcon/Raven)
    "b4:1e:52"
};

// CRADLEPOINT (Network Equipment - used by surveillance systems)
// Cradlepoint routers are commonly used in Flock Safety and other surveillance deployments
// They should be detected and correctly identified as "Cradlepoint" manufacturer
static const char* cradlepoint_ouis[] = {
    "00:30:44", "00:e0:1c"
};

// ARUBA NETWORKS (HPE)
static const char* aruba_ouis[] = {
    "00:0b:86", // Aruba Networks
    "00:1a:1e", // Aruba Networks
    "d8:c7:c8", // Aruba Networks
    "ac:a3:1e", // Aruba Networks
    "24:de:c6", // Aruba Networks (HPE)
    "94:b4:0f", // Aruba Networks
    "f4:2e:7f"  // Aruba Networks (HPE)
};

// AXON ENTERPRISE (Law Enforcement Body Cameras)
static const char* axon_ouis[] = {
    "00:25:df"  // Axon Body 2/3, Axon Fleet
};

// RING (Doorbell & Security Cameras)
static const char* ring_ouis[] = {
    "18:7f:88", "24:2b:d6", "34:3e:a4", "54:e0:19",
    "5c:47:5e", "64:9a:63", "90:48:6c", "9c:76:13",
    "ac:9f:c3", "c4:db:ad", "cc:3b:fb"
};

// DJI (Consumer & Commercial Drones)
static const char* dji_ouis[] = {
    "0c:9a:e6", "8c:58:23", "04:a8:5a", "58:b8:58",
    "e4:7a:2c", "60:60:1f", "48:1c:b9", "34:d2:62"
};

// PARROT (Consumer & Commercial Drones)
static const char* parrot_ouis[] = {
    "00:12:1c", "00:26:7e", "90:03:b7",
    "90:3a:e6", "a0:14:3d"
};

// SKYDIO (Commercial & Enterprise Drones)
static const char* skydio_ouis[] = {
    "38:1d:14"
};

// NEST/GOOGLE (Security Cameras & Doorbells)
static const char* nest_ouis[] = {
    "18:b4:30", "1c:f2:9a", "44:07:0b", "54:60:09",
    "64:16:66", "94:94:26", "98:d2:93", "ac:0d:1a",
    "d4:a9:28", "e8:eb:11", "f4:f5:d8", "f4:f5:e8"
};

// ARLO (Security Cameras)
static const char* arlo_ouis[] = {
    "00:1a:3a", "20:df:b9", "28:b4:66", "3c:37:86",
    "44:6c:24", "6c:b0:ce", "84:d6:d0", "9c:53:22",
    "a0:c5:89", "c4:04:15", "c4:41:1e"
};

// EUFY (Security Cameras & Doorbells)
static const char* eufy_ouis[] = {
    "10:d7:b0", "18:3a:2d", "1c:1b:68", "48:a9:d2",
    "60:fd:a8", "74:fe:ce", "78:02:b1", "a4:3b:fa",
    "ac:c1:ee", "d4:a6:51"
};

// WYZE (Budget Security Cameras)
static const char* wyze_ouis[] = {
    "2c:aa:8e", "d0:3f:27", "7c:78:b2", "8c:4b:14"
};

// BLINK (Amazon Security Cameras)
static const char* blink_ouis[] = {
    "18:e7:4a", "24:62:ab", "34:4b:50", "44:91:60",
    "68:9c:70", "74:6f:f7", "b4:7c:9c"
};

// Combined list for quick iteration (legacy compatibility)
// Includes Cradlepoint as they're network equipment used in surveillance infrastructure
static const char* mac_prefixes[] = {
    // Flock Safety & Surveillance
    "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "04:0d:84",
    "f0:82:c0", "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9",
    "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "14:5a:fc",
    "74:4c:a1", "08:3a:88", "9c:2f:9d", "94:08:53", "e4:aa:ea",
    "b4:1e:52",

    // Cradlepoint (Network Equipment used by surveillance systems)
    "00:30:44", "00:e0:1c",

    // Aruba Networks (HPE)
    "00:0b:86", "00:1a:1e", "d8:c7:c8", "ac:a3:1e", "24:de:c6", "94:b4:0f", "f4:2e:7f",

    // Axon
    "00:25:df",

    // Ring
    "18:7f:88", "24:2b:d6", "34:3e:a4", "54:e0:19",
    "5c:47:5e", "64:9a:63", "90:48:6c", "9c:76:13",
    "ac:9f:c3", "c4:db:ad", "cc:3b:fb",

    // DJI
    "0c:9a:e6", "8c:58:23", "04:a8:5a", "58:b8:58",
    "e4:7a:2c", "60:60:1f", "48:1c:b9", "34:d2:62",

    // Parrot
    "00:12:1c", "00:26:7e", "90:03:b7", "90:3a:e6", "a0:14:3d",

    // Skydio
    "38:1d:14"
};

// Device name patterns for BLE advertisement detection
// BLE has shorter range (10-100m) so these are HIGH CONFIDENCE close-range detections
static const char* device_name_patterns[] = {
    // Flock Safety & Surveillance
    "FS Ext Battery",  // Flock Safety Extended Battery
    "Flock",           // Standard Flock Safety devices
    "Falcon",          // Flock Falcon cameras
    "Raven",           // Flock Raven gunshot detection
    "Penguin",         // Penguin surveillance devices
    "Pigvision",       // Pigvision surveillance systems

    // Axon (Law Enforcement)
    "Axon",            // Axon Body Cam / Fleet
    "Axon Body",       // Axon Body Cam specific
    "Axon Fleet",      // Axon Fleet system
    "Body 2",          // Axon Body 2
    "Body 3",          // Axon Body 3
    "Body 4",          // Axon Body 4

    // Ring (Security Cameras)
    "Ring",            // Ring Doorbell / Camera
    "Ring-",           // Ring devices with suffix

    // Cradlepoint (Network Equipment)
    "Cradlepoint",     // Cradlepoint routers
    "IBR",             // Cradlepoint IBR series
    "AER",             // Cradlepoint AER series

    // Aruba Networks
    "Aruba",           // Aruba devices
    "Instant",         // Aruba Instant

    // DJI Drones
    "DJI",             // DJI drones
    "Mavic",           // DJI Mavic series
    "Phantom",         // DJI Phantom series
    "Mini",            // DJI Mini series
    "Air",             // DJI Air series
    "FPV",             // DJI FPV
    "Inspire",         // DJI Inspire
    "Matrice",         // DJI Matrice (commercial)

    // Parrot Drones
    "Parrot",          // Parrot drones
    "Anafi",           // Parrot Anafi
    "Bebop",           // Parrot Bebop
    "Disco",           // Parrot Disco

    // Skydio Drones
    "Skydio",          // Skydio drones
    "S2",              // Skydio 2
    "X2"               // Skydio X2 (commercial/enterprise)
};

// ============================================================================
// RAVEN SURVEILLANCE DEVICE UUID PATTERNS
// ============================================================================
// These UUIDs are specific to Raven surveillance devices (acoustic gunshot detection)
// Source: raven_configurations.json - firmware versions 1.1.7, 1.2.0, 1.3.1

// Raven Device Information Service (used across all firmware versions)
#define RAVEN_DEVICE_INFO_SERVICE       "0000180a-0000-1000-8000-00805f9b34fb"

// Raven GPS Location Service (firmware 1.2.0+)
#define RAVEN_GPS_SERVICE               "00003100-0000-1000-8000-00805f9b34fb"

// Raven Power/Battery Service (firmware 1.2.0+)
#define RAVEN_POWER_SERVICE             "00003200-0000-1000-8000-00805f9b34fb"

// Raven Network Status Service (firmware 1.2.0+)
#define RAVEN_NETWORK_SERVICE           "00003300-0000-1000-8000-00805f9b34fb"

// Raven Upload Statistics Service (firmware 1.2.0+)
#define RAVEN_UPLOAD_SERVICE            "00003400-0000-1000-8000-00805f9b34fb"

// Raven Error/Failure Service (firmware 1.2.0+)
#define RAVEN_ERROR_SERVICE             "00003500-0000-1000-8000-00805f9b34fb"

// Health Thermometer Service (firmware 1.1.7)
#define RAVEN_OLD_HEALTH_SERVICE        "00001809-0000-1000-8000-00805f9b34fb"

// Location and Navigation Service (firmware 1.1.7)
#define RAVEN_OLD_LOCATION_SERVICE      "00001819-0000-1000-8000-00805f9b34fb"

// Known Raven service UUIDs for detection
static const char* raven_service_uuids[] = {
    RAVEN_DEVICE_INFO_SERVICE,    // Device info (all versions)
    RAVEN_GPS_SERVICE,            // GPS data (1.2.0+)
    RAVEN_POWER_SERVICE,          // Battery/Solar (1.2.0+)
    RAVEN_NETWORK_SERVICE,        // LTE/WiFi status (1.2.0+)
    RAVEN_UPLOAD_SERVICE,         // Upload stats (1.2.0+)
    RAVEN_ERROR_SERVICE,          // Error tracking (1.2.0+)
    RAVEN_OLD_HEALTH_SERVICE,     // Old health service (1.1.7)
    RAVEN_OLD_LOCATION_SERVICE    // Old location service (1.1.7)
};

// ============================================================================
// GLOBAL VARIABLES
// ============================================================================

enum DetectionType {
    NONE = 0,
    FLOCK_SAFETY = 1,
    AXON = 2,
    RAVEN = 3,
    RING = 4,
    CRADLEPOINT = 5,
    DRONE = 6,
    NEST_GOOGLE = 7,
    ARLO = 8,
    EUFY = 9,
    WYZE = 10,
    BLINK = 11,
    ARUBA = 12
};

static DetectionType current_detection_type = NONE;
static uint8_t current_channel = 1;
static unsigned long last_channel_hop = 0;
static bool triggered = false;
static bool device_in_range = false;
static unsigned long last_detection_time = 0;
static unsigned long last_heartbeat = 0;
static int last_rssi = -100;
static NimBLEScan* pBLEScan;
static NimBLEServer* pServer = NULL;
static NimBLECharacteristic* pTxCharacteristic = NULL;
static bool deviceConnected = false;

// Session statistics
static unsigned long session_start_time = 0;
static int total_wifi_detections = 0;
static int total_ble_detections = 0;
static int unique_devices_seen = 0;

// ============================================================================
// DEBOUNCE HELPER FUNCTIONS
// ============================================================================

// Check if device was recently seen (returns true if should be debounced/skipped)
bool isDeviceDebounced(const char* mac) {
    unsigned long now = millis();
    
    // Search for existing entry
    for (int i = 0; i < seenDeviceCount; i++) {
        if (strcasecmp(seenDevices[i].mac, mac) == 0) {
            // Found it - check if within debounce window
            if (now - seenDevices[i].lastSeen < DEBOUNCE_WINDOW_MS) {
                seenDevices[i].detectionCount++;
                seenDevices[i].lastSeen = now;
                return true;  // Debounce - skip alert
            } else {
                // Expired - update timestamp and allow alert
                seenDevices[i].lastSeen = now;
                seenDevices[i].detectionCount++;
                return false;
            }
        }
    }
    
    // New device - add to cache
    if (seenDeviceCount < MAX_SEEN_DEVICES) {
        strncpy(seenDevices[seenDeviceCount].mac, mac, 17);
        seenDevices[seenDeviceCount].mac[17] = '\0';
        seenDevices[seenDeviceCount].lastSeen = now;
        seenDevices[seenDeviceCount].detectionCount = 1;
        seenDeviceCount++;
        unique_devices_seen++;
    } else {
        // Cache full - find oldest entry and replace
        int oldestIdx = 0;
        unsigned long oldestTime = seenDevices[0].lastSeen;
        for (int i = 1; i < MAX_SEEN_DEVICES; i++) {
            if (seenDevices[i].lastSeen < oldestTime) {
                oldestTime = seenDevices[i].lastSeen;
                oldestIdx = i;
            }
        }
        strncpy(seenDevices[oldestIdx].mac, mac, 17);
        seenDevices[oldestIdx].mac[17] = '\0';
        seenDevices[oldestIdx].lastSeen = now;
        seenDevices[oldestIdx].detectionCount = 1;
        unique_devices_seen++;
    }
    
    return false;  // New device - allow alert
}

// Get detection count for a device
int getDeviceDetectionCount(const char* mac) {
    for (int i = 0; i < seenDeviceCount; i++) {
        if (strcasecmp(seenDevices[i].mac, mac) == 0) {
            return seenDevices[i].detectionCount;
        }
    }
    return 0;
}

// ============================================================================
// FORWARD DECLARATIONS
// ============================================================================
DetectionType categorize_by_mac(const char* mac_prefix);
const char* get_manufacturer_name(const char* mac_prefix);

// ============================================================================
// BLE NOTIFICATION SYSTEM
// ============================================================================

class MyServerCallbacks: public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer) {
        deviceConnected = true;
        printf("Client connected\n");
    };

    void onDisconnect(NimBLEServer* pServer) {
        deviceConnected = false;
        printf("Client disconnected\n");
        pServer->startAdvertising(); // Restart advertising
    }
};

void send_notification(String message) {
    if (deviceConnected && pTxCharacteristic != NULL) {
        // Take mutex to ensure atomic transmission
        // unique_lock would be nicer but we are in C-ish land
        if (bleMutex != NULL) {
            xSemaphoreTake(bleMutex, portMAX_DELAY);
        }

        // Append newline as delimiter
        message += "\n";
        
        size_t length = message.length();
        const char* data = message.c_str();
        size_t offset = 0;
        
        // Use a safe chunk size (20 bytes is standard BLE MTU safe limit)
        // We could negotiate higher, but 20 ensures compatibility
        const size_t chunk_size = 20;
        
        while (offset < length) {
            size_t len = (length - offset) > chunk_size ? chunk_size : (length - offset);
            std::string chunk(data + offset, len);
            pTxCharacteristic->setValue(chunk);
            pTxCharacteristic->notify();
            offset += len;
            delay(5); // Small delay to prevent congestion
        }
        printf("Notification sent (chunked): %d bytes\n", length);

        if (bleMutex != NULL) {
            xSemaphoreGive(bleMutex);
        }
    }
}

void flock_detected_notification(String details)
{
    // This function is now deprecated in favor of sending full JSON
    // But we keep it for the heartbeat logic
    printf("FLOCK SAFETY DEVICE DETECTED!\n");
}

// ============================================================================
// JSON OUTPUT FUNCTIONS
// ============================================================================

void update_detection_state(DetectionType new_type) {
    device_in_range = true; // Enable LED and Heartbeat
    if (!triggered) {
        triggered = true;
        current_detection_type = new_type;
    } else {
        // Upgrade priority if new detection is more critical
        if (new_type > current_detection_type) {
            current_detection_type = new_type;
        }
    }
    // Update timestamps
    last_detection_time = millis();
}

void output_wifi_detection_json(const char* ssid, const uint8_t* mac, int rssi, const char* detection_type)
{
    char mac_prefix[9];
    snprintf(mac_prefix, sizeof(mac_prefix), "%02x:%02x:%02x", mac[0], mac[1], mac[2]);
    
    // Categorize by MAC address
    DetectionType resolved_type = categorize_by_mac(mac_prefix);
    
    // Override with SSID if it gives us more specific info
    // WiFi detection = longer range (100-300m+), medium-high confidence
    if (ssid && strlen(ssid) > 0) {
        // Check Raven first (highest priority)
        if (strcasestr(ssid, "raven")) {
            resolved_type = RAVEN;
        }
        // Check Axon (law enforcement)
        else if (strcasestr(ssid, "axon") || strcasestr(ssid, "body 2") || strcasestr(ssid, "body 3")) {
            resolved_type = AXON;
        }
        // Check Ring
        else if (strcasestr(ssid, "ring")) {
            resolved_type = RING;
        }
        // Check Cradlepoint
        else if (strcasestr(ssid, "cradlepoint") || strcasestr(ssid, "cp ")) {
            resolved_type = CRADLEPOINT;
        }
        // Check Aruba
        else if (strcasestr(ssid, "aruba") || strcasestr(ssid, "instant") || strcasestr(ssid, "setmeup")) {
            resolved_type = ARUBA;
        }
        // Check Drones (DJI, Parrot, Skydio)
        else if (strcasestr(ssid, "dji") || strcasestr(ssid, "mavic") || strcasestr(ssid, "phantom") ||
                 strcasestr(ssid, "mini") || strcasestr(ssid, "air") || strcasestr(ssid, "fpv") ||
                 strcasestr(ssid, "inspire") || strcasestr(ssid, "matrice") ||
                 strcasestr(ssid, "parrot") || strcasestr(ssid, "anafi") || strcasestr(ssid, "bebop") ||
                 strcasestr(ssid, "disco") || strcasestr(ssid, "skydio")) {
            resolved_type = DRONE;
        }
        // Check Nest/Google
        else if (strcasestr(ssid, "nest") || strcasestr(ssid, "google")) {
            resolved_type = NEST_GOOGLE;
        }
        // Check Arlo
        else if (strcasestr(ssid, "arlo") || strcasestr(ssid, "vmc")) {
            resolved_type = ARLO;
        }
        // Check Eufy
        else if (strcasestr(ssid, "eufy") || strcasestr(ssid, "solocam")) {
            resolved_type = EUFY;
        }
        // Check Wyze
        else if (strcasestr(ssid, "wyze")) {
            resolved_type = WYZE;
        }
        // Check Blink
        else if (strcasestr(ssid, "blink")) {
            resolved_type = BLINK;
        }
        // Check Flock Safety (keep this last as it's most common)
        else if (strcasestr(ssid, "flock") || strcasestr(ssid, "falcon") ||
                 strcasestr(ssid, "penguin") || strcasestr(ssid, "pigvision") ||
                 strcasestr(ssid, "fs ext battery")) {
            resolved_type = FLOCK_SAFETY;
        }
    }

    // Default to NONE if no specific match (don't force Flock Safety)
    /* 
    if (resolved_type == NONE) {
        resolved_type = FLOCK_SAFETY;
    } 
    */

    update_detection_state(resolved_type);
    last_rssi = rssi;
    
    const char* manufacturer = get_manufacturer_name(mac_prefix);

    DynamicJsonDocument doc(2048);

    // Core detection info
    doc["timestamp"] = millis();
    doc["detection_time"] = String(millis() / 1000.0, 3) + "s";
    doc["protocol"] = "wifi";
    doc["detection_method"] = detection_type;

    // Detection range and confidence
    // WiFi = longer range (100-300m+), medium-high confidence
    doc["detection_range"] = "MEDIUM_TO_FAR";
    doc["estimated_distance"] = "100-300m";

    doc["alert_level"] = "HIGH";
    doc["device_category"] = "FLOCK_SAFETY";
    
    // WiFi specific info
    doc["ssid"] = ssid;
    doc["ssid_length"] = strlen(ssid);
    doc["rssi"] = rssi;
    doc["signal_strength"] = rssi > -50 ? "STRONG" : (rssi > -70 ? "MEDIUM" : "WEAK");
    doc["channel"] = current_channel;
    
    // MAC address info
    char mac_str[18];
    snprintf(mac_str, sizeof(mac_str), "%02x:%02x:%02x:%02x:%02x:%02x", 
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    doc["mac_address"] = mac_str;
    
    // mac_prefix already calculated above
    doc["mac_prefix"] = mac_prefix;
    doc["vendor_oui"] = mac_prefix;
    doc["manufacturer"] = manufacturer;
    
    // Device category based on detection type (vendor-specific with grouping)
    const char* device_category_str;
    switch(resolved_type) {
        case FLOCK_SAFETY: device_category_str = "FLOCK_SAFETY"; break;
        case AXON: device_category_str = "AXON"; break;
        case RAVEN: device_category_str = "RAVEN"; break;
        case RING: device_category_str = "RING"; break;
        case CRADLEPOINT: device_category_str = "CRADLEPOINT"; break;
        case ARUBA: device_category_str = "ARUBA"; break;
        case DRONE: device_category_str = "DRONE"; break;
        default: device_category_str = "UNKNOWN"; break;
    }
    doc["device_category"] = device_category_str;
    
    // Detection pattern matching
    bool ssid_match = false;
    bool mac_match = false;
    
    for (int i = 0; i < sizeof(wifi_ssid_patterns)/sizeof(wifi_ssid_patterns[0]); i++) {
        if (strcasestr(ssid, wifi_ssid_patterns[i])) {
            doc["matched_ssid_pattern"] = wifi_ssid_patterns[i];
            doc["ssid_match_confidence"] = "HIGH";
            ssid_match = true;
            break;
        }
    }
    
    for (int i = 0; i < sizeof(mac_prefixes)/sizeof(mac_prefixes[0]); i++) {
        if (strncasecmp(mac_prefix, mac_prefixes[i], 8) == 0) {
            doc["matched_mac_pattern"] = mac_prefixes[i];
            doc["mac_match_confidence"] = "HIGH";
            mac_match = true;
            break;
        }
    }
    
    // Detection summary and confidence scoring
    // Highest confidence = both MAC and SSID match
    // High confidence = MAC match only (OUI is reliable)
    // Medium confidence = SSID match only (can be spoofed)
    if (ssid_match && mac_match) {
        doc["detection_criteria"] = "SSID_AND_MAC";
        doc["detection_confidence"] = "HIGHEST";
        doc["threat_score"] = 100;
    } else if (mac_match) {
        doc["detection_criteria"] = "MAC_ONLY";
        doc["detection_confidence"] = "HIGH";
        doc["threat_score"] = 90;
    } else if (ssid_match) {
        doc["detection_criteria"] = "SSID_ONLY";
        doc["detection_confidence"] = "MEDIUM";
        doc["threat_score"] = 75;
    } else {
        doc["detection_criteria"] = "PATTERN_MATCH";
        doc["detection_confidence"] = "MEDIUM";
        doc["threat_score"] = 70;
    }
    
    // Frame type details
    if (strcmp(detection_type, "probe_request") == 0 || strcmp(detection_type, "probe_request_mac") == 0) {
        doc["frame_type"] = "PROBE_REQUEST";
        doc["frame_description"] = "Device actively scanning for networks";
    } else {
        doc["frame_type"] = "BEACON";
        doc["frame_description"] = "Device advertising its network";
    }
    
    String json_output;
    serializeJson(doc, json_output);
    Serial.println(json_output);
    
    // Send full JSON over BLE
    send_notification(json_output);
}

void output_ble_detection_json(const char* mac, const char* name, int rssi, const char* detection_method)
{
    // Extract MAC prefix for categorization
    char mac_prefix[9];
    strncpy(mac_prefix, mac, 8);
    mac_prefix[8] = '\0';

    DetectionType resolved_type = categorize_by_mac(mac_prefix);

    // Override with name if it gives us more specific info
    // BLE detection = shorter range (10-100m), HIGH CONFIDENCE close proximity
    if (name && strlen(name) > 0) {
        // Check Raven first (highest priority - gunshot detection)
        if (strcasestr(name, "raven")) {
            resolved_type = RAVEN;
        }
        // Check Axon (law enforcement body cams)
        else if (strcasestr(name, "axon") || strcasestr(name, "body 2") ||
                 strcasestr(name, "body 3") || strcasestr(name, "body 4")) {
            resolved_type = AXON;
        }
        // Check Ring (security cameras)
        else if (strcasestr(name, "ring")) {
            resolved_type = RING;
        }
        // Check Cradlepoint (network equipment)
        else if (strcasestr(name, "cradlepoint") || strcasestr(name, "ibr") || strcasestr(name, "aer")) {
            resolved_type = CRADLEPOINT;
        }
        // Check Aruba (network equipment)
        else if (strcasestr(name, "aruba") || strcasestr(name, "instant")) {
            resolved_type = ARUBA;
        }
        // Check Drones (DJI, Parrot, Skydio)
        else if (strcasestr(name, "dji") || strcasestr(name, "mavic") || strcasestr(name, "phantom") ||
                 strcasestr(name, "mini") || strcasestr(name, "air") || strcasestr(name, "fpv") ||
                 strcasestr(name, "inspire") || strcasestr(name, "matrice") ||
                 strcasestr(name, "parrot") || strcasestr(name, "anafi") || strcasestr(name, "bebop") ||
                 strcasestr(name, "disco") || strcasestr(name, "skydio") ||
                 strcasestr(name, "s2") || strcasestr(name, "x2")) {
            resolved_type = DRONE;
        }
        // Check Nest/Google
        else if (strcasestr(name, "nest") || strcasestr(name, "google")) {
            resolved_type = NEST_GOOGLE;
        }
        // Check Arlo
        else if (strcasestr(name, "arlo") || strcasestr(name, "vmc")) {
            resolved_type = ARLO;
        }
        // Check Eufy
        else if (strcasestr(name, "eufy") || strcasestr(name, "solocam")) {
            resolved_type = EUFY;
        }
        // Check Wyze
        else if (strcasestr(name, "wyze")) {
            resolved_type = WYZE;
        }
        // Check Blink
        else if (strcasestr(name, "blink")) {
            resolved_type = BLINK;
        }
        // Check Flock Safety (surveillance cameras)
        else if (strcasestr(name, "flock") || strcasestr(name, "falcon") ||
                 strcasestr(name, "penguin") || strcasestr(name, "pigvision") ||
                 strcasestr(name, "fs ext battery")) {
            resolved_type = FLOCK_SAFETY;
        }
    }

    // Default to NONE if no specific match (don't force Flock Safety)
    /*
    if (resolved_type == NONE) {
        resolved_type = FLOCK_SAFETY;
    }
    */

    update_detection_state(resolved_type);
    last_rssi = rssi;

    DynamicJsonDocument doc(2048);

    // Core detection info
    doc["timestamp"] = millis();
    doc["detection_time"] = String(millis() / 1000.0, 3) + "s";
    doc["protocol"] = "bluetooth_le";
    doc["detection_method"] = detection_method;

    // Detection range and confidence
    // BLE = shorter range (10-100m), HIGH CONFIDENCE close proximity
    doc["detection_range"] = "CLOSE";
    doc["estimated_distance"] = "10-100m";
    doc["proximity_confidence"] = "HIGH";

    doc["alert_level"] = "HIGH";

    // Device category based on detection type (vendor-specific with grouping)
    const char* device_category_str;
    switch(resolved_type) {
        case FLOCK_SAFETY: device_category_str = "FLOCK_SAFETY"; break;
        case AXON: device_category_str = "AXON"; break;
        case RAVEN: device_category_str = "RAVEN"; break;
        case RING: device_category_str = "RING"; break;
        case CRADLEPOINT: device_category_str = "CRADLEPOINT"; break;
        case ARUBA: device_category_str = "ARUBA"; break;
        case DRONE: device_category_str = "DRONE"; break;
        default: device_category_str = "UNKNOWN"; break;
    }
    doc["device_category"] = device_category_str;

    // BLE specific info
    doc["mac_address"] = mac;
    doc["rssi"] = rssi;
    doc["signal_strength"] = rssi > -50 ? "STRONG" : (rssi > -70 ? "MEDIUM" : "WEAK");

    // Get manufacturer name from OUI
    const char* manufacturer = get_manufacturer_name(mac_prefix);
    doc["manufacturer"] = manufacturer;
    
    // Device name info
    if (name && strlen(name) > 0) {
        doc["device_name"] = name;
        doc["device_name_length"] = strlen(name);
        doc["has_device_name"] = true;
    } else {
        doc["device_name"] = "";
        doc["device_name_length"] = 0;
        doc["has_device_name"] = false;
    }

    // MAC address analysis (mac_prefix already extracted at top of function)
    doc["mac_prefix"] = mac_prefix;
    doc["vendor_oui"] = mac_prefix;
    
    // Detection pattern matching
    bool name_match = false;
    bool mac_match = false;
    
    // Check MAC prefix patterns
    for (int i = 0; i < sizeof(mac_prefixes)/sizeof(mac_prefixes[0]); i++) {
        if (strncasecmp(mac, mac_prefixes[i], strlen(mac_prefixes[i])) == 0) {
            doc["matched_mac_pattern"] = mac_prefixes[i];
            doc["mac_match_confidence"] = "HIGH";
            mac_match = true;
            break;
        }
    }
    
    // Check device name patterns
    if (name && strlen(name) > 0) {
        for (int i = 0; i < sizeof(device_name_patterns)/sizeof(device_name_patterns[0]); i++) {
            if (strcasestr(name, device_name_patterns[i])) {
                doc["matched_name_pattern"] = device_name_patterns[i];
                doc["name_match_confidence"] = "HIGH";
                name_match = true;
                break;
            }
        }
    }
    
    // Detection summary and confidence scoring
    // BLE = close range detection = inherently higher confidence
    // Highest confidence = both MAC and name match
    // High confidence = MAC match only (OUI is reliable)
    // High confidence = Name match only (BLE names are fairly reliable at close range)
    if (name_match && mac_match) {
        doc["detection_criteria"] = "NAME_AND_MAC";
        doc["detection_confidence"] = "HIGHEST";
        doc["threat_score"] = 100;
    } else if (mac_match) {
        doc["detection_criteria"] = "MAC_ONLY";
        doc["detection_confidence"] = "HIGH";
        doc["threat_score"] = 90;
    } else if (name_match) {
        doc["detection_criteria"] = "NAME_ONLY";
        doc["detection_confidence"] = "HIGH";
        doc["threat_score"] = 85;
    } else {
        doc["detection_criteria"] = "PATTERN_MATCH";
        doc["detection_confidence"] = "MEDIUM";
        doc["threat_score"] = 70;
    }
    
    // BLE advertisement type analysis
    doc["advertisement_type"] = "BLE_ADVERTISEMENT";
    doc["advertisement_description"] = "Bluetooth Low Energy device advertisement";
    
    // Detection method details
    if (strcmp(detection_method, "mac_prefix") == 0) {
        doc["primary_indicator"] = "MAC_ADDRESS";
        doc["detection_reason"] = "MAC address matches known Flock Safety prefix";
    } else if (strcmp(detection_method, "device_name") == 0) {
        doc["primary_indicator"] = "DEVICE_NAME";
        doc["detection_reason"] = "Device name matches Flock Safety pattern";
    }
    
    String json_output;
    serializeJson(doc, json_output);
    Serial.println(json_output);
    
    // Set global state to triggered
    device_in_range = true; 
    last_detection_time = millis();
    triggered = true;

    // Send full JSON over BLE
    send_notification(json_output);
}

// ============================================================================
// DETECTION HELPER FUNCTIONS
// ============================================================================

// Categorize device by MAC prefix
DetectionType categorize_by_mac(const char* mac_prefix)
{
    // Check Raven (highest priority for gunshot detection)
    // This should be handled separately via BLE service UUIDs

    // Check Axon
    for (int i = 0; i < sizeof(axon_ouis)/sizeof(axon_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, axon_ouis[i], 8) == 0) {
            return AXON;
        }
    }

    // Check Cradlepoint (must be before Flock Safety check)
    // Network equipment used by surveillance systems - detect and alert
    for (int i = 0; i < sizeof(cradlepoint_ouis)/sizeof(cradlepoint_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, cradlepoint_ouis[i], 8) == 0) {
            return CRADLEPOINT;
        }
    }

    // Check Aruba
    for (int i = 0; i < sizeof(aruba_ouis)/sizeof(aruba_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, aruba_ouis[i], 8) == 0) {
            return ARUBA;
        }
    }

    // Check Flock Safety
    for (int i = 0; i < sizeof(flock_safety_ouis)/sizeof(flock_safety_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, flock_safety_ouis[i], 8) == 0) {
            return FLOCK_SAFETY;
        }
    }

    // Check Ring
    for (int i = 0; i < sizeof(ring_ouis)/sizeof(ring_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, ring_ouis[i], 8) == 0) {
            return RING;
        }
    }

    // Check DJI (Drones)
    for (int i = 0; i < sizeof(dji_ouis)/sizeof(dji_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, dji_ouis[i], 8) == 0) {
            return DRONE;
        }
    }

    // Check Parrot (Drones)
    for (int i = 0; i < sizeof(parrot_ouis)/sizeof(parrot_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, parrot_ouis[i], 8) == 0) {
            return DRONE;
        }
    }

    // Check Skydio (Drones)
    for (int i = 0; i < sizeof(skydio_ouis)/sizeof(skydio_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, skydio_ouis[i], 8) == 0) {
            return DRONE;
        }
    }

    // Check Nest/Google
    for (int i = 0; i < sizeof(nest_ouis)/sizeof(nest_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, nest_ouis[i], 8) == 0) {
            return NEST_GOOGLE;
        }
    }

    // Check Arlo
    for (int i = 0; i < sizeof(arlo_ouis)/sizeof(arlo_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, arlo_ouis[i], 8) == 0) {
            return ARLO;
        }
    }

    // Check Eufy
    for (int i = 0; i < sizeof(eufy_ouis)/sizeof(eufy_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, eufy_ouis[i], 8) == 0) {
            return EUFY;
        }
    }

    // Check Wyze
    for (int i = 0; i < sizeof(wyze_ouis)/sizeof(wyze_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, wyze_ouis[i], 8) == 0) {
            return WYZE;
        }
    }

    // Check Blink
    for (int i = 0; i < sizeof(blink_ouis)/sizeof(blink_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, blink_ouis[i], 8) == 0) {
            return BLINK;
        }
    }

    return NONE;
}

// Get manufacturer name from MAC prefix
const char* get_manufacturer_name(const char* mac_prefix)
{
    // Check all OUI arrays - order matters for specificity
    for (int i = 0; i < sizeof(axon_ouis)/sizeof(axon_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, axon_ouis[i], 8) == 0) return "Axon Enterprise";
    }
    for (int i = 0; i < sizeof(cradlepoint_ouis)/sizeof(cradlepoint_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, cradlepoint_ouis[i], 8) == 0) return "Cradlepoint";
    }
    for (int i = 0; i < sizeof(aruba_ouis)/sizeof(aruba_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, aruba_ouis[i], 8) == 0) return "Aruba Networks";
    }
    for (int i = 0; i < sizeof(flock_safety_ouis)/sizeof(flock_safety_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, flock_safety_ouis[i], 8) == 0) return "Flock Safety";
    }
    for (int i = 0; i < sizeof(ring_ouis)/sizeof(ring_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, ring_ouis[i], 8) == 0) return "Ring/Amazon";
    }
    for (int i = 0; i < sizeof(dji_ouis)/sizeof(dji_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, dji_ouis[i], 8) == 0) return "DJI";
    }
    for (int i = 0; i < sizeof(parrot_ouis)/sizeof(parrot_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, parrot_ouis[i], 8) == 0) return "Parrot";
    }
    for (int i = 0; i < sizeof(skydio_ouis)/sizeof(skydio_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, skydio_ouis[i], 8) == 0) return "Skydio";
    }
    for (int i = 0; i < sizeof(nest_ouis)/sizeof(nest_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, nest_ouis[i], 8) == 0) return "Nest/Google";
    }
    for (int i = 0; i < sizeof(arlo_ouis)/sizeof(arlo_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, arlo_ouis[i], 8) == 0) return "Arlo";
    }
    for (int i = 0; i < sizeof(eufy_ouis)/sizeof(eufy_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, eufy_ouis[i], 8) == 0) return "Eufy";
    }
    for (int i = 0; i < sizeof(wyze_ouis)/sizeof(wyze_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, wyze_ouis[i], 8) == 0) return "Wyze";
    }
    for (int i = 0; i < sizeof(blink_ouis)/sizeof(blink_ouis[0]); i++) {
        if (strncasecmp(mac_prefix, blink_ouis[i], 8) == 0) return "Blink/Amazon";
    }
    return "Unknown";
}

bool check_mac_prefix(const uint8_t* mac)
{
    char mac_str[9];  // Only need first 3 octets for prefix check
    snprintf(mac_str, sizeof(mac_str), "%02x:%02x:%02x", mac[0], mac[1], mac[2]);
    
    for (int i = 0; i < sizeof(mac_prefixes)/sizeof(mac_prefixes[0]); i++) {
        if (strncasecmp(mac_str, mac_prefixes[i], 8) == 0) {
            return true;
        }
    }
    return false;
}

bool check_ssid_pattern(const char* ssid)
{
    if (!ssid) return false;
    
    for (int i = 0; i < sizeof(wifi_ssid_patterns)/sizeof(wifi_ssid_patterns[0]); i++) {
        if (strcasestr(ssid, wifi_ssid_patterns[i])) {
            return true;
        }
    }
    return false;
}

bool check_device_name_pattern(const char* name)
{
    if (!name) return false;
    
    for (int i = 0; i < sizeof(device_name_patterns)/sizeof(device_name_patterns[0]); i++) {
        if (strcasestr(name, device_name_patterns[i])) {
            return true;
        }
    }
    return false;
}

// ============================================================================
// RAVEN UUID DETECTION
// ============================================================================

// Check if a BLE device advertises any Raven surveillance service UUIDs
bool check_raven_service_uuid(NimBLEAdvertisedDevice* device, char* detected_service_out = nullptr)
{
    if (!device) return false;
    
    // Check if device has service UUIDs
    if (!device->haveServiceUUID()) return false;
    
    // Get the number of service UUIDs
    int serviceCount = device->getServiceUUIDCount();
    if (serviceCount == 0) return false;
    
    // Check each advertised service UUID against known Raven UUIDs
    for (int i = 0; i < serviceCount; i++) {
        NimBLEUUID serviceUUID = device->getServiceUUID(i);
        std::string uuidStr = serviceUUID.toString();
        
        // Compare against each known Raven service UUID
        for (int j = 0; j < sizeof(raven_service_uuids)/sizeof(raven_service_uuids[0]); j++) {
            if (strcasecmp(uuidStr.c_str(), raven_service_uuids[j]) == 0) {
                // Match found! Store the detected service UUID if requested
                if (detected_service_out != nullptr) {
                    strncpy(detected_service_out, uuidStr.c_str(), 40);
                }
                return true;
            }
        }
    }
    
    return false;
}

// Get a human-readable description of the Raven service
const char* get_raven_service_description(const char* uuid)
{
    if (!uuid) return "Unknown Service";
    
    if (strcasecmp(uuid, RAVEN_DEVICE_INFO_SERVICE) == 0)
        return "Device Information (Serial, Model, Firmware)";
    if (strcasecmp(uuid, RAVEN_GPS_SERVICE) == 0)
        return "GPS Location Service (Lat/Lon/Alt)";
    if (strcasecmp(uuid, RAVEN_POWER_SERVICE) == 0)
        return "Power Management (Battery/Solar)";
    if (strcasecmp(uuid, RAVEN_NETWORK_SERVICE) == 0)
        return "Network Status (LTE/WiFi)";
    if (strcasecmp(uuid, RAVEN_UPLOAD_SERVICE) == 0)
        return "Upload Statistics Service";
    if (strcasecmp(uuid, RAVEN_ERROR_SERVICE) == 0)
        return "Error/Failure Tracking Service";
    if (strcasecmp(uuid, RAVEN_OLD_HEALTH_SERVICE) == 0)
        return "Health/Temperature Service (Legacy)";
    if (strcasecmp(uuid, RAVEN_OLD_LOCATION_SERVICE) == 0)
        return "Location Service (Legacy)";
    
    return "Unknown Raven Service";
}

// Estimate firmware version based on detected service UUIDs
const char* estimate_raven_firmware_version(NimBLEAdvertisedDevice* device)
{
    if (!device || !device->haveServiceUUID()) return "Unknown";
    
    bool has_new_gps = false;
    bool has_old_location = false;
    bool has_power_service = false;
    
    int serviceCount = device->getServiceUUIDCount();
    for (int i = 0; i < serviceCount; i++) {
        NimBLEUUID serviceUUID = device->getServiceUUID(i);
        std::string uuidStr = serviceUUID.toString();
        
        if (strcasecmp(uuidStr.c_str(), RAVEN_GPS_SERVICE) == 0)
            has_new_gps = true;
        if (strcasecmp(uuidStr.c_str(), RAVEN_OLD_LOCATION_SERVICE) == 0)
            has_old_location = true;
        if (strcasecmp(uuidStr.c_str(), RAVEN_POWER_SERVICE) == 0)
            has_power_service = true;
    }
    
    // Firmware version heuristics based on service presence
    if (has_old_location && !has_new_gps)
        return "1.1.x (Legacy)";
    if (has_new_gps && !has_power_service)
        return "1.2.x";
    if (has_new_gps && has_power_service)
        return "1.3.x (Latest)";
    
    return "Unknown Version";
}

// ============================================================================
// WIFI PROMISCUOUS MODE HANDLER
// ============================================================================

typedef struct {
    unsigned frame_ctrl:16;
    unsigned duration_id:16;
    uint8_t addr1[6]; /* receiver address */
    uint8_t addr2[6]; /* sender address */
    uint8_t addr3[6]; /* filtering address */
    unsigned sequence_ctrl:16;
    uint8_t addr4[6]; /* optional */
} wifi_ieee80211_mac_hdr_t;

typedef struct {
    wifi_ieee80211_mac_hdr_t hdr;
    uint8_t payload[0]; /* network data ended with 4 bytes csum (CRC32) */
} wifi_ieee80211_packet_t;

void wifi_sniffer_packet_handler(void* buff, wifi_promiscuous_pkt_type_t type)
{
    
    const wifi_promiscuous_pkt_t *ppkt = (wifi_promiscuous_pkt_t *)buff;
    const wifi_ieee80211_packet_t *ipkt = (wifi_ieee80211_packet_t *)ppkt->payload;
    const wifi_ieee80211_mac_hdr_t *hdr = &ipkt->hdr;
    
    // Check for probe requests (subtype 0x04) and beacons (subtype 0x08)
    uint8_t frame_type = (hdr->frame_ctrl & 0xFF) >> 2;
    if (frame_type != 0x10 && frame_type != 0x20) { // Probe request (0x10) or beacon (0x20)
        return;
    }
    
    // Extract SSID from probe request or beacon
    char ssid[33] = {0};
    uint8_t *payload = (uint8_t *)ipkt + 24; // Skip MAC header
    
    if (frame_type == 0x10) { // Probe request
        payload += 0; // Probe requests start with SSID immediately
    } else { // Beacon frame
        payload += 12; // Skip fixed parameters in beacon
    }
    
    // Parse SSID element (tag 0, length, data)
    if (payload[0] == 0 && payload[1] <= 32) {
        memcpy(ssid, &payload[2], payload[1]);
        ssid[payload[1]] = '\0';
    }
    
    // Check if SSID matches our patterns
    if (strlen(ssid) > 0 && check_ssid_pattern(ssid)) {
        const char* detection_type = (frame_type == 0x10) ? "probe_request" : "beacon";
        output_wifi_detection_json(ssid, hdr->addr2, ppkt->rx_ctrl.rssi, detection_type);
        
        if (!triggered) {
            triggered = true;
            flock_detected_notification(String("WiFi: ") + ssid + " [RSSI:" + String(ppkt->rx_ctrl.rssi) + "]");
        }
        // Always update detection time for heartbeat tracking
        last_detection_time = millis();
        last_rssi = ppkt->rx_ctrl.rssi;
        return;
    }
    
    // Check MAC address
    if (check_mac_prefix(hdr->addr2)) {
        const char* detection_type = (frame_type == 0x10) ? "probe_request_mac" : "beacon_mac";
        output_wifi_detection_json(ssid[0] ? ssid : "hidden", hdr->addr2, ppkt->rx_ctrl.rssi, detection_type);
        
        if (!triggered) {
            triggered = true;
            char mac_str[18];
            snprintf(mac_str, sizeof(mac_str), "%02x:%02x:%02x:%02x:%02x:%02x", 
                     hdr->addr2[0], hdr->addr2[1], hdr->addr2[2], hdr->addr2[3], hdr->addr2[4], hdr->addr2[5]);
            flock_detected_notification(String("WiFi MAC: ") + mac_str + " [RSSI:" + String(ppkt->rx_ctrl.rssi) + "]");
        }
        // Always update detection time for heartbeat tracking
        last_detection_time = millis();
        last_rssi = ppkt->rx_ctrl.rssi;
        return;
    }
}

// ============================================================================
// BLE SCANNING
// ============================================================================

class AdvertisedDeviceCallbacks: public NimBLEAdvertisedDeviceCallbacks {
    void onResult(NimBLEAdvertisedDevice* advertisedDevice) {
        
        NimBLEAddress addr = advertisedDevice->getAddress();
        std::string addrStr = addr.toString();
        uint8_t mac[6];
        sscanf(addrStr.c_str(), "%02x:%02x:%02x:%02x:%02x:%02x", 
               &mac[0], &mac[1], &mac[2], &mac[3], &mac[4], &mac[5]);
        
        int rssi = advertisedDevice->getRSSI();
        std::string name = "";
        if (advertisedDevice->haveName()) {
            name = advertisedDevice->getName();
        }
        
        // Check MAC prefix
        if (check_mac_prefix(mac)) {
            output_ble_detection_json(addrStr.c_str(), name.c_str(), rssi, "mac_prefix");
            if (!triggered) {
                triggered = true;
                flock_detected_notification(String("BLE MAC: ") + addrStr.c_str() + " [RSSI:" + String(rssi) + "]");
            }
            // Always update detection time for heartbeat tracking
            last_detection_time = millis();
            last_rssi = rssi;
            return;
        }
        
        // Check device name
        if (!name.empty() && check_device_name_pattern(name.c_str())) {
            output_ble_detection_json(addrStr.c_str(), name.c_str(), rssi, "device_name");
            if (!triggered) {
                triggered = true;
                flock_detected_notification(String("BLE Name: ") + name.c_str() + " [RSSI:" + String(rssi) + "]");
            }
            // Always update detection time for heartbeat tracking
            last_detection_time = millis();
            last_rssi = rssi;
            return;
        }
        
        // Check for Raven surveillance device service UUIDs
        char detected_service_uuid[41] = {0};
        if (check_raven_service_uuid(advertisedDevice, detected_service_uuid)) {
            update_detection_state(RAVEN);
            // Raven device detected! Get firmware version estimate
            const char* fw_version = estimate_raven_firmware_version(advertisedDevice);
            const char* service_desc = get_raven_service_description(detected_service_uuid);
            
            // Create enhanced JSON output with Raven-specific data
            StaticJsonDocument<1024> doc;
            doc["protocol"] = "bluetooth_le";
            doc["detection_method"] = "raven_service_uuid";
            doc["device_type"] = "RAVEN_GUNSHOT_DETECTOR";
            doc["manufacturer"] = "SoundThinking/ShotSpotter";
            doc["mac_address"] = addrStr.c_str();
            doc["rssi"] = rssi;
            doc["signal_strength"] = rssi > -50 ? "STRONG" : (rssi > -70 ? "MEDIUM" : "WEAK");
            
            if (!name.empty()) {
                doc["device_name"] = name.c_str();
            }
            
            // Raven-specific information
            doc["raven_service_uuid"] = detected_service_uuid;
            doc["raven_service_description"] = service_desc;
            doc["raven_firmware_version"] = fw_version;
            doc["threat_level"] = "CRITICAL";
            doc["threat_score"] = 100;
            
            // List all detected service UUIDs
            if (advertisedDevice->haveServiceUUID()) {
                JsonArray services = doc.createNestedArray("service_uuids");
                int serviceCount = advertisedDevice->getServiceUUIDCount();
                for (int i = 0; i < serviceCount; i++) {
                    NimBLEUUID serviceUUID = advertisedDevice->getServiceUUID(i);
                    services.add(serviceUUID.toString().c_str());
                }
            }
            
            // Output the detection
            serializeJson(doc, Serial);
            Serial.println();
            
            if (!triggered) {
                triggered = true;
                device_in_range = true; // Enable fast blinking LED
                flock_detected_notification(String("Raven: ") + service_desc + " [RSSI:" + String(rssi) + "]");
            }
            // Always update detection time for heartbeat tracking
            last_detection_time = millis();
            last_rssi = rssi;
            device_in_range = true; // Ensure heartbeat keeps going with fresh RSSI
            return;
        }
    }
};

// ============================================================================
// CHANNEL HOPPING
// ============================================================================

void hop_channel()
{
    unsigned long now = millis();
    if (now - last_channel_hop > CHANNEL_HOP_INTERVAL) {
        current_channel++;
        if (current_channel > MAX_CHANNEL) {
            current_channel = 1;
        }
        esp_wifi_set_channel(current_channel, WIFI_SECOND_CHAN_NONE);
        last_channel_hop = now;
         printf("[WiFi] Hopped to channel %d\n", current_channel);
    }
}

// ============================================================================
// MAIN FUNCTIONS
// ============================================================================

void setup()
{
    Serial.begin(115200);
    
    // Create mutex for thread-safe BLE notifications
    bleMutex = xSemaphoreCreateMutex();

    // Initialize session tracking
    session_start_time = millis();
    
    // Initialize debounce cache
    memset(seenDevices, 0, sizeof(seenDevices));
    seenDeviceCount = 0;

    // Initialize RGB LED
    pixel.begin();
    pixel.setBrightness(20); // Low-ish brightness (max 255)
    pixel.setPixelColor(0, pixel.Color(0, 0, 255)); // Blue start
    pixel.show();

    // Wait for Serial connection
    delay(2000); 
    unsigned long start = millis();
    while(!Serial && (millis() - start < 5000)) {
        delay(10);
    }
    
    printf("Starting Flock Squawk Enhanced Detection System...\n\n");
    printf("Type 'help' for available serial commands\n\n");
    
    // Initialize WiFi in promiscuous mode
    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
    delay(100);
    
    esp_wifi_set_promiscuous(true);
    esp_wifi_set_promiscuous_rx_cb(&wifi_sniffer_packet_handler);
    esp_wifi_set_channel(current_channel, WIFI_SECOND_CHAN_NONE);
    
    printf("WiFi promiscuous mode enabled on channel %d\n", current_channel);
    printf("Monitoring probe requests and beacons...\n");
    
    // Initialize BLE
    printf("Initializing BLE scanner and server...\n");
    NimBLEDevice::init("FlockDetector");
    
    // Create the BLE Server
    pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    // Create the BLE Service
    NimBLEService *pService = pServer->createService(SERVICE_UUID);

    // Create a BLE Characteristic
    pTxCharacteristic = pService->createCharacteristic(
                                        CHARACTERISTIC_UUID_TX,
                                        NIMBLE_PROPERTY::NOTIFY
                                    );
                                    
    NimBLECharacteristic * pRxCharacteristic = pService->createCharacteristic(
                                             CHARACTERISTIC_UUID_RX,
                                             NIMBLE_PROPERTY::WRITE
                                         );

    // Start the service
    pService->start();

    // Start advertising
    NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->start();
    printf("BLE Advertising started. Connect to 'FlockDetector' to receive notifications.\n");

    pBLEScan = NimBLEDevice::getScan();
    pBLEScan->setAdvertisedDeviceCallbacks(new AdvertisedDeviceCallbacks());
    pBLEScan->setActiveScan(true);
    pBLEScan->setInterval(100);
    pBLEScan->setWindow(99);
    
    printf("BLE scanner initialized\n");
    printf("System ready - hunting for Flock Safety devices...\n\n");
    printf("Type 'test' or 'axon' in serial console to simulate Axon detection\n\n");
    
    last_channel_hop = millis();
}

void loop() 
{
    unsigned long now = millis();

    // ===================================
    // SERIAL COMMAND HANDLER
    // ===================================
    if (Serial.available() > 0) {
        String cmd = Serial.readStringUntil('\n');
        cmd.trim();
        String cmdLower = cmd;
        cmdLower.toLowerCase();
        
        if (cmdLower == "test" || cmdLower == "axon") {
            printf("\n[TEST] Simulating Axon Body Cam detection...\n");
            
            // Create fake Axon BLE detection
            DynamicJsonDocument doc(2048);
            doc["timestamp"] = millis();
            doc["detection_time"] = String(millis() / 1000.0, 3) + "s";
            doc["protocol"] = "bluetooth_le";
            doc["detection_method"] = "test_console";
            doc["alert_level"] = "HIGH";
            doc["device_category"] = "AXON";
            doc["mac_address"] = "00:25:df:aa:bb:cc";
            doc["device_name"] = "Axon Body 3";
            doc["rssi"] = -55;
            doc["signal_strength"] = "STRONG";
            doc["threat_score"] = 95;
            doc["vendor_oui"] = "00:25:df";
            doc["manufacturer"] = "Axon Enterprise";
            doc["test_mode"] = true;

            String json_output;
            serializeJson(doc, json_output);
            Serial.println(json_output);
            send_notification(json_output);

            // Update detection state
            update_detection_state(AXON);
            last_rssi = -55;
            
            printf("[TEST] Axon detection simulated successfully\n\n");
            
        } else if (cmdLower == "status") {
            // Display current status
            unsigned long uptime = millis() / 1000;
            printf("\n========== FLOCK-YOU STATUS ==========\n");
            printf("Uptime: %lu seconds\n", uptime);
            printf("Session start: %lu ms ago\n", millis() - session_start_time);
            printf("WiFi Channel: %d / %d\n", current_channel, MAX_CHANNEL);
            printf("BLE Connected: %s\n", deviceConnected ? "YES" : "NO");
            printf("Device in range: %s\n", device_in_range ? "YES" : "NO");
            printf("Current detection: %d\n", current_detection_type);
            printf("Last RSSI: %d dBm\n", last_rssi);
            printf("\n--- Detection Stats ---\n");
            printf("Total WiFi detections: %d\n", total_wifi_detections);
            printf("Total BLE detections: %d\n", total_ble_detections);
            printf("Unique devices seen: %d\n", unique_devices_seen);
            printf("Debounce cache: %d / %d\n", seenDeviceCount, MAX_SEEN_DEVICES);
            printf("\n--- Memory ---\n");
            printf("Free heap: %d bytes\n", ESP.getFreeHeap());
            printf("Min free heap: %d bytes\n", ESP.getMinFreeHeap());
            printf("=======================================\n\n");
            
        } else if (cmdLower == "stats") {
            // JSON stats output (for app consumption)
            DynamicJsonDocument doc(1024);
            doc["uptime_seconds"] = millis() / 1000;
            doc["wifi_channel"] = current_channel;
            doc["ble_connected"] = deviceConnected;
            doc["device_in_range"] = device_in_range;
            doc["current_detection"] = current_detection_type;
            doc["last_rssi"] = last_rssi;
            doc["total_wifi_detections"] = total_wifi_detections;
            doc["total_ble_detections"] = total_ble_detections;
            doc["unique_devices"] = unique_devices_seen;
            doc["debounce_cache_size"] = seenDeviceCount;
            doc["free_heap"] = ESP.getFreeHeap();
            
            String json_output;
            serializeJson(doc, json_output);
            Serial.println(json_output);
            
        } else if (cmdLower == "devices") {
            // List seen devices
            printf("\n========== SEEN DEVICES ==========\n");
            for (int i = 0; i < seenDeviceCount; i++) {
                unsigned long age = (millis() - seenDevices[i].lastSeen) / 1000;
                printf("%d. %s - Count: %d, Last seen: %lu sec ago\n",
                    i + 1,
                    seenDevices[i].mac,
                    seenDevices[i].detectionCount,
                    age);
            }
            printf("==================================\n\n");
            
        } else if (cmdLower == "clear") {
            // Clear debounce cache
            seenDeviceCount = 0;
            unique_devices_seen = 0;
            total_wifi_detections = 0;
            total_ble_detections = 0;
            printf("[OK] Stats and debounce cache cleared\n");
            
        } else if (cmdLower == "help") {
            printf("\n========== FLOCK-YOU COMMANDS ==========\n");
            printf("status  - Show detailed system status\n");
            printf("stats   - Output stats as JSON\n");
            printf("devices - List recently seen devices\n");
            printf("clear   - Clear detection cache and stats\n");
            printf("test    - Simulate Axon detection\n");
            printf("axon    - Simulate Axon detection\n");
            printf("help    - Show this help message\n");
            printf("=========================================\n\n");
            
        } else if (cmdLower.length() > 0) {
            printf("Unknown command: %s\n", cmd.c_str());
            printf("Type 'help' for available commands\n\n");
        }
    }

    // ===================================
    // LED CONTROL LOGIC (RGB)
    // ===================================
    if (device_in_range) {

        switch (current_detection_type) {
            case RAVEN:
                // CRITICAL PRIORITY: RAVEN / GUNSHOT DETECTION
                // FAST RED STROBE (50ms ON/OFF)
                if ((now % 100) < 50) {
                    pixel.setPixelColor(0, pixel.Color(255, 0, 0)); // RED
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case AXON:
                // HIGH PRIORITY: AXON / POLICE SYSTEMS
                // BLUE/RED POLICE STROBE
                if ((now % 200) < 100) {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 255)); // BLUE
                } else {
                    pixel.setPixelColor(0, pixel.Color(255, 0, 0)); // RED
                }
                break;

            case RING:
                // MEDIUM PRIORITY: RING DEVICES
                // CYAN BLINK (300ms ON/300ms OFF)
                if ((now % 600) < 300) {
                    pixel.setPixelColor(0, pixel.Color(0, 255, 255)); // CYAN
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case CRADLEPOINT:
                // MEDIUM PRIORITY: NETWORK EQUIPMENT (used in surveillance)
                // GREEN BLINK (400ms ON/400ms OFF)
                if ((now % 800) < 400) {
                    pixel.setPixelColor(0, pixel.Color(0, 255, 0)); // GREEN
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case ARUBA:
                // MEDIUM PRIORITY: ARUBA NETWORKS
                // MAGENTA BLINK (400ms ON/400ms OFF)
                if ((now % 800) < 400) {
                    pixel.setPixelColor(0, pixel.Color(255, 0, 255)); // MAGENTA
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case DRONE:
                // LOW PRIORITY: DRONES (DJI, Parrot, Skydio)
                // YELLOW SLOW BLINK (500ms ON/500ms OFF)
                if ((now % 1000) < 500) {
                    pixel.setPixelColor(0, pixel.Color(255, 255, 0)); // YELLOW
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case NEST_GOOGLE:
                // MEDIUM PRIORITY: NEST/GOOGLE CAMERAS
                // WHITE BLINK (400ms ON/400ms OFF)
                if ((now % 800) < 400) {
                    pixel.setPixelColor(0, pixel.Color(255, 255, 255)); // WHITE
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case ARLO:
                // MEDIUM PRIORITY: ARLO CAMERAS
                // GREEN BLINK (400ms ON/400ms OFF)
                if ((now % 800) < 400) {
                    pixel.setPixelColor(0, pixel.Color(0, 255, 100)); // GREEN-TEAL
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case EUFY:
                // MEDIUM PRIORITY: EUFY CAMERAS
                // PINK BLINK (400ms ON/400ms OFF)
                if ((now % 800) < 400) {
                    pixel.setPixelColor(0, pixel.Color(255, 100, 200)); // PINK
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case WYZE:
                // MEDIUM PRIORITY: WYZE CAMERAS
                // LIGHT BLUE BLINK (400ms ON/400ms OFF)
                if ((now % 800) < 400) {
                    pixel.setPixelColor(0, pixel.Color(100, 200, 255)); // LIGHT BLUE
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case BLINK:
                // MEDIUM PRIORITY: BLINK/AMAZON CAMERAS
                // CYAN/WHITE ALTERNATING (300ms each)
                if ((now % 600) < 300) {
                    pixel.setPixelColor(0, pixel.Color(0, 255, 255)); // CYAN
                } else {
                    pixel.setPixelColor(0, pixel.Color(255, 255, 255)); // WHITE
                }
                break;

            case FLOCK_SAFETY:
            default:
                // HIGH PRIORITY: FLOCK SAFETY
                // ORANGE BLINK (200ms ON/200ms OFF)
                if ((now % 400) < 200) {
                    pixel.setPixelColor(0, pixel.Color(255, 140, 0)); // ORANGE
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;
        }

    } else {
        // SCANNING BREATHE/BLINK (Blue)
        // Gentle Blue Pulse every 2 seconds
        int cycle = now % 2000;
        if (cycle < 100) {
           pixel.setPixelColor(0, pixel.Color(0, 0, 50)); // Dim Blue
        } else {
           pixel.setPixelColor(0, pixel.Color(0, 0, 0)); // OFF
        }
        
        // Reset detection type if not in range to avoid stale state on next detect
        current_detection_type = NONE;
    }
    pixel.show();

    // (put this at the top of the loop)
    static unsigned long lastLog = 0;
    if (millis() - lastLog > 2000) {
        lastLog = millis();
        Serial.println("System active - Scanning...");
    }
    // Handle channel hopping for WiFi promiscuous mode
    hop_channel();
    
    // Handle heartbeat pulse if device is in range
    if (device_in_range) {
        unsigned long now = millis();
        
        // Check if 10 seconds have passed since last heartbeat
        if (now - last_heartbeat >= 10000) {
            // Send heartbeat as JSON
            DynamicJsonDocument doc(256);
            doc["type"] = "heartbeat";
            doc["message"] = "Still Detected";
            doc["rssi"] = last_rssi;
            doc["timestamp"] = millis();
            String json_output;
            serializeJson(doc, json_output);
            send_notification(json_output);
            
            last_heartbeat = now;
        }
        
        // Check if device has gone out of range (no detection for 30 seconds)
        if (now - last_detection_time >= 30000) {
            printf("Device out of range - stopping heartbeat\n");
            send_notification("Device out of range");
            device_in_range = false;
            triggered = false; // Allow new detections
        }
    }
    
    if (millis() - last_ble_scan >= BLE_SCAN_INTERVAL && !pBLEScan->isScanning()) {
        printf("[BLE] scan...\n");
        pBLEScan->start(BLE_SCAN_DURATION, false);
        last_ble_scan = millis();
    }
    
    if (pBLEScan->isScanning() == false && millis() - last_ble_scan > BLE_SCAN_DURATION * 1000) {
        pBLEScan->clearResults();
    }
    
    delay(100);
}
