# AGENTS.md

## Project overview
Android app (Kotlin, Android Views, custom Canvas drawing) for indoor position detection using WiFi RSSI. The app scans nearby access points, compares signal strengths, and estimates the user's room/position in a lab environment (routers mapped to LAB 1–LAB 4). Firebase Authentication gates the dashboard, and Firebase Realtime Database stores scan data and live multi-user presence.

Current state: login/registration, database connectivity, and the **multi-user presence feature** are complete and working across devices (see "Feature history" below).

## Repository structure
- `app/` – Android app module
  - `src/main/java/com/example/indoorpositiondetectionsystem/`
    - `LoginActivity.kt` – launcher; auto-forwards to `MainActivity` if already signed in
    - `RegisterActivity.kt` – creates the account and writes `users/{uid}` (`name`, `email`, `createdAt`)
    - `MainActivity.kt` – auth guard, permission request, WiFi scanning, dashboard UI, detection-time stats, `scans` upload, 25 s auto-refresh, ACTIVE USERS card
    - `MapActivity.kt` – floor-map screen with its own live WiFi scanning (25 s Handler-driven, no self-triggering loop)
    - `MapView.kt` – custom Canvas view: router nodes, coverage zones, multi-user markers, overlap spreading, crowd scaling
    - `SignalGraphView.kt` – RSSI bar graph on the dashboard
    - `RouterConfig.kt` – the ONLY place for the BSSID → room mapping; also holds `calculateDistance()`
    - `PresenceRepository.kt` – owner-keyed multi-subscriber publisher/reader of live user presence
  - `src/main/res/` – layouts (`activity_main`, `activity_map`, `activity_login`, `activity_register`, `item_active_user`), drawables, themes
  - `google-services.json` – Firebase config; do not replace with placeholders
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` – Gradle config (version catalog in use)
- `app/build.gradle.kts` – Firebase deps are BOM-managed
- `README.md`, `screenshots/`

## Current app flow
1. `LoginActivity` (sole launcher) → `MainActivity` if signed in.
2. `MainActivity` auth guard bounces unauthenticated users back to `LoginActivity`.
3. Requests `ACCESS_FINE_LOCATION`, registers a `SCAN_RESULTS_AVAILABLE_ACTION` receiver, starts scanning.
4. BSSIDs (uppercased, null-safe: `r.BSSID?.uppercase() ?: continue`) are matched against `RouterConfig.routerMap`; strongest RSSI per lab wins (`-100` = not seen).
   - LAB 1: `54:AF:97:28:6B:79`; LAB 2: `3C:78:95:31:6C:54`
   - LAB 3: `40:3F:8C:E0:72:37`; LAB 4: `40:3F:8C:E0:72:36`
5. Strongest lab = detected lab. Signal quality labels: Strong ≥ -60, Good ≥ -70, Weak ≥ -80, Very Weak < -80, Out of Range = -100.
6. Distance = `10 ^ ((-40 - rssi) / (10 * 3.0))`, `99.0` when not seen.
7. Each scan is pushed to Firebase `scans` (with `timestamp`, `detectedLab`, `signals`, `userId`) and published to `presence/{uid}`.
8. "VIEW MAP" passes current distances to `MapActivity` via the `"distances"` extra; the map also scans on its own every 25 s while visible.

## Position logic (MapView)
- Routers sit at fixed corners (18% / 14% insets); `zoneRadius = 0.30 * width`; `mapScale = diagonal / 20`.
- `estimatePosition(distances)`:
  - Normal case (2+ usable routers): primary = nearest usable router, secondary = second nearest; the dot is placed from the primary toward the secondary at `min(primaryDistance * mapScale, zoneRadius - 30)`.
  - Single-router fallback (exactly 1 router with distance < 90): start at that router and move toward the map center by `min(distance * mapScale, zoneRadius - 30)`.
  - Zero usable routers: returns `null`; the marker cannot be placed and is counted as "out of range".
- Keep this math stable; new behavior must be a fallback or a post-processing step, never a fork.
- **Overlap resolution (post-process in `onDraw`)**: markers whose true positions are within 60 px are grouped. The self marker stays at its true position; others are spread on concentric rings around the group anchor (self position, else centroid). Ring 1 radius 50 px, each next ring +45 px, capacity `max(1, floor(2π·r / 44))`, start angle −90°, every other ring offset by half a step. Non-self markers are sorted by name then original index so dots do not shuffle between updates. Final positions are clamped inside the view with ~30 px padding.
- **Draw order**: others first, self last (always on top) with a thin white ring. Self is green (`#00FF9C`), others pink (`#FF6EC7`). The self label shows the user's name, not the literal "YOU".
- **Crowd scaling**: with more than 12 placed markers, others use dot radius 11, glow 22, label size 22f, and labels longer than 10 characters are truncated to 9 + "…". Self keeps normal size and full name.
- **Label collision**: up to 4 candidate positions (above, below, alternating left/right); never outside the view.
- **Caption**: bottom of the map shows "N on map" plus ", M out of range" when M > 0.
- Distances `>= 90` render as "–" in the router distance labels.

