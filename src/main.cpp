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

// ============================================================================
// CONFIGURATION
// ============================================================================

// LED Configuration - Using Adafruit NeoPixel on Pin 21 (Common for Waveshare S3 Zero)
// The datasheet confirms it's a WS2812B compatible RGB LED
#define NEOPIXEL_PIN 21
#define NUMPIXELS 1

Adafruit_NeoPixel pixel(NUMPIXELS, NEOPIXEL_PIN, NEO_GRB + NEO_KHZ800);

// Test Button Configuration (GPIO 0 = BOOT button on most ESP32-S3 boards)
#define TEST_BUTTON_PIN 0

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

// Detection Pattern Limits
#define MAX_SSID_PATTERNS 10
#define MAX_MAC_PATTERNS 50
#define MAX_DEVICE_NAMES 20

// ============================================================================
// DETECTION PATTERNS (Extracted from Real Flock Safety Device Databases)
// ============================================================================

// WiFi SSID patterns to detect (case-insensitive)
static const char* wifi_ssid_patterns[] = {
    "flock",        // Standard Flock Safety naming
    "Flock",        // Capitalized variant
    "FLOCK",        // All caps variant
    "FS Ext Battery", // Flock Safety Extended Battery devices
    "Penguin",      // Penguin surveillance devices
    "Pigvision",    // Pigvision surveillance systems
    "Axon"          // Axon Body Cam / Fleet
};

// Known Flock Safety MAC address prefixes (from real device databases)
static const char* mac_prefixes[] = {
    // FS Ext Battery devices
    "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "04:0d:84", 
    "f0:82:c0", "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9",
    
    // Flock WiFi devices
    "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "14:5a:fc",
    "74:4c:a1", "08:3a:88", "9c:2f:9d", "94:08:53", "e4:aa:ea",
    
    // Cradlepoint routers (used in surveillance systems)
    "00:30:44", "00:e0:1c",

    // Axon Enterprise, Inc. (Body 2/3, Fleet)
    "00:25:df"
    
    // Penguin devices - these are NOT OUI based, so use local ouis
    // from the wigle.net db relative to your location 
    // "cc:09:24", "ed:c7:63", "e8:ce:56", "ea:0c:ea", "d8:8f:14",
    // "f9:d9:c0", "f1:32:f9", "f6:a0:76", "e4:1c:9e", "e7:f2:43",
    // "e2:71:33", "da:91:a9", "e1:0e:15", "c8:ae:87", "f4:ed:b2",
    // "d8:bf:b5", "ee:8f:3c", "d7:2b:21", "ea:5a:98"
};

