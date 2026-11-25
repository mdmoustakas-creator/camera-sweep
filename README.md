# Camera Sweep (Android)

Scans the Wi-Fi network you're on and nearby Bluetooth for hidden cameras and trackers.

## What it checks

**Wi-Fi scan (about 1 minute)**
- Finds every device on the network (checks all 254 addresses).
- Sends the security-camera discovery request (ONVIF) that most IP cameras answer.
- Probes video-streaming ports (RTSP 554/8554) and ports used by camera brands (Dahua 37777, XMEye 34567, Hikvision 8000, Tuya 6668).
- Reads device names and UPnP descriptions and flags camera brands (Wyze, Blink, Eufy, Reolink, Tapo, Arlo, Hikvision, Dahua and more).

**Bluetooth scan (live, up to 2 minutes)**
- Lists nearby devices with a distance hint and a "getting stronger / weaker" trend, so you can walk toward a source.
- Flags camera-like names, AirTag/Find My, Tile and SmartTag trackers, Tuya devices and ESP chips.

Results are color-coded: **red = possible camera**, **amber = worth a look**.

## Install it on your phone

1. Install **Android Studio** (free) from developer.android.com/studio on a Mac or Windows computer.
2. Unzip this folder, then in Android Studio choose **Open** and pick the `CameraSweep` folder. Wait for "Gradle sync" to finish; the first time takes several minutes.
3. On your phone: **Settings > About phone**, then tap **Build number** 7 times to unlock Developer options. Then turn on **Settings > System > Developer options > USB debugging**.
4. Plug the phone into the computer with a USB cable and tap **Allow** on the phone.
5. In Android Studio, pick your phone in the device menu at the top and press the green **Run ▶** button. The app installs and opens.

To get an installable file instead, use **Build > Build App Bundle(s) / APK(s) > Build APK(s)**, then copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and open it (allow "Install unknown apps" when asked).

## Using it

- Join the home's Wi-Fi first, then tap **Scan Wi-Fi**.
- For Bluetooth, allow the "Nearby devices" and "Location" permissions (Android requires location for Bluetooth scanning; the app doesn't use or store your location), make sure Location is on, then walk slowly through each room.

## Limits

Cameras on a separate network, on cellular data, or recording only to a memory card won't show up. Pair this with a flashlight lens check. Some guest networks block devices from seeing each other, which makes the Wi-Fi scan find very little.