## Firebase Realtime Database schema
```
users/{uid}      { name, email, createdAt }
scans/{pushId}   { timestamp, detectedLab, signals{LAB n: rssi}, userId }
presence/{uid}   { uid, name, detectedLab, signals, distances, timestamp }
```
- `presence/{uid}` is OVERWRITTEN with `setValue` (never `push`), removed via `onDisconnect().removeValue()` and on logout.
- Username source of truth: `users/{uid}/name`; fallback is email prefix, then `"User"`.
- Stale timeout: entries older than 90 s (server-time adjusted using `.info/serverTimeOffset`) are filtered out.

### Security rules (published)
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
- Target rules: authenticated read on the `presence` **parent**; a user may write only their own `presence/{uid}` and `users/{uid}`.
- Firebase read rules do NOT cascade upward. The app listens on the parent `presence` node, so `.read` must be on `presence` itself. Putting `.read` only under `$uid` causes `Permission denied` (code -3) and every device sees 0 users.

## PresenceRepository design
- Owner-keyed subscribers: `startListening(owner: Any, listener)` / `stopListening(owner: Any)`. Re-subscribing the same owner replaces its listener.
- Ref-counted Firebase listeners: the `presence` and `.info/serverTimeOffset` listeners and the stale-refresh runnable attach when the FIRST subscriber joins and detach (clearing cached users) when the LAST one leaves.
- A late subscriber immediately receives the current filtered list if a snapshot has already arrived.
- `emitFilteredUsers()` filters once and delivers to a copy of the subscriber list.
- `clear()` stops all subscribers, removes the caller's `presence/{uid}` (with a 2 s timeout fallback), and resets the cached name.
- `publish()` is async; failures are logged with `Log.w` under tag `PresenceRepository`, never Toasted.

## Feature history: multi-user presence (all done)
1. Write layer – `publish()/clear()`, called after each scan in `MainActivity` and `MapActivity`; `clear()` before `signOut()`. ✅
2. Read layer – ValueEventListener on `presence`, stale filtering, self flagged. ✅
3. Main page – "ACTIVE USERS" card. ✅
4. Map view – other users' dots, distinct color, label avoidance. ✅
5. Polish – DB rules, stale timeout, docs. ✅
6. Diagnosis + rules fix – `.read` moved to the `presence` parent. ✅
7. Marker visibility – single-router fallback, overlap spreading, self drawn on top, out-of-range caption. ✅
8. Listener lifecycle – multi-subscriber repository; `MapActivity` starts/stops listening and the receiver in `onStart`/`onStop`; 25 s Handler scan replaces the self-triggering loop. ✅
9. Large-crowd support – concentric rings, crowd scaling, "N on map / M out of range". ✅
10. Cleanup – debug logs removed, README and this file updated. ✅

