# AGENTS.md

## Project overview
This repository contains an Android application for indoor position detection using WiFi RSSI measurements. The app scans nearby access points, compares their signal strengths, and estimates the current room/position using a room-based indoor positioning model. The project is built in Kotlin, uses Android Views and custom Canvas drawing, and stores scan data in Firebase Realtime Database.

The app is designed for a lab environment with several WiFi routers mapped to room names such as LAB 1–LAB 4. It detects the strongest router, estimates the user’s likely location inside the corresponding zone, and displays the result in both a signal dashboard and a floor map.

## Repository structure
- `app/` – Android app module
  - `src/main/java/com/example/indoorpositiondetectionsystem/` – Kotlin activities and custom views
    - `MainActivity.kt` – main signal dashboard, permission checks, WiFi scanning, Firebase upload, auto-refresh logic
    - `MapActivity.kt` – floor-map screen with live WiFi scanning
    - `MapView.kt` – custom canvas-based map renderer for room zones and device marker
    - `SignalGraphView.kt` – RSSI graph drawing for the dashboard
    - `LoginActivity.kt` and `RegisterActivity.kt` – Firebase authentication flow
  - `src/main/res/` – layouts, drawables, colors, themes, XML resources
- `build.gradle.kts` – root Gradle configuration
- `settings.gradle.kts` – includes the app module
- `README.md` – project overview and project-level documentation
- `screenshots/` – app screenshots

## Core architecture
### Android app flow
1. `MainActivity` checks whether the user is logged in.
2. If logged in, it requests location permission and begins WiFi scanning.
3. Nearby router BSSIDs are matched against the configured `routerMap`.
4. The strongest signal determines the detected lab/room.
5. Signal values are displayed with labels like Strong, Good, Weak, Very Weak, and Out of Range.
6. Distance estimates are computed using the log-distance path loss model and passed to the map screen.
7. Scan results are saved to Firebase under `scans`.

### Position logic
- The strongest signal identifies the primary room.
- Secondary strongest signal helps determine direction/leaning toward another lab.
- `MapView` estimates a device position inside the primary zone using a directional offset and distance value.
- Layout is custom-rendered on Canvas instead of relying only on standard Android Views.

## Key technologies
- Kotlin
- Android SDK with AndroidX components
- `WifiManager.startScan()` and `BroadcastReceiver`
- Firebase Authentication
- Firebase Realtime Database
- Custom Views drawn with `Canvas` and `Paint`

## Build and validation commands
Use these commands from the project root:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew connectedDebugAndroidTest
```

If you are working only on app logic and resources, the most relevant validation is usually:

```bash
./gradlew assembleDebug
```

## Coding conventions for agents
- Prefer Kotlin idioms and keep code readable and consistent with the existing project style.
- Preserve the existing room naming convention (`LAB 1` to `LAB 4`) unless a broader project change requires updating all references.
- Be careful with WiFi/BSSID matching; router addresses are case-insensitive but must match the configured keys exactly after normalization.
- When adding or changing detection logic, keep the room labels and `routerMap` mapping in sync across `MainActivity` and `MapActivity`.
- Do not hardcode secrets or Firebase credentials in source files.
- Keep XML layouts and custom drawing logic aligned with the existing design language: dark theme, cyan and green accent colors, lab dashboard style.
- Preserve runtime permission checks and avoid breaking the login guard flow in `MainActivity`.

## Important behaviors to preserve
- Location permission is required for WiFi scanning on modern Android.
- App-level auto-refresh is implemented with a repeating `Handler` and should remain stable when changed.
- Firebase writes are asynchronous; avoid making assumptions that `setValue()` has completed immediately.
- `MapView` uses estimated distances to draw the “YOU” marker; keep math stable if modifying the position algorithm.
- The app is designed for indoor lab-level accuracy rather than precise global positioning.

## Safe edit principles
- Prefer targeted changes in the relevant activity or custom view.
- When editing detection logic, validate with debug builds and check for scan result edge cases like missing routers or no valid signal readings.
- If you modify the room detection model or router mapping, update documentation and any affected screen labels or tests.
- Avoid unnecessary refactors in the Android UI unless they directly support the task.

## Typical tasks
- Add or change router mappings for a new laboratory setup
- Improve the distance/position estimation formula
- Adjust the UI dashboard values or graph styling
- Add support for new labs or room names
- Review Firebase scan logging and auth flow
- Fix permission handling or Android scanning reliability

## Notes for future agents
This project is compact but domain-specific. Most functional changes will touch either:
- WiFi scan and detection logic in `MainActivity.kt`
- Map rendering in `MapView.kt`
- Firebase auth or scan persistence logic
- XML layout resources and UI labels

Keep all changes focused on the indoor WiFi positioning workflow rather than introducing unrelated architecture patterns.
