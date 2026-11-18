# Implemented Features Matrix — Linienbegleiter

This document details all **currently implemented, tested, and active features** in **Linienbegleiter** `v0.25`.

---

## Feature Matrix Overview

| Feature | Subsystem / Location | Status | Summary |
|---|---|---|---|
| **Interactive Map View** | `MapScreen.kt` | ✅ Active | Vector map rendering via MapLibre SDK (OpenFreeMap Liberty/Dark styles). |
| **Compass & Motion Arrow** | `MapScreen.kt` | ✅ Active | Dynamic location arrow: turns by motion vector when travelling (>3 km/h), and rotates smoothly by hardware compass sensor (`Sensor.TYPE_ROTATION_VECTOR`) when stationary. |
| **Instant Route Dropdown** | `MapScreen.kt` | ✅ Active | Top floating **"🚀 Drive"** button opening a quick-select route dropdown directly over the map. |
| **Auto Route Bounds Framing** | `MapScreen.kt` | ✅ Active | Calculates `LatLngBounds` of all route points upon selection and smoothly animates camera to fit the entire line on screen. |
| **Background GPS Recording** | `RouteRecordingService.kt` | ✅ Active | Foreground Service recording location, elevation, speed, accuracy, and checkpointing every 10 seconds. |
| **Eco-Drive Telemetry & Scoring** | `DrivingBehaviorAnalyzer.kt` | ✅ Active | Global setting in `SettingsScreen` analyzing braking, acceleration, and speed G-forces to calculate 0–100 score & 5-star rating. |
| **Route Navigation & Progress** | `RouteFollowingService.kt` | ✅ Active | Real-time progress calculator (`RouteProgressCalculator`) tracking remaining distance, speed, deviation, and next stops. |
| **Arrival–Departure Engine** | `RouteFollowingManager.kt` | ✅ Active | Detects terminal station arrival and suggests reverse trips (`🔄 Return Trip`) and matching line departures. |
| **Shade Notification Actions** | `RouteFollowingService.kt` | ✅ Active | System notification actions allowing next-trip startup directly from the Android notification shade. |
| **Visual Geometry Editor** | `RouteEditorScreen.kt` | ✅ Active | Distance-window section editor with smooth deformation (`smoothInfluence`) and automatic multi-segment selection merging (`mergeRouteSections`). |
| **Framed Stop Cards** | `RouteEditorScreen.kt` | ✅ Active | Outlined cards for stops with 1-tap renaming, moving on map, deletion, and sequence reordering. |
| **Multi-Format Import/Export** | `ImportExportScreen.kt` | ✅ Active | Import and export in GPX, GeoJSON, GTFS Static/CSV, and custom ZIP backup archives with SHA-256 integrity. |
| **Hamburg Transit Theme System** | `ui/theme/*` | ✅ Active | Material 3 theme in Hamburg Transit palette (*Hamburg Red, Elbe Blue, Harbor Navy, Brick, Sand*) with Light/Dark/Auto-light sensor modes. |

---

## Detailed Feature Descriptions

### 1. Dynamic Location Indicator & Hardware Compass
- **When Traveling:** When GPS speed exceeds ~3 km/h (0.8 m/s), the blue location arrow aligns with the movement velocity vector (`bearingDegrees`), pointing forward along the travel direction.
- **When Stationary:** When standing still, a low-pass angular filter monitors the device's hardware rotation sensor (`Sensor.TYPE_ROTATION_VECTOR`). Turning the physical phone in hand smoothly rotates the blue location arrow on screen in real time.

### 2. Arrival–Departure Next Trip Suggestions
- When following a line, approaching within 50 meters of the final stop triggers terminal arrival.
- The app evaluates candidates:
  1. **Return Trip (`🔄 Return Trip`):** Automatic detection of the reverse direction for the same line.
  2. **Station Departures:** Routes whose starting stop matches the current terminal station.
  3. **Similar Lines:** Lines matching the line number or title prefix.
- The next route can be launched in 1 tap from the on-screen `ArrivalDeparturePanel` or from the Android system notification action button.

### 3. Visual Geometry Editor & Multi-Segment Selection
- Tapping a route segment highlights a local editing window. Tapping additional segments automatically merges them via `mergeRouteSections` into a continuous selection span.
- Dragging the handle smoothly deforms the merged segment using a raised cosine influence falloff (`smoothInfluence`).
- Clicking `−` deletes the entire combined section at once, joining remaining geometry cleanly.

### 4. Eco-Drive & Driving Behavior Analyzer
- Global toggle in `SettingsScreen` stored in `AppPreferences`.
- Evaluates linear acceleration and deceleration G-forces during recording.
- Detects harsh braking (deceleration > 3.0 m/s²), rapid acceleration (> 3.0 m/s²), and speeding (> 90 km/h).
- Calculates a 0–100 score and 5-star grade (*⭐⭐⭐⭐⭐ Excellent* to *⭐ Dangerous*) displayed live on the recording dashboard.

---

## Unimplemented / Non-Existent Features Disclaimer

To ensure honesty and accuracy:
- **Turn-by-turn Voice Navigation:** Not implemented. Route following is visual and progress-based.
- **Road Network Routing (Snapping to roads via OSRM / GraphHopper):** Not connected. Route geometry is drawn and stored as direct geographic line strings.
- **`.busway` File Support:** Not supported.