// Device name patterns for BLE advertisement detection
static const char* device_name_patterns[] = {
    "FS Ext Battery",  // Flock Safety Extended Battery
    "Penguin",         // Penguin surveillance devices
    "Flock",           // Standard Flock Safety devices
    "Pigvision",       // Pigvision surveillance systems
    "Axon"             // Axon Body Cam / Fleet
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
    WIFI_CAMERA = 1,
    BLE_CAMERA = 2,
    AXON_SYSTEM = 3,
    RAVEN_GUNSHOT = 4
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

// Button state tracking
static bool lastButtonState = HIGH;
static unsigned long lastDebounceTime = 0;
static const unsigned long debounceDelay = 50;

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
    DetectionType resolved_type = WIFI_CAMERA;
    char mac_prefix[9];
    snprintf(mac_prefix, sizeof(mac_prefix), "%02x:%02x:%02x", mac[0], mac[1], mac[2]);
    
    if (strcasecmp(mac_prefix, "00:25:df") == 0) {
        resolved_type = AXON_SYSTEM;
    } else if (ssid && strcasestr(ssid, "axon")) {
        resolved_type = AXON_SYSTEM;
    }

    update_detection_state(resolved_type);
    last_rssi = rssi;

    DynamicJsonDocument doc(2048);
    
    // Core detection info
    doc["timestamp"] = millis();
    doc["detection_time"] = String(millis() / 1000.0, 3) + "s";
    doc["protocol"] = "wifi";
    doc["detection_method"] = detection_type;
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
    
    // Detection summary
    doc["detection_criteria"] = ssid_match && mac_match ? "SSID_AND_MAC" : (ssid_match ? "SSID_ONLY" : "MAC_ONLY");
    doc["threat_score"] = ssid_match && mac_match ? 100 : (ssid_match || mac_match ? 85 : 70);
    
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
    DetectionType resolved_type = BLE_CAMERA;
    if (mac && strncasecmp(mac, "00:25:df", 8) == 0) {
        resolved_type = AXON_SYSTEM;
    } else if (name && strcasestr(name, "axon")) {
        resolved_type = AXON_SYSTEM;
    }

    update_detection_state(resolved_type);
    last_rssi = rssi;

    DynamicJsonDocument doc(2048);
    
    // Core detection info
    doc["timestamp"] = millis();
    doc["detection_time"] = String(millis() / 1000.0, 3) + "s";
    doc["protocol"] = "bluetooth_le";
    doc["detection_method"] = detection_method;
    doc["alert_level"] = "HIGH";
    doc["device_category"] = "FLOCK_SAFETY";
    
    // BLE specific info
    doc["mac_address"] = mac;
    doc["rssi"] = rssi;
    doc["signal_strength"] = rssi > -50 ? "STRONG" : (rssi > -70 ? "MEDIUM" : "WEAK");
    
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
    
    // MAC address analysis
    char mac_prefix[9];
    strncpy(mac_prefix, mac, 8);
    mac_prefix[8] = '\0';
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
    
    // Detection summary
    doc["detection_criteria"] = name_match && mac_match ? "NAME_AND_MAC" : 
                               (name_match ? "NAME_ONLY" : "MAC_ONLY");
    doc["threat_score"] = name_match && mac_match ? 100 : 
                         (name_match || mac_match ? 85 : 70);
    
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
    if (frame_type != 0x20 && frame_type != 0x80) { // Probe request or beacon
        return;
    }
    
    // Extract SSID from probe request or beacon
    char ssid[33] = {0};
    uint8_t *payload = (uint8_t *)ipkt + 24; // Skip MAC header
    
    if (frame_type == 0x20) { // Probe request
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
        const char* detection_type = (frame_type == 0x20) ? "probe_request" : "beacon";
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
        const char* detection_type = (frame_type == 0x20) ? "probe_request_mac" : "beacon_mac";
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
            update_detection_state(RAVEN_GUNSHOT);
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
    
    // Initialize test button
    pinMode(TEST_BUTTON_PIN, INPUT_PULLUP);
    printf("Test button initialized on GPIO %d (Press to simulate Axon detection)\n\n", TEST_BUTTON_PIN);
    
    last_channel_hop = millis();
}

void loop() 
{
    unsigned long now = millis();

    // ===================================
    // TEST BUTTON LOGIC (Simulate Axon)
    // ===================================
    int buttonReading = digitalRead(TEST_BUTTON_PIN);
    if (buttonReading != lastButtonState) {
        lastDebounceTime = now;
    }
    
    if ((now - lastDebounceTime) > debounceDelay) {
        if (buttonReading == LOW && lastButtonState == HIGH) {
            // Button pressed - simulate Axon detection
            printf("\n[TEST] Simulating Axon Body Cam detection...\n");
            
            // Create fake Axon BLE detection
            DynamicJsonDocument doc(2048);
            doc["timestamp"] = millis();
            doc["detection_time"] = String(millis() / 1000.0, 3) + "s";
            doc["protocol"] = "bluetooth_le";
            doc["detection_method"] = "test_button";
            doc["alert_level"] = "HIGH";
            doc["device_category"] = "AXON_SYSTEM";
            doc["mac_address"] = "00:25:df:aa:bb:cc";
            doc["device_name"] = "Axon Body 3";
            doc["rssi"] = -55;
            doc["signal_strength"] = "STRONG";
            doc["threat_score"] = 95;
            doc["vendor_oui"] = "00:25:df";
            doc["manufacturer"] = "Axon Enterprise, Inc.";
            
            String json_output;
            serializeJson(doc, json_output);
            Serial.println(json_output);
            send_notification(json_output);
            
            // Update detection state
            update_detection_state(AXON_SYSTEM);
            last_rssi = -55;
            
            printf("[TEST] Axon detection simulated!\n\n");
        }
    }
    lastButtonState = buttonReading;

    // ===================================
    // LED CONTROL LOGIC (RGB)
    // ===================================
    if (device_in_range) {
        
        switch (current_detection_type) {
            case RAVEN_GUNSHOT:
                // CRITICAL PRIORITY: RAVEN / GUNSHOT DETECTION
                // FAST RED STROBE (50ms ON/OFF)
                if ((now % 100) < 50) {
                    pixel.setPixelColor(0, pixel.Color(255, 0, 0)); // RED
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case AXON_SYSTEM:
                // HIGH PRIORITY: AXON / POLICE SYSTEMS
                // BLUE/RED POLICE STROBE
                if ((now % 200) < 100) {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 255)); // BLUE
                } else {
                    pixel.setPixelColor(0, pixel.Color(255, 0, 0)); // RED
                }
                break;

            case BLE_CAMERA:
                // HIGH PRIORITY: BLE CAMERA
                // PURPLE BLINK (100ms ON/OFF)
                if ((now % 200) < 100) {
                    pixel.setPixelColor(0, pixel.Color(255, 0, 255)); // PURPLE
                } else {
                    pixel.setPixelColor(0, pixel.Color(0, 0, 0));
                }
                break;

            case WIFI_CAMERA:
            default:
                // MEDIUM PRIORITY: WIFI CAMERA
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
