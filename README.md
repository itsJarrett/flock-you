# Flock You: Flock Safety Detection System

<img src="flock.png" alt="Flock You" width="300px">

**Professional surveillance camera detection for the Oui-Spy device available at [colonelpanic.tech](https://colonelpanic.tech)**

> **Note:** This is a fork of the original project, modified to replace the buzzer with a custom Android App for notifications, proximity tracking, and Android Auto integration.

## Overview

Flock You is an advanced detection system designed to identify Flock Safety surveillance cameras, Raven gunshot detectors, and similar surveillance devices using multiple detection methodologies. Built for the Xiao ESP32 S3 and Waveshare ESP32-S3 SuperMini, it provides real-time monitoring with a companion Android app for alerts and signal tracking.

## Features

### Multi-Method Detection
- **WiFi Promiscuous Mode**: Captures probe requests and beacon frames
- **Bluetooth Low Energy (BLE) Scanning**: Monitors BLE advertisements
- **MAC Address Filtering**: Detects devices by known MAC prefixes
- **SSID Pattern Matching**: Identifies networks by specific names
- **Device Name Pattern Matching**: Detects BLE devices by advertised names
- **BLE Service UUID Detection**: Identifies Raven gunshot detectors by service UUIDs (NEW)

### Android App Integration (NEW)
- **Custom Companion App**: Dedicated Android application for managing detections
- **Color-Coded Device Categories**: Visual indicators for 7 device types (Surveillance, Law Enforcement, Drones, etc.)
- **Device-Specific Icons**: Unique icons for each category (camera, badge, drone, doorbell)
- **Rich Data Notifications**: Displays detailed threat info including Device Type, Manufacturer, MAC Address, and Threat Score
- **GPS Tagging**: Automatically tags every detection with your phone's current GPS coordinates
- **Detection Counting**: Tracks how many times each unique device has been seen
- **Persistent Storage**: Detections are saved locally, preserving history across app restarts
- **Session & Lifetime Stats**: View detailed statistics for the current session and all-time history
- **Proximity Radar**: Visual RSSI graph (Blue/Orange/Red) to track distance to the device
- **Android Auto Support**: Notifications appear directly on your car's dashboard with category badges
- **Smart Filtering**: Ignores heartbeat messages, alerting only on confirmed detections
- **JSON Protocol**: Uses robust chunked JSON transmission for reliable data transfer over BLE
- **Manufacturer Database**: Displays manufacturer name for all 57 tracked OUIs

### Comprehensive Output
- **JSON Detection Data**: Structured output with timestamps, RSSI, MAC addresses
- **Real-time Web Dashboard**: Live monitoring at `http://localhost:5000`
- **Serial Terminal**: Real-time device output in the web interface
- **Detection History**: Persistent storage and export capabilities (CSV, KML)
- **Device Information**: Full device details including signal strength and threat assessment
- **Detection Method Tracking**: Identifies which detection method triggered the alert

## Hardware Requirements

### Option 1: Oui-Spy Device (Available at colonelpanic.tech)
- **Microcontroller**: Xiao ESP32 S3
- **Wireless**: Dual WiFi/BLE scanning capabilities
- **Connectivity**: USB-C for programming and power

### Option 2: Standard Xiao ESP32 S3 Setup
- **Microcontroller**: Xiao ESP32 S3 board
- **Power**: USB-C cable for programming and power

### Option 3: Waveshare ESP32-S3 SuperMini / Zero (Recommended)
- **Microcontroller**: Waveshare ESP32-S3 SuperMini or ESP32-S3-Zero
- **LED**: Onboard WS2812B RGB LED (GPIO 21) for color-coded status
- **Configuration**: Use `[env:esp32-s3-supermini]` in `platformio.ini`

## Installation

### Firmware Setup
1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd flock-you
   ```

2. **Connect your device** via USB-C.
   - **WSL Users**: Windows users running WSL2 must use `usbipd` to pass the USB device to Linux.
     ```powershell
     # In Windows PowerShell (Admin)
     usbipd list
     usbipd bind --busid <BUSID>
     usbipd attach --wsl --busid <BUSID>
     ```

3. **Flash the firmware**:
   - For Xiao ESP32 S3: `pio run -e xiao_esp32s3 --target upload`
   - For Waveshare SuperMini: `pio run -e esp32-s3-supermini --target upload`

### Android App Setup
The companion app is located in the `android_app/` directory and supports Android Auto.

1. **Build via Command Line**:
   ```bash
   cd android_app
   chmod +x gradlew
   ./gradlew assembleDebug
   ```
   The APK will be at: `android_app/app/build/outputs/apk/debug/app-debug.apk`

2. **Build via Android Studio**:
   - Open the `android_app` folder in Android Studio.
   - Connect your phone.
   - Click **Run**.

4. **Set up the web interface**:
   ```bash
   cd api
   python3 -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   pip install -r requirements.txt
   ```

5. **Start the web server**:
   ```bash
   python flockyou.py
   ```

6. **Access the dashboard**:
   - Open your browser to `http://localhost:5000`
   - The web interface provides real-time detection monitoring
   - Serial terminal for device output
   - Detection history and export capabilities

7. **Monitor device output** (optional):
   ```bash
   pio device monitor
   ```

## Detection Coverage

### Detected Device Categories
Flock You now detects **7 distinct device categories** with intelligent categorization:

1. **Surveillance Cameras** (Flock Safety, Falcon, Penguin, Pigvision)
   - 24 MAC OUIs tracked
   - LED: Orange blink
   - Threat Level: HIGH

2. **Law Enforcement** (Axon Body Cameras, Axon Fleet)
   - 1 MAC OUI (00:25:df)
   - LED: Red/Blue police strobe
   - Threat Level: CRITICAL

3. **Gunshot Detection** (Raven/ShotSpotter)
   - BLE Service UUID fingerprinting
   - LED: Fast red strobe
   - Threat Level: CRITICAL

4. **Security Cameras** (Ring Doorbell, Ring Camera)
   - 11 MAC OUIs tracked
   - LED: Cyan blink
   - Threat Level: MEDIUM

5. **Consumer Drones** (DJI Mavic/Phantom/Mini, Parrot Anafi/Bebop)
   - 13 MAC OUIs tracked
   - LED: Yellow slow blink
   - Threat Level: LOW

6. **Commercial Drones** (Skydio 2/X2/3)
   - 1 MAC OUI tracked
   - LED: Yellow slow blink
   - Threat Level: LOW

7. **BLE Surveillance** (Generic BLE surveillance devices)
   - LED: Purple blink
   - Threat Level: HIGH

**Total: 57 unique MAC OUIs tracked across all manufacturers**

### WiFi Detection Methods
- **Probe Requests**: Captures devices actively searching for networks
- **Beacon Frames**: Monitors network advertisements
- **Channel Hopping**: Cycles through all 13 WiFi channels (2.4GHz)
- **SSID Patterns**: Detects networks with "flock", "Penguin", "Pigvision", "Ring", "DJI" patterns
- **MAC Prefixes**: Identifies devices by 57 known manufacturer MAC addresses
- **Smart Categorization**: Automatically classifies devices by manufacturer and type

### BLE Detection Methods
- **Advertisement Scanning**: Monitors BLE device broadcasts
- **Device Names**: Matches against known surveillance device names
- **MAC Address Filtering**: Detects devices by BLE MAC prefixes
- **Service UUID Detection**: Identifies Raven devices by advertised service UUIDs
- **Firmware Version Estimation**: Automatically determines Raven firmware version (1.1.x, 1.2.x, 1.3.x)
- **Active Scanning**: Continuous monitoring with 100ms intervals

### Real-World Database Integration
Detection patterns are derived from actual field data including:
- Flock Safety camera signatures
- Penguin surveillance device patterns
- Pigvision system identifiers
- Raven acoustic gunshot detection devices (SoundThinking/ShotSpotter)
- Extended battery and external antenna configurations

**Datasets from deflock.me are included in the `datasets/` folder of this repository**, providing comprehensive device signatures and detection patterns for enhanced accuracy.

### Raven Gunshot Detection System
Flock You now includes specialized detection for **Raven acoustic gunshot detection devices** (by SoundThinking/ShotSpotter) using BLE service UUID fingerprinting:

#### Detected Raven Services
- **Device Information Service** (`0000180a-...`) - Serial number, model, firmware version
- **GPS Location Service** (`00003100-...`) - Real-time device coordinates
- **Power Management Service** (`00003200-...`) - Battery and solar panel status
- **Network Status Service** (`00003300-...`) - LTE and WiFi connectivity information
- **Upload Statistics Service** (`00003400-...`) - Data transmission metrics
- **Error/Failure Service** (`00003500-...`) - System diagnostics and error logs
- **Legacy Services** (`00001809-...`, `00001819-...`) - Older firmware versions (1.1.x)

#### Firmware Version Detection
The system automatically identifies Raven firmware versions based on advertised services:
- **1.1.x (Legacy)**: Uses Health Thermometer and Location/Navigation services
- **1.2.x**: Introduces GPS, Power, and Network services
- **1.3.x (Latest)**: Full suite of diagnostic and monitoring services

#### Raven Detection Output
When a Raven device is detected, the system provides:
- Device type identification: `RAVEN_GUNSHOT_DETECTOR`
- Manufacturer: `SoundThinking/ShotSpotter`
- Complete list of advertised service UUIDs
- Service descriptions (GPS, Battery, Network status, etc.)
- Estimated firmware version
- Threat level: `CRITICAL` with score of 100

**Configuration data sourced from `raven_configurations.json`** (provided by [GainSec](https://github.com/GainSec)) in the datasets folder, containing verified service UUIDs from firmware versions 1.1.7, 1.2.0, and 1.3.1.

## Technical Specifications

### WiFi Capabilities
- **Frequency**: 2.4GHz only (13 channels)
- **Mode**: Promiscuous monitoring
- **Channel Hopping**: Automatic cycling every 2 seconds
- **Packet Types**: Probe requests (0x04) and beacons (0x08)

### BLE Capabilities
- **Framework**: NimBLE-Arduino
- **Scan Mode**: Active scanning
- **Interval**: 100ms scan intervals
- **Window**: 99ms scan windows

### BLE Notification System
- **Service UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART)
- **TX Characteristic**: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` (Notify)
- **RX Characteristic**: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` (Write)
- **Data Format**: `FLOCK DETECTED! [Details] [RSSI:-XX]`
- **Notification Rate**: Immediate on detection, 10s heartbeat

### JSON Output Format

#### WiFi Detection Example
```json
{
  "timestamp": 12345,
  "detection_time": "12.345s",
  "protocol": "wifi",
  "detection_method": "probe_request",
  "alert_level": "HIGH",
  "device_category": "FLOCK_SAFETY",
  "ssid": "Flock_Camera_001",
  "rssi": -65,
  "signal_strength": "MEDIUM",
  "channel": 6,
  "mac_address": "aa:bb:cc:dd:ee:ff",
  "threat_score": 95,
  "matched_patterns": ["ssid_pattern", "mac_prefix"],
  "device_info": {
    "manufacturer": "Flock Safety",
    "model": "Surveillance Camera",
    "capabilities": ["video", "audio", "gps"]
  }
}
```

#### Raven BLE Detection Example (NEW)
```json
{
  "protocol": "bluetooth_le",
  "detection_method": "raven_service_uuid",
  "device_type": "RAVEN_GUNSHOT_DETECTOR",
  "manufacturer": "SoundThinking/ShotSpotter",
  "mac_address": "12:34:56:78:9a:bc",
  "rssi": -72,
  "signal_strength": "MEDIUM",
  "device_name": "Raven-Device-001",
  "raven_service_uuid": "00003100-0000-1000-8000-00805f9b34fb",
  "raven_service_description": "GPS Location Service (Lat/Lon/Alt)",
  "raven_firmware_version": "1.3.x (Latest)",
  "threat_level": "CRITICAL",
  "threat_score": 100,
  "service_uuids": [
    "0000180a-0000-1000-8000-00805f9b34fb",
    "00003100-0000-1000-8000-00805f9b34fb",
    "00003200-0000-1000-8000-00805f9b34fb",
    "00003300-0000-1000-8000-00805f9b34fb",
    "00003400-0000-1000-8000-00805f9b34fb",
    "00003500-0000-1000-8000-00805f9b34fb"
  ]
}
```

## Usage

### Startup Sequence
1. **Power on** the Oui-Spy device
2. **Launch the Android App** and connect (see below)
3. **Start the web server** (Optional): `python flockyou.py` (from the `api` directory)
4. **Open the dashboard**: Navigate to `http://localhost:5000`
5. **Connect devices**: Use the web interface to connect your Flock You device and GPS
6. **System ready** when "hunting for Flock Safety devices" appears in the serial terminal

### Connecting to Phone / Android Auto
1. **Install the App**: Build and install the `Flock You Client` app (see Installation above).
2. **Open the App**: Launch "Flock You Client" on your phone.
3. **Grant Permissions**: Allow Bluetooth and Notification permissions when prompted.
4. **Scan & Connect**: Tap "Scan for Devices". The app will automatically find and connect to your "FlockDetector".
5. **Proximity Mode**: Use the RSSI bar to track signal strength (Blue -> Red).
6. **Android Auto**: Connect your phone to your car. Detections will appear as high-priority notifications on the dashboard.

### Detection Monitoring
- **Phone Notifications**: Instant text alerts on your phone/watch/car
- **Web Dashboard**: Real-time detection display at `http://localhost:5000`
- **Serial Terminal**: Live device output in the web interface
- **Heartbeat**: Continuous "Still Detected" updates while devices in range
- **Range Tracking**: "Device out of range" notification
- **Export Options**: Download detections as CSV or KML files

### Channel Information
- **WiFi**: Automatically hops through channels 1-13
- **BLE**: Continuous scanning across all BLE channels
- **Status Updates**: Channel changes logged to serial terminal

### LED Status Indicators (Waveshare ESP32-S3 SuperMini)
The onboard RGB LED (GPIO 21) provides instant visual feedback on detections:

| Color | Pattern | Meaning | Priority |
| :--- | :--- | :--- | :--- |
| **Blue** | Slow Breathe/Pulse | **Scanning** (Idle state) | N/A |
| **Red** | Fast Strobe | **Raven/Gunshot Sensor Detected** | **Critical** |
| **Blue/Red**| Rapid Alternating | **Axon / Law Enforcement Presence** | **High** |
| **Purple** | Fast Blink | **Flock Safety Camera (BLE)** | **High** |
| **Orange** | Medium Blink | **Flock Safety Camera (WiFi)** | **Medium** |

- **Priority Logic**: If multiple devices are detected, the LED shows the highest priority threat.
- **Auto-Reset**: LED returns to Blue breathing mode when devices go out of range (~30s).

## Detection Patterns

### SSID Patterns
- `flock*` - Flock Safety cameras
- `Penguin*` - Penguin surveillance devices
- `Pigvision*` - Pigvision systems
- `FS_*` - Flock Safety variants

### MAC Address Prefixes
- `AA:BB:CC` - Flock Safety manufacturer codes
- `00:25:DF` - Axon Enterprise (Body Body 2/3, Fleet systems)
- `DD:EE:FF` - Penguin device identifiers
- `11:22:33` - Pigvision system codes

### BLE Device Names
- `Flock*` - Flock Safety BLE devices
- `Axon*` - Axon Body Cams and Fleet systems
- `Penguin*` - Penguin BLE identifiers
- `Pigvision*` - Pigvision BLE devices

### Raven Service UUIDs (NEW)
- `0000180a-0000-1000-8000-00805f9b34fb` - Device Information Service
- `00003100-0000-1000-8000-00805f9b34fb` - GPS Location Service
- `00003200-0000-1000-8000-00805f9b34fb` - Power Management Service
- `00003300-0000-1000-8000-00805f9b34fb` - Network Status Service
- `00003400-0000-1000-8000-00805f9b34fb` - Upload Statistics Service
- `00003500-0000-1000-8000-00805f9b34fb` - Error/Failure Service
- `00001809-0000-1000-8000-00805f9b34fb` - Health Service (Legacy 1.1.x)
- `00001819-0000-1000-8000-00805f9b34fb` - Location Service (Legacy 1.1.x)

## Limitations

### Technical Constraints
- **WiFi Range**: Limited to 2.4GHz spectrum
- **Detection Range**: Approximately 50-100 meters depending on environment
- **False Positives**: Possible with similar device signatures
- **Battery Life**: Continuous scanning reduces battery runtime

### Environmental Factors
- **Interference**: Other WiFi networks may affect detection
- **Obstacles**: Walls and structures reduce detection range
- **Weather**: Outdoor conditions may impact performance

## Troubleshooting

### Common Issues
1. **Web Server Won't Start**: Check Python version (3.8+) and virtual environment setup
2. **No Serial Output**: Check USB connection and device port selection in web interface
3. **No Notifications**: Ensure phone is connected to "FlockDetector" and app has notification permissions
4. **No Detections**: Ensure device is in range and scanning is active
5. **False Alerts**: Review detection patterns and adjust if needed
6. **Connection Issues**: Verify device is connected via the web interface controls

### Debug Information
- **Web Dashboard**: Real-time status and connection monitoring at `http://localhost:5000`
- **Serial Terminal**: Live device output in the web interface
- **Channel Hopping**: Logs channel changes for debugging
- **Detection Logs**: Full JSON output for analysis

## Legal and Ethical Considerations

### Intended Use
- **Research and Education**: Understanding surveillance technology
- **Security Assessment**: Evaluating privacy implications
- **Technical Analysis**: Studying wireless communication patterns

### Compliance
- **Local Laws**: Ensure compliance with local regulations
- **Privacy Rights**: Respect individual privacy and property rights
- **Authorized Use**: Only use in authorized locations and situations

## Credits and Research

### Research Foundation
This project is based on extensive research and public datasets from the surveillance detection community:

- **[DeFlock](https://deflock.me)** - Crowdsourced ALPR location and reporting tool
  - GitHub: [FoggedLens/deflock](https://github.com/FoggedLens/deflock)
  - Provides comprehensive datasets and methodologies for surveillance device detection
  - **Datasets included**: Real-world device signatures from deflock.me are included in the `datasets/` folder

- **[GainSec](https://github.com/GainSec)** - OSINT and privacy research
  - Specialized in surveillance technology analysis and detection methodologies
  - **Research referenced**: Some methodologies are based on their published research on surveillance technology
  - **Raven UUID Dataset Provider**: Contributed the `raven_configurations.json` dataset containing verified BLE service UUIDs from SoundThinking/ShotSpotter Raven devices across firmware versions 1.1.7, 1.2.0, and 1.3.1
  - Enables precise detection of Raven acoustic gunshot detection devices through BLE service UUID fingerprinting

### Methodology Integration
Flock You unifies multiple known detection methodologies into a comprehensive scanner/wardriver specifically designed for Flock Safety cameras and similar surveillance devices. The system combines:

- **WiFi Promiscuous Monitoring**: Based on DeFlock's network analysis techniques
- **BLE Device Detection**: Leveraging GainSec's Bluetooth surveillance research
- **MAC Address Filtering**: Using crowdsourced device databases from deflock.me
- **BLE Service UUID Fingerprinting**: Identifying Raven devices through advertised service characteristics
- **Firmware Version Detection**: Analyzing service combinations to determine device capabilities
- **Pattern Recognition**: Implementing research-based detection algorithms

### Acknowledgments
Special thanks to the researchers and contributors who have made this work possible through their open-source contributions and public datasets:

- **GainSec** for providing the comprehensive Raven BLE service UUID dataset, enabling detection of SoundThinking/ShotSpotter acoustic surveillance devices
- **DeFlock** for crowdsourced surveillance camera location data and detection methodologies
- The broader surveillance detection community for their continued research and privacy protection efforts

This project builds upon their foundational work in surveillance detection and privacy protection.



### Purchase Information
**Oui-Spy devices are available exclusively at [colonelpanic.tech](https://colonelpanic.tech)**

## License

This project is provided for educational and research purposes. Please ensure compliance with all applicable laws and regulations in your jurisdiction.

---

**Flock You: Professional surveillance detection for the privacy-conscious**
