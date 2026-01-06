# Flock You Android Client

This is a simple Android application to connect to the Flock You device via Bluetooth Low Energy (BLE) and receive notifications.

**Now with Android Auto Support!** Get high-priority detection alerts directly on your vehicle's dashboard.

## Prerequisites

- Android Studio (latest version recommended)
- An Android device with Bluetooth support (Android 8.0+ recommended)
- USB cable to connect your phone to your computer

## How to Build and Install

1. **Open in Android Studio:**
   - Open Android Studio.
   - Select "Open" and navigate to this `android_app` folder.
   - Wait for Gradle to sync the project.

2. **Enable Developer Options on your Phone:**
   - Go to **Settings > About Phone**.
   - Tap **Build Number** 7 times until you see "You are now a developer".
   - Go back to **Settings > System > Developer Options**.
   - Enable **USB Debugging**.

3. **Run the App:**
   - Connect your phone to your computer via USB.
   - In Android Studio, select your device from the dropdown menu in the toolbar.
   - Click the green **Run** button (Play icon).
   - The app will compile and install on your device.

## Usage

1. Ensure Bluetooth is enabled on your phone.
2. Open the **Flock You Client** app.
3. Grant the requested Location, Bluetooth, and Notification permissions.
4. Tap **Scan for Devices**.
5. The app will scan for a device named "FlockDetector" or with the specific Service UUID.
6. Once found, it will automatically connect.
7. **Android Auto**: Connect your phone to your car. Alerts will appear automatically on the car display.
8. **Proximity Monitor**: Observe the log and signal strength indicators to locate the source.

## Troubleshooting

- **Permissions:** If the app crashes or doesn't scan, ensure you have granted all permissions in the Android Settings.
- **Bluetooth:** Toggle Bluetooth off and on if scanning fails.
- **Device Not Found:** Ensure the Flock You device is powered on and advertising.
- **Android Auto**: If the app doesn't appear in the launcher, you may need to enable "Unknown Sources" in Android Auto developer settings (tap version number 10 times in Android Auto settings on your phone).
