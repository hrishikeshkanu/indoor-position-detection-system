# AGENTS.md

## Project overview
This repository contains an Android application for indoor position detection using WiFi RSSI measurements. The app scans nearby access points, compares their signal strengths, and estimates the current room/position using a room-based indoor positioning model. The project is built in Kotlin, uses Android Views and custom Canvas drawing, and stores scan data in Firebase Realtime Database. Firebase Authentication gates access to the main dashboard.

The app is designed for a lab environment with several WiFi routers mapped to room names such as LAB 1–LAB 4. It detects the strongest router, estimates the user's likely location inside the corresponding zone, and displays the result in both a signal dashboard and a floor map.

## Repository structure
- `app/` – Android app module
  - `src/main/java/com/example/indoorpositiondetectionsystem/` – Kotlin activities, views, and configuration
    - `MainActivity.kt` – main signal dashboard, auth guard, permission checks, WiFi scanning, Firebase upload, auto-refresh logic
    - `MapActivity.kt` – floor-map screen with live WiFi scanning
    - `MapView.kt` – custom canvas-based map renderer for room zones and device marker
    - `SignalGraphView.kt` – RSSI graph drawing for the dashboard
    - `RouterConfig.kt` – central BSSID-to-room mapping for the indoor positioning model
    - `LoginActivity.kt` and `RegisterActivity.kt` – Firebase authentication flow (LoginActivity is the app's launcher)
  - `src/main/res/` – layouts, drawables, colors, themes, XML resources
  - `google-services.json` – Firebase project config (do not replace with placeholder values; treat project ownership carefully)
- `build.gradle.kts` – root Gradle configuration
- `app/build.gradle.kts` – app module Gradle config; Firebase deps are BOM-managed (`firebase-bom`), don't mix in separately-pinned Firebase versions
- `settings.gradle.kts` – includes the app module
- `README.md` – project overview and project-level documentation
- `screenshots/` – app screenshots

## Core architecture
### Android app flow
1. `LoginActivity` is the app's launcher; it auto-forwards to `MainActivity` if already signed in.
2. `MainActivity` checks whether the user is logged in and bounces to `LoginActivity` if not (auth guard — do not remove).
3. If logged in, it requests location permission and begins WiFi scanning.
4. Nearby router BSSIDs are matched against the centralized `RouterConfig.routerMap`.
5. The strongest signal determines the detected lab/room.
6. Signal values are displayed with labels like Strong, Good, Weak, Very Weak, and Out of Range.
7. Distance estimates are computed using the log-distance path loss model and passed to the map screen.
8. Scan results are saved to Firebase under `scans`.

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

## Known outstanding issues (fix before adding new features)
- Keep the router-to-room mapping centralized in `RouterConfig.kt`; do not reintroduce duplicated room mappings in `MainActivity` or `MapActivity`.
- Maintain the single-launcher setup in `AndroidManifest.xml`: only `LoginActivity` should declare the `MAIN`/`LAUNCHER` intent-filter.
- Keep Firebase dependencies BOM-managed and avoid reintroducing individually pinned versions.
- Preserve the auth guard flow so unauthenticated users are redirected back to `LoginActivity`.

## Coding conventions for agents
- Prefer Kotlin idioms and keep code readable and consistent with the existing project style.
- Preserve the existing room naming convention (`LAB 1` to `LAB 4`) unless a broader project change requires updating all references.
- Be careful with WiFi/BSSID matching; router addresses are case-insensitive but must match the configured keys exactly after normalization.
- `routerMap` now lives in `RouterConfig.kt` and should be updated there only. Do not duplicate or re-declare room mappings in `MainActivity`, `MapActivity`, or any other file.
- Do not hardcode new secrets or Firebase credentials in source files beyond the existing `google-services.json`.
- Keep XML layouts and custom drawing logic aligned with the existing design language: dark theme, cyan and green accent colors, lab dashboard style.
- Preserve runtime permission checks and avoid breaking the login guard flow in `MainActivity`.
- Firebase dependencies should stay BOM-managed (`platform("com.google.firebase:firebase-bom:...")`); don't reintroduce individually pinned Firebase library versions.

## Important behaviors to preserve
- Location permission is required for WiFi scanning on modern Android.
- App-level auto-refresh is implemented with a repeating `Handler` and should remain stable when changed.
- Firebase writes are asynchronous; avoid making assumptions that `setValue()` has completed immediately.
- `MapView` uses estimated distances to draw the "YOU" marker; keep math stable if modifying the position algorithm.
- The app is designed for indoor lab-level accuracy rather than precise global positioning.
- Only one activity should hold the `LAUNCHER` intent-filter at any time.

## Safe edit principles
- Prefer targeted changes in the relevant activity or custom view.
- When editing detection logic, validate with debug builds and check for scan result edge cases like missing routers or no valid signal readings.
- If you modify the room detection model or router mapping, update documentation and any affected screen labels or tests.
- Avoid unnecessary refactors in the Android UI unless they directly support the task.
- Before adding new manifest entries, check for existing duplicate/dead declarations first.

## Typical tasks
- Add or change router mappings for a new laboratory setup
- Improve the distance/position estimation formula
- Adjust the UI dashboard values or graph styling
- Add support for new labs or room names
- Review Firebase scan logging and auth flow
- Fix permission handling or Android scanning reliability
- Keep configuration centralized and maintain repo consistency

## Notes for future agents
This project is compact but domain-specific. Most functional changes will touch either:
- WiFi scan and detection logic in `MainActivity.kt`
- Router mapping configuration in `RouterConfig.kt`
- Map rendering in `MapView.kt`
- Firebase auth or scan persistence logic
- XML layout resources and UI labels
- `AndroidManifest.xml` and `app/build.gradle.kts` when updating app wiring or dependency management

Keep all changes focused on the indoor WiFi positioning workflow rather than introducing unrelated architecture patterns.