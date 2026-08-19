<img align="right" alt="App icon" src="app-icon.png" height="115px">

# UsbGps4Droid — a USB GPS provider for Android

Feed your Android device with position data from an external USB GPS receiver, and make
every app that asks for location — maps, navigation, tracking — use it as if it were a
built-in GPS.

**[⬇ Download the latest APK (v3.0.1)](../../releases/latest)** · Android 7.0+ · `GPL-3.0`

> ### This is a maintained continuation of the project
> The upstream repository [`freshollie/UsbGps4Droid`](https://github.com/freshollie/UsbGps4Droid)
> has had no commits since **September 2020** and no longer runs on modern Android.
> This fork brings it back: Android 14+ support, the u-blox UBX binary protocol, Dead Reckoning,
> and a rewritten USB layer. The changes were offered upstream as
> [PR #46](https://github.com/freshollie/UsbGps4Droid/pull/46); it remains unreviewed.

## Why you might need this

Many devices have no GPS chip at all, or one that is unusable — Wi-Fi-only tablets, Chinese
car head units, industrial terminals, Raspberry-Pi-class boards running Android. An external
USB receiver is cheap, far more accurate, and has a real antenna. This app takes its output
and injects it into Android as a mock location provider, so no other app needs to know or care.

## Features

- **NMEA 0183** and **UBX** (u-blox binary) protocols — pick either, or run both at once
- **Dead Reckoning** for u-blox LEA-6R: position keeps updating in tunnels and parking garages
- **Receiver configuration from the app** — update rate, dynamic model, SBAS, save/reset to
  the receiver's own flash
- Live view of position, speed, altitude, fix type, satellites in view, and a raw NMEA log
- **Automatic start on boot**, foreground service with a persistent notification
- Choose which USB device to use when several are attached
- Sync the Android clock to GPS time (requires root)

## Supported hardware

Any USB-serial adapter handled by
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) 3.7.3 should work.
Recognised out of the box:

| Chip / vendor | USB VID |
|---|---|
| FTDI (FT232, FT231…) | `0x0403` |
| Prolific PL2303 | `0x067B` |
| Silicon Labs CP210x | `0x10C4` |
| **u-blox** (direct USB) | `0x1546` |
| QinHeng CH340 / CH341 | `0x1A86` |
| Arduino-based receivers | `0x2341` |

If your receiver is not detected, the app also falls back to probing by known USB-serial
vendor IDs. Receivers speaking plain NMEA over any of these bridges are supported regardless
of the GNSS chip inside — SiRF, MediaTek, u-blox and others.

## Tested devices

| Device | Android | Receiver | Result |
|---|---|---|---|
| Xiaomi (HyperOS) | 14 | u-blox LEA-6R via USB-OTG | ✅ works |
| PX5 car head unit | 10 | u-blox LEA-6R | ✅ works |
| Car head unit | 8 | u-blox LEA-6R | ✅ works — see mock-location note below |

**Please report your own results** — device, Android version, receiver, and whether it worked.
This table is the most useful thing this project can offer, and it only grows if people write in.

## Usage

### 1. Select the app as the mock location provider

On most devices this is required, and nothing will work without it:

**Settings → Developer options → Select mock location app → UsbGps4Droid**

### 2. Connect the receiver

- On phones and tablets, use a **USB OTG** adapter — the device must support
  [USB On-The-Go](https://en.wikipedia.org/wiki/USB_On-The-Go).
- On boards and head units with normal USB host ports, plug it in directly; no adapter needed.

### 3. USB permission popup

Android asks for permission every time the device is reconnected, unless your ROM is modified.
If your device is rooted you can suppress this system-wide by following
[this tutorial](https://stackoverflow.com/a/30563253/1741602). On an embedded or in-car
installation this is strongly recommended. Without root, Android offers no way around it.

### Starting the service from another app

The background service can be started by intent:

```java
Intent intent = new Intent();
intent.setComponent(
    new ComponentName(
        "org.broeuschmeul.android.gps.usb.provider",
        "org.broeuschmeul.android.gps.usb.provider.driver.USBGpsProviderService"
    )
);
intent.setAction("org.broeuschmeul.android.gps.usb.provider.action.START_GPS_PROVIDER");
```

Or from a root shell:

```bash
am startservice \
  -a org.broeuschmeul.android.gps.usb.provider.action.START_GPS_PROVIDER \
  -n org.broeuschmeul.android.gps.usb.provider/.driver.USBGpsProviderService
```

The service shuts itself down if the USB device stays disconnected for too long.

## Building

```bash
git clone https://github.com/larionovavi-stack/UsbGps4Droid.git
cd UsbGps4Droid
./gradlew assembleRelease
```

`compileSdk 35` · `targetSdk 34` · `minSdk 24` · AndroidX

## Reporting a problem

Include the device model and Android version, the receiver and its chip, the protocol and baud
rate you selected, and the output of:

```bash
adb logcat -s UsbGpsProviderService USBGpsManager UbxParser
```

Without a log there is usually nothing to go on.

## Screenshots

### Landscape tablet
<p align="center">
    <img src="fastlane/metadata/android/en-US/images/sevenInchScreenshots/2.png" align="center" alt="Main interface" width="800"/>
</p>

### Portrait
<p align="center">
    <img src="fastlane/metadata/android/en-US/images/sevenInchScreenshots/1.png" align="center" alt="Main interface portrait" width="400"/>
</p>

### Device selection
<p align="center">
    <img src="fastlane/metadata/android/en-US/images/sevenInchScreenshots/4.png" align="center" alt="Device selection" width="800"/>
</p>

## Contributing

Contributions are welcome — fork the repository and open a pull request describing what you
changed and why. Reports of working and non-working hardware are just as valuable as code.

## Credits

Originally written in 2011 by **Herbert von Broeuschmeul**, then maintained by
**Oliver Bell (freshollie)** until 2020, whose fork this one continues.
Current maintenance and the v3.x work: **Alexander Larionov**.

## License

`GPL-3.0` — see [LICENSE](LICENSE).
