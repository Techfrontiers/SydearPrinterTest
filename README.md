# SYDEAR Printer Test

First-stage Android test app for EasyPrint ES-9910UB.

## What it tests
- Lists Android paired Bluetooth devices.
- Highlights the known ES-9910UB MAC: `66:32:8E:84:F6:84`.
- Connects using Bluetooth Classic RFCOMM / SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.
- Falls back to insecure RFCOMM if secure SPP fails.
- Sends a minimal ESC/POS text test.
- Sends a simple paper-feed test.

## Important
This is intentionally a protocol-discovery MVP. It does **not** assume that ES-9910UB is ESC/POS-compatible. The text test is the experiment that will tell us whether this connection path works.

## Build
Open this folder in a current Android Studio. The project uses Android Gradle Plugin 9.3.0 and Gradle 9.5.0. JDK 17 is recommended by AGP 9.3.

If the phone is Android 12+, the app requests `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` at runtime.

## Test procedure
1. Pair `ES-9910UB` in Android Bluetooth settings.
2. Launch the app.
3. Select `ES-9910UB` (the known MAC is highlighted).
4. Tap `เชื่อมต่อ`.
5. If connected, tap `TEST: ESC/POS Text`.
6. Record whether paper prints anything.

The result determines the next protocol implementation step.
