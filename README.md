#  Indoor Position Detection System

An Android application that estimates a user's indoor room from WiFi RSSI (Received Signal Strength Indicator) readings from access points mapped to LAB 1–LAB 4. It provides room-level positioning rather than precise coordinates.

---

##  Screenshots

| Signal Dashboard | Floor Map | Proximity Graph | Detection Time Comparison | 
|:---:|:---:|:---:|:---:|
| ![Signal Page](screenshots/1.png) | ![Map Page](screenshots/2.png) | ![Graph Page](screenshots/3.png) | ![Detection Time Comparison Page](screenshots/4.png) |

---


## How It Works

The app scans nearby WiFi access points and compares each mapped router's RSSI. The strongest signal identifies the detected lab. The floor map estimates a position within that lab using the nearest routers and the distance model below; accuracy is intended to be room-level.

The dashboard and map refresh WiFi scans every 25 seconds, with a manual dashboard refresh control. Detection statistics report the time from a scan request to its results.

## Authentication and Presence

Firebase Authentication with email and password provides Login and Register screens and gates access to the dashboard. Firebase Realtime Database stores scan records under `scans/{pushId}`.

The dashboard's **ACTIVE USERS** list and the map's user markers use `presence/{uid}`. Presence is overwritten on each scan and removed on disconnect or logout. `PresenceRepository` shares one Firebase presence listener across multiple owner-keyed subscribers and distributes filtered updates to each subscriber. Each listening activity starts and stops its subscription with its lifecycle. The current user is shown with a green marker; other active users are pink. Entries older than 90 seconds are treated as stale.

The map spreads overlapping markers into concentric rings around their estimated shared position. With only one usable router, a marker is placed from that router toward the map center. The caption reports `N on map` and, when applicable, `M out of range`.

## Router Configuration

The BSSID-to-room mapping is defined only in `RouterConfig.kt`, in `routerMap`. The configured access points are:

| Lab | BSSID |
|---|---|
| LAB 1 | `54:AF:97:28:6B:79` |
| LAB 2 | `3C:78:95:31:6C:54` |
| LAB 3 | `40:3F:8C:E0:72:37` |
| LAB 4 | `40:3F:8C:E0:72:36` |

To use this app in another building, replace those BSSIDs with the access point BSSIDs for your four labs. Enter addresses in the usual colon-separated form; the app normalizes scanned BSSIDs to uppercase before lookup.

## Distance Formula

The app uses the **Log-Distance Path Loss** model:

```
distance = 10 ^ ((TxPower - RSSI) / (10 * n))
```

| Variable | Value | Description |
|---|---|---|
| TxPower | -40 dBm | Reference RSSI at 1 meter |
| n | 3.0 | Path loss exponent (indoor) |
| RSSI | measured | Live signal reading in dBm |

When a router is not detected (`RSSI = -100`), the app uses a distance sentinel of `99.0`.

## Setup

1. Clone the repository:

     ```bash
    git clone https://github.com/hrishikeshkanu/indoor-position-detection-system.git
     ```

2. Open the cloned project folder in Android Studio and allow Gradle sync to finish.

3. Set up Firebase:
     - Create a Firebase project and add an Android app with package name `com.example.indoorpositiondetectionsystem`.
     - Download that app's `google-services.json` and place it at `app/google-services.json`. Use your own Firebase configuration when forking; do not replace it with a placeholder.
     - Enable the Email/Password provider in Firebase Authentication.
     - Create a Realtime Database, then open **Realtime Database → Rules**, replace the rules with this JSON, and select **Publish**:

     ```json
     {
         "rules": {
             ".read": false,
             ".write": false,
             "scans": {
                 ".read": "auth != null",
                 "$scanId": {
                     ".write": "auth != null && newData.child('userId').val() == auth.uid"
                 }
             },
             "users": {
                 "$uid": {
                     ".read": "auth != null",
                     ".write": "auth != null && auth.uid == $uid"
                 }
             },
             "presence": {
                 ".read": "auth != null",
                 "$uid": {
                     ".write": "auth != null && auth.uid == $uid",
                     ".validate": "newData.hasChildren(['uid', 'name', 'detectedLab', 'timestamp']) && newData.child('uid').val() == $uid && newData.child('name').isString() && newData.child('detectedLab').isString() && newData.child('timestamp').isNumber()"
                 }
             }
         }
     }
     ```

    Firebase read rules do not cascade upward, so listening on the `presence` parent requires read access on that parent.

4. Replace the sample router BSSIDs in `RouterConfig.kt` with the real BSSIDs for your four lab access points.

5. Connect an Android device, enable USB debugging, and run the `app` configuration from Android Studio. The minimum supported Android version is API 24.

## Required Permissions

The app declares these permissions:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

Location permission is requested at runtime because Android requires it for WiFi scan results. Firebase Authentication and Realtime Database also require an internet connection.

## Capacity

There is no hard user limit enforced by the app. Firebase's free plan is limited by simultaneous database connections (about 100) and download quota. Because each user's presence updates are delivered to the other listeners, bandwidth grows roughly with the square of the number of users.

## Known Limitations

- Android scan throttling limits `startScan()` to roughly four calls per two minutes per app on Android 9 and newer. The OS controls this limit.
- On Android 10 and newer, the device Location toggle must be ON for WiFi scanning to return results; granting app permission alone may not be enough.
- RSSI fluctuates with walls, furniture, people, and radio interference. The result targets room-level, not precise coordinate, accuracy.
- The path-loss model is approximate and real environments can differ from its assumptions.
- Presence capacity depends on Firebase's simultaneous-connection and download quotas; see [Capacity](#capacity). Marker spreading improves readability when estimates overlap but does not make those estimates more precise.

## Project Structure

| File | Purpose |
|---|---|
| `LoginActivity.kt`, `RegisterActivity.kt` | Firebase email/password authentication |
| `MainActivity.kt` | Dashboard, scan lifecycle, auto-refresh, and detection statistics |
| `MapActivity.kt`, `MapView.kt` | Live map scanning and Canvas rendering of router and user markers |
| `RouterConfig.kt` | Single BSSID-to-room mapping and shared distance calculation |
| `PresenceRepository.kt` | Publish, listen for, and filter live presence |
| `SignalGraphView.kt` | Dashboard RSSI graph |

## Signal Quality Reference

| RSSI Range | Label |
|---|---|
| ≥ -60 dBm | Strong |
| -60 to -70 dBm | Good |
| -70 to -80 dBm | Weak |
| < -80 dBm | Very Weak |
| Not detected | Out of Range |

---

##  Author

**Hrishikesh Kanu**
B.Sc. in Information & Technology
Jahangirnagar University, Bangladesh

---

##  License

This project is built for academic purposes as part of a university project.
