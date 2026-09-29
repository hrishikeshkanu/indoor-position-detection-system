# AGENTS.md

## Project overview
Android app (Kotlin, Android Views, custom Canvas drawing) for indoor position detection using WiFi RSSI. The app scans nearby access points, compares signal strengths, and estimates the user's room/position in a lab environment (routers mapped to LAB 1–LAB 4). Firebase Authentication gates the dashboard, and Firebase Realtime Database stores scan data.

Current state: login/registration, database connectivity, and the multi-user presence feature are working.

## Repository structure
- `app/` – Android app module
  - `src/main/java/com/example/indoorpositiondetectionsystem/`
    - `LoginActivity.kt` – launcher; auto-forwards to `MainActivity` if already signed in
    - `RegisterActivity.kt` – creates the account and writes `users/{uid}` (`name`, `email`, `createdAt`)
    - `MainActivity.kt` – auth guard, permission request, WiFi scanning, dashboard UI, detection-time stats, `scans` upload, 25 s auto-refresh
    - `MapActivity.kt` – floor-map screen with its own live WiFi scanning
    - `MapView.kt` – custom Canvas view: router nodes, coverage zones, and multi-user markers
    - `SignalGraphView.kt` – RSSI bar graph on the dashboard
    - `RouterConfig.kt` – the ONLY place for the BSSID → room mapping; also holds `calculateDistance()`
    - `PresenceRepository.kt` – publishes/reads live user presence
  - `src/main/res/` – layouts (`activity_main`, `activity_map`, `activity_login`, `activity_register`, `item_active_user`), drawables, themes
  - `google-services.json` – Firebase config; do not replace with placeholders
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` – Gradle config (version catalog in use)
- `app/build.gradle.kts` – Firebase deps are BOM-managed
- `README.md`, `screenshots/`

## Current app flow
1. `LoginActivity` (sole launcher) → `MainActivity` if signed in.
2. `MainActivity` auth guard bounces unauthenticated users back to `LoginActivity`.
3. Requests `ACCESS_FINE_LOCATION`, registers a `SCAN_RESULTS_AVAILABLE_ACTION` receiver, starts scanning.
4. BSSIDs (uppercased) are matched against `RouterConfig.routerMap`; strongest RSSI per lab wins (`-100` = not seen).
  - LAB 1: `54:AF:97:28:6B:79`; LAB 2: `3C:78:95:31:6C:54`
  - LAB 3: `40:3F:8C:E0:72:37`; LAB 4: `40:3F:8C:E0:72:36`
5. Strongest lab = detected lab. Signal quality labels: Strong ≥ -60, Good ≥ -70, Weak ≥ -80, Very Weak < -80, Out of Range = -100.
6. Distance = `10 ^ ((-40 - rssi) / (10 * 3.0))`, `99.0` when not seen.
7. Each scan is pushed to Firebase `scans` (with `timestamp`, `detectedLab`, `signals`, `userId`).
8. "VIEW MAP" passes current distances to `MapActivity` via the `"distances"` extra; the map also scans on its own.

## Position logic (MapView)
- Routers sit at fixed corners (18% / 14% insets); `zoneRadius = 0.30 * width`; `mapScale = diagonal / 20`.
- `estimatePosition(distances)`: primary = nearest usable router, secondary = second nearest; the dot is placed from the primary toward the secondary at `min(primaryDistance * mapScale, zoneRadius - 30)`. Multiple `UserMarker`s use this same math; self is green and others are pink. The self label shows the user's name rather than the literal "YOU".
- Distances `>= 90` render as "–". Keep this math stable; the multi-user work should reuse it, not fork it.

## Firebase Realtime Database schema
```
users/{uid}      { name, email, createdAt }
scans/{pushId}   { timestamp, detectedLab, signals{LAB n: rssi}, userId }
presence/{uid}   { uid, name, detectedLab, signals, distances, timestamp }
```
- `presence/{uid}` is OVERWRITTEN with `setValue` (never `push`), removed via `onDisconnect().removeValue()` and on logout.
- Username source of truth: `users/{uid}/name`; fallback is email prefix, then `"User"`.
- Target rules: authenticated read is granted at the `presence` parent; a user may write only their own `presence/{uid}` and `users/{uid}`.

## Multi-user presence
Stages (implement one at a time, don't skip ahead):
1. **Write layer** – `PresenceRepository.publish()/clear()`; called after each scan in `MainActivity` and `MapActivity`; `clear()` before `signOut()`. ✅
2. **Read layer** – ValueEventListener on `presence`, exposes list of active users, filters stale entries (based on `timestamp`), excludes/flags self. ✅
3. **Main page** – "ACTIVE USERS" card: username + lab per user. ✅
4. **Map view** – draw other users' dots with name labels using the same estimation math as "YOU"; spread overlaps into rings and provide single-router fallback placement. ✅
5. **Shared subscriptions** – support owner-keyed multiple subscribers and stop each activity's subscription in its matching lifecycle callback. ✅
6. **Polish** – tighten DB rules, tune stale timeout, and document the rules, map behavior, and capacity. ✅

Multi-user presence feature complete as of Stage 6.

## Invariants (do not break)
- BSSID map lives only in `RouterConfig.kt`; never redeclare it in activities.
- Only `LoginActivity` has the `MAIN`/`LAUNCHER` intent-filter.
- Keep the auth guard in `MainActivity`.
- Firebase deps stay BOM-managed (`platform("com.google.firebase:firebase-bom:...")`), no individually pinned versions.
- Preserve runtime permission checks and the room names `LAB 1`–`LAB 4`.
- No new secrets/credentials in source. Do not add new architecture libraries.
- Firebase writes are async; never assume completion. Don't Toast on every-scan failures (log with `Log.w`).
- Presence `.read` permission must be granted at the `presence` parent because activities listen on that node.
- `PresenceRepository` uses owner-keyed subscribers through `startListening(owner, ...)` and `stopListening(owner)`.
- Every activity that listens must stop its subscription in the matching lifecycle callback.
- Keep the dark theme with cyan (`#00E5FF`) and green (`#00FF9C`) accents.
- Only one activity may hold `LAUNCHER`; check for duplicate/dead manifest entries before adding new ones.

## Known issues / tech debt (fix only when relevant to the task, or when asked)
- `scans` grows unbounded (one push per scan per user); consider retention/cleanup later.
- Manifest theme is hard-coded `Theme.AppCompat.Light.NoActionBar` instead of the app theme.
- `google-services.json` contains an API key (normal for Firebase); restrict it in the Google Cloud console and rely on DB rules for protection.

## Build and validation
```bash
./gradlew assembleDebug
./gradlew test
./gradlew connectedDebugAndroidTest
```
`assembleDebug` is the minimum check after any change. Test on a real device with Location toggle ON (required for scan results on Android 10+).

## Coding conventions
- Idiomatic, readable Kotlin consistent with the existing style; targeted edits over refactors.
- Handle edge cases: missing routers, all signals `-100`, null user, null/empty database values.
- Router BSSIDs are case-insensitive but must match `RouterConfig` keys after `uppercase()`.
- If you change detection logic, router mapping, or the schema, update README and this file.
- Accuracy target is room-level, not precise coordinates.