## Invariants (do not break)
- BSSID map lives only in `RouterConfig.kt`; never redeclare it in activities.
- Only `LoginActivity` has the `MAIN`/`LAUNCHER` intent-filter.
- Keep the auth guard in `MainActivity`.
- Firebase deps stay BOM-managed (`platform("com.google.firebase:firebase-bom:...")`), no individually pinned versions.
- Preserve runtime permission checks and the room names `LAB 1`–`LAB 4`.
- No new secrets/credentials in source. Do not add new architecture libraries.
- Firebase writes are async; never assume completion. Don't Toast on every-scan failures (log with `Log.w`).
- Keep the dark theme with cyan (`#00E5FF`) and green (`#00FF9C`) accents.
- Only one activity may hold `LAUNCHER`; check for duplicate/dead manifest entries before adding new ones.
- Presence `.read` must stay at the `presence` parent, never only under `$uid`.
- `PresenceRepository` uses owner-keyed subscribers (`startListening(owner, ...)` / `stopListening(owner)`). Every Activity that listens must stop in the matching lifecycle callback (`onStart` ↔ `onStop`).
- Do not reintroduce `startScan()` inside a scan-result receiver (self-triggering loop). Use a Handler with a 25 s interval, started in `onStart`/`onResume` and cancelled in `onStop`/`onPause`.
- `MapView.estimatePosition()` normal-case math stays unchanged; additions are fallbacks or post-processing.
- Always null-guard `r.BSSID` in scan loops.

## Known issues / tech debt (fix only when relevant to the task, or when asked)
- Android throttles `startScan()` (~4 per 2 min on API 28+); a 25 s interval is slightly above that, so some scans may be served from cache. The OS controls this.
- `scans` grows unbounded (one push per scan per user); consider retention/cleanup later.
- Manifest theme is hard-coded `Theme.AppCompat.Light.NoActionBar` instead of the app theme.
- `google-services.json` contains an API key (normal for Firebase); restrict it in the Google Cloud console and rely on DB rules for protection.
- Test data with a far-future `timestamp` never goes stale; delete any `presence/fake*` children after load testing.
- `hs_err_pid*.log` JVM crash dumps in the repo root are IDE/Gradle memory crashes, not app bugs. Delete them and add `hs_err_pid*.log` to `.gitignore`. If Gradle/VS Code crash with out-of-memory, close other heavy apps or lower `org.gradle.jvmargs` / raise system page file.
- Free Firebase plan: about 100 simultaneous connections and a 10 GB/month download quota; bandwidth grows roughly with users squared.

## Build and validation
```bash
./gradlew assembleDebug
./gradlew test
./gradlew connectedDebugAndroidTest
```
`assembleDebug` is the minimum check after any change. Test on real devices with the Location toggle ON (required for scan results on Android 10+). For multi-user checks use two or more devices with different accounts.

## Coding conventions
- Idiomatic, readable Kotlin consistent with the existing style; targeted edits over refactors.
- Handle edge cases: missing routers, all signals `-100`, null user, null/empty database values, empty user lists, duplicate names.
- Router BSSIDs are case-insensitive but must match `RouterConfig` keys after `uppercase()`.
- If you change detection logic, router mapping, rules, or the schema, update README and this file.
- Accuracy target is room-level, not precise coordinates.

## Floor map background (added)
- MapView draws `app/src/main/res/drawable-nodpi/floor_map.jpeg` as a square background inside `mapRect` (centered, letterboxed, dark scrim).
- Routers are at the lab centers: fractions (0.25, 0.25), (0.75, 0.25), (0.25, 0.75), (0.75, 0.75) of `mapRect` for LAB 1–LAB 4.
- `mapScale = mapRect.width() / 20` (20 m assumed map width); `zoneRadius = 0.22 * mapRect.width()`.
- Coverage circles are no longer drawn; the grid fallback is square and clipped to `mapRect`.
- Labels use a dark halo and dots a dark outline for readability.
- Markers and marker labels are clamped to `mapRect` with a 30 px inset.
- The detected lab (reference marker's smallest distance < 90) gets a translucent green highlight. Lab rectangles as fractions of `mapRect`: LAB 1 (0.03, 0.03, 0.45, 0.44), LAB 2 (0.55, 0.03, 0.97, 0.44), LAB 3 (0.03, 0.56, 0.45, 0.97), LAB 4 (0.55, 0.56, 0.97, 0.97).
- Router AP markers use a 9 px dot, 24 px glow, and `AP n` labels; distance values use a bold light paint and sit opposite the router name.
- The map caption uses a dark pill, and a top-left legend identifies you, other users, and routers on maps at least 400 px wide.
- Marker label offset radius is 44f with a 90f near-router threshold.
- All four stages are complete. Tunables: `MAP_WIDTH_METERS` (20f), router fractions (0.25/0.75), `zoneRadius` fraction (0.22), and lab rectangle fractions listed above.
